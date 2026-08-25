package chisel

import chisel3._
import chisel3.experimental.ExtModule

class SdioBlockGapCounter extends Module {
  val io = IO(new Bundle {
    val datarState: UInt = Input(UInt(3.W))
    val sampleCe: Bool = Input(Bool())
    val sdDat: UInt = Input(UInt(4.W))
    val sdClock: Bool = Input(Bool())
    val betweenBlocks: Bool = Output(Bool())
    val blockStartToken: Bool = Output(Bool())
    val lastSystemCycles: UInt = Output(UInt(32.W))
    val lastSdClockEdges: UInt = Output(UInt(32.W))
    val valid: Bool = Output(Bool())
  })

  private val previousDatarState = RegNext(io.datarState, 0.U)
  private val previousSdClock = RegNext(io.sdClock, false.B)
  private val betweenBlocks = RegInit(false.B)
  private val systemCycles = RegInit(0.U(32.W))
  private val sdClockEdges = RegInit(0.U(32.W))
  private val lastSystemCycles = RegInit(0.U(32.W))
  private val lastSdClockEdges = RegInit(0.U(32.W))
  private val valid = RegInit(false.B)

  private val blockEnd = previousDatarState === 2.U && io.datarState === 0.U
  private val blockStartToken = io.datarState === 1.U && io.sampleCe && io.sdDat === 0.U
  private val sdClockRise = io.sdClock && !previousSdClock
  private val nextSystemCycles = Mux(systemCycles.andR, systemCycles, systemCycles + 1.U)
  private val nextSdClockEdges = Mux(
    sdClockEdges.andR || !sdClockRise,
    sdClockEdges,
    sdClockEdges + 1.U
  )

  when(blockEnd) {
    betweenBlocks := true.B
    systemCycles := 0.U
    sdClockEdges := 0.U
  }.elsewhen(betweenBlocks) {
    systemCycles := nextSystemCycles
    sdClockEdges := nextSdClockEdges
    when(blockStartToken) {
      betweenBlocks := false.B
      lastSystemCycles := nextSystemCycles
      lastSdClockEdges := nextSdClockEdges
      valid := true.B
    }
  }

  io.betweenBlocks := betweenBlocks
  io.blockStartToken := blockStartToken
  io.lastSystemCycles := lastSystemCycles
  io.lastSdClockEdges := lastSdClockEdges
  io.valid := valid
}

/** Source-connected ILA for the LiteSD receive and CRC path. */
class SdioCrcIla extends ExtModule {
  override def desiredName: String = "ila_sdio_crc_source"

  val clk: Clock = IO(Input(Clock()))
  val probe0: Bool = IO(Input(Bool()))
  val probe1: Bool = IO(Input(Bool()))
  val probe2: Bool = IO(Input(Bool()))
  val probe3: UInt = IO(Input(UInt(4.W)))
  val probe4: UInt = IO(Input(UInt(5.W)))
  val probe5: UInt = IO(Input(UInt(3.W)))
  val probe6: UInt = IO(Input(UInt(10.W)))
  val probe7: UInt = IO(Input(UInt(8.W)))
  val probe8: UInt = IO(Input(UInt(16.W)))
  val probe9: UInt = IO(Input(UInt(16.W)))
  val probe10: UInt = IO(Input(UInt(16.W)))
  val probe11: UInt = IO(Input(UInt(16.W)))
  val probe12: UInt = IO(Input(UInt(4.W)))
  val probe13: UInt = IO(Input(UInt(4.W)))
  val probe14: Bool = IO(Input(Bool()))
  val probe15: Bool = IO(Input(Bool()))
  val probe16: Bool = IO(Input(Bool()))
  val probe17: Bool = IO(Input(Bool()))
  val probe18: Bool = IO(Input(Bool()))
  val probe19: Bool = IO(Input(Bool()))
  val probe20: Bool = IO(Input(Bool()))
  val probe21: Bool = IO(Input(Bool()))
  val probe22: Bool = IO(Input(Bool()))
  val probe23: Bool = IO(Input(Bool()))
  val probe24: Bool = IO(Input(Bool()))
  val probe25: Bool = IO(Input(Bool()))
  val probe26: Bool = IO(Input(Bool()))
  val probe27: UInt = IO(Input(UInt(9.W)))
  val probe28: UInt = IO(Input(UInt(16.W)))
  val probe29: Bool = IO(Input(Bool()))
  val probe30: Bool = IO(Input(Bool()))
  val probe31: UInt = IO(Input(UInt(8.W)))
  val probe32: Bool = IO(Input(Bool()))
  val probe33: Bool = IO(Input(Bool()))
  val probe34: UInt = IO(Input(UInt(32.W)))
  val probe35: UInt = IO(Input(UInt(32.W)))
  val probe36: Bool = IO(Input(Bool()))
}

/** Source-connected ILA for the selected LiteSD DMA stream and AXI path. */
class SdioAxiIla extends ExtModule {
  override def desiredName: String = "ila_sdio_axi_source"

  val clk: Clock = IO(Input(Clock()))
  val probe0: Bool = IO(Input(Bool()))
  val probe1: Bool = IO(Input(Bool()))
  val probe2: UInt = IO(Input(UInt(32.W)))
  val probe3: UInt = IO(Input(UInt(21.W)))
  val probe4: Bool = IO(Input(Bool()))
  val probe5: Bool = IO(Input(Bool()))
  val probe6: UInt = IO(Input(UInt(32.W)))
  val probe7: UInt = IO(Input(UInt(4.W)))
  val probe8: Bool = IO(Input(Bool()))
  val probe9: Bool = IO(Input(Bool()))
  val probe10: Bool = IO(Input(Bool()))
  val probe11: UInt = IO(Input(UInt(32.W)))
  val probe12: UInt = IO(Input(UInt(3.W)))
  val probe13: UInt = IO(Input(UInt(21.W)))
  val probe14: UInt = IO(Input(UInt(4.W)))
  val probe15: Bool = IO(Input(Bool()))
  val probe16: UInt = IO(Input(UInt(32.W)))
  val probe17: UInt = IO(Input(UInt(8.W)))
  val probe18: Bool = IO(Input(Bool()))
  val probe19: Bool = IO(Input(Bool()))
  val probe20: UInt = IO(Input(UInt(32.W)))
  val probe21: UInt = IO(Input(UInt(4.W)))
  val probe22: Bool = IO(Input(Bool()))
  val probe23: Bool = IO(Input(Bool()))
  val probe24: Bool = IO(Input(Bool()))
  val probe25: UInt = IO(Input(UInt(2.W)))
  val probe26: Bool = IO(Input(Bool()))
  val probe27: Bool = IO(Input(Bool()))
}
