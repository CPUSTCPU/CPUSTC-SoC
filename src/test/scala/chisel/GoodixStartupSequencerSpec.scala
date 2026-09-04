package chisel

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import chisel.axiInterconnect.lcd.{GoodixStartupSequencer, GoodixStartupSequencerIO}
import org.scalatest.freespec.AnyFreeSpec

private class GoodixStartupSequencerAsyncHarnessIO_sim extends Bundle {
  val restart: Bool = Input(Bool())
  val sequencer: GoodixStartupSequencerIO = new GoodixStartupSequencerIO
}

private class GoodixStartupSequencerAsyncHarness_sim(
    resetHoldCycles: Int,
    interruptHoldCycles: Int
) extends Module {
  val io: GoodixStartupSequencerAsyncHarnessIO_sim =
    IO(new GoodixStartupSequencerAsyncHarnessIO_sim)

  private val sequencer = withReset(io.restart.asAsyncReset) {
    Module(new GoodixStartupSequencer(resetHoldCycles, interruptHoldCycles))
  }

  io.sequencer <> sequencer.io
}

class GoodixStartupSequencerSpec extends AnyFreeSpec with ChiselScalatestTester {
  private val annotations = Seq(VerilatorBackendAnnotation)

  "GoodixStartupSequencer holds RESET and INT for exact cycle counts and restarts on asynchronous reset" in {
    val resetHoldCycles = 3
    val interruptHoldCycles = 2

    test(new GoodixStartupSequencerAsyncHarness_sim(resetHoldCycles, interruptHoldCycles))
      .withAnnotations(annotations) { dut =>
        def expectResetHold(): Unit = {
          dut.io.sequencer.resetn.expect(false.B)
          dut.io.sequencer.interruptOut.expect(false.B)
          dut.io.sequencer.interruptOutputEnable.expect(true.B)
          dut.io.sequencer.ready.expect(false.B)
        }

        def expectInterruptHold(): Unit = {
          dut.io.sequencer.resetn.expect(true.B)
          dut.io.sequencer.interruptOut.expect(false.B)
          dut.io.sequencer.interruptOutputEnable.expect(true.B)
          dut.io.sequencer.ready.expect(false.B)
        }

        def expectRunning(): Unit = {
          dut.io.sequencer.resetn.expect(true.B)
          dut.io.sequencer.interruptOut.expect(false.B)
          dut.io.sequencer.interruptOutputEnable.expect(false.B)
          dut.io.sequencer.ready.expect(true.B)
        }

        def runStartupSequence(): Unit = {
          expectResetHold()
          for (_ <- 1 until resetHoldCycles) {
            dut.clock.step()
            expectResetHold()
          }

          dut.clock.step()
          expectInterruptHold()
          for (_ <- 1 until interruptHoldCycles) {
            dut.clock.step()
            expectInterruptHold()
          }

          dut.clock.step()
          expectRunning()
        }

        dut.reset.poke(false.B)
        dut.io.restart.poke(true.B)
        expectResetHold()
        dut.clock.step(2)
        expectResetHold()

        dut.io.restart.poke(false.B)
        runStartupSequence()

        dut.io.restart.poke(true.B)
        expectResetHold()
        dut.clock.step()
        expectResetHold()

        dut.io.restart.poke(false.B)
        runStartupSequence()
      }
  }
}
