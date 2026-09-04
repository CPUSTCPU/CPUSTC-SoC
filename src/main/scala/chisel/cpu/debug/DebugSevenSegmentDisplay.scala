package chisel.cpu.debug

import chisel3._
import chisel3.util.{log2Ceil, MuxLookup}

class DebugSevenSegmentDisplayIO extends Bundle {
  val value: UInt = Input(UInt(32.W))
  val csn:   UInt = Output(UInt(8.W))
  val aG:    UInt = Output(UInt(7.W))
}

/** 将 32 位调试数据按八位十六进制形式扫描到板载数码管。
  *
  * 该模块只实现调试显示所需的位扫描和十六进制段码，不包含显示源选择或跨时钟域处理。
  * CPUSTCSoc 在顶层选择本模块或 confreg 的正常数码管输出。
  */
class DebugSevenSegmentDisplay(clockHz: Int = 33_000_000, refreshHz: Int = 63) extends Module {
  require(clockHz > 0)
  require(refreshHz > 0)

  val io: DebugSevenSegmentDisplayIO = IO(new DebugSevenSegmentDisplayIO)

  private val cyclesPerDigit = math.max(1, clockHz / (refreshHz * 8))
  private val scanCounterWidth = math.max(1, log2Ceil(cyclesPerDigit))

  val scanCounter = RegInit(0.U(scanCounterWidth.W))
  val digitIndex = RegInit(0.U(3.W))

  when(scanCounter === (cyclesPerDigit - 1).U) {
    scanCounter := 0.U
    digitIndex := digitIndex + 1.U
  }.otherwise {
    scanCounter := scanCounter + 1.U
  }

  val digits = VecInit((0 until 8).map(index => io.value(index * 4 + 3, index * 4)))
  val digit = digits(7.U - digitIndex)

  io.csn := ~(1.U(8.W) << (7.U - digitIndex))
  io.aG := MuxLookup(digit, "b0000000".U)(Seq(
    0.U  -> "b1111110".U,
    1.U  -> "b0110000".U,
    2.U  -> "b1101101".U,
    3.U  -> "b1111001".U,
    4.U  -> "b0110011".U,
    5.U  -> "b1011011".U,
    6.U  -> "b1011111".U,
    7.U  -> "b1110000".U,
    8.U  -> "b1111111".U,
    9.U  -> "b1111011".U,
    10.U -> "b1110111".U,
    11.U -> "b0011111".U,
    12.U -> "b1001110".U,
    13.U -> "b0111101".U,
    14.U -> "b1001111".U,
    15.U -> "b1000111".U
  ))
}
