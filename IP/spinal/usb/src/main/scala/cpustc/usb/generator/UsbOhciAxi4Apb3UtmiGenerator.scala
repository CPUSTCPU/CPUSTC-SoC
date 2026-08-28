package cpustc.usb.generator

import cpustc.usb.utmi.UsbOhciAxi4Apb3Utmi
import spinal.core._
import spinal.lib.com.usb.ohci.{OhciPortParameter, UsbOhciParameter}

/** 生成 xc7a200t 使用的单端口 USB3500 OHCI Host Verilog。 */
object UsbOhciAxi4Apb3UtmiGenerator extends App {
  private val moduleName = "UsbOhciAxi4Apb3Utmi"
  private val outputDirectory = "../../../generated/xc7a200t/spinal/usb"

  private val parameter = UsbOhciParameter(
    noPowerSwitching = true,
    powerSwitchingMode = false,
    noOverCurrentProtection = true,
    powerOnToPowerGoodTime = 0,
    dataWidth = 32,
    portsConfig = Seq(OhciPortParameter(powerControlMask = false)),
    dmaLengthWidth = 6,
    fifoBytes = 2048,
    storageBursts = 4
  )

  private val resetConfig = ClockDomainConfig(
    resetKind = SYNC,
    resetActiveLevel = HIGH
  )

  SpinalConfig(
    globalPrefix = s"${moduleName}_",
    targetDirectory = outputDirectory
  ).generateVerilog {
    val ctrlCd = ClockDomain.external("ctrl", config = resetConfig)
    val dmaCd = ClockDomain.external("dma", config = resetConfig)
    val utmiCd = ClockDomain.external(
      "utmi",
      frequency = FixedFrequency(60 MHz),
      config = resetConfig
    )

    UsbOhciAxi4Apb3Utmi(
      parameter,
      ctrlCd,
      utmiCd,
      dmaCd,
      withClockSofDiagnostic = false,
      resetChirpDiagnostic = true
    )
      .setDefinitionName(moduleName)
  }
}
