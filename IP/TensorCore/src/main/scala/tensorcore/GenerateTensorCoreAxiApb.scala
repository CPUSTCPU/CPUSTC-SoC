package tensorcore

import circt.stage.ChiselStage
import java.nio.file.{Files, Path, Paths}

private object TensorCoreSystemVerilogOutput {
  private val ResourceManifestHeader =
    "// ----- 8< ----- FILE \"firrtl_black_box_resource_files.f\" ----- 8< -----"
  private val ResourceFilePattern = "(?:\\./)?[A-Za-z0-9_./-]+\\.(?:sv|v)"

  def removeTrailingResourceManifest(output: Path): Unit = {
    val contents = Files.readString(output)
    val manifestStart = contents.indexOf(ResourceManifestHeader)

    if (manifestStart >= 0) {
      val entries = contents
        .substring(manifestStart + ResourceManifestHeader.length)
        .linesIterator
        .map(_.trim)
        .filter(_.nonEmpty)
        .toSeq
      require(entries.nonEmpty, s"empty FIRRTL resource manifest in $output")
      require(
        entries.forall(_.matches(ResourceFilePattern)),
        s"unexpected FIRRTL resource manifest in $output: ${entries.mkString(", ")}"
      )
      Files.writeString(output, contents.substring(0, manifestStart))
    }
  }
}

object GenerateTensorCoreAxiApb extends App {
  private val targetDirectory = Paths.get("target", "generated")

  ChiselStage.emitSystemVerilogFile(
    new TensorCoreGemmAxiApbTop,
    args = Array("--target-dir", targetDirectory.toString),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )

  TensorCoreSystemVerilogOutput.removeTrailingResourceManifest(
    targetDirectory.resolve("tensor_core_gemm_axi_apb_top.sv")
  )
}
