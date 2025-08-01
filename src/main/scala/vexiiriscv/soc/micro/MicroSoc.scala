package vexiiriscv.soc.micro

import spinal.core._
import spinal.core.fiber.Fiber
import spinal.lib._
import spinal.lib.bus.amba3.apb.Apb3
import spinal.lib.bus.tilelink
import spinal.lib.bus.tilelink.{M2sSupport, M2sTransfers}
import spinal.lib.bus.tilelink.fabric.Node
import spinal.lib.com.spi.ddr.{SpiXdrMasterCtrl, SpiXdrParameter}
import spinal.lib.com.spi.xdr.TilelinkSpiXdrMasterFiber
import spinal.lib.com.uart.TilelinkUartFiber
import spinal.lib.misc.{Elf, TilelinkClintFiber}
import spinal.lib.misc.plic.TilelinkPlicFiber
import spinal.lib.system.tag.MemoryConnection
import vexiiriscv.soc.TilelinkVexiiRiscvFiber


// 定义SoC顶层
class MicroSoc(p : MicroSocParam) extends Component {
    // socCtrl为SoC提供时钟、复位控制器和调试模块（通过JTAG）
    val socCtrl = new SocCtrl(p.socCtrl)

    val system = new ClockingArea(socCtrl.system.cd) {
        // 定义主TileLink总线，CPU、RAM和外设“接口”将在其上连接
        val mainBus = tilelink.fabric.Node()

        val cpu = new TilelinkVexiiRiscvFiber(p.vexii.plugins())
        if(p.socCtrl.withDebug) socCtrl.debugModule.bindHart(cpu)
        mainBus << cpu.buses
        cpu.dBus.setDownConnection(a = StreamPipe.S2M)  // 在cpu.dBus上添加一些流水线以提高最大工作频率

        val ram = new tilelink.fabric.RamFiber(p.ramBytes)
        ram.up at 0x80000000l of mainBus

        // 处理所有IO/外设相关内容
        val peripheral = new Area {
            // 某些外设可能需要与CPU XLEN一样宽的访问，因此定义一个总线来保证这一点
            val busXlen = Node()
            busXlen.forceDataWidth(p.vexii.xlen)
            busXlen << mainBus
            busXlen.setUpConnection(a = StreamPipe.HALF, d = StreamPipe.HALF)

            // 大多数外设将使用32位数据总线
            val bus32 = Node()
            bus32.forceDataWidth(32)
            bus32 << busXlen

            // clint是一个标准的RISC-V定时器外设
            val clint = new TilelinkClintFiber()
            clint.node at 0x10010000 of busXlen

            // plic是一个标准的RISC-V中断控制器
            val plic = new TilelinkPlicFiber()
            plic.node at 0x10C00000 of bus32

            // 一个串口外设，定义在lib/com/uart/TilelinkUartCtrl.scala
            val uart = new TilelinkUartFiber()
            uart.node at 0x10001000 of bus32
            plic.mapUpInterrupt(1, uart.interrupt)

            // 一个SPI外设，定义在lib/com/spi/xdr/TilelinkSpiXdrMasterFiber.scala
            val spiFlash = p.withSpiFlash generate new TilelinkSpiXdrMasterFiber(SpiXdrMasterCtrl.MemoryMappingParameters(
                SpiXdrMasterCtrl.Parameters(8, 12, SpiXdrParameter(2, 2, 1)).addFullDuplex(0,1,false),
                xipEnableInit = true,
                xip = SpiXdrMasterCtrl.XipBusParameters(addressWidth = 24, lengthWidth = 6)
            )) {
                plic.mapUpInterrupt(2, interrupt)
                ctrl at 0x10002000 of bus32
                xip at 0x20000000 of bus32
            }

            // 在PeripheralDemo.scala的自定义外设
            val demo = p.demoPeripheral.map(new PeripheralDemoFiber(_){
                node at 0x10003000 of bus32
                plic.mapUpInterrupt(3, interrupt)
            })

            // 将一些CPU接口连接到各自的外设
            val cpuPlic = cpu.bind(plic)    // 外部中断连接
            val cpuClint = cpu.bind(clint)  // 定时器中断+时间参考+停止时间连接
        }

        val patcher = Fiber patch new Area {
            p.ramElf.foreach(new Elf(_, p.vexii.xlen).init(ram.thread.logic.mem, 0x80000000l))
            println(MemoryConnection.getMemoryTransfers(cpu.dBus).mkString("\n"))
        }
    }
}