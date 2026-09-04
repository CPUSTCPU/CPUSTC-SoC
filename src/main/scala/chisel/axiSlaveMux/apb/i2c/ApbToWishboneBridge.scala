package chisel.axiSlaveMux.apb.i2c

import chisel3._
import chisel3.util.Cat
import chisel.common.bus.APB3IO

class WishboneMasterPort(
  addrWidth: Int = 3,
  dataWidth: Int = 8
) extends Bundle {
  val addr: UInt = Output(UInt(addrWidth.W))
  val writeData: UInt = Output(UInt(dataWidth.W))
  val readData: UInt = Input(UInt(dataWidth.W))
  val write: Bool = Output(Bool())
  val strobe: Bool = Output(Bool())
  val cycle: Bool = Output(Bool())
  val acknowledge: Bool = Input(Bool())
}

class ApbToWishboneBridgeIO extends Bundle {
  val clk: Clock = Input(Clock())
  val resetn: Bool = Input(Bool())
  val apb: APB3IO = Flipped(new APB3IO(addrWidth = 20))
  val wishbone: WishboneMasterPort = new WishboneMasterPort
}

/** 将 32 位 APB3 单笔访问转换为 8 位 Wishbone 访问。
  *
  * Wishbone 寄存器按 4 字节间隔映射，桥内只允许一个未完成请求且不提供超时处理。
  */
class ApbToWishboneBridge extends RawModule {
  val io: ApbToWishboneBridgeIO = IO(new ApbToWishboneBridgeIO)

  val (active, addressValid, address, writeData, write) =
    withClockAndReset(io.clk, (!io.resetn).asAsyncReset) {
      val activeReg = RegInit(false.B)
      val addressValidReg = RegInit(false.B)
      val addressReg = RegInit(0.U(3.W))
      val writeDataReg = RegInit(0.U(8.W))
      val writeReg = RegInit(false.B)

      val setup = io.apb.psel && !io.apb.penable
      val access = io.apb.psel && io.apb.penable
      val completed = access && activeReg &&
        (!addressValidReg || io.wishbone.acknowledge)

      when(completed) {
        activeReg := false.B
      }.elsewhen(setup && !activeReg) {
        activeReg := true.B
        addressValidReg := io.apb.paddr(13, 5) === 0.U &&
          io.apb.paddr(1, 0) === 0.U
        addressReg := io.apb.paddr(4, 2)
        writeDataReg := io.apb.pwdata(7, 0)
        writeReg := io.apb.pwrite
      }

      (activeReg, addressValidReg, addressReg, writeDataReg, writeReg)
    }

  val access = io.apb.psel && io.apb.penable
  val wishboneRequest = active && addressValid
  val completed = wishboneRequest && io.wishbone.acknowledge

  io.apb.prdata := Mux(addressValid, Cat(0.U(24.W), io.wishbone.readData), 0.U)
  io.apb.pready := access && active && (completed || !addressValid)
  io.apb.pslverr := access && active && !addressValid

  io.wishbone.addr := address
  io.wishbone.writeData := writeData
  io.wishbone.write := write
  io.wishbone.strobe := wishboneRequest
  io.wishbone.cycle := wishboneRequest
}
