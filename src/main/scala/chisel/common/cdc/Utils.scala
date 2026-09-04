package chisel.common.cdc

import chisel3._
import chisel3.experimental.IntParam
import chisel3.util.{HasBlackBoxInline, ShiftRegister}

/** active-low resetn 的跨时钟域同步释放工具。
  *
  * 该工具在 `srcResetn` 拉低时异步拉低输出，在 `srcResetn` 拉高后，
  * 通过 `dstClock` 域的移位寄存器延迟 `stages` 拍释放输出。
  * 它只处理 resetn 的同步释放，不做时钟门控、PLL lock 判断或 reset 条件组合。
  */
private class ResetnSyncImpl(stages: Int)
    extends BlackBox(Map("STAGES" -> IntParam(stages)))
    with HasBlackBoxInline {
  require(stages >= 1, "ResetnSync requires at least one stage")

  val io = IO(new Bundle {
    val clock: Clock = Input(Clock())
    val resetn: Bool = Input(Bool())
    val out: Bool = Output(Bool())
  })

  setInline(
    "ResetnSyncImpl.sv",
    """
      |module ResetnSyncImpl #(
      |    parameter integer STAGES = 3
      |) (
      |    input  wire clock,
      |    input  wire resetn,
      |    output wire out
      |);
      |
      |    (* ASYNC_REG = "TRUE", SHREG_EXTRACT = "NO", CPUSTC_RESET_SYNC = "TRUE" *)
      |    reg [STAGES-1:0] sync_regs = {STAGES{1'b0}};
      |
      |    generate
      |        if (STAGES == 1) begin : gen_single_stage
      |            always @(posedge clock or negedge resetn) begin
      |                if (!resetn)
      |                    sync_regs <= 1'b0;
      |                else
      |                    sync_regs <= 1'b1;
      |            end
      |        end else begin : gen_multi_stage
      |            always @(posedge clock or negedge resetn) begin
      |                if (!resetn)
      |                    sync_regs <= {STAGES{1'b0}};
      |                else
      |                    sync_regs <= {sync_regs[STAGES-2:0], 1'b1};
      |            end
      |        end
      |    endgenerate
      |
      |    assign out = sync_regs[STAGES-1];
      |
      |endmodule
      |""".stripMargin
  )
}

object ResetnSync {
  def apply(dstClock: Clock, srcResetn: Bool, stages: Int = 3): Bool = {
    val synchronizer = Module(new ResetnSyncImpl(stages))
    synchronizer.io.clock := dstClock
    synchronizer.io.resetn := srcResetn
    synchronizer.io.out
  }
}

/** 将单比特异步电平同步到目标时钟域。
  *
  * `dstResetn` 拉低时异步清零全部同步寄存器；复位释放后，输入经过至少两级寄存器采样后输出。
  * 该工具不做脉冲展宽，也不用于保证多位数据跨时钟域的一致性。
  */
object BoolSync {
  def apply(dstClock: Clock, dstResetn: Bool, source: Bool, stages: Int = 2): Bool = {
    require(stages >= 2, "BoolSync requires at least two stages")
    withClockAndReset(dstClock, (!dstResetn).asAsyncReset) {
      ShiftRegister(source, stages, false.B, true.B)
    }
  }
}
