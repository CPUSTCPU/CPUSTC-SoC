package main

import circt.stage.ChiselStage
import java.nio.file.{Files, Path, Paths}
import _root_.chisel.SocFeatureConfig

object SystemVerilogOutput {
  private val ResourceManifestHeader =
    "// ----- 8< ----- FILE \"firrtl_black_box_resource_files.f\" ----- 8< -----"
  private val ResourceFilePattern = "(?:\\./)?[A-Za-z0-9_./-]+\\.(?:sv|v)"

  /** Remove the non-HDL file list that CIRCT 1.62 appends after inline BlackBoxes. */
  def removeTrailingResourceManifest(output: Path): Unit = {
    val contents = Files.readString(output)
    val manifestStart = contents.indexOf(ResourceManifestHeader)

    if (manifestStart >= 0) {
      require(
        contents.indexOf(ResourceManifestHeader, manifestStart + ResourceManifestHeader.length) < 0,
        s"multiple FIRRTL resource manifests found in $output"
      )

      val entries = contents
        .substring(manifestStart + ResourceManifestHeader.length)
        .linesIterator
        .map(_.trim)
        .filter(_.nonEmpty)
        .toSeq
      require(entries.nonEmpty, s"empty FIRRTL resource manifest in $output")
      require(
        entries.forall(_.matches(ResourceFilePattern)),
        s"unexpected content after FIRRTL resource manifest in $output: ${entries.mkString(", ")}"
      )

      Files.writeString(output, contents.substring(0, manifestStart))
    }
  }
}

object main extends App {
  private val socFeatures: SocFeatureConfig =
    SocFeatureConfig.full
  private val targetDirectory = Paths.get("generated", "xc7a200t")

  ChiselStage.emitSystemVerilogFile(
    new _root_.chisel.CPUSTCSoc(
      features = socFeatures
    ),
    args = Array("--target-dir", targetDirectory.toString),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )

  SystemVerilogOutput.removeTrailingResourceManifest(targetDirectory.resolve("CPUSTCSoc.sv"))
}
