package chisel

import circt.stage.ChiselStage
import java.nio.file.{Files, Path, Paths}
import org.scalatest.freespec.AnyFreeSpec

class SocFeatureConfigSpec extends AnyFreeSpec {
  private def elaborate(profileName: String, features: SocFeatureConfig): String = {
    val targetDirectory: Path = Paths.get("target", "soc-profile-elaboration", profileName)
    ChiselStage.emitSystemVerilogFile(
      new CPUSTCSoc(
        features = features
      ),
      args = Array("--target-dir", targetDirectory.toString),
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
    )
    Files.readString(targetDirectory.resolve("CPUSTCSoc.sv"))
  }

  private lazy val fullSystemVerilog = elaborate("full", SocFeatureConfig.full)
  private lazy val sdioLiteSdSystemVerilog =
    elaborate("sdio-litesd", SocFeatureConfig.sdioLiteSd)
  private lazy val retirePcDebugSystemVerilog =
    elaborate("retire-pc-debug", SocFeatureConfig.retirePcDebug)

  private def topModuleHeader(systemVerilog: String): String = {
    val start = systemVerilog.indexOf("module CPUSTCSoc(")
    require(start >= 0)
    val end = systemVerilog.indexOf(");", start)
    require(end >= 0)
    systemVerilog.substring(start, end + 2)
  }

  private def instanceBody(systemVerilog: String, instance: String): String = {
    val start = systemVerilog.indexOf(instance)
    require(start >= 0)
    val end = systemVerilog.indexOf(");", start)
    require(end >= 0)
    systemVerilog.substring(start, end + 2)
  }

  "full profile should elaborate every required and optional peripheral" in {
    Seq(
      "EthernetTop ethernetTop",
      "NandDMAMaster nandDmaMaster",
      "VGACtrl vgaCtrl",
      "TwoD_GPU twoDGpu",
      "TensorCoreAccel tensorCoreAccel",
      "dot_matrix_controller dotMatrixController",
      "LcdCtrl lcdCtrl",
      "UsbOhciAxi4Apb3UtmiChisel usbOhciAxi4Apb3Utmi",
      "LiteSdioController sdioController",
      "CameraCapture cameraCapture",
      "I2cMaster cameraI2cMaster"
    ).foreach(instance => assert(fullSystemVerilog.contains(instance)))
  }

  "minimal profile should omit optional peripherals and retain confreg GPIO" in {
    Seq(
      "NandDMAMaster nandDmaMaster",
      "VGACtrl vgaCtrl",
      "TwoD_GPU twoDGpu",
      "TensorCoreAccel tensorCoreAccel",
      "dot_matrix_controller dotMatrixController",
      "LcdCtrl lcdCtrl",
      "UsbOhciAxi4Apb3UtmiChisel usbOhciAxi4Apb3Utmi",
      "LiteSdioController sdioController",
      "CameraCapture cameraCapture",
      "I2cMaster cameraI2cMaster"
    ).foreach(instance => assert(!retirePcDebugSystemVerilog.contains(instance)))
    assert(retirePcDebugSystemVerilog.contains("EthernetTop ethernetTop"))
    assert(!retirePcDebugSystemVerilog.contains("HexSevenSegmentDisplay retirePcDisplay"))
    assert(retirePcDebugSystemVerilog.contains("Axi3ErrorSlave sdioError"))
    assert(SocFeatureConfig.minimal === SocFeatureConfig.retirePcDebug)
  }

  "LiteSD profile should retain SDIO and omit TensorCore" in {
    assert(sdioLiteSdSystemVerilog.contains("LiteSdioController sdioController"))
    assert(sdioLiteSdSystemVerilog.contains("AxiClockConverter0 sdioControlClockConverter"))
    val sdio = instanceBody(sdioLiteSdSystemVerilog, "LiteSdioController sdioController (")
    val interconnect = instanceBody(sdioLiteSdSystemVerilog, "AxiInterconnect0 axiInterconnect0 (")
    assert(sdio.contains(".io_clock            (io_clk)"))
    assert(interconnect.contains(".io_s8Clock     (io_clk)"))
    assert(!sdioLiteSdSystemVerilog.contains("TensorCoreAccel tensorCoreAccel"))
    assert(SocFeatureConfig.sdioLiteSd === SocFeatureConfig.full.copy(tensorCore = false))
  }

  "LiteSD profile should not instantiate source ILAs" in {
    assert(!sdioLiteSdSystemVerilog.contains("ila_sdio_crc_source"))
    assert(!sdioLiteSdSystemVerilog.contains("ila_sdio_axi_source"))
  }

  "all profiles should expose identical top-level ports" in {
    assert(topModuleHeader(fullSystemVerilog) === topModuleHeader(retirePcDebugSystemVerilog))
    assert(topModuleHeader(fullSystemVerilog) === topModuleHeader(sdioLiteSdSystemVerilog))
  }

  "LCD touch should require the LCD controller" in {
    assertThrows[IllegalArgumentException] {
      SocFeatureConfig.minimal.copy(lcdTouch = true)
    }
  }
}
