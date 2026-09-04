package chisel.axiInterconnect.sdio

import chisel3._
import chisel3.util._
import chisel.common.bus.{AXI3IO, AXI4IO, SocConfig}

/** 32-bit, word-addressed Wishbone B4 master port. */
class Wishbone32MasterIO(addrWidth: Int = 30) extends Bundle {
  val adr: UInt = Output(UInt(addrWidth.W))
  val datW: UInt = Output(UInt(32.W))
  val datR: UInt = Input(UInt(32.W))
  val sel: UInt = Output(UInt(4.W))
  val cyc: Bool = Output(Bool())
  val stb: Bool = Output(Bool())
  val ack: Bool = Input(Bool())
  val we: Bool = Output(Bool())
  val cti: UInt = Output(UInt(3.W))
  val bte: UInt = Output(UInt(2.W))
  val err: Bool = Input(Bool())
}

class Axi3ToWishboneControlBridgeIO extends Bundle {
  val axi: AXI3IO = Flipped(new AXI3IO)
  val wishbone: Wishbone32MasterIO = new Wishbone32MasterIO
}

/** Convert single-beat, aligned AXI3 MMIO accesses to classic Wishbone cycles.
  *
  * LiteSDCard's standalone control bus is word-addressed inside a 64 KiB CPU
  * window, so the bridge discards the AXI window base and converts byte offsets
  * to word addresses. Unsupported AXI bursts are drained and answered DECERR.
  */
class Axi3ToWishboneControlBridge extends Module {
  val io: Axi3ToWishboneControlBridgeIO = IO(new Axi3ToWishboneControlBridgeIO)

  private val okay = 0.U(SocConfig.axiRespWidth.W)
  private val slverr = 2.U(SocConfig.axiRespWidth.W)
  private val decerr = 3.U(SocConfig.axiRespWidth.W)
  private val idle :: writeData :: writeWishbone :: writeResponse :: readWishbone :: readResponse :: readErrorResponse :: Nil = Enum(7)
  private val state = RegInit(idle)

  private val writeId = RegInit(0.U(SocConfig.axiIdWidth.W))
  private val writeAddress = RegInit(0.U(SocConfig.axiAddrWidth.W))
  private val writeSupported = RegInit(false.B)
  private val writeDataReg = RegInit(0.U(SocConfig.axiDataWidth.W))
  private val writeStrobeReg = RegInit(0.U(SocConfig.axiStrbWidth.W))
  private val writeResponseCode = RegInit(okay)

  private val readId = RegInit(0.U(SocConfig.axiIdWidth.W))
  private val readAddress = RegInit(0.U(SocConfig.axiAddrWidth.W))
  private val readDataReg = RegInit(0.U(SocConfig.axiDataWidth.W))
  private val readResponseCode = RegInit(okay)
  private val readErrorBeatsRemaining = RegInit(0.U(SocConfig.axiLenWidth.W))

  io.axi.awready := false.B
  io.axi.wready := false.B
  io.axi.bid := writeId
  io.axi.bresp := writeResponseCode
  io.axi.bvalid := state === writeResponse
  io.axi.arready := false.B
  io.axi.rid := readId
  io.axi.rdata := Mux(state === readResponse, readDataReg, 0.U)
  io.axi.rresp := Mux(state === readResponse, readResponseCode, decerr)
  io.axi.rlast := state === readResponse ||
    (state === readErrorResponse && readErrorBeatsRemaining === 0.U)
  io.axi.rvalid := state === readResponse || state === readErrorResponse

  io.wishbone.adr := Cat(0.U(16.W),
    Mux(state === writeWishbone, writeAddress(15, 2), readAddress(15, 2)))
  io.wishbone.datW := writeDataReg
  io.wishbone.sel := writeStrobeReg
  io.wishbone.cyc := state === writeWishbone || state === readWishbone
  io.wishbone.stb := io.wishbone.cyc
  io.wishbone.we := state === writeWishbone
  io.wishbone.cti := 0.U
  io.wishbone.bte := 0.U

  switch(state) {
    is(idle) {
      // Give AW priority if read and write addresses arrive together.
      io.axi.awready := true.B
      io.axi.arready := !io.axi.awvalid

      when(io.axi.awvalid && io.axi.awready) {
        writeId := io.axi.awid
        writeAddress := io.axi.awaddr
        writeSupported := io.axi.awlen === 0.U &&
          io.axi.awsize === 2.U &&
          io.axi.awaddr(1, 0) === 0.U &&
          io.axi.awlock === 0.U
        writeResponseCode := okay
        state := writeData
      }.elsewhen(io.axi.arvalid && io.axi.arready) {
        readId := io.axi.arid
        readAddress := io.axi.araddr
        when(io.axi.arlen === 0.U &&
          io.axi.arsize === 2.U &&
          io.axi.araddr(1, 0) === 0.U &&
          io.axi.arlock === 0.U) {
          state := readWishbone
        }.otherwise {
          readErrorBeatsRemaining := io.axi.arlen
          state := readErrorResponse
        }
      }
    }

    is(writeData) {
      io.axi.wready := true.B
      when(io.axi.wvalid && io.axi.wready) {
        when(writeSupported && io.axi.wlast && io.axi.wid === writeId) {
          writeDataReg := io.axi.wdata
          writeStrobeReg := io.axi.wstrb
          state := writeWishbone
        }.elsewhen(io.axi.wlast) {
          writeResponseCode := decerr
          state := writeResponse
        }.otherwise {
          writeSupported := false.B
        }
      }
    }

    is(writeWishbone) {
      when(io.wishbone.err) {
        writeResponseCode := slverr
        state := writeResponse
      }.elsewhen(io.wishbone.ack) {
        writeResponseCode := okay
        state := writeResponse
      }
    }

    is(writeResponse) {
      when(io.axi.bready) {
        state := idle
      }
    }

    is(readWishbone) {
      when(io.wishbone.err || io.wishbone.ack) {
        readDataReg := io.wishbone.datR
        readResponseCode := Mux(io.wishbone.err, slverr, okay)
        state := readResponse
      }
    }

    is(readResponse) {
      when(io.axi.rready) {
        state := idle
      }
    }

    is(readErrorResponse) {
      when(io.axi.rready) {
        when(readErrorBeatsRemaining === 0.U) {
          state := idle
        }.otherwise {
          readErrorBeatsRemaining := readErrorBeatsRemaining - 1.U
        }
      }
    }
  }
}

class WishboneToAxi4DmaBridgeIO extends Bundle {
  val wishbone: Wishbone32MasterIO = Flipped(new Wishbone32MasterIO)
  val axi: AXI4IO = new AXI4IO(
    idWidth = 4,
    addrWidth = 32,
    lenWidth = 8,
    lockWidth = 1,
    dataWidth = 32,
    strbWidth = 4
  )
}

/** Convert LiteSDCard's non-bursting Wishbone DMA requests to AXI4 single beats. */
class WishboneToAxi4DmaBridge extends Module {
  val io: WishboneToAxi4DmaBridgeIO = IO(new WishboneToAxi4DmaBridgeIO)

  private val idle :: writeIssue :: writeResponse :: readIssue :: readResponse :: Nil = Enum(5)
  private val state = RegInit(idle)
  private val addressReg = RegInit(0.U(30.W))
  private val writeDataReg = RegInit(0.U(32.W))
  private val selectReg = RegInit(0.U(4.W))
  private val awAccepted = RegInit(false.B)
  private val wAccepted = RegInit(false.B)

  io.wishbone.datR := io.axi.rdata
  io.wishbone.ack := false.B
  io.wishbone.err := false.B

  io.axi.awid := 0.U
  io.axi.awaddr := Cat(addressReg, 0.U(2.W))
  io.axi.awlen := 0.U
  io.axi.awsize := 2.U
  io.axi.awburst := 1.U
  io.axi.awlock := false.B
  io.axi.awcache := 0.U
  io.axi.awprot := 0.U
  io.axi.awqos := 0.U
  io.axi.awregion := 0.U
  io.axi.awvalid := state === writeIssue && !awAccepted

  io.axi.wdata := writeDataReg
  io.axi.wstrb := selectReg
  io.axi.wlast := true.B
  io.axi.wvalid := state === writeIssue && !wAccepted
  io.axi.bready := state === writeResponse

  io.axi.arid := 0.U
  io.axi.araddr := Cat(addressReg, 0.U(2.W))
  io.axi.arlen := 0.U
  io.axi.arsize := 2.U
  io.axi.arburst := 1.U
  io.axi.arlock := false.B
  io.axi.arcache := 0.U
  io.axi.arprot := 0.U
  io.axi.arqos := 0.U
  io.axi.arregion := 0.U
  io.axi.arvalid := state === readIssue
  io.axi.rready := state === readResponse

  private val awFire = io.axi.awvalid && io.axi.awready
  private val wFire = io.axi.wvalid && io.axi.wready

  switch(state) {
    is(idle) {
      when(io.wishbone.cyc && io.wishbone.stb) {
        addressReg := io.wishbone.adr
        writeDataReg := io.wishbone.datW
        selectReg := io.wishbone.sel
        when(io.wishbone.we) {
          awAccepted := false.B
          wAccepted := false.B
          state := writeIssue
        }.otherwise {
          state := readIssue
        }
      }
    }

    is(writeIssue) {
      when(awFire) {
        awAccepted := true.B
      }
      when(wFire) {
        wAccepted := true.B
      }
      when((awAccepted || awFire) && (wAccepted || wFire)) {
        state := writeResponse
      }
    }

    is(writeResponse) {
      when(io.axi.bvalid) {
        io.wishbone.ack := io.axi.bresp === 0.U
        io.wishbone.err := io.axi.bresp =/= 0.U
        state := idle
      }
    }

    is(readIssue) {
      when(io.axi.arready) {
        state := readResponse
      }
    }

    is(readResponse) {
      when(io.axi.rvalid) {
        io.wishbone.ack := io.axi.rresp === 0.U && io.axi.rlast
        io.wishbone.err := io.axi.rresp =/= 0.U || !io.axi.rlast
        state := idle
      }
    }
  }
}
