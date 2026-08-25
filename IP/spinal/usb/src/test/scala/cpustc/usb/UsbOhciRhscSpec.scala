package cpustc.usb

import cpustc.usb.sim.Usb3500UtmiAgent
import cpustc.usb.utmi.{UsbHubLsFsToUtmiTiming, UsbOhciAxi4Apb3Utmi}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba3.apb.sim.Apb3Driver
import spinal.lib.com.usb.ohci.{OhciPortParameter, UsbOhciParameter}

class UsbOhciRhscSpec extends AnyFunSuite {
  private val hcControl = 0x04
  private val hcInterruptStatus = 0x0c
  private val hcInterruptEnable = 0x10
  private val hcInterruptDisable = 0x14
  private val hcRhStatus = 0x50
  private val hcRhPortStatus = 0x54

  private val rhsc = BigInt(1) << 6
  private val masterInterruptEnable = BigInt(1) << 31
  private val connectStatusChange = BigInt(1) << 16

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

  test("RHSC connect, W1C, mask and disconnect are one-shot") {
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
      apb.write(hcInterruptEnable, masterInterruptEnable | rhsc)

      utmi.connectLowSpeed()
      utmi.waitCycles(timing.attachDebounceCycles + 4)
      dut.frontCd.waitSampling(12)

      val connected = apb.read(hcRhPortStatus)
      assert((connected & 1) != 0, f"CCS was not set: 0x$connected%x")
      assert((connected & (BigInt(1) << 9)) != 0, f"LSDA was not set: 0x$connected%x")
      assert((connected & connectStatusChange) != 0, f"CSC was not set: 0x$connected%x")
      assert((apb.read(hcInterruptStatus) & rhsc) != 0)
      assert(dut.io.interrupt.toBoolean)

      apb.write(hcRhPortStatus, connectStatusChange)
      apb.write(hcInterruptStatus, rhsc)
      dut.frontCd.waitSampling(8)
      assert((apb.read(hcRhPortStatus) & connectStatusChange) == 0)
      assert((apb.read(hcInterruptStatus) & rhsc) == 0)
      assert(!dut.io.interrupt.toBoolean)

      utmi.waitCycles(timing.attachDebounceCycles * 3)
      dut.frontCd.waitSampling(8)
      assert((apb.read(hcInterruptStatus) & rhsc) == 0)

      apb.write(hcInterruptDisable, rhsc)
      utmi.disconnect()
      utmi.waitCycles(timing.disconnectCycles + 4)
      dut.frontCd.waitSampling(12)

      val disconnected = apb.read(hcRhPortStatus)
      assert((disconnected & 1) == 0, f"CCS remained set: 0x$disconnected%x")
      assert((disconnected & connectStatusChange) != 0, f"CSC was not set: 0x$disconnected%x")
      assert((apb.read(hcInterruptStatus) & rhsc) != 0)
      assert(!dut.io.interrupt.toBoolean)

      apb.write(hcRhPortStatus, connectStatusChange)
      apb.write(hcInterruptStatus, rhsc)
      utmi.waitCycles(timing.disconnectCycles * 3)
      dut.frontCd.waitSampling(8)
      assert((apb.read(hcInterruptStatus) & rhsc) == 0)
      assert(!dut.io.interrupt.toBoolean)
    }
  }
}
