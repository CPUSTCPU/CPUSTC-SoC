package cpustc.usb

import cpustc.usb.sim.Usb3500UtmiPhyAgent
import cpustc.usb.utmi.{
  Usb3500UtmiIo,
  UsbHubLsFsCtrlCc,
  UsbHubLsFsToUtmi,
  UsbHubLsFsToUtmiTiming
}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.com.usb.phy.{UsbHubLsFs, UsbLsFsPhyAbstractIo}
import spinal.lib.com.usb.sim.UsbLsFsPhyAbstractIoAgent

import scala.collection.mutable.ArrayBuffer

/** 验证项目 CtrlCc 对 UTMI 发送数据、速度和完成脉冲的双时钟传递。 */
private case class UsbHubLsFsToUtmiCcSimTop(timing: UsbHubLsFsToUtmiTiming) extends Component {
  private val resetConfig = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH)
  val frontCd: ClockDomain = ClockDomain.external(
    "front",
    frequency = FixedFrequency(33 MHz),
    config = resetConfig
  )
  val backCd: ClockDomain = ClockDomain.external(
    "back",
    frequency = FixedFrequency(60 MHz),
    config = resetConfig
  )

  val io = new Bundle {
    val ctrl = slave(UsbHubLsFs.Ctrl(1))
    val utmi = master(Usb3500UtmiIo())
    val backTxEop = out(Bool())
    val backLowSpeed = out(Bool())
    val backTxValid = out(Bool())
    val backTxReady = out(Bool())
    val backTxLast = out(Bool())
    val backTxFragment = out(Bits(8 bits))

    // 纯仿真虚拟总线；全部叶子由 testbench 的 PHY agent 驱动。
    val usb = UsbLsFsPhyAbstractIo()
    in(usb.tx.enable, usb.tx.data, usb.tx.se0, usb.rx.dp, usb.rx.dm)
  }

  val ctrlCc = UsbHubLsFsCtrlCc(1, frontCd, backCd)
  ctrlCc.input <> io.ctrl

  val backArea = new ClockingArea(backCd) {
    val adapter = UsbHubLsFsToUtmi(timing)
    adapter.io.ctrl <> ctrlCc.output
  }

  io.utmi <> backArea.adapter.io.utmi
  io.backTxEop := backArea.adapter.io.ctrl.txEop
  io.backLowSpeed := ctrlCc.output.lowSpeed
  io.backTxValid := ctrlCc.output.tx.valid
  io.backTxReady := ctrlCc.output.tx.ready
  io.backTxLast := ctrlCc.output.tx.last
  io.backTxFragment := ctrlCc.output.tx.fragment
}

class UsbHubLsFsToUtmiCcSpec extends AnyFunSuite {
  private val timing = UsbHubLsFsToUtmiTiming(
    attachDebounceCycles = 8,
    disconnectCycles = 128,
    resetCycles = 32,
    resumeCycles = 32,
    txEopTimeoutCycles = 1024
  )

  test("physical low-speed EOP crosses from the 60 MHz adapter domain to the front domain once") {
    SimConfig.withVerilator.compile(UsbHubLsFsToUtmiCcSimTop(timing)).doSim { dut =>
      dut.frontCd.forkStimulus(30303)
      dut.backCd.forkStimulus(16667)
      initialize(dut)

      val physical = new UsbLsFsPhyAbstractIoAgent(dut.io.usb, dut.backCd, 5)
      val phy = new Usb3500UtmiPhyAgent(dut.io.utmi, dut.io.usb, dut.backCd)
      phy.initialize()
      phy.start()

      val backEopRiseTimes = ArrayBuffer.empty[Long]
      val backEopSawPhysicalSe0 = ArrayBuffer.empty[Boolean]
      val backEopLineStates = ArrayBuffer.empty[Int]
      val frontEopRiseTimes = ArrayBuffer.empty[Long]
      val lastByteAcceptTimes = ArrayBuffer.empty[Long]
      var backEopHighSamples = 0
      var frontEopHighSamples = 0
      var backEopLast = false
      var frontEopLast = false
      var sawPhysicalPacket = false
      var sawPhysicalSe0 = false

      dut.backCd.onSamplings {
        val lineState = dut.io.utmi.lineState.toInt
        if (dut.io.usb.tx.enable.toBoolean && !dut.io.usb.tx.se0.toBoolean) {
          sawPhysicalPacket = true
        }
        if (
          sawPhysicalPacket &&
          dut.io.usb.tx.enable.toBoolean &&
          dut.io.usb.tx.se0.toBoolean
        ) {
          sawPhysicalSe0 = true
        }

        val backEop = dut.io.backTxEop.toBoolean
        if (backEop) {
          backEopHighSamples += 1
          if (!backEopLast) {
            backEopRiseTimes += simTime()
            backEopSawPhysicalSe0 += sawPhysicalSe0
            backEopLineStates += lineState
          }
        }
        backEopLast = backEop
      }

      dut.frontCd.onSamplings {
        val frontEop = dut.io.ctrl.txEop.toBoolean
        if (frontEop) {
          frontEopHighSamples += 1
          if (!frontEopLast) {
            frontEopRiseTimes += simTime()
          }
        }
        frontEopLast = frontEop

        val tx = dut.io.ctrl.tx
        if (tx.valid.toBoolean && tx.ready.toBoolean && tx.last.toBoolean) {
          lastByteAcceptTimes += simTime()
        }
      }

      enablePort(dut, physical, lowSpeed = true)
      sendSingleBytePacket(dut, 0xd2)

      waitUntil(dut.backCd, 20000)(backEopRiseTimes.nonEmpty)
      waitUntil(dut.frontCd, 20000)(frontEopRiseTimes.nonEmpty)
      waitUntil(dut.backCd, 2000)(phy.physicalTx.nonEmpty)
      dut.frontCd.waitSampling(16)
      dut.backCd.waitSampling(64)

      assert(phy.txRuns.size == 1, s"expected one UTMI TX run, observed ${phy.txRuns.size}")
      assert(phy.txRuns.head.bytes == Vector(0xd2), s"unexpected UTMI bytes ${phy.txRuns.head.bytes}")
      assert(phy.txRuns.head.lowSpeed, "UTMI packet was not sent in low-speed mode")
      assert(phy.physicalTx.size == 1, s"expected one physical packet, observed ${phy.physicalTx.size}")
      assert(phy.physicalTx.head.bytes == Vector(0xd2), s"unexpected physical bytes ${phy.physicalTx.head.bytes}")

      assert(lastByteAcceptTimes.size == 1, s"last byte was accepted ${lastByteAcceptTimes.size} times")
      assert(backEopRiseTimes.size == 1, s"back txEop rose ${backEopRiseTimes.size} times")
      assert(backEopHighSamples == 1, s"back txEop was high for $backEopHighSamples samples")
      assert(
        backEopSawPhysicalSe0 == Seq(true),
        "back txEop occurred without a preceding physical SE0"
      )
      assert(
        backEopLineStates == Seq(2),
        s"back txEop did not follow the low-speed return to J: $backEopLineStates"
      )
      assert(frontEopRiseTimes.size == 1, s"front txEop rose ${frontEopRiseTimes.size} times")
      assert(frontEopHighSamples == 1, s"front txEop was high for $frontEopHighSamples samples")
      assert(
        frontEopRiseTimes.head > backEopRiseTimes.head,
        s"front txEop at ${frontEopRiseTimes.head} did not follow back txEop at ${backEopRiseTimes.head}"
      )
    }
  }

  test("local CtrlCc aligns LS then FS mode with the first destination TX beat across clock phases") {
    val compiled = SimConfig.withVerilator.compile(UsbHubLsFsToUtmiCcSimTop(timing))

    for (phase <- 0 until 20) {
      compiled.doSim { dut =>
        dut.frontCd.forkStimulus(30303)
        dut.backCd.forkStimulus(16667)
        initialize(dut)

        val physical = new UsbLsFsPhyAbstractIoAgent(dut.io.usb, dut.backCd, 5)
        val phy = new Usb3500UtmiPhyAgent(dut.io.utmi, dut.io.usb, dut.backCd)
        phy.initialize()
        phy.start()

        val destinationFirstBeats = ArrayBuffer.empty[(Int, Boolean, Long)]
        var destinationPacketActive = false
        dut.backCd.onSamplings {
          if (dut.io.backTxValid.toBoolean && dut.io.backTxReady.toBoolean) {
            if (!destinationPacketActive) {
              destinationFirstBeats += ((
                dut.io.backTxFragment.toInt & 0xff,
                dut.io.backLowSpeed.toBoolean,
                simTime()
              ))
            }
            destinationPacketActive = !dut.io.backTxLast.toBoolean
          }
        }

        dut.io.ctrl.lowSpeed #= false
        enablePort(dut, physical, lowSpeed = false)
        waitUntil(dut.backCd, 100)(!dut.io.backLowSpeed.toBoolean)
        dut.frontCd.waitSampling(phase)

        dut.io.ctrl.lowSpeed #= true
        sendSingleBytePacket(dut, 0xd2)
        waitUntil(dut.backCd, 20000)(phy.physicalTx.size == 1)
        dut.frontCd.waitSampling((phase * 7 + 3) % 20)

        dut.io.ctrl.lowSpeed #= false
        sendSingleBytePacket(dut, 0x5a)
        waitUntil(dut.backCd, 20000)(phy.physicalTx.size == 2)
        dut.backCd.waitSampling(8)

        assert(
          destinationFirstBeats.map { case (byte, lowSpeed, _) => byte -> lowSpeed } ==
            Seq(0xd2 -> true, 0x5a -> false),
          s"phase=$phase destination first beats were $destinationFirstBeats"
        )
        assert(
          phy.txRuns.map(run => run.bytes -> (run.lowSpeed, run.preamble)) ==
            Seq(Vector(0xd2) -> (true, true), Vector(0x5a) -> (false, false)),
          s"phase=$phase physical modes were ${phy.txRuns}"
        )
      }
    }
  }

  private def initialize(dut: UsbHubLsFsToUtmiCcSimTop): Unit = {
    dut.io.ctrl.lowSpeed #= true
    dut.io.ctrl.usbReset #= false
    dut.io.ctrl.usbResume #= false
    dut.io.ctrl.tx.valid #= false
    dut.io.ctrl.tx.fragment #= 0
    dut.io.ctrl.tx.last #= false

    val port = dut.io.ctrl.ports(0)
    port.removable #= true
    port.power #= true
    port.disable.valid #= false
    port.reset.valid #= false
    port.suspend.valid #= false
    port.resume.valid #= false

    dut.io.utmi.dataI #= 0
    dut.io.utmi.txReady #= false
    dut.io.utmi.rxValid #= false
    dut.io.utmi.rxActive #= false
    dut.io.utmi.rxError #= false
    dut.io.utmi.lineState #= 0
    dut.io.utmi.vbusValid #= true
    dut.io.utmi.hostDisconnect #= false

    dut.io.usb.tx.enable #= false
    dut.io.usb.tx.data #= false
    dut.io.usb.tx.se0 #= false
    dut.io.usb.rx.dp #= false
    dut.io.usb.rx.dm #= false
    dut.frontCd.waitSampling(5)
    dut.backCd.waitSampling(5)
  }

  private def enablePort(
      dut: UsbHubLsFsToUtmiCcSimTop,
      physical: UsbLsFsPhyAbstractIoAgent,
      lowSpeed: Boolean
  ): Unit = {
    physical.connect(lowSpeed)
    dut.backCd.waitSampling(timing.attachDebounceCycles + 4)
    dut.frontCd.waitSampling(8)

    val reset = dut.io.ctrl.ports(0).reset
    reset.valid #= true
    waitUntil(dut.frontCd, 200)(reset.ready.toBoolean)
    reset.valid #= false
    dut.frontCd.waitSampling(4)
    dut.backCd.waitSampling(4)
  }

  private def sendSingleBytePacket(dut: UsbHubLsFsToUtmiCcSimTop, byte: Int): Unit = {
    val tx = dut.io.ctrl.tx
    tx.fragment #= byte
    tx.last #= true
    tx.valid #= true
    var accepted = false
    var remaining = 200
    while (!accepted && remaining > 0) {
      dut.frontCd.waitSampling()
      accepted = tx.ready.toBoolean
      remaining -= 1
    }
    assert(accepted, "front TX byte was not accepted")
    tx.valid #= false
    tx.last #= false
  }

  private def waitUntil(clockDomain: ClockDomain, timeoutCycles: Int)(condition: => Boolean): Unit = {
    var remaining = timeoutCycles
    while (!condition && remaining > 0) {
      clockDomain.waitSampling()
      remaining -= 1
    }
    assert(condition, s"condition was not met within $timeoutCycles cycles")
  }
}
