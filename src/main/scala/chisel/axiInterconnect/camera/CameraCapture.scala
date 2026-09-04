package chisel.axiInterconnect.camera

import chisel3._
import chisel3.experimental.ExtModule
import chisel.axiSlaveMux.apb.i2c.I2cPadPort
import chisel.common.bus.{APB3IO, AXI4IO}

class CameraDvpPort extends Bundle {
  val pclk: Clock = Input(Clock())
  val vsync: Bool = Input(Bool())
  val href: Bool = Input(Bool())
  val data: UInt = Input(UInt(8.W))
  val xclk: Clock = Output(Clock())
  val resetn: Bool = Output(Bool())
  val pwdn: Bool = Output(Bool())
  val sccb: I2cPadPort = new I2cPadPort
}

/** camera capture RTL 的原始端口声明，不改变 APB、AXI 或 DVP 时序。 */
class RawCameraCapture extends ExtModule {
  override def desiredName: String = "cpustc_camera_capture"

  val aclk: Clock = IO(Input(Clock()))
  val aresetn: Bool = IO(Input(Bool()))
  val pclk: Clock = IO(Input(Clock()))
  val pclk_resetn: Bool = IO(Input(Bool()))
  val vsync: Bool = IO(Input(Bool()))
  val href: Bool = IO(Input(Bool()))
  val data: UInt = IO(Input(UInt(8.W)))

  val paddr: UInt = IO(Input(UInt(13.W)))
  val psel: Bool = IO(Input(Bool()))
  val penable: Bool = IO(Input(Bool()))
  val pwrite: Bool = IO(Input(Bool()))
  val pwdata: UInt = IO(Input(UInt(32.W)))
  val prdata: UInt = IO(Output(UInt(32.W)))
  val pready: Bool = IO(Output(Bool()))
  val pslverr: Bool = IO(Output(Bool()))
  val interrupt: Bool = IO(Output(Bool()))

  val m_axi_awid: UInt = IO(Output(UInt(4.W)))
  val m_axi_awaddr: UInt = IO(Output(UInt(32.W)))
  val m_axi_awlen: UInt = IO(Output(UInt(8.W)))
  val m_axi_awsize: UInt = IO(Output(UInt(3.W)))
  val m_axi_awburst: UInt = IO(Output(UInt(2.W)))
  val m_axi_awlock: Bool = IO(Output(Bool()))
  val m_axi_awcache: UInt = IO(Output(UInt(4.W)))
  val m_axi_awprot: UInt = IO(Output(UInt(3.W)))
  val m_axi_awqos: UInt = IO(Output(UInt(4.W)))
  val m_axi_awregion: UInt = IO(Output(UInt(4.W)))
  val m_axi_awvalid: Bool = IO(Output(Bool()))
  val m_axi_awready: Bool = IO(Input(Bool()))
  val m_axi_wdata: UInt = IO(Output(UInt(32.W)))
  val m_axi_wstrb: UInt = IO(Output(UInt(4.W)))
  val m_axi_wlast: Bool = IO(Output(Bool()))
  val m_axi_wvalid: Bool = IO(Output(Bool()))
  val m_axi_wready: Bool = IO(Input(Bool()))
  val m_axi_bid: UInt = IO(Input(UInt(4.W)))
  val m_axi_bresp: UInt = IO(Input(UInt(2.W)))
  val m_axi_bvalid: Bool = IO(Input(Bool()))
  val m_axi_bready: Bool = IO(Output(Bool()))

  val m_axi_arid: UInt = IO(Output(UInt(4.W)))
  val m_axi_araddr: UInt = IO(Output(UInt(32.W)))
  val m_axi_arlen: UInt = IO(Output(UInt(8.W)))
  val m_axi_arsize: UInt = IO(Output(UInt(3.W)))
  val m_axi_arburst: UInt = IO(Output(UInt(2.W)))
  val m_axi_arlock: Bool = IO(Output(Bool()))
  val m_axi_arcache: UInt = IO(Output(UInt(4.W)))
  val m_axi_arprot: UInt = IO(Output(UInt(3.W)))
  val m_axi_arqos: UInt = IO(Output(UInt(4.W)))
  val m_axi_arregion: UInt = IO(Output(UInt(4.W)))
  val m_axi_arvalid: Bool = IO(Output(Bool()))
  val m_axi_arready: Bool = IO(Input(Bool()))
  val m_axi_rid: UInt = IO(Input(UInt(4.W)))
  val m_axi_rdata: UInt = IO(Input(UInt(32.W)))
  val m_axi_rresp: UInt = IO(Input(UInt(2.W)))
  val m_axi_rlast: Bool = IO(Input(Bool()))
  val m_axi_rvalid: Bool = IO(Input(Bool()))
  val m_axi_rready: Bool = IO(Output(Bool()))
}

class CameraCaptureIO extends Bundle {
  val aclk: Clock = Input(Clock())
  val aresetn: Bool = Input(Bool())
  val pclk: Clock = Input(Clock())
  val pclkResetn: Bool = Input(Bool())
  val vsync: Bool = Input(Bool())
  val href: Bool = Input(Bool())
  val data: UInt = Input(UInt(8.W))
  val apb: APB3IO = Flipped(new APB3IO(addrWidth = 13))
  val axi: AXI4IO = new AXI4IO(idWidth = 4, lenWidth = 8, lockWidth = 1)
  val interrupt: Bool = Output(Bool())
}

/** 整理 camera capture 的 DVP、APB3 与 AXI4 接口，不增加缓冲或协议转换。 */
class CameraCapture extends RawModule {
  val io: CameraCaptureIO = IO(new CameraCaptureIO)
  private val raw = Module(new RawCameraCapture)

  raw.aclk := io.aclk
  raw.aresetn := io.aresetn
  raw.pclk := io.pclk
  raw.pclk_resetn := io.pclkResetn
  raw.vsync := io.vsync
  raw.href := io.href
  raw.data := io.data

  raw.paddr := io.apb.paddr
  raw.psel := io.apb.psel
  raw.penable := io.apb.penable
  raw.pwrite := io.apb.pwrite
  raw.pwdata := io.apb.pwdata
  io.apb.prdata := raw.prdata
  io.apb.pready := raw.pready
  io.apb.pslverr := raw.pslverr
  io.interrupt := raw.interrupt

  io.axi.awid := raw.m_axi_awid
  io.axi.awaddr := raw.m_axi_awaddr
  io.axi.awlen := raw.m_axi_awlen
  io.axi.awsize := raw.m_axi_awsize
  io.axi.awburst := raw.m_axi_awburst
  io.axi.awlock := raw.m_axi_awlock
  io.axi.awcache := raw.m_axi_awcache
  io.axi.awprot := raw.m_axi_awprot
  io.axi.awqos := raw.m_axi_awqos
  io.axi.awregion := raw.m_axi_awregion
  io.axi.awvalid := raw.m_axi_awvalid
  raw.m_axi_awready := io.axi.awready
  io.axi.wdata := raw.m_axi_wdata
  io.axi.wstrb := raw.m_axi_wstrb
  io.axi.wlast := raw.m_axi_wlast
  io.axi.wvalid := raw.m_axi_wvalid
  raw.m_axi_wready := io.axi.wready
  raw.m_axi_bid := io.axi.bid
  raw.m_axi_bresp := io.axi.bresp
  raw.m_axi_bvalid := io.axi.bvalid
  io.axi.bready := raw.m_axi_bready

  io.axi.arid := raw.m_axi_arid
  io.axi.araddr := raw.m_axi_araddr
  io.axi.arlen := raw.m_axi_arlen
  io.axi.arsize := raw.m_axi_arsize
  io.axi.arburst := raw.m_axi_arburst
  io.axi.arlock := raw.m_axi_arlock
  io.axi.arcache := raw.m_axi_arcache
  io.axi.arprot := raw.m_axi_arprot
  io.axi.arqos := raw.m_axi_arqos
  io.axi.arregion := raw.m_axi_arregion
  io.axi.arvalid := raw.m_axi_arvalid
  raw.m_axi_arready := io.axi.arready
  raw.m_axi_rid := io.axi.rid
  raw.m_axi_rdata := io.axi.rdata
  raw.m_axi_rresp := io.axi.rresp
  raw.m_axi_rlast := io.axi.rlast
  raw.m_axi_rvalid := io.axi.rvalid
  io.axi.rready := raw.m_axi_rready
}
