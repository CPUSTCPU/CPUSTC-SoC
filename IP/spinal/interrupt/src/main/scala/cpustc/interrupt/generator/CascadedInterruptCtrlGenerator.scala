package cpustc.interrupt.generator

import cpustc.interrupt.CascadedInterruptCtrl
import spinal.core._

/** 生成 xc7a200t 使用的 8 路 APB3 级联中断控制器 Verilog。 */
object CascadedInterruptCtrlGenerator extends App {
  private val moduleName = "CascadedInterruptCtrl"
  private val outputDirectory = "../../../generated/xc7a200t/spinal/interrupt"

  SpinalConfig(
    defaultConfigForClockDomains = ClockDomainConfig(
      resetKind = SYNC,
      resetActiveLevel = HIGH
    ),
    globalPrefix = s"${moduleName}_",
    targetDirectory = outputDirectory
  ).generateVerilog {
    CascadedInterruptCtrl(width = 8).setDefinitionName(moduleName)
  }
}
