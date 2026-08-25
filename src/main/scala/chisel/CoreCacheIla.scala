package chisel

import chisel3._
import chisel3.experimental.ExtModule

/** 与 Vivado `ila_core_cache` IP 端口严格对应的 20 路探针黑盒。
  *
  * 该封装只声明采样边界；采样深度、Storage Qualification 和触发条件由
  * Vivado IP 与 Hardware Manager 管理。
  */
class CoreCacheIla extends ExtModule {
  override def desiredName: String = "ila_core_cache"

  val clk:     Clock = IO(Input(Clock()))
  val probe0:  UInt  = IO(Input(UInt(32.W)))
  val probe1:  UInt  = IO(Input(UInt(32.W)))
  val probe2:  UInt  = IO(Input(UInt(32.W)))
  val probe3:  UInt  = IO(Input(UInt(3.W)))
  val probe4:  Bool  = IO(Input(Bool()))
  val probe5:  UInt  = IO(Input(UInt(10.W)))
  val probe6:  UInt  = IO(Input(UInt(32.W)))
  val probe7:  UInt  = IO(Input(UInt(32.W)))
  val probe8:  UInt  = IO(Input(UInt(32.W)))
  val probe9:  UInt  = IO(Input(UInt(32.W)))
  val probe10: UInt  = IO(Input(UInt(32.W)))
  val probe11: UInt  = IO(Input(UInt(32.W)))
  val probe12: UInt  = IO(Input(UInt(32.W)))
  val probe13: UInt  = IO(Input(UInt(32.W)))
  val probe14: UInt  = IO(Input(UInt(32.W)))
  val probe15: UInt  = IO(Input(UInt(32.W)))
  val probe16: UInt  = IO(Input(UInt(32.W)))
  val probe17: UInt  = IO(Input(UInt(32.W)))
  val probe18: UInt  = IO(Input(UInt(32.W)))
  val probe19: UInt  = IO(Input(UInt(32.W)))
}
