package cpustc.interrupt

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.apb.Apb3
import spinal.lib.misc.Apb3InterruptCtrl

/** 将多个外设中断锁存、屏蔽并经一根 CPU 中断线汇总，不负责输入跨时钟域同步。 */
case class CascadedInterruptCtrl(width: Int = 8) extends Component {
  require(width > 0, "interrupt width must be positive")

  val io = new Bundle {
    val apb = slave(Apb3(addressWidth = 4, dataWidth = 32))
    val inputs = in Bits (width bits)
    val pendings = out Bits (width bits)
    val interrupt = out Bool ()
  }

  private val ctrl = Apb3InterruptCtrl(width)
  ctrl.io.bus <> io.apb
  ctrl.io.inputs := io.inputs

  io.pendings := ctrl.io.pendings
  io.interrupt := ctrl.io.pendings.orR
}
