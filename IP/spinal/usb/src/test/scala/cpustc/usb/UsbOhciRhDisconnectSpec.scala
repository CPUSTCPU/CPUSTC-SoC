package cpustc.usb

import cpustc.usb.sim.Usb3500UtmiAgent
import cpustc.usb.utmi.{UsbHubLsFsToUtmiTiming, UsbOhciAxi4Apb3Utmi}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba3.apb.sim.Apb3Driver
import spinal.lib.com.usb.ohci.{OhciPortParameter, UsbOhciParameter}

class UsbOhciRhDisconnectSpec extends AnyFunSuite {
  private val hcControl = 0x04
  private val hcRhStatus = 0x50
  private val hcRhPortStatus = 0x54

  private val currentConnectStatus = BigInt(1) << 0
  private val portEnableStatus = BigInt(1) << 1
  private val portResetStatus = BigInt(1) << 4
  private val setPortReset = BigInt(1) << 4
  private val connectStatusChange = BigInt(1) << 16
  private val portEnableStatusChange = BigInt(1) << 17
  private val portResetStatusChange = BigInt(1) << 20
  private val portChangeMask = ((BigInt(1) << 5) - 1) << 16

  private val parameter = UsbOhciParameter(
    noPowerSwitching = false,
    powerSwitchingMode = false,
    noOverCurrentProtection = true,
    powerOnToPowerGoodTime = 10,
    dataWidth = 32,
    portsConfig = Seq(OhciPortParameter()),
    dmaLengthWidth = 6,
    fifoBytes = 2048,
    storageBursts = 4
  )

  private val timing = UsbHubLsFsToUtmiTiming(
    attachDebounceCycles = 8,
    disconnectCycles = 4,
    resetCycles = 16,
    resumeCycles = 16
  )

  test("an enabled root-hub port reports disconnect status and independent W1C changes") {
    SimConfig.withVerilator.compile {
      val resetConfig = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH)
      val ctrlCd = ClockDomain.external("ctrl", config = resetConfig)
      val dmaCd = ClockDomain.external("dma", config = resetConfig)
      val utmiCd = ClockDomain.external(
        "utmi",
        frequency = FixedFrequency(60 MHz),
        config = resetConfig
      )
      UsbOhciAxi4Apb3Utmi(parameter, ctrlCd, utmiCd, dmaCd, timing)
    }.doSim { dut =>
      dut.frontCd.forkStimulus(30000)
      dut.dmaCd.forkStimulus(30300)
      dut.backCd.forkStimulus(16667)

      dut.io.dma.aw.ready #= false
      dut.io.dma.w.ready #= false
      dut.io.dma.b.valid #= false
      dut.io.dma.ar.ready #= false
      dut.io.dma.r.valid #= false

      val utmi = new Usb3500UtmiAgent(dut.io.utmi, dut.backCd)
      utmi.initialize()
      val apb = Apb3Driver(dut.io.ctrl, dut.frontCd)

      dut.frontCd.waitSampling(10)
      apb.write(hcControl, 2 << 6)
      apb.write(hcRhStatus, 1 << 16)

      utmi.connectLowSpeed()
      utmi.waitCycles(timing.attachDebounceCycles + 4)
      dut.frontCd.waitSampling(12)

      val attached = apb.read(hcRhPortStatus)
      assert(
        (attached & currentConnectStatus) != 0,
        f"CCS was not set before reset: 0x$attached%x"
      )

      apb.write(hcRhPortStatus, connectStatusChange)
      apb.write(hcRhPortStatus, setPortReset)
      utmi.waitCycles(timing.resetCycles + 8)
      dut.frontCd.waitSampling(12)

      val enabled = apb.read(hcRhPortStatus)
      assert(
        (enabled & currentConnectStatus) != 0,
        f"CCS was not set after reset: 0x$enabled%x"
      )
      assert(
        (enabled & portEnableStatus) != 0,
        f"PES was not set after reset: 0x$enabled%x"
      )
      assert(
        (enabled & portResetStatus) == 0,
        f"PRS remained set after reset: 0x$enabled%x"
      )
      assert(
        (enabled & portResetStatusChange) != 0,
        f"PRSC was not set after reset: 0x$enabled%x"
      )

      apb.write(hcRhPortStatus, portChangeMask)
      dut.frontCd.waitSampling(8)
      val armed = apb.read(hcRhPortStatus)
      assert(
        (armed & (currentConnectStatus | portEnableStatus)) ==
          (currentConnectStatus | portEnableStatus),
        f"port was not connected and enabled before disconnect: 0x$armed%x"
      )
      assert(
        (armed & portChangeMask) == 0,
        f"change bits were not clear before disconnect: 0x$armed%x"
      )

      // USB3500 的 HOSTDISC 只用于 HS Host；LS 物理断连由持续 SE0 表示。
      dut.io.utmi.lineState #= 0
      dut.io.utmi.hostDisconnect #= false
      utmi.waitCycles(timing.disconnectCycles + 4)
      dut.frontCd.waitSampling(12)

      val disconnected = apb.read(hcRhPortStatus)
      assert(
        (disconnected & currentConnectStatus) == 0,
        f"CCS remained set after disconnect: 0x$disconnected%x"
      )
      assert(
        (disconnected & portEnableStatus) == 0,
        f"PES remained set after disconnect: 0x$disconnected%x"
      )
      assert(
        (disconnected & connectStatusChange) != 0,
        f"CSC was not set after disconnect: 0x$disconnected%x"
      )
      assert(
        (disconnected & portEnableStatusChange) != 0,
        f"PESC was not set after disconnect: 0x$disconnected%x"
      )

      apb.write(hcRhPortStatus, connectStatusChange)
      dut.frontCd.waitSampling(8)
      val cscCleared = apb.read(hcRhPortStatus)
      assert(
        (cscCleared & connectStatusChange) == 0,
        f"CSC did not clear on W1C: 0x$cscCleared%x"
      )
      assert(
        (cscCleared & portEnableStatusChange) != 0,
        f"clearing CSC also cleared PESC: 0x$cscCleared%x"
      )

      apb.write(hcRhPortStatus, 0)
      dut.frontCd.waitSampling(8)
      val zeroWritten = apb.read(hcRhPortStatus)
      assert(
        (zeroWritten & portEnableStatusChange) != 0,
        f"writing zero cleared PESC: 0x$zeroWritten%x"
      )

      apb.write(hcRhPortStatus, portEnableStatusChange)
      dut.frontCd.waitSampling(8)
      val changesCleared = apb.read(hcRhPortStatus)
      assert(
        (changesCleared & (connectStatusChange | portEnableStatusChange)) == 0,
        f"disconnect change bits did not clear independently: 0x$changesCleared%x"
      )
      assert(
        (changesCleared & (currentConnectStatus | portEnableStatus)) == 0,
        f"W1C write changed disconnected port state: 0x$changesCleared%x"
      )
    }
  }
}
