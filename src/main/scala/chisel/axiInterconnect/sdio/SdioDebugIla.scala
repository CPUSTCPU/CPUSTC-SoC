package chisel.axiInterconnect.sdio

import chisel3._

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
