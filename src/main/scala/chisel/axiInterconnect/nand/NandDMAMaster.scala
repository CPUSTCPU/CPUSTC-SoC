package chisel.axiInterconnect.nand

import chisel3._
import chisel3.experimental.ExtModule
import chisel.axiSlaveMux.confreg.ConfregNandDmaPort
import chisel.common.bus.AXI3IO

class RawNandDMAMaster extends ExtModule {
  override def desiredName: String = "dma_master"

  val clk:   Clock = IO(Input(Clock()))
  val rst_n: Bool  = IO(Input(Bool()))

  val arid:     UInt = IO(Output(UInt(4.W)))
  val araddr:   UInt = IO(Output(UInt(32.W)))
  val arlen:    UInt = IO(Output(UInt(4.W)))
  val arsize:   UInt = IO(Output(UInt(3.W)))
  val arburst:  UInt = IO(Output(UInt(2.W)))
  val arlock:   UInt = IO(Output(UInt(2.W)))
  val arcache:  UInt = IO(Output(UInt(4.W)))
  val arprot:   UInt = IO(Output(UInt(3.W)))
  val arvalid:  Bool = IO(Output(Bool()))
  val arready:  Bool = IO(Input(Bool()))
  val rid:      UInt = IO(Input(UInt(4.W)))
  val rdata:    UInt = IO(Input(UInt(64.W)))
  val rresp:    UInt = IO(Input(UInt(2.W)))
  val rlast:    Bool = IO(Input(Bool()))
  val rvalid:   Bool = IO(Input(Bool()))
  val rready:   Bool = IO(Output(Bool()))

  val awid:     UInt = IO(Output(UInt(4.W)))
  val awaddr:   UInt = IO(Output(UInt(32.W)))
  val awlen:    UInt = IO(Output(UInt(4.W)))
  val awsize:   UInt = IO(Output(UInt(3.W)))
  val awburst:  UInt = IO(Output(UInt(2.W)))
  val awlock:   UInt = IO(Output(UInt(2.W)))
  val awcache:  UInt = IO(Output(UInt(4.W)))
  val awprot:   UInt = IO(Output(UInt(3.W)))
  val awvalid:  Bool = IO(Output(Bool()))
  val awready:  Bool = IO(Input(Bool()))
  val wid:      UInt = IO(Output(UInt(4.W)))
  val wdata:    UInt = IO(Output(UInt(64.W)))
  val wstrb:    UInt = IO(Output(UInt(8.W)))
  val wlast:    Bool = IO(Output(Bool()))
  val wvalid:   Bool = IO(Output(Bool()))
  val wready:   Bool = IO(Input(Bool()))
  val bid:      UInt = IO(Input(UInt(4.W)))
  val bresp:    UInt = IO(Input(UInt(2.W)))
  val bvalid:   Bool = IO(Input(Bool()))
  val bready:   Bool = IO(Output(Bool()))

  val dma_int:           Bool = IO(Output(Bool()))
  val order_addr_in:     UInt = IO(Input(UInt(32.W)))
  val dma_req_in:        Bool = IO(Input(Bool()))
  val dma_ack_out:       Bool = IO(Output(Bool()))
  val finish_read_order: Bool = IO(Output(Bool()))
  val write_dma_end:     Bool = IO(Output(Bool()))
  val dma_gnt:           Bool = IO(Input(Bool()))

  val apb_valid_req: Bool = IO(Output(Bool()))
  val apb_psel:      Bool = IO(Output(Bool()))
  val apb_penable:   Bool = IO(Output(Bool()))
  val apb_rw:        Bool = IO(Output(Bool()))
  val apb_addr:      UInt = IO(Output(UInt(32.W)))
  val apb_rdata:     UInt = IO(Input(UInt(32.W)))
  val apb_wdata:     UInt = IO(Output(UInt(32.W)))
}

class ApbMuxDmaPort extends Bundle {
  val ready: Bool = Output(Bool())
  val write: Bool = Input(Bool())
  val psel: Bool = Input(Bool())
  val penable: Bool = Input(Bool())
  val addr: UInt = Input(UInt(20.W))
  val writeData: UInt = Input(UInt(32.W))
  val readData: UInt = Output(UInt(32.W))
  val valid: Bool = Input(Bool())
  val grant: Bool = Output(Bool())
}

class NandDmaApbMasterPort extends Bundle {
  val request:     Bool = Input(Bool())
  val acknowledge: Bool = Output(Bool())
  val mux: ApbMuxDmaPort = Flipped(new ApbMuxDmaPort)
}

class NandDMAMasterIO extends Bundle {
  val clk:      Clock                = Input(Clock())
  val resetn:   Bool                 = Input(Bool())
  val axi:      AXI3IO       = new AXI3IO(dataWidth = 64, strbWidth = 8)
  val interrupt:   Bool = Output(Bool())
  val confreg:  ConfregNandDmaPort   = Flipped(new ConfregNandDmaPort)
  val nandApb:  NandDmaApbMasterPort = new NandDmaApbMasterPort
}

class NandDMAMaster extends RawModule {
  override def desiredName: String = "NandDMAMaster"

  val io: NandDMAMasterIO = IO(new NandDMAMasterIO)

  val raw: RawNandDMAMaster = Module(new RawNandDMAMaster)

  raw.clk   := io.clk
  raw.rst_n := io.resetn

  io.axi.awid     := raw.awid
  io.axi.awaddr   := raw.awaddr
  io.axi.awlen    := raw.awlen
  io.axi.awsize   := raw.awsize
  io.axi.awburst  := raw.awburst
  io.axi.awlock   := raw.awlock
  io.axi.awcache  := raw.awcache
  io.axi.awprot   := raw.awprot
  io.axi.awvalid  := raw.awvalid
  raw.awready     := io.axi.awready

  io.axi.wid      := raw.wid
  io.axi.wdata    := raw.wdata
  io.axi.wstrb    := raw.wstrb
  io.axi.wlast    := raw.wlast
  io.axi.wvalid   := raw.wvalid
  raw.wready      := io.axi.wready

  raw.bid         := io.axi.bid
  raw.bresp       := io.axi.bresp
  raw.bvalid      := io.axi.bvalid
  io.axi.bready   := raw.bready

  io.axi.arid     := raw.arid
  io.axi.araddr   := raw.araddr
  io.axi.arlen    := raw.arlen
  io.axi.arsize   := raw.arsize
  io.axi.arburst  := raw.arburst
  io.axi.arlock   := raw.arlock
  io.axi.arcache  := raw.arcache
  io.axi.arprot   := raw.arprot
  io.axi.arvalid  := raw.arvalid
  raw.arready     := io.axi.arready

  raw.rid         := io.axi.rid
  raw.rdata       := io.axi.rdata
  raw.rresp       := io.axi.rresp
  raw.rlast       := io.axi.rlast
  raw.rvalid      := io.axi.rvalid
  io.axi.rready   := raw.rready

  io.interrupt          := raw.dma_int
  raw.dma_req_in            := io.nandApb.request
  io.nandApb.acknowledge        := raw.dma_ack_out
  raw.dma_gnt               := io.nandApb.mux.grant

  raw.order_addr_in         := io.confreg.orderAddr
  io.confreg.finishReadOrder := raw.finish_read_order
  io.confreg.writeDmaEnd    := raw.write_dma_end

  io.nandApb.mux.valid     := raw.apb_valid_req
  io.nandApb.mux.psel      := raw.apb_psel
  io.nandApb.mux.penable   := raw.apb_penable
  io.nandApb.mux.write     := raw.apb_rw
  io.nandApb.mux.addr      := raw.apb_addr(19, 0)
  raw.apb_rdata            := io.nandApb.mux.readData
  io.nandApb.mux.writeData := raw.apb_wdata
}
