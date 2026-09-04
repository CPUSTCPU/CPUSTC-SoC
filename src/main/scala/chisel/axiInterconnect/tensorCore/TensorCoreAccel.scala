package chisel.axiInterconnect.tensorCore

import chisel3._
import chisel.common.bus.{APB3IO, AXI4IO}
import tensorcore.TensorCoreGemmAxiApbTop

class TensorCoreAccelIO extends Bundle {
  val apb: APB3IO = Flipped(new APB3IO(addrWidth = 20))
  val axi: AXI4IO = new AXI4IO(
    idWidth = 3,
    addrWidth = 32,
    lenWidth = 8,
    lockWidth = 1,
    dataWidth = 32,
    strbWidth = 4
  )
  val interrupt: Bool = Output(Bool())
}

/** 将 TensorCore Chisel 核整理为 SoC 的 APB3、AXI4 和中断 Bundle。
  *
  * 本模块只做端口映射，不改变 TensorCore 的寄存器、DMA或计算行为。
  */
class TensorCoreAccel extends Module {
  val io: TensorCoreAccelIO = IO(new TensorCoreAccelIO)
  val core: TensorCoreGemmAxiApbTop = Module(new TensorCoreGemmAxiApbTop)

  core.io.apb.psel := io.apb.psel
  core.io.apb.penable := io.apb.penable
  core.io.apb.pwrite := io.apb.pwrite
  core.io.apb.paddr := io.apb.paddr
  core.io.apb.pwdata := io.apb.pwdata
  io.apb.prdata := core.io.apb.prdata
  io.apb.pready := core.io.apb.pready
  io.apb.pslverr := core.io.apb.pslverr

  io.axi.awid := core.io.axi.awid
  io.axi.awaddr := core.io.axi.awaddr
  io.axi.awlen := core.io.axi.awlen
  io.axi.awsize := core.io.axi.awsize
  io.axi.awburst := core.io.axi.awburst
  io.axi.awlock := core.io.axi.awlock
  io.axi.awcache := core.io.axi.awcache
  io.axi.awprot := core.io.axi.awprot
  io.axi.awqos := core.io.axi.awqos
  io.axi.awregion := core.io.axi.awregion
  io.axi.awvalid := core.io.axi.awvalid
  core.io.axi.awready := io.axi.awready

  io.axi.wdata := core.io.axi.wdata
  io.axi.wstrb := core.io.axi.wstrb
  io.axi.wlast := core.io.axi.wlast
  io.axi.wvalid := core.io.axi.wvalid
  core.io.axi.wready := io.axi.wready

  core.io.axi.bid := io.axi.bid
  core.io.axi.bresp := io.axi.bresp
  core.io.axi.bvalid := io.axi.bvalid
  io.axi.bready := core.io.axi.bready

  io.axi.arid := core.io.axi.arid
  io.axi.araddr := core.io.axi.araddr
  io.axi.arlen := core.io.axi.arlen
  io.axi.arsize := core.io.axi.arsize
  io.axi.arburst := core.io.axi.arburst
  io.axi.arlock := core.io.axi.arlock
  io.axi.arcache := core.io.axi.arcache
  io.axi.arprot := core.io.axi.arprot
  io.axi.arqos := core.io.axi.arqos
  io.axi.arregion := core.io.axi.arregion
  io.axi.arvalid := core.io.axi.arvalid
  core.io.axi.arready := io.axi.arready

  core.io.axi.rid := io.axi.rid
  core.io.axi.rdata := io.axi.rdata
  core.io.axi.rresp := io.axi.rresp
  core.io.axi.rlast := io.axi.rlast
  core.io.axi.rvalid := io.axi.rvalid
  io.axi.rready := core.io.axi.rready

  io.interrupt := core.io.interrupt
}
