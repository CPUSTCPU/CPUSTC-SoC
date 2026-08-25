package cpustc.usb

import cpustc.usb.sim.Usb3500UtmiPhyAgent
import cpustc.usb.utmi.{
  Usb3500UtmiIo,
  UsbHubLsFsToUtmi,
  UsbHubLsFsToUtmiDebug,
  UsbHubLsFsToUtmiTiming
}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.com.usb.ohci.UsbPid
import spinal.lib.com.usb.phy.{UsbHubLsFs, UsbLsFsPhyAbstractIo}
import spinal.lib.com.usb.sim.{UsbDeviceAgent, UsbDeviceAgentListener, UsbLsFsPhyAbstractIoAgent}

import scala.collection.mutable.ArrayBuffer

private case class UsbHubLsFsToUtmiSimTop(timing: UsbHubLsFsToUtmiTiming) extends Component {
  val io = new Bundle {
    val ctrl = slave(UsbHubLsFs.Ctrl(1))
    val utmi = master(Usb3500UtmiIo())
    val debug = out(
      UsbHubLsFsToUtmiDebug(
        waitCounterWidth = log2Up(timing.txEopTimeoutCycles),
        ipdCounterWidth = log2Up(
          timing.fullSpeedInterPacketCycles max timing.lowSpeedInterPacketCycles
        ),
        chirpFilterCounterWidth = log2Up(timing.chirpFilterCycles + 1)
      )
    )

    // 纯仿真虚拟总线；全部叶子作为 testbench 驱动端口。
    val usb = UsbLsFsPhyAbstractIo()
    in(usb.tx.enable, usb.tx.data, usb.tx.se0, usb.rx.dp, usb.rx.dm)
  }

  val adapter = UsbHubLsFsToUtmi(timing)
  io.ctrl <> adapter.io.ctrl
  io.utmi <> adapter.io.utmi
  io.debug := adapter.io.debug
}

class UsbHubLsFsToUtmiSpec extends AnyFunSuite {
  private val timing = UsbHubLsFsToUtmiTiming(
    attachDebounceCycles = 8,
    disconnectCycles = 128,
    resetCycles = 32,
    resumeCycles = 32,
    txEopTimeoutCycles = 1024
  )

  private val setupToken = Seq(0x2d, 0x00, 0x10)
  private val outToken = Seq(UsbPid.token(UsbPid.OUT), 0x01, 0xe8)
  private val inToken = Seq(UsbPid.token(UsbPid.IN), 0x00, 0x10)
  private val sofToken = Seq(UsbPid.token(UsbPid.SOF), 0x34, 0x12)
  private val getDescriptorData0 = Seq(0xc3, 0x80, 0x06, 0x00, 0x01, 0x00, 0x00, 0x40, 0x00, 0xdd, 0x94)
  private val outData1 = Seq(UsbPid.token(UsbPid.DATA1), 0x55, 0xaa, 0x41, 0x60)

  for ((speedName, lowSpeed) <- Seq("low-speed" -> true, "full-speed" -> false)) {
    test(s"single adapter DUT reaches the official USB device transaction model at $speedName") {
      compileDut().doSim { dut =>
        initialize(dut)
        val physical = new UsbLsFsPhyAbstractIoAgent(dut.io.usb, dut.clockDomain, 5)
        val device = new UsbDeviceAgent(physical)
        val phy = new Usb3500UtmiPhyAgent(dut.io.utmi, dut.io.usb, dut.clockDomain)
        phy.initialize()
        phy.start()

        var resetSeen = false
        var setupSeen = false
        val received = ArrayBuffer.empty[Int]
        fork {
          while (received.isEmpty) {
            dut.clockDomain.waitSampling()
            if (dut.io.ctrl.rx.flow.valid.toBoolean) {
              received += dut.io.ctrl.rx.flow.data.toInt
            }
          }
        }

        device.listener = new UsbDeviceAgentListener {
          override def reset(): Unit = resetSeen = true

          override def hcToUsb(
              addr: Int,
              endp: Int,
              tokenPid: Int,
              dataPid: Int,
              data: scala.collection.Seq[Int]
          ): Unit = {
            assert(addr == 0)
            assert(endp == 0)
            assert(tokenPid == UsbPid.SETUP)
            assert(dataPid == UsbPid.DATA0)
            assert(data == Seq(0x80, 0x06, 0x00, 0x01, 0x00, 0x00, 0x40, 0x00))
            setupSeen = true
            physical.emitBytes(UsbPid.ACK, Seq.empty, crc16 = false, turnaround = true, ls = lowSpeed)
          }

          override def usbToHc(addr: Int, endp: Int): Boolean = false
        }

        enablePort(dut, physical, lowSpeed)
        waitUntil(dut, 200)(resetSeen)
        sendPacket(dut, setupToken)
        sendPacket(dut, getDescriptorData0)
        waitUntil(dut, 20000)(setupSeen)
        waitUntil(dut, 1000)(phy.physicalTx.size == 2)
        waitUntil(dut, 1000)(received.nonEmpty)
        waitUntil(dut, 1000)(phy.physicalRx.nonEmpty)

        assert(phy.txRuns.map(_.bytes) == Seq(setupToken.toVector, getDescriptorData0.toVector))
        assert(phy.txRuns.forall(_.lowSpeed == lowSpeed))
        assert(phy.physicalTx.size == 2)
        assert(phy.physicalRx == Seq(Vector(UsbPid.token(UsbPid.ACK))))
        assert(received == Seq(UsbPid.token(UsbPid.ACK)))
      }
    }
  }

  test("full-speed hub carries low-speed OUT and IN transactions before returning to full-speed traffic") {
    compileDut().doSim { dut =>
      initialize(dut)
      val physical = new UsbLsFsPhyAbstractIoAgent(dut.io.usb, dut.clockDomain, 5)
      val phy = new Usb3500UtmiPhyAgent(dut.io.utmi, dut.io.usb, dut.clockDomain)
      phy.initialize()
      phy.start()

      val received = ArrayBuffer.empty[Int]
      val unsafeModeChanges = ArrayBuffer.empty[(Long, Int, Int, Boolean, Boolean)]
      var previousXcvrSel = dut.io.utmi.xcvrSel.toInt
      var previousTxValid = dut.io.utmi.txValid.toBoolean
      var previousRxActive = dut.io.utmi.rxActive.toBoolean
      var lowSpeedRxSamples = 0
      dut.clockDomain.onSamplings {
        val xcvrSel = dut.io.utmi.xcvrSel.toInt
        val txValid = dut.io.utmi.txValid.toBoolean
        val rxActive = dut.io.utmi.rxActive.toBoolean
        if (
          xcvrSel != previousXcvrSel &&
          (txValid || previousTxValid || rxActive || previousRxActive)
        ) {
          unsafeModeChanges += ((phy.cycle, previousXcvrSel, xcvrSel, txValid, rxActive))
        }
        if (rxActive) {
          assert(xcvrSel == 3, s"low-speed hub response used XCVRSEL=$xcvrSel instead of 3")
          lowSpeedRxSamples += 1
        }
        if (dut.io.ctrl.rx.flow.valid.toBoolean) {
          received += dut.io.ctrl.rx.flow.data.toInt
        }
        previousXcvrSel = xcvrSel
        previousTxValid = txValid
        previousRxActive = rxActive
      }

      enablePort(dut, physical, lowSpeed = false)
      assert(!dut.io.debug.portLowSpeed.toBoolean, "the physical root port was not full-speed")

      dut.io.ctrl.lowSpeed #= true
      sendPacket(dut, setupToken)
      sendPacket(dut, getDescriptorData0)
      waitUntil(dut, 30000)(phy.physicalTx.size == 2)

      assert(phy.txRuns.take(2).forall(_.lowSpeed), "hub-targeted packets did not use low-speed data timing")
      assert(phy.txRuns.take(2).forall(_.preamble), "USB3500 did not prepend PRE to both low-speed packets")
      assert(phy.physicalTx.take(2).forall(_.preamble), "physical PRE coverage was not recorded")
      val setupEopJStartCycle = phy.physicalTx.head.eopStartCycle + 80
      assert(
        phy.txRuns(1).startCycle - setupEopJStartCycle >= timing.lowSpeedInterPacketCycles,
        "hub low-speed DATA started before the 80-cycle inter-packet delay completed"
      )
      assert(dut.io.utmi.xcvrSel.toInt == 3, "adapter did not retain hub low-speed receive mode")

      physical.emitBytes(UsbPid.ACK, Seq.empty, crc16 = false, turnaround = true, ls = true)
      physical.waitDone()
      waitUntil(dut, 2000)(received.contains(UsbPid.token(UsbPid.ACK)))

      sendPacket(dut, inToken)
      waitUntil(dut, 20000)(phy.physicalTx.size == 3)
      physical.emitBytes(UsbPid.DATA0, Seq(0x5a), crc16 = true, turnaround = true, ls = true)
      physical.waitDone()
      waitUntil(dut, 3000)(phy.physicalRx.size == 2)
      sendPacket(dut, Seq(UsbPid.token(UsbPid.ACK)))
      waitUntil(dut, 20000)(phy.physicalTx.size == 4)

      assert(phy.txRuns.take(4).forall(_.lowSpeed))
      assert(phy.txRuns.take(4).forall(_.preamble), "one low-speed host packet was sent without PRE")
      assert(lowSpeedRxSamples > 0, "no low-speed hub response reached the UTMI receive interface")

      dut.io.ctrl.lowSpeed #= false
      waitUntil(dut, 100)(dut.io.utmi.xcvrSel.toInt == 1)
      sendPacket(dut, sofToken)
      waitUntil(dut, 20000)(phy.physicalTx.size == 5)

      assert(!phy.txRuns.last.lowSpeed, "full-speed traffic retained low-speed timing")
      assert(!phy.txRuns.last.preamble, "full-speed SOF was incorrectly preceded by PRE")
      assert(unsafeModeChanges.isEmpty, s"XCVRSEL changed while TX/RX was active: $unsafeModeChanges")
    }
  }

  for ((speedName, lowSpeed, token, data, ipdCycles) <- Seq(
      (
        "low-speed SETUP/DATA0",
        true,
        setupToken,
        getDescriptorData0,
        timing.lowSpeedInterPacketCycles
      ),
      ("full-speed OUT/DATA1", false, outToken, outData1, timing.fullSpeedInterPacketCycles)
    )) {
    test(s"$speedName pair defers the token until DATA is complete and reports two logical EOPs") {
      compileDut().doSim { dut =>
        initialize(dut)
        val physical = new UsbLsFsPhyAbstractIoAgent(dut.io.usb, dut.clockDomain, 5)
        val phy = new Usb3500UtmiPhyAgent(dut.io.utmi, dut.io.usb, dut.clockDomain)
        phy.initialize()
        phy.start()

        val eopCycles = ArrayBuffer.empty[Long]
        var monitorPhysicalEop = false
        var sawPhysicalSe0 = false
        var physicalEopCount = 0
        dut.clockDomain.onSamplings {
          val lineState = dut.io.utmi.lineState.toInt
          if (monitorPhysicalEop) {
            if (lineState == 0) {
              sawPhysicalSe0 = true
            } else if (sawPhysicalSe0 && lineState == (if (lowSpeed) 2 else 1)) {
              physicalEopCount += 1
              sawPhysicalSe0 = false
            }
          }
          if (dut.io.ctrl.txEop.toBoolean) {
            if (eopCycles.nonEmpty) {
              assert(physicalEopCount == 2, "paired DATA txEop did not follow the second physical EOP")
              assert(lineState == (if (lowSpeed) 2 else 1), "paired DATA txEop did not coincide with J")
            }
            eopCycles += phy.cycle
          }
        }

        enablePort(dut, physical, lowSpeed)
        monitorPhysicalEop = true
        for (byte <- token.dropRight(1)) pushByte(dut, byte, last = false)
        dut.clockDomain.waitSampling(4)
        assert(eopCycles.isEmpty, "paired token produced logical EOP before its last byte")
        assert(!dut.io.utmi.txValid.toBoolean, "partial paired token reached UTMI")
        pushByte(dut, token.last, last = true)
        waitUntil(dut, 20)(eopCycles.size == 1)
        assert(phy.txRuns.isEmpty, "paired token reached UTMI before the DATA packet was complete")
        assert(phy.physicalTx.isEmpty, "paired token reached the USB bus before the DATA packet was complete")
        assert(!dut.io.utmi.txValid.toBoolean, "paired token asserted TXVALID before DATA was complete")

        for (byte <- data.dropRight(1)) pushByte(dut, byte, last = false)
        dut.clockDomain.waitSampling(4)
        assert(eopCycles.size == 1, "partial DATA packet produced an extra logical EOP")
        assert(phy.txRuns.isEmpty, "paired token reached UTMI before the DATA last byte was accepted")
        assert(!dut.io.utmi.txValid.toBoolean, "paired token asserted TXVALID before the DATA last byte was accepted")
        pushByte(dut, data.last, last = true)

        waitUntil(dut, 20000)(phy.physicalTx.nonEmpty)
        assert(eopCycles.size == 1, "the paired token physical EOP produced a second logical EOP")
        waitUntil(dut, 20000)(phy.txRuns.size == 2)
        assert(phy.txRuns.map(_.bytes) == Seq(token.toVector, data.toVector))
        assert(phy.txRuns.forall(_.lowSpeed == lowSpeed))
        val eopJStartCycle = phy.physicalTx.head.eopStartCycle + (if (lowSpeed) 80 else 10)
        assert(
          phy.txRuns(1).startCycle - eopJStartCycle >= ipdCycles,
          s"DATA started before the $speedName inter-packet delay completed"
        )

        waitUntil(dut, 20000)(phy.physicalTx.size == 2)
        waitUntil(dut, 20)(eopCycles.size == 2)
        assert(eopCycles.size == 2, "paired transaction did not produce exactly two logical EOPs")
      }
    }
  }

  test("IN SOF and unpaired packets report txEop only after their physical EOP") {
    compileDut().doSim { dut =>
      initialize(dut)
      val physical = new UsbLsFsPhyAbstractIoAgent(dut.io.usb, dut.clockDomain, 5)
      val phy = new Usb3500UtmiPhyAgent(dut.io.utmi, dut.io.usb, dut.clockDomain)
      phy.initialize()
      phy.start()

      var eopCount = 0
      var monitorPhysicalEop = false
      var sawPhysicalSe0 = false
      dut.clockDomain.onSamplings {
        val lineState = dut.io.utmi.lineState.toInt
        if (monitorPhysicalEop && lineState == 0) sawPhysicalSe0 = true
        if (dut.io.ctrl.txEop.toBoolean) {
          assert(sawPhysicalSe0, "unpaired packet txEop asserted before physical SE0")
          assert(lineState == 1, "unpaired packet txEop did not coincide with the return to J")
          sawPhysicalSe0 = false
          eopCount += 1
        }
      }

      enablePort(dut, physical, lowSpeed = false)
      monitorPhysicalEop = true
      val packets = Seq(
        "IN" -> inToken,
        "SOF" -> sofToken,
        "ACK" -> Seq(UsbPid.token(UsbPid.ACK)),
        "unpaired DATA0" -> Seq(UsbPid.token(UsbPid.DATA0), 0x5a, 0xc0, 0x84)
      )

      for (((packetName, packet), index) <- packets.zipWithIndex) {
        enqueuePacket(dut, packet)
        waitUntil(dut, 20)(dut.io.utmi.txValid.toBoolean)
        assert(eopCount == index, s"$packetName produced txEop before physical EOP")
        waitUntil(dut, 20000)(phy.physicalTx.size == index + 1)
        waitUntil(dut, 20)(eopCount == index + 1)
        assert(phy.txRuns(index).bytes == packet.toVector)
      }
    }
  }

  test("port disable before physical launch clears the deferred pair state") {
    compileDut().doSim { dut =>
      initialize(dut)
      val physical = new UsbLsFsPhyAbstractIoAgent(dut.io.usb, dut.clockDomain, 5)
      val phy = new Usb3500UtmiPhyAgent(dut.io.utmi, dut.io.usb, dut.clockDomain)
      phy.initialize()
      phy.start()

      var eopCount = 0
      dut.clockDomain.onSamplings {
        if (dut.io.ctrl.txEop.toBoolean) eopCount += 1
      }

      enablePort(dut, physical, lowSpeed = false)
      enqueuePacket(dut, outToken)
      waitUntil(dut, 20)(eopCount == 1)
      assert(phy.txRuns.isEmpty, "deferred OUT token launched before port disable")

      val disable = dut.io.ctrl.ports(0).disable
      disable.valid #= true
      dut.clockDomain.waitSampling()
      disable.valid #= false
      waitUntil(dut, 20) {
        dut.io.debug.txBufferState0.toInt == 0 &&
        dut.io.debug.txBufferState1.toInt == 0 &&
        !dut.io.utmi.txValid.toBoolean
      }
      dut.clockDomain.waitSampling(4)
      assert(eopCount == 1, "port disable repeated the deferred token logical EOP")
      assert(phy.txRuns.isEmpty, "deferred token reached UTMI while the port was disabled")

      enqueuePacket(dut, outData1)
      waitUntil(dut, 20)(eopCount == 2)
      assert(!dut.io.utmi.txValid.toBoolean, "dropped paired DATA asserted TXVALID")
      assert(phy.txRuns.isEmpty, "dropped paired DATA reached UTMI")

      enablePortDirect(dut, lowSpeed = false)
      val standaloneData = Seq(UsbPid.token(UsbPid.DATA1), 0x33, 0x00, 0xaa)
      enqueuePacket(dut, standaloneData)
      waitUntil(dut, 20)(dut.io.utmi.txValid.toBoolean)
      dut.clockDomain.waitSampling(16)
      assert(eopCount == 2, "standalone DATA used stale deferred-pair completion state")
      waitUntil(dut, 20000)(phy.physicalTx.size == 1)
      waitUntil(dut, 20)(eopCount == 3)
      assert(phy.txRuns.map(_.bytes) == Seq(standaloneData.toVector))
    }
  }

  test("paired token followed by a non-DATA packet enters PairProtocol fault") {
    compileDut().doSim { dut =>
      initialize(dut)
      enablePortDirect(dut, lowSpeed = false)

      var eopCount = 0
      dut.clockDomain.onSamplings {
        if (dut.io.ctrl.txEop.toBoolean) eopCount += 1
      }

      enqueuePacket(dut, outToken)
      waitUntil(dut, 20)(eopCount == 1)
      enqueuePacket(dut, Seq(UsbPid.token(UsbPid.ACK)))

      waitUntil(dut, 20)(dut.io.debug.txFault.toBoolean)
      waitUntil(dut, 20)(eopCount == 2)
      assert(dut.io.debug.txFaultReason.toInt == 3)
      assert(!dut.io.debug.txLaunchAllowed.toBoolean)
      assert(!dut.io.utmi.txValid.toBoolean)
      assert(!dut.io.ctrl.tx.ready.toBoolean)

      dut.io.ctrl.ports(0).power #= false
      dut.clockDomain.waitSampling(4)
      assert(dut.io.debug.txFault.toBoolean)
      assert(eopCount == 2)
    }
  }

  test("paired token and DATA with different transaction speeds enter PairProtocol fault") {
    compileDut().doSim { dut =>
      initialize(dut)
      enablePortDirect(dut, lowSpeed = false)

      var eopCount = 0
      dut.clockDomain.onSamplings {
        if (dut.io.ctrl.txEop.toBoolean) eopCount += 1
      }

      dut.io.ctrl.lowSpeed #= true
      enqueuePacket(dut, outToken)
      waitUntil(dut, 20)(eopCount == 1)

      dut.io.ctrl.lowSpeed #= false
      enqueuePacket(dut, outData1)

      waitUntil(dut, 20)(dut.io.debug.txFault.toBoolean)
      waitUntil(dut, 20)(eopCount == 2)
      assert(dut.io.debug.txFaultReason.toInt == 3)
      assert(!dut.io.utmi.txValid.toBoolean)
      assert(!dut.io.ctrl.tx.ready.toBoolean)
    }
  }

  test("port disable during paired token transmission reports one DATA termination EOP") {
    compileDut().doSim { dut =>
      initialize(dut)
      enablePortDirect(dut, lowSpeed = false)

      var eopCount = 0
      dut.clockDomain.onSamplings {
        if (dut.io.ctrl.txEop.toBoolean) eopCount += 1
      }

      enqueuePacket(dut, outToken)
      waitUntil(dut, 20)(eopCount == 1)
      enqueuePacket(dut, outData1)
      waitUntil(dut, 100)(dut.io.utmi.txValid.toBoolean)

      val disable = dut.io.ctrl.ports(0).disable
      disable.valid #= true
      dut.clockDomain.waitSampling()
      disable.valid #= false

      waitUntil(dut, 20)(eopCount == 2)
      waitUntil(dut, 20) {
        dut.io.debug.txBufferState0.toInt == 0 &&
        dut.io.debug.txBufferState1.toInt == 0 &&
        !dut.io.utmi.txValid.toBoolean
      }
      assert(!dut.io.debug.txFault.toBoolean)
      dut.clockDomain.waitSampling(4)
      assert(eopCount == 2)
    }
  }

  test("paired token WAIT_SE0 timeout reports DATA completion before locking TX") {
    compileDut().doSim { dut =>
      initialize(dut)
      enablePortDirect(dut, lowSpeed = false)

      var eopCount = 0
      dut.clockDomain.onSamplings {
        if (dut.io.ctrl.txEop.toBoolean) eopCount += 1
      }

      enqueuePacket(dut, outToken)
      waitUntil(dut, 20)(eopCount == 1)
      enqueuePacket(dut, outData1)
      acceptUtmiPacketDirect(dut, outToken)

      waitUntil(dut, timing.txEopTimeoutCycles + 20)(dut.io.debug.txFault.toBoolean)
      waitUntil(dut, 20)(eopCount == 2)
      assert(dut.io.debug.txFaultReason.toInt == 1)
      assert(!dut.io.debug.txLaunchAllowed.toBoolean)
      assert(!dut.io.utmi.txValid.toBoolean)
    }
  }

  test("paired DATA WAIT_J timeout reports DATA completion before locking TX") {
    compileDut().doSim { dut =>
      initialize(dut)
      enablePortDirect(dut, lowSpeed = false)

      var eopCount = 0
      dut.clockDomain.onSamplings {
        if (dut.io.ctrl.txEop.toBoolean) eopCount += 1
      }

      enqueuePacket(dut, outToken)
      waitUntil(dut, 20)(eopCount == 1)
      enqueuePacket(dut, outData1)
      acceptUtmiPacketDirect(dut, outToken)

      dut.io.utmi.lineState #= 0
      dut.clockDomain.waitSampling(2)
      dut.io.utmi.lineState #= 1
      acceptUtmiPacketDirect(dut, outData1)

      dut.io.utmi.lineState #= 0
      dut.clockDomain.waitSampling(2)
      dut.io.utmi.lineState #= 2
      waitUntil(dut, timing.txEopTimeoutCycles + 20)(dut.io.debug.txFault.toBoolean)
      waitUntil(dut, 20)(eopCount == 2)
      assert(dut.io.debug.txFaultReason.toInt == 2)
      assert(!dut.io.debug.txLaunchAllowed.toBoolean)
      assert(!dut.io.utmi.txValid.toBoolean)
    }
  }

  test("adapter keeps one TXVALID run across source Stream bubbles") {
    compileDut().doSim { dut =>
      initialize(dut)
      val physical = new UsbLsFsPhyAbstractIoAgent(dut.io.usb, dut.clockDomain, 5)
      val phy = new Usb3500UtmiPhyAgent(
        dut.io.utmi,
        dut.io.usb,
        dut.clockDomain
      )
      phy.initialize()
      phy.start()

      enablePort(dut, physical, lowSpeed = true)
      sendPacket(dut, inToken, gapCyclesAfterByte = 2)
      waitUntil(dut, 100)(phy.txRuns.nonEmpty)

      assert(phy.txRuns.map(_.bytes) == Seq(inToken.toVector))
    }
  }

  test("adapter waits for last and advances every cycle of a three-cycle first TXREADY") {
    compileDut().doSim { dut =>
      initialize(dut)
      val physical = new UsbLsFsPhyAbstractIoAgent(dut.io.usb, dut.clockDomain, 5)
      val phy = new Usb3500UtmiPhyAgent(
        dut.io.utmi,
        dut.io.usb,
        dut.clockDomain,
        serializeHostPackets = false,
        firstReadyLatencyCycles = 0,
        firstReadyHighCycles = 3
      )
      phy.initialize()
      phy.start()

      enablePort(dut, physical, lowSpeed = true)
      pushByte(dut, inToken(0), last = false)
      assertTxInvalidFor(dut, 12)
      pushByte(dut, inToken(1), last = false)
      assertTxInvalidFor(dut, 12)
      pushByte(dut, inToken(2), last = true)

      waitUntil(dut, 200)(phy.txRuns.nonEmpty)
      assert(phy.txRuns.map(_.bytes) == Seq(inToken.toVector))
    }
  }

  test("registered TX beat stays stable while stalled and advances once per TXREADY cycle") {
    compileDut().doSim { dut =>
      initialize(dut)
      enablePortDirect(dut, lowSpeed = false)

      val packet = Seq(UsbPid.token(UsbPid.IN), 0x91, 0x46, 0xa5)
      enqueuePacket(dut, packet)
      waitUntil(dut, 100)(dut.io.utmi.txValid.toBoolean)

      val firstByte = dut.io.utmi.dataO.toInt
      for (_ <- 0 until 6) {
        assertTxOutputShadowMatches(dut)
        assert(dut.io.utmi.txValid.toBoolean)
        assert(dut.io.utmi.dataOe.toBoolean)
        assert(dut.io.utmi.dataO.toInt == firstByte)
        assert(!dut.io.debug.txLastAccepted.toBoolean)
        dut.clockDomain.waitSampling()
      }

      val accepted = ArrayBuffer.empty[Int]
      val readyPattern = Seq(true, true, false, true, false, true, true, true)
      for (ready <- readyPattern) {
        dut.clockDomain.waitFallingEdge()
        dut.io.utmi.txReady #= ready
        sleep(1)
        assertTxOutputShadowMatches(dut)

        val validBeforeEdge = dut.io.utmi.txValid.toBoolean
        val dataBeforeEdge = dut.io.utmi.dataO.toInt
        val fireBeforeEdge = validBeforeEdge && ready
        val expectedLast = fireBeforeEdge && accepted.size == packet.size - 1
        assert(dut.io.utmi.dataOe.toBoolean == validBeforeEdge)
        assert(dut.io.debug.txLastAccepted.toBoolean == expectedLast)

        if (fireBeforeEdge) accepted += dataBeforeEdge
        dut.clockDomain.waitRisingEdge()
        sleep(1)
        assertTxOutputShadowMatches(dut)

        if (validBeforeEdge && !ready) {
          assert(dut.io.utmi.txValid.toBoolean)
          assert(dut.io.utmi.dataOe.toBoolean)
          assert(dut.io.utmi.dataO.toInt == dataBeforeEdge)
          assert(!dut.io.debug.txLastAccepted.toBoolean)
        }
      }

      assertTxOutputShadowMatches(dut)
      assert(accepted == packet)
      assert(!dut.io.utmi.txValid.toBoolean)
      assert(!dut.io.utmi.dataOe.toBoolean)

      dut.io.utmi.lineState #= 0
      dut.clockDomain.waitSampling(2)
      dut.io.utmi.lineState #= 1
      waitUntil(dut, 20)(dut.io.ctrl.txEop.toBoolean)
      dut.clockDomain.waitSampling(timing.fullSpeedInterPacketCycles + 2)

      val readyBeforeValidPacket = Seq(0x5a, 0xc3)
      dut.io.utmi.txReady #= true
      enqueuePacket(dut, readyBeforeValidPacket)
      val acceptedWithReadyHigh = ArrayBuffer.empty[Int]
      var timeout = 100
      while (acceptedWithReadyHigh.size < readyBeforeValidPacket.size && timeout > 0) {
        dut.clockDomain.waitFallingEdge()
        assertTxOutputShadowMatches(dut)
        if (dut.io.utmi.txValid.toBoolean && dut.io.utmi.txReady.toBoolean) {
          acceptedWithReadyHigh += dut.io.utmi.dataO.toInt
        }
        dut.clockDomain.waitRisingEdge()
        timeout -= 1
      }
      assertTxOutputShadowMatches(dut)
      assert(acceptedWithReadyHigh == readyBeforeValidPacket)
    }
  }

  for ((speedName, lowSpeed, lineJ, ipdCycles) <- Seq(
      ("low-speed", true, 2, timing.lowSpeedInterPacketCycles),
      ("full-speed", false, 1, timing.fullSpeedInterPacketCycles)
    )) {
    test(s"two buffered packets wait for physical EOP and $speedName inter-packet delay") {
      compileDut().doSim { dut =>
        initialize(dut)
        val physical = new UsbLsFsPhyAbstractIoAgent(dut.io.usb, dut.clockDomain, 5)
        val phy = new Usb3500UtmiPhyAgent(
          dut.io.utmi,
          dut.io.usb,
          dut.clockDomain,
          firstReadyLatencyCycles = 64
        )
        phy.initialize()
        phy.start()

        val eopCycles = ArrayBuffer.empty[Long]
        fork {
          var sawSe0 = false
          while (eopCycles.size < 2) {
            dut.clockDomain.waitSampling()
            val currentLine = dut.io.utmi.lineState.toInt
            if (currentLine == 0) sawSe0 = true
            if (dut.io.ctrl.txEop.toBoolean) {
              assert(sawSe0, "txEop asserted before physical SE0")
              assert(currentLine == lineJ, "txEop did not coincide with the return to J")
              eopCycles += phy.cycle
              sawSe0 = false
            }
          }
        }

        enablePort(dut, physical, lowSpeed)
        enqueuePacket(dut, inToken)
        enqueuePacket(dut, Seq(0xd2))
        assert(phy.txRuns.isEmpty)

        waitUntil(dut, 20000)(phy.txRuns.size == 2)
        waitUntil(dut, 20000)(phy.physicalTx.size == 2)
        waitUntil(dut, 100)(eopCycles.size == 2)
        assert(phy.txRuns.map(_.bytes) == Seq(inToken.toVector, Vector(0xd2)))
        assert(phy.txRuns(1).startCycle - eopCycles.head >= ipdCycles)
      }
    }
  }

  test("two TX banks preserve packet order when bank 0 is refilled before physical EOP completes") {
    compileDut().doSim { dut =>
      initialize(dut)
      val phy = new Usb3500UtmiPhyAgent(dut.io.utmi, dut.io.usb, dut.clockDomain)
      phy.initialize()
      dut.io.usb.rx.dp #= false
      dut.io.usb.rx.dm #= true
      dut.clockDomain.onSamplings {
        if (!dut.io.usb.tx.enable.toBoolean) {
          dut.io.usb.rx.dp #= false
          dut.io.usb.rx.dm #= true
        } else if (dut.io.usb.tx.se0.toBoolean) {
          dut.io.usb.rx.dp #= false
          dut.io.usb.rx.dm #= false
        } else {
          val dp = dut.io.usb.tx.data.toBoolean
          dut.io.usb.rx.dp #= dp
          dut.io.usb.rx.dm #= !dp
        }
      }
      phy.start()

      val packetA = Seq(0xa1)
      val packetB = Seq(0xb2)
      val packetC = Seq(0xc3)

      enablePortDirect(dut, lowSpeed = true)
      enqueuePacket(dut, packetA)
      enqueuePacket(dut, packetB)

      waitUntil(dut, 20000)(phy.txRuns.size == 1)
      assert(phy.txRuns.map(_.bytes) == Seq(packetA.toVector))
      assert(phy.physicalTx.isEmpty, "packet A physical EOP completed before packet C injection")

      enqueuePacket(dut, packetC)
      assert(phy.physicalTx.isEmpty, "packet A physical EOP completed before packet C entered bank 0")

      waitUntil(dut, 20000)(phy.txRuns.size == 3)
      assert(phy.txRuns.map(_.bytes) == Seq(packetA.toVector, packetB.toVector, packetC.toVector))
    }
  }

  test("a complete 1026-byte full-speed packet is accepted and transmitted without gaps") {
    compileDut().doSim { dut =>
      initialize(dut)
      val physical = new UsbLsFsPhyAbstractIoAgent(dut.io.usb, dut.clockDomain, 5)
      val phy = new Usb3500UtmiPhyAgent(
        dut.io.utmi,
        dut.io.usb,
        dut.clockDomain,
        firstReadyLatencyCycles = 0,
        firstReadyHighCycles = 1026
      )
      phy.initialize()
      phy.start()

      val packet = (0 until 1026).map(_ & 0xff)
      enablePort(dut, physical, lowSpeed = false)
      enqueuePacket(dut, packet)

      waitUntil(dut, 5000)(phy.txRuns.nonEmpty)
      assert(phy.txRuns.map(_.bytes) == Seq(packet.toVector))
    }
  }

  test("paired token reserves one bank while a complete 1026-byte DATA packet fills the other") {
    compileDut().doSim { dut =>
      initialize(dut)
      enablePortDirect(dut, lowSpeed = false)

      var eopCount = 0
      dut.clockDomain.onSamplings {
        if (dut.io.ctrl.txEop.toBoolean) eopCount += 1
      }

      val maxData = (0 until 1026).map { index =>
        if (index == 0) UsbPid.token(UsbPid.DATA0) else index & 0xff
      }
      enqueuePacket(dut, outToken)
      waitUntil(dut, 20)(eopCount == 1)
      enqueuePacket(dut, maxData)

      acceptUtmiPacketDirect(dut, outToken)
      dut.io.utmi.lineState #= 0
      dut.clockDomain.waitSampling(2)
      dut.io.utmi.lineState #= 1
      acceptUtmiPacketDirect(dut, maxData)
      dut.io.utmi.lineState #= 0
      dut.clockDomain.waitSampling(2)
      dut.io.utmi.lineState #= 1

      waitUntil(dut, 20)(eopCount == 2)
      assert(!dut.io.debug.txFault.toBoolean)
      assert(dut.io.debug.txBufferState0.toInt == 0)
      assert(dut.io.debug.txBufferState1.toInt == 0)
    }
  }

  test("WAIT_SE0 timeout creates a sticky TX lock across port power-off") {
    compileDut().doSim { dut =>
      initialize(dut)
      assert(!dut.io.debug.txFault.toBoolean)
      enablePortDirect(dut, lowSpeed = true)
      acceptSingleBytePacketDirect(dut)

      waitUntil(dut, timing.txEopTimeoutCycles + 10)(dut.io.debug.txFault.toBoolean)
      assert(dut.io.debug.txFaultReason.toInt == 1)
      assert(!dut.io.debug.txLaunchAllowed.toBoolean)
      assert(!dut.io.utmi.txValid.toBoolean)
      assert(!dut.io.ctrl.txEop.toBoolean)

      dut.io.ctrl.ports(0).power #= false
      dut.clockDomain.waitSampling(4)
      assert(dut.io.debug.txFault.toBoolean)
    }
  }

  test("WAIT_J timeout locks TX without reporting txEop") {
    compileDut().doSim { dut =>
      initialize(dut)
      enablePortDirect(dut, lowSpeed = true)
      acceptSingleBytePacketDirect(dut)

      dut.io.utmi.lineState #= 0
      dut.clockDomain.waitSampling(2)
      dut.io.utmi.lineState #= 1

      waitUntil(dut, timing.txEopTimeoutCycles + 10)(dut.io.debug.txFault.toBoolean)
      assert(dut.io.debug.txFaultReason.toInt == 2)
      assert(!dut.io.debug.txLaunchAllowed.toBoolean)
      assert(!dut.io.utmi.txValid.toBoolean)
      assert(!dut.io.ctrl.txEop.toBoolean)
    }
  }

  test("UTMI receive injection reaches the official UsbHubLsFs receive interface") {
    compileDut().doSim(942922714) { dut =>
      initialize(dut)
      val physical = new UsbLsFsPhyAbstractIoAgent(dut.io.usb, dut.clockDomain, 5)
      val phy = new Usb3500UtmiPhyAgent(
        dut.io.utmi,
        dut.io.usb,
        dut.clockDomain,
        serializeHostPackets = false
      )
      phy.initialize()
      phy.start()

      enablePort(dut, physical, lowSpeed = true)
      val received = ArrayBuffer.empty[(Int, Boolean)]
      fork {
        while (true) {
          dut.clockDomain.waitSampling()
          if (dut.io.ctrl.rx.flow.valid.toBoolean) {
            received += dut.io.ctrl.rx.flow.data.toInt -> dut.io.ctrl.rx.flow.stuffingError.toBoolean
          }
        }
      }

      phy.emitRxPacket(Seq(0xd2), lowSpeed = true)
      waitUntil(dut, 100)(received.nonEmpty)
      dut.clockDomain.waitSampling(4)
      assert(received == Seq(0xd2 -> false))
    }
  }

  test("PHY receive inputs remain combinational at the adapter boundary") {
    compileDut().doSim { dut =>
      initialize(dut)
      enablePortDirect(dut, lowSpeed = false)

      dut.clockDomain.waitFallingEdge()
      dut.io.utmi.dataI #= 0xa6
      dut.io.utmi.rxActive #= true
      dut.io.utmi.rxValid #= true
      dut.io.utmi.rxError #= false
      sleep(1)

      assert(dut.io.ctrl.rx.active.toBoolean)
      assert(dut.io.ctrl.rx.flow.valid.toBoolean)
      assert(dut.io.ctrl.rx.flow.data.toInt == 0xa6)
      assert(!dut.io.ctrl.rx.flow.stuffingError.toBoolean)

      dut.io.utmi.dataI #= 0x3c
      dut.io.utmi.rxValid #= false
      dut.io.utmi.rxError #= true
      sleep(1)

      assert(dut.io.ctrl.rx.active.toBoolean)
      assert(dut.io.ctrl.rx.flow.valid.toBoolean)
      assert(dut.io.ctrl.rx.flow.data.toInt == 0x3c)
      assert(dut.io.ctrl.rx.flow.stuffingError.toBoolean)
    }
  }

  private def compileDut() = SimConfig.withVerilator.compile(UsbHubLsFsToUtmiSimTop(timing))

  private def assertTxOutputShadowMatches(dut: UsbHubLsFsToUtmiSimTop): Unit = {
    assert(dut.io.debug.txOutputData.toInt == dut.io.utmi.dataO.toInt)
    assert(dut.io.debug.txOutputValid.toBoolean == dut.io.utmi.txValid.toBoolean)
    assert(dut.io.utmi.dataT.toInt == (if (dut.io.utmi.txValid.toBoolean) 0x00 else 0xff))
  }

  private def initialize(dut: UsbHubLsFsToUtmiSimTop): Unit = {
    dut.clockDomain.forkStimulus(16667)

    dut.io.ctrl.lowSpeed #= false
    dut.io.ctrl.usbReset #= false
    dut.io.ctrl.usbResume #= false
    dut.io.ctrl.tx.valid #= false
    dut.io.ctrl.tx.fragment #= 0
    dut.io.ctrl.tx.last #= false

    dut.io.utmi.dataI #= 0
    dut.io.utmi.txReady #= false
    dut.io.utmi.rxValid #= false
    dut.io.utmi.rxActive #= false
    dut.io.utmi.rxError #= false
    dut.io.utmi.lineState #= 0
    dut.io.utmi.vbusValid #= true
    dut.io.utmi.hostDisconnect #= false

    val port = dut.io.ctrl.ports(0)
    port.removable #= true
    port.power #= true
    port.disable.valid #= false
    port.reset.valid #= false
    port.suspend.valid #= false
    port.resume.valid #= false

    dut.io.usb.tx.enable #= false
    dut.io.usb.tx.data #= false
    dut.io.usb.tx.se0 #= false
    dut.io.usb.rx.dp #= false
    dut.io.usb.rx.dm #= false
    dut.clockDomain.waitSampling(5)
  }

  private def enablePort(
      dut: UsbHubLsFsToUtmiSimTop,
      physical: UsbLsFsPhyAbstractIoAgent,
      lowSpeed: Boolean
  ): Unit = {
    physical.connect(lowSpeed)
    dut.clockDomain.waitSampling(timing.attachDebounceCycles + 4)

    val reset = dut.io.ctrl.ports(0).reset
    reset.valid #= true
    var ready = false
    var timeout = timing.resetCycles + 20
    while (!ready && timeout > 0) {
      dut.clockDomain.waitSampling()
      ready = reset.ready.toBoolean
      timeout -= 1
    }
    assert(ready, "port reset did not complete")
    reset.valid #= false
    dut.clockDomain.waitSampling(4)
  }

  private def enablePortDirect(dut: UsbHubLsFsToUtmiSimTop, lowSpeed: Boolean): Unit = {
    dut.io.utmi.lineState #= (if (lowSpeed) 2 else 1)
    dut.clockDomain.waitSampling(timing.attachDebounceCycles + 4)

    val reset = dut.io.ctrl.ports(0).reset
    reset.valid #= true
    waitUntil(dut, timing.resetCycles + 20)(reset.ready.toBoolean)
    reset.valid #= false
    dut.clockDomain.waitSampling(4)
  }

  private def acceptUtmiPacketDirect(dut: UsbHubLsFsToUtmiSimTop, expected: Seq[Int]): Unit = {
    waitUntil(dut, 100)(dut.io.utmi.txValid.toBoolean)
    val accepted = ArrayBuffer.empty[Int]
    var timeout = expected.size + 10
    while (accepted.size < expected.size && timeout > 0) {
      dut.clockDomain.waitFallingEdge()
      if (dut.io.utmi.txValid.toBoolean) {
        accepted += dut.io.utmi.dataO.toInt
        dut.io.utmi.txReady #= true
        dut.clockDomain.waitRisingEdge()
      }
      timeout -= 1
    }
    dut.clockDomain.waitFallingEdge()
    dut.io.utmi.txReady #= false
    assert(accepted == expected)
    waitUntil(dut, 4)(!dut.io.utmi.txValid.toBoolean)
  }

  private def acceptSingleBytePacketDirect(dut: UsbHubLsFsToUtmiSimTop): Unit = {
    enqueuePacket(dut, Seq(0xd2))
    waitUntil(dut, 100)(dut.io.utmi.txValid.toBoolean)
    dut.io.utmi.txReady #= true
    dut.clockDomain.waitSampling()
    dut.io.utmi.txReady #= false
    waitUntil(dut, 10)(!dut.io.utmi.txValid.toBoolean)
  }

  private def sendPacket(
      dut: UsbHubLsFsToUtmiSimTop,
      bytes: Seq[Int],
      gapCyclesAfterByte: Int = 0
  ): Unit = {
    require(bytes.nonEmpty)
    require(gapCyclesAfterByte >= 0)

    val tx = dut.io.ctrl.tx
    tx.valid #= true
    for ((byte, index) <- bytes.zipWithIndex) {
      tx.fragment #= byte
      tx.last #= index == bytes.size - 1

      var fired = false
      var readyTimeout = 10000
      while (!fired && readyTimeout > 0) {
        dut.clockDomain.waitSampling()
        fired = tx.ready.toBoolean
        readyTimeout -= 1
      }
      assert(fired, s"TX byte $index was not accepted")

      if (index != bytes.size - 1 && gapCyclesAfterByte > 0) {
        tx.valid #= false
        dut.clockDomain.waitSampling(gapCyclesAfterByte)
        tx.valid #= true
      }
    }
    tx.valid #= false
    tx.last #= false

    var eopTimeout = 10000
    do {
      dut.clockDomain.waitSampling()
      eopTimeout -= 1
    } while (!dut.io.ctrl.txEop.toBoolean && eopTimeout > 0)
    assert(dut.io.ctrl.txEop.toBoolean, "txEop was not observed")
  }

  private def pushByte(dut: UsbHubLsFsToUtmiSimTop, byte: Int, last: Boolean): Unit = {
    val tx = dut.io.ctrl.tx
    tx.valid #= true
    tx.fragment #= byte
    tx.last #= last

    var fired = false
    var readyTimeout = 10000
    while (!fired && readyTimeout > 0) {
      dut.clockDomain.waitSampling()
      fired = tx.ready.toBoolean
      readyTimeout -= 1
    }
    assert(fired, s"TX byte 0x${byte.toHexString} was not accepted")
    tx.valid #= false
    tx.last #= false
  }

  private def enqueuePacket(dut: UsbHubLsFsToUtmiSimTop, bytes: Seq[Int]): Unit = {
    require(bytes.nonEmpty)
    for ((byte, index) <- bytes.zipWithIndex) {
      pushByte(dut, byte, last = index == bytes.size - 1)
    }
  }

  private def assertTxInvalidFor(dut: UsbHubLsFsToUtmiSimTop, cycles: Int): Unit = {
    for (_ <- 0 until cycles) {
      dut.clockDomain.waitSampling()
      assert(!dut.io.utmi.txValid.toBoolean, "TXVALID asserted before the packet last byte was accepted")
    }
  }

  private def waitUntil(dut: UsbHubLsFsToUtmiSimTop, timeoutCycles: Int)(condition: => Boolean): Unit = {
    var remaining = timeoutCycles
    while (!condition && remaining > 0) {
      dut.clockDomain.waitSampling()
      remaining -= 1
    }
    assert(condition, s"condition was not met within $timeoutCycles cycles")
  }
}
