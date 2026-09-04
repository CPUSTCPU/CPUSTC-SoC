package chisel

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import chisel.cpu.debug.RetirementStallWatchdog
import org.scalatest.freespec.AnyFreeSpec

private object RetirementStallWatchdogSpec {
  val LaneCount = 3
  val ThresholdCycles = 4

  def initialize(dut: RetirementStallWatchdog): Unit = {
    dut.io.clear.poke(false.B)
    dut.io.commitValid.poke(0.U)
    for (lane <- 0 until LaneCount) {
      dut.io.commitPc(lane).poke(0.U)
    }
  }

  def reset(dut: RetirementStallWatchdog): Unit = {
    initialize(dut)
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)
  }
}

class RetirementStallWatchdogSpec extends AnyFreeSpec with ChiselScalatestTester {
  import RetirementStallWatchdogSpec._

  private val annotations = Seq(VerilatorBackendAnnotation)

  "RetirementStallWatchdog should reset, trigger at the threshold, and saturate" in {
    test(new RetirementStallWatchdog(LaneCount, ThresholdCycles))
      .withAnnotations(annotations) { dut =>
        reset(dut)
        dut.io.noRetireCount.expect(0.U)
        dut.io.trigger.expect(false.B)
        dut.io.lastCommitPc.expect(0.U)

        for (expectedCount <- 1 until ThresholdCycles) {
          dut.clock.step()
          dut.io.noRetireCount.expect(expectedCount.U)
          dut.io.trigger.expect(false.B)
        }

        dut.clock.step()
        dut.io.noRetireCount.expect(ThresholdCycles.U)
        dut.io.trigger.expect(true.B)

        dut.clock.step(3)
        dut.io.noRetireCount.expect(ThresholdCycles.U)
        dut.io.trigger.expect(true.B)
      }
  }

  "RetirementStallWatchdog should clear the count on any commit and retain the highest valid lane PC" in {
    test(new RetirementStallWatchdog(LaneCount, ThresholdCycles))
      .withAnnotations(annotations) { dut =>
        reset(dut)
        dut.clock.step(2)
        dut.io.noRetireCount.expect(2.U)

        dut.io.commitPc(0).poke("h11111111".U)
        dut.io.commitPc(1).poke("h22222222".U)
        dut.io.commitPc(2).poke("h33333333".U)
        dut.io.commitValid.poke("b101".U)
        dut.clock.step()

        dut.io.noRetireCount.expect(0.U)
        dut.io.trigger.expect(false.B)
        dut.io.lastCommitPc.expect("h33333333".U)

        dut.io.commitValid.poke("b010".U)
        dut.clock.step()
        dut.io.noRetireCount.expect(0.U)
        dut.io.lastCommitPc.expect("h22222222".U)
      }
  }

  "RetirementStallWatchdog clear should zero all state with priority over commit" in {
    test(new RetirementStallWatchdog(LaneCount, ThresholdCycles))
      .withAnnotations(annotations) { dut =>
        reset(dut)

        dut.io.commitPc(1).poke("h89abcdef".U)
        dut.io.commitValid.poke("b010".U)
        dut.clock.step()
        dut.io.lastCommitPc.expect("h89abcdef".U)

        dut.io.commitValid.poke(0.U)
        dut.clock.step(ThresholdCycles)
        dut.io.trigger.expect(true.B)

        dut.io.clear.poke(true.B)
        dut.io.commitValid.poke("b111".U)
        dut.io.commitPc(2).poke("hdeadbeef".U)
        dut.clock.step()

        dut.io.noRetireCount.expect(0.U)
        dut.io.trigger.expect(false.B)
        dut.io.lastCommitPc.expect(0.U)
      }
  }
}
