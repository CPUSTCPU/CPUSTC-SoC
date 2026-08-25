package chisel

import chisel3.experimental.ExtModule
import chisel3._

class ClkPll33 extends ExtModule{
  override def desiredName: String = "clk_pll_33"

  val clk_in1:  Clock = IO(Input(Clock()))
  val clk_out1: Clock = IO(Output(Clock()))
  val clk_out2: Clock = IO(Output(Clock()))
}

class ClkWiz0 extends ExtModule {
  override def desiredName: String = "clk_wiz_0"

  val clk_in1:  Clock = IO(Input(Clock()))
  val clk_out1: Clock = IO(Output(Clock()))
}

class ClkWiz1 extends ExtModule {
  override def desiredName: String = "clk_wiz_1"

  val clk_in1:  Clock = IO(Input(Clock()))
  val clk_out1: Clock = IO(Output(Clock()))
  val clk_out2: Clock = IO(Output(Clock()))
}