package chisel

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.freespec.AnyFreeSpec

class SdioBlockGapCounterSpec extends AnyFreeSpec with ChiselScalatestTester {
  "counter latches system cycles and physical SD clock edges between read blocks" in {
    test(new SdioBlockGapCounter).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.io.datarState.poke(0.U)
      dut.io.sampleCe.poke(false.B)
      dut.io.sdDat.poke("hf".U)
      dut.io.sdClock.poke(false.B)
      dut.clock.step()

      dut.io.datarState.poke(2.U)
      dut.clock.step()
      dut.io.datarState.poke(0.U)
      dut.clock.step()
      dut.io.betweenBlocks.expect(true.B)

      Seq(true, false, true, false).foreach { level =>
        dut.io.sdClock.poke(level.B)
        dut.clock.step()
      }

      dut.io.datarState.poke(1.U)
      dut.io.sampleCe.poke(true.B)
      dut.io.sdDat.poke(0.U)
      dut.io.sdClock.poke(true.B)
      dut.io.blockStartToken.expect(true.B)
      dut.clock.step()

      dut.io.sampleCe.poke(false.B)
      dut.io.betweenBlocks.expect(false.B)
      dut.io.lastSystemCycles.expect(5.U)
      dut.io.lastSdClockEdges.expect(3.U)
      dut.io.valid.expect(true.B)
    }
  }
}
