package chisel

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import chisel.axiInterconnect.usb.UsbPreSetupDebugMonitor
import org.scalatest.freespec.AnyFreeSpec

class UsbPreSetupDebugMonitorSpec extends AnyFreeSpec with ChiselScalatestTester {
  "monitor should retain the latest pre-SETUP timing and fault evidence" in {
    test(new UsbPreSetupDebugMonitor).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.io.txData.poke(0.U)
      dut.io.txValid.poke(false.B)
      dut.io.txReady.poke(false.B)
      dut.io.txLastAccepted.poke(false.B)
      dut.io.lineState.poke(0.U)
      dut.io.vbusValid.poke(false.B)
      dut.io.hostDisconnect.poke(false.B)
      dut.io.rxActive.poke(false.B)
      dut.io.phyXcvrSel.poke(0.U)
      dut.io.phyTermSel.poke(false.B)
      dut.io.phyOpMode.poke(0.U)
      dut.io.portState.poke(1.U)
      dut.io.portConnectPulse.poke(false.B)
      dut.io.portDisconnectPulse.poke(false.B)
      dut.io.portResetActive.poke(false.B)
      dut.io.rxEventOverflow.poke(false.B)
      dut.io.rxEventCollision.poke(false.B)
      dut.clock.step(2)

      dut.io.vbusValid.poke(true.B)
      dut.io.lineState.poke(1.U)
      dut.clock.step()
      assert((dut.io.eventFlags.peek().litValue & 0x1) != 0)
      val attachTimestamp = dut.io.attachCandidateTimestamp.peek().litValue

      dut.io.portConnectPulse.poke(true.B)
      dut.clock.step()
      dut.io.portConnectPulse.poke(false.B)
      assert((dut.io.eventFlags.peek().litValue & 0x2) != 0)

      dut.io.portState.poke(3.U)
      dut.io.lineState.poke(0.U)
      dut.io.portResetActive.poke(true.B)
      dut.clock.step()
      val resetStart = dut.io.resetStartTimestamp.peek().litValue
      dut.clock.step(5)
      dut.io.portResetActive.poke(false.B)
      dut.clock.step()
      val resetEnd = dut.io.resetEndTimestamp.peek().litValue
      dut.io.portState.poke(4.U)
      dut.io.lineState.poke(1.U)
      dut.clock.step()
      dut.io.attachCandidateTimestamp.expect(attachTimestamp.U)
      dut.io.resetCount.expect(1.U)
      dut.io.resetDurationCycles.expect((resetEnd - resetStart).U)
      assert((dut.io.eventFlags.peek().litValue & 0x40) != 0)

      def sendPid(pid: Int): Unit = {
        dut.io.txData.poke(pid.U)
        dut.io.txValid.poke(true.B)
        dut.io.txReady.poke(true.B)
        dut.io.txLastAccepted.poke(true.B)
        dut.clock.step()
        dut.io.txValid.poke(false.B)
        dut.io.txReady.poke(false.B)
        dut.io.txLastAccepted.poke(false.B)
      }

      sendPid(0xa5)
      val firstSof = dut.io.firstSofTimestamp.peek().litValue
      dut.clock.step(9)
      sendPid(0xa5)
      dut.io.sofCountBeforeSetup.expect(2.U)
      assert(dut.io.maxSofGapCycles.peek().litValue >= 10)

      sendPid(0x69)
      dut.io.unexpectedPacketCountBeforeSetup.expect(1.U)
      sendPid(0x2d)
      dut.io.setupCountAfterReset.expect(1.U)
      assert(dut.io.firstSetupTimestamp.peek().litValue > firstSof)
      val setupFlags = dut.io.eventFlags.peek().litValue
      assert((setupFlags & 0x10) != 0)
      assert((setupFlags & 0x20) != 0)
      assert((setupFlags & 0x40) == 0)

      sendPid(0x69)
      dut.io.unexpectedPacketCountBeforeSetup.expect(1.U)

      dut.io.rxEventOverflow.poke(true.B)
      dut.io.rxEventCollision.poke(true.B)
      dut.io.rxActive.poke(true.B)
      dut.io.phyOpMode.poke(1.U)
      dut.clock.step()
      val faultFlags = dut.io.eventFlags.peek().litValue
      assert((faultFlags & 0x200) != 0)
      assert((faultFlags & 0x400) != 0)
      assert((faultFlags & 0x800) != 0)

      dut.io.vbusValid.poke(false.B)
      dut.io.portDisconnectPulse.poke(true.B)
      dut.clock.step()
      dut.io.vbusFallCount.expect(1.U)
      dut.io.disconnectCount.expect(1.U)
    }
  }
}
