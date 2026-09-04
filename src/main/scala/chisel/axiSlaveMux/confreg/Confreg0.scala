package chisel.axiSlaveMux.confreg

import chisel3._
import chisel3.experimental.ExtModule
import chisel.GpioPort
import chisel.common.bus.AXI3IO

class RawConfreg extends ExtModule {
  override def desiredName: String = "confreg"

  val aclk:              Clock = IO(Input(Clock()))
  val aresetn:           Bool  = IO(Input(Bool()))

  val s_awid:            UInt = IO(Input(UInt(4.W)))
  val s_awaddr:          UInt = IO(Input(UInt(32.W)))
  val s_awlen:           UInt = IO(Input(UInt(8.W)))
  val s_awsize:          UInt = IO(Input(UInt(3.W)))
  val s_awburst:         UInt = IO(Input(UInt(2.W)))
  val s_awlock:          Bool = IO(Input(Bool()))
  val s_awcache:         UInt = IO(Input(UInt(4.W)))
  val s_awprot:          UInt = IO(Input(UInt(3.W)))
  val s_awvalid:         Bool = IO(Input(Bool()))
  val s_awready:         Bool = IO(Output(Bool()))

  val s_wid:             UInt = IO(Input(UInt(4.W)))
  val s_wdata:           UInt = IO(Input(UInt(32.W)))
  val s_wstrb:           UInt = IO(Input(UInt(4.W)))
  val s_wlast:           Bool = IO(Input(Bool()))
  val s_wvalid:          Bool = IO(Input(Bool()))
  val s_wready:          Bool = IO(Output(Bool()))

  val s_bid:             UInt = IO(Output(UInt(4.W)))
  val s_bresp:           UInt = IO(Output(UInt(2.W)))
  val s_bvalid:          Bool = IO(Output(Bool()))
  val s_bready:          Bool = IO(Input(Bool()))

  val s_arid:            UInt = IO(Input(UInt(4.W)))
  val s_araddr:          UInt = IO(Input(UInt(32.W)))
  val s_arlen:           UInt = IO(Input(UInt(8.W)))
  val s_arsize:          UInt = IO(Input(UInt(3.W)))
  val s_arburst:         UInt = IO(Input(UInt(2.W)))
  val s_arlock:          Bool = IO(Input(Bool()))
  val s_arcache:         UInt = IO(Input(UInt(4.W)))
  val s_arprot:          UInt = IO(Input(UInt(3.W)))
  val s_arvalid:         Bool = IO(Input(Bool()))
  val s_arready:         Bool = IO(Output(Bool()))

  val s_rid:             UInt = IO(Output(UInt(4.W)))
  val s_rdata:           UInt = IO(Output(UInt(32.W)))
  val s_rresp:           UInt = IO(Output(UInt(2.W)))
  val s_rlast:           Bool = IO(Output(Bool()))
  val s_rvalid:          Bool = IO(Output(Bool()))
  val s_rready:          Bool = IO(Input(Bool()))

  val order_addr_reg:    UInt = IO(Output(UInt(32.W)))
  val finish_read_order: Bool = IO(Input(Bool()))
  val write_dma_end:     Bool = IO(Input(Bool()))

  val cr00:              UInt = IO(Output(UInt(32.W)))
  val cr01:              UInt = IO(Output(UInt(32.W)))
  val cr02:              UInt = IO(Output(UInt(32.W)))
  val cr03:              UInt = IO(Output(UInt(32.W)))
  val cr04:              UInt = IO(Output(UInt(32.W)))
  val cr05:              UInt = IO(Output(UInt(32.W)))
  val cr06:              UInt = IO(Output(UInt(32.W)))
  val cr07:              UInt = IO(Output(UInt(32.W)))

  val led:               UInt = IO(Output(UInt(16.W)))
  val led_rg0:           UInt = IO(Output(UInt(2.W)))
  val led_rg1:           UInt = IO(Output(UInt(2.W)))
  val num_csn:           UInt = IO(Output(UInt(8.W)))
  val num_a_g:           UInt = IO(Output(UInt(7.W)))
  val switch:            UInt = IO(Input(UInt(8.W)))
  val btn_key_col:       UInt = IO(Output(UInt(4.W)))
  val btn_key_row:       UInt = IO(Input(UInt(4.W)))
  val btn_key_value:     UInt = IO(Output(UInt(16.W)))
  val btn_step:          UInt = IO(Input(UInt(2.W)))
}

class ConfregControlRegs extends Bundle {
  val cr00: UInt = Output(UInt(32.W))
  val cr01: UInt = Output(UInt(32.W))
  val cr02: UInt = Output(UInt(32.W))
  val cr03: UInt = Output(UInt(32.W))
  val cr04: UInt = Output(UInt(32.W))
  val cr05: UInt = Output(UInt(32.W))
  val cr06: UInt = Output(UInt(32.W))
  val cr07: UInt = Output(UInt(32.W))
}

class ConfregNandDmaPort extends Bundle {
  val orderAddr:       UInt = Output(UInt(32.W))
  val finishReadOrder: Bool = Input(Bool())
  val writeDmaEnd:     Bool = Input(Bool())
}

class ConfregIO extends Bundle {
  val aclk:            Clock              = Input(Clock())
  val aresetn:         Bool               = Input(Bool())
  val axi:             AXI3IO = Flipped(new AXI3IO)
  val nandDma:         ConfregNandDmaPort = new ConfregNandDmaPort
  val cr:              ConfregControlRegs = new ConfregControlRegs
  val gpio:            GpioPort           = new GpioPort
  val buttonValue:     UInt               = Output(UInt(16.W))
}
//这里命名为Confreg0，是因为虽然vivado对大小写敏感，但是实际发现Confreg和confreg同时存在时，会导致vivado额外识别一个在confreg_syn.v的Confreg模块。
// 而confreg_syn.v实际是confreg模块。改成Confreg0后不再有此异常。
class Confreg0 extends RawModule {
  val io: ConfregIO = IO(new ConfregIO)

  val raw: RawConfreg = Module(new RawConfreg)

  raw.aclk    := io.aclk
  raw.aresetn := io.aresetn

  raw.s_awid    := io.axi.awid
  raw.s_awaddr  := io.axi.awaddr
  raw.s_awlen   := io.axi.awlen
  raw.s_awsize  := io.axi.awsize
  raw.s_awburst := io.axi.awburst
  raw.s_awlock  := io.axi.awlock(0)
  raw.s_awcache := io.axi.awcache
  raw.s_awprot  := io.axi.awprot
  raw.s_awvalid := io.axi.awvalid
  io.axi.awready := raw.s_awready

  raw.s_wid    := io.axi.wid
  raw.s_wdata  := io.axi.wdata
  raw.s_wstrb  := io.axi.wstrb
  raw.s_wlast  := io.axi.wlast
  raw.s_wvalid := io.axi.wvalid
  io.axi.wready := raw.s_wready

  io.axi.bid    := raw.s_bid
  io.axi.bresp  := raw.s_bresp
  io.axi.bvalid := raw.s_bvalid
  raw.s_bready  := io.axi.bready

  raw.s_arid    := io.axi.arid
  raw.s_araddr  := io.axi.araddr
  raw.s_arlen   := io.axi.arlen
  raw.s_arsize  := io.axi.arsize
  raw.s_arburst := io.axi.arburst
  raw.s_arlock  := io.axi.arlock(0)
  raw.s_arcache := io.axi.arcache
  raw.s_arprot  := io.axi.arprot
  raw.s_arvalid := io.axi.arvalid
  io.axi.arready := raw.s_arready

  io.axi.rid    := raw.s_rid
  io.axi.rdata  := raw.s_rdata
  io.axi.rresp  := raw.s_rresp
  io.axi.rlast  := raw.s_rlast
  io.axi.rvalid := raw.s_rvalid
  raw.s_rready  := io.axi.rready

  io.nandDma.orderAddr  := raw.order_addr_reg
  raw.finish_read_order := io.nandDma.finishReadOrder
  raw.write_dma_end     := io.nandDma.writeDmaEnd

  io.cr.cr00 := raw.cr00
  io.cr.cr01 := raw.cr01
  io.cr.cr02 := raw.cr02
  io.cr.cr03 := raw.cr03
  io.cr.cr04 := raw.cr04
  io.cr.cr05 := raw.cr05
  io.cr.cr06 := raw.cr06
  io.cr.cr07 := raw.cr07

  io.gpio.led         := raw.led
  io.gpio.led_rg0     := raw.led_rg0
  io.gpio.led_rg1     := raw.led_rg1
  io.gpio.num_csn     := raw.num_csn
  io.gpio.num_a_g     := raw.num_a_g
  raw.switch          := io.gpio.switch
  io.gpio.btn_key_col := raw.btn_key_col
  raw.btn_key_row     := io.gpio.btn_key_row
  io.buttonValue      := raw.btn_key_value
  raw.btn_step        := io.gpio.btn_step
}
