package chisel

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import chisel.common.cdc.ResetnSync
import circt.stage.ChiselStage
import java.nio.file.{Files, Path, Paths}
import _root_.main.SystemVerilogOutput
import org.scalatest.freespec.AnyFreeSpec

private class ResetnSyncHarness(stages: Int) extends Module {
  val io = IO(new Bundle {
    val srcResetn: Bool = Input(Bool())
    val dstResetn: Bool = Output(Bool())
  })

  io.dstResetn := ResetnSync(clock, io.srcResetn, stages)
}

class ResetnSyncSpec extends AnyFreeSpec with ChiselScalatestTester {
  "ResetnSync asserts asynchronously and releases after the configured stages" in {
    test(new ResetnSyncHarness(3)).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.reset.poke(false.B)
      dut.io.srcResetn.poke(false.B)
      dut.io.dstResetn.expect(false.B)

      dut.io.srcResetn.poke(true.B)
      dut.io.dstResetn.expect(false.B)
      dut.clock.step(2)
      dut.io.dstResetn.expect(false.B)
      dut.clock.step()
      dut.io.dstResetn.expect(true.B)

      dut.io.srcResetn.poke(false.B)
      dut.io.dstResetn.expect(false.B)
    }
  }

  "ResetnSync emits a constant-one synchronizer with Vivado attributes" in {
    val targetDirectory: Path = Paths.get("target", "resetn-sync-elaboration")
    ChiselStage.emitSystemVerilogFile(
      new ResetnSyncHarness(3),
      args = Array("--target-dir", targetDirectory.toString),
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
    )

    val output = targetDirectory.resolve("ResetnSyncHarness.sv")
    SystemVerilogOutput.removeTrailingResourceManifest(output)
    val implementation = Files.readString(output)
    assert(implementation.contains("ASYNC_REG = \"TRUE\""))
    assert(implementation.contains("SHREG_EXTRACT = \"NO\""))
    assert(implementation.contains("CPUSTC_RESET_SYNC = \"TRUE\""))
    assert(implementation.contains("sync_regs <= {sync_regs[STAGES-2:0], 1'b1}"))
    assert(!implementation.contains("sync_regs <= resetn"))
    assert(!implementation.contains("firrtl_black_box_resource_files.f"))
    assert(!implementation.linesIterator.exists(_.trim == "ResetnSyncImpl.sv"))
  }

  "the output cleanup rejects HDL after the FIRRTL resource manifest" in {
    val output = Files.createTempFile("resetn-sync-invalid-manifest", ".sv")
    Files.writeString(
      output,
      """module Example;
        |endmodule
        |// ----- 8< ----- FILE "firrtl_black_box_resource_files.f" ----- 8< -----
        |ResetnSyncImpl.sv
        |assign invalid = 1'b0;
        |""".stripMargin
    )

    intercept[IllegalArgumentException] {
      SystemVerilogOutput.removeTrailingResourceManifest(output)
    }
    assert(Files.readString(output).contains("assign invalid = 1'b0;"))
  }

  "the board constraint excludes only marked synchronizer clear pins" in {
    val constraints = Files.readString(Paths.get("fpga", "xc7a200t", "soc_up.xdc"))
    assert(constraints.contains("CPUSTC_RESET_SYNC == TRUE"))
    assert(constraints.contains("REF_PIN_NAME == CLR"))
    assert(constraints.contains("set_false_path -to $cpustc_reset_sync_clear_pins"))
  }
}
