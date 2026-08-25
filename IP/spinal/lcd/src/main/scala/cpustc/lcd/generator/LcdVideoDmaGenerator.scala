package cpustc.lcd.generator

import cpustc.lcd.{LcdVideoDma, LcdVideoDmaParameter}
import spinal.core._

/** 生成 xc7a200t LCD framebuffer reader Verilog。 */
object LcdVideoDmaGenerator extends App {
  private val moduleName = "LcdVideoDma"
  private val outputDirectory = "../../../generated/xc7a200t/spinal/lcd"
  private val resetConfig = ClockDomainConfig(
    resetKind = SYNC,
    resetActiveLevel = HIGH
  )

  SpinalConfig(
    globalPrefix = s"${moduleName}_",
    targetDirectory = outputDirectory
  ).generateVerilog {
    val axiClockDomain = ClockDomain.external("axi", config = resetConfig)
    val frameClockDomain = ClockDomain.external("frame", config = resetConfig)

    LcdVideoDma(
      LcdVideoDmaParameter(),
      axiClockDomain,
      frameClockDomain
    ).setDefinitionName(moduleName)
  }
}
