package chisel

/** SoC 可选外设的编译期配置。
  *
  * CPU、DDR、时钟复位、UART、SPI Flash、Ethernet 和 confreg/GPIO 属于基础系统，
  * 不在此配置中裁剪。各开关只控制外设实例及其总线、板级端口和中断连接，
  * 顶层端口与 Vivado AXI Interconnect 的静态接口保持不变。
  */
final case class SocFeatureConfig(
  nand: Boolean,
  usb: Boolean,
  sdio: Boolean,
  vga: Boolean,
  lcd: Boolean,
  lcdTouch: Boolean,
  twoDGpu: Boolean,
  tensorCore: Boolean,
  dotMatrix: Boolean,
  camera: Boolean
) {
  require(!lcdTouch || lcd, "LCD touch requires the LCD controller")

  /** 显示 APB 页内至少存在一个从设备。 */
  val hasDisplayPeripheral: Boolean = vga || twoDGpu || tensorCore || dotMatrix
}

object SocFeatureConfig {
  /** 当前 xc7a200t 主线的全外设配置。 */
  val full: SocFeatureConfig = SocFeatureConfig(
    nand = true,
    usb = true,
    sdio = true,
    vga = true,
    lcd = true,
    lcdTouch = true,
    twoDGpu = true,
    tensorCore = true,
    dotMatrix = true,
    camera = true
  )

  /** LiteSD 对照工程配置：保留全外设配置，但关闭 TensorCore。 */
  val sdioLiteSd: SocFeatureConfig = full.copy(tensorCore = false)

  /** 全外设配置裁剪 SDIO、Camera 和 TensorCore。 */
  val fullWithoutSdioCameraTensorCore: SocFeatureConfig = full.copy(
    sdio = false,
    tensorCore = false,
    camera = false
  )

  /** 仅保留基础系统的配置。 */
  val retirePcDebug: SocFeatureConfig = SocFeatureConfig(
    nand = false,
    usb = false,
    sdio = false,
    vga = false,
    lcd = false,
    lcdTouch = false,
    twoDGpu = false,
    tensorCore = false,
    dotMatrix = false,
    camera = false
  )

  /** 无可选外设配置的兼容名称。 */
  val minimal: SocFeatureConfig = retirePcDebug
}
