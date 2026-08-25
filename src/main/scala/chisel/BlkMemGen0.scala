/**
 * blk_mem_gen_0 的 Chisel 外层封装。
 * fpga/xc7a200t/CPUSTC-SoC/CPUSTC-SoC.gen/sources_1/ip/blk_mem_gen_0/blk_mem_gen_0.veo
 */
package chisel

import chisel3._
import chisel3.experimental.ExtModule

class RawBlkMemGen0 extends ExtModule {
  override def desiredName: String = "blk_mem_gen_0"

  val clka:  Clock = IO(Input(Clock()))
  val ena:   Bool  = IO(Input(Bool()))
  val wea:   UInt  = IO(Input(Bool()))
  val addra: UInt  = IO(Input(UInt(10.W)))
  val dina:  UInt  = IO(Input(UInt(32.W)))

  val clkb:  Clock = IO(Input(Clock()))
  val enb:   Bool  = IO(Input(Bool()))
  val addrb: UInt  = IO(Input(UInt(10.W)))
  val doutb: UInt  = IO(Output(UInt(32.W)))
}

class BlkMemGen0PortA extends Bundle {
  val clk:   Clock = Input(Clock())
  val en:    Bool  = Input(Bool())
  val we: Bool  = Input(Bool())
  val addr:  UInt  = Input(UInt(10.W))
  val din:   UInt  = Input(UInt(32.W))
}

class BlkMemGen0PortB extends Bundle {
  val clk:  Clock = Input(Clock())
  val en  : Bool  = Input(Bool())
  val addr: UInt  = Input(UInt(10.W))
  val dout: UInt  = Output(UInt(32.W))
}

class BlkMemGen0IO extends Bundle {
  val write: BlkMemGen0PortA = new BlkMemGen0PortA
  val read: BlkMemGen0PortB = new BlkMemGen0PortB
}

/** blk_mem_gen_0 的 Chisel 外层封装。
  *
  * A 口按 Vivado 配置作为 32-bit 写端口，B 口作为 32-bit 读端口，地址宽度均为 10 bit。
  * 该封装只整理端口名，不改变 IP 的读延迟、时钟域或写读冲突语义。
  */
class BlkMemGen0 extends RawModule {
  val io: BlkMemGen0IO = IO(new BlkMemGen0IO)

  val raw: RawBlkMemGen0 = Module(new RawBlkMemGen0)

  raw.clka  := io.write.clk
  raw.ena   := io.write.en
  raw.wea   := io.write.we
  raw.addra := io.write.addr
  raw.dina  := io.write.din

  raw.clkb      := io.read.clk
  raw.enb       := io.read.en
  raw.addrb     := io.read.addr
  io.read.dout := raw.doutb
}
