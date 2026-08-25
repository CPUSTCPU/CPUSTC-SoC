package tensorcore

import chisel3._
import chisel3.util._

object TensorCoreRegisters {
  val status: Int = 0x200
  val control: Int = 0x204
  val aBase: Int = 0x208
  val bBase: Int = 0x20c
  val cBase: Int = 0x210
  val k: Int = 0x214
  val aStride: Int = 0x218
  val bStride: Int = 0x21c
  val cStride: Int = 0x220
  val roundMode: Int = 0x224
  val irqEnable: Int = 0x228
  val irqStatus: Int = 0x22c
  val cycleCount: Int = 0x230
  val identification: Int = 0x234
  val capabilities: Int = 0x238
  val version: Int = 0x23c
}

class TensorCoreApb3SlaveIO(addrWidth: Int = 20, dataWidth: Int = 32) extends Bundle {
  val psel: Bool = Input(Bool())
  val penable: Bool = Input(Bool())
  val pwrite: Bool = Input(Bool())
  val paddr: UInt = Input(UInt(addrWidth.W))
  val pwdata: UInt = Input(UInt(dataWidth.W))
  val prdata: UInt = Output(UInt(dataWidth.W))
  val pready: Bool = Output(Bool())
  val pslverr: Bool = Output(Bool())
}

class TensorCoreAxi4MasterIO(
  idWidth: Int = 3,
  addrWidth: Int = 32,
  dataWidth: Int = 32
) extends Bundle {
  val awid: UInt = Output(UInt(idWidth.W))
  val awaddr: UInt = Output(UInt(addrWidth.W))
  val awlen: UInt = Output(UInt(8.W))
  val awsize: UInt = Output(UInt(3.W))
  val awburst: UInt = Output(UInt(2.W))
  val awlock: Bool = Output(Bool())
  val awcache: UInt = Output(UInt(4.W))
  val awprot: UInt = Output(UInt(3.W))
  val awqos: UInt = Output(UInt(4.W))
  val awregion: UInt = Output(UInt(4.W))
  val awvalid: Bool = Output(Bool())
  val awready: Bool = Input(Bool())

  val wdata: UInt = Output(UInt(dataWidth.W))
  val wstrb: UInt = Output(UInt((dataWidth / 8).W))
  val wlast: Bool = Output(Bool())
  val wvalid: Bool = Output(Bool())
  val wready: Bool = Input(Bool())

  val bid: UInt = Input(UInt(idWidth.W))
  val bresp: UInt = Input(UInt(2.W))
  val bvalid: Bool = Input(Bool())
  val bready: Bool = Output(Bool())

  val arid: UInt = Output(UInt(idWidth.W))
  val araddr: UInt = Output(UInt(addrWidth.W))
  val arlen: UInt = Output(UInt(8.W))
  val arsize: UInt = Output(UInt(3.W))
  val arburst: UInt = Output(UInt(2.W))
  val arlock: Bool = Output(Bool())
  val arcache: UInt = Output(UInt(4.W))
  val arprot: UInt = Output(UInt(3.W))
  val arqos: UInt = Output(UInt(4.W))
  val arregion: UInt = Output(UInt(4.W))
  val arvalid: Bool = Output(Bool())
  val arready: Bool = Input(Bool())

  val rid: UInt = Input(UInt(idWidth.W))
  val rdata: UInt = Input(UInt(dataWidth.W))
  val rresp: UInt = Input(UInt(2.W))
  val rlast: Bool = Input(Bool())
  val rvalid: Bool = Input(Bool())
  val rready: Bool = Output(Bool())
}

class TensorCoreAxiApbTopIO extends Bundle {
  val apb = new TensorCoreApb3SlaveIO
  val axi = new TensorCoreAxi4MasterIO
  val interrupt = Output(Bool())
}

/** APB3-controlled, AXI4 DMA wrapper around the output-stationary TensorCore.
  *
  * A and B are read as FP32 row-major matrices.  One invocation computes a
  * fixed `rows x k` by `k x cols` tile and writes a `rows x cols` FP32 tile.
  * The wrapper intentionally uses single-beat AXI transfers; arbitration,
  * cache coherency and large-matrix tiling remain SoC/software concerns.
  */
class TensorCoreAxiApbTop(
  expWidth: Int = 8,
  precision: Int = 24,
  rows: Int = 1,
  cols: Int = 4
) extends Module {
  override def desiredName: String = "tensor_core_axi_apb_top"

  require(expWidth + precision == 32, "The SoC wrapper currently supports FP32 elements")
  require(rows > 0 && rows <= 255)
  require(cols > 0 && cols <= 255)

  val io: TensorCoreAxiApbTopIO = IO(new TensorCoreAxiApbTopIO)

  private val rowIndexWidth = math.max(1, log2Ceil(rows))
  private val colIndexWidth = math.max(1, log2Ceil(cols))
  private val mulDelay = 2
  private val drainCycles = (rows - 1) + (cols - 1) + mulDelay + 2
  private val drainWidth = math.max(1, log2Ceil(drainCycles + 1))

  private val aBaseReg = RegInit(0.U(32.W))
  private val bBaseReg = RegInit(0.U(32.W))
  private val cBaseReg = RegInit(0.U(32.W))
  private val kReg = RegInit(0.U(32.W))
  private val aStrideReg = RegInit(0.U(32.W))
  private val bStrideReg = RegInit(0.U(32.W))
  private val cStrideReg = RegInit(0.U(32.W))
  private val roundModeReg = RegInit(0.U(3.W))
  private val irqEnableReg = RegInit(false.B)

  private val busyReg = RegInit(false.B)
  private val doneReg = RegInit(false.B)
  private val errorReg = RegInit(false.B)
  private val irqPendingReg = RegInit(false.B)
  private val cycleCountReg = RegInit(0.U(32.W))

  private val states = Enum(11)
  private val sIdle = states(0)
  private val sSoftReset = states(1)
  private val sCoreReset = states(2)
  private val sReadAAddress = states(3)
  private val sReadAData = states(4)
  private val sReadBAddress = states(5)
  private val sReadBData = states(6)
  private val sIssue = states(7)
  private val sDrain = states(8)
  private val sWrite = states(9)
  private val sWriteResponse = states(10)
  private val state = RegInit(sIdle)

  private val coreResetActive = state === sSoftReset || state === sCoreReset
  private val tensorCore = withReset(reset.asBool || coreResetActive) {
    Module(new TensorCore(expWidth, precision, rows, cols))
  }

  private val aValues = Reg(Vec(rows, UInt(32.W)))
  private val bValues = Reg(Vec(cols, UInt(32.W)))
  private val rowIndex = RegInit(0.U(rowIndexWidth.W))
  private val colIndex = RegInit(0.U(colIndexWidth.W))
  private val kIndex = RegInit(0.U(32.W))
  private val writeRowIndex = RegInit(0.U(rowIndexWidth.W))
  private val writeColIndex = RegInit(0.U(colIndexWidth.W))
  private val drainCounter = RegInit(0.U(drainWidth.W))

  private val aColumnBase = RegInit(0.U(32.W))
  private val bRowBase = RegInit(0.U(32.W))
  private val cRowBase = RegInit(0.U(32.W))
  private val readAddress = RegInit(0.U(32.W))
  private val writeAddress = RegInit(0.U(32.W))
  private val writeAddressAccepted = RegInit(false.B)
  private val writeDataAccepted = RegInit(false.B)

  tensorCore.io.a := aValues
  tensorCore.io.b := bValues
  tensorCore.io.valid := (state === sIssue).asUInt
  tensorCore.io.rm := roundModeReg

  private val registerOffset = io.apb.paddr(13, 0)
  private val knownOffsets = Seq(
    TensorCoreRegisters.status,
    TensorCoreRegisters.control,
    TensorCoreRegisters.aBase,
    TensorCoreRegisters.bBase,
    TensorCoreRegisters.cBase,
    TensorCoreRegisters.k,
    TensorCoreRegisters.aStride,
    TensorCoreRegisters.bStride,
    TensorCoreRegisters.cStride,
    TensorCoreRegisters.roundMode,
    TensorCoreRegisters.irqEnable,
    TensorCoreRegisters.irqStatus,
    TensorCoreRegisters.cycleCount,
    TensorCoreRegisters.identification,
    TensorCoreRegisters.capabilities,
    TensorCoreRegisters.version
  )
  private val knownRegister = knownOffsets
    .map(offset => registerOffset === offset.U)
    .reduce(_ || _)
  private val apbAccess = io.apb.psel && io.apb.penable
  private val apbWrite = apbAccess && io.apb.pwrite
  private val statusWrite = apbWrite && registerOffset === TensorCoreRegisters.status.U
  private val controlWrite = apbWrite && registerOffset === TensorCoreRegisters.control.U
  private val irqStatusWrite = apbWrite && registerOffset === TensorCoreRegisters.irqStatus.U
  private val readOnlyWrite = apbWrite && Seq(
    TensorCoreRegisters.cycleCount,
    TensorCoreRegisters.identification,
    TensorCoreRegisters.capabilities,
    TensorCoreRegisters.version
  ).map(offset => registerOffset === offset.U).reduce(_ || _)
  private val blockedWrite = apbWrite && busyReg && !statusWrite && !irqStatusWrite
  private val misalignedAccess = apbAccess && io.apb.paddr(1, 0).orR

  private val statusValue = Cat(0.U(29.W), errorReg, doneReg, busyReg)
  private val capabilitiesValue = Cat(
    precision.U(8.W),
    expWidth.U(8.W),
    cols.U(8.W),
    rows.U(8.W)
  )

  io.apb.prdata := MuxLookup(registerOffset, 0.U)(Seq(
    TensorCoreRegisters.status.U -> statusValue,
    TensorCoreRegisters.control.U -> 0.U,
    TensorCoreRegisters.aBase.U -> aBaseReg,
    TensorCoreRegisters.bBase.U -> bBaseReg,
    TensorCoreRegisters.cBase.U -> cBaseReg,
    TensorCoreRegisters.k.U -> kReg,
    TensorCoreRegisters.aStride.U -> aStrideReg,
    TensorCoreRegisters.bStride.U -> bStrideReg,
    TensorCoreRegisters.cStride.U -> cStrideReg,
    TensorCoreRegisters.roundMode.U -> roundModeReg,
    TensorCoreRegisters.irqEnable.U -> irqEnableReg,
    TensorCoreRegisters.irqStatus.U -> irqPendingReg,
    TensorCoreRegisters.cycleCount.U -> cycleCountReg,
    TensorCoreRegisters.identification.U -> "h54434f52".U,
    TensorCoreRegisters.capabilities.U -> capabilitiesValue,
    TensorCoreRegisters.version.U -> 1.U
  ))
  io.apb.pready := true.B
  io.apb.pslverr := apbAccess &&
    (!knownRegister || readOnlyWrite || blockedWrite || misalignedAccess)

  when(statusWrite) {
    when(io.apb.pwdata(1)) { doneReg := false.B }
    when(io.apb.pwdata(2)) { errorReg := false.B }
  }
  when(irqStatusWrite && io.apb.pwdata(0)) {
    irqPendingReg := false.B
  }

  when(apbWrite && !busyReg && !readOnlyWrite) {
    switch(registerOffset) {
      is(TensorCoreRegisters.aBase.U) { aBaseReg := io.apb.pwdata }
      is(TensorCoreRegisters.bBase.U) { bBaseReg := io.apb.pwdata }
      is(TensorCoreRegisters.cBase.U) { cBaseReg := io.apb.pwdata }
      is(TensorCoreRegisters.k.U) { kReg := io.apb.pwdata }
      is(TensorCoreRegisters.aStride.U) { aStrideReg := io.apb.pwdata }
      is(TensorCoreRegisters.bStride.U) { bStrideReg := io.apb.pwdata }
      is(TensorCoreRegisters.cStride.U) { cStrideReg := io.apb.pwdata }
      is(TensorCoreRegisters.roundMode.U) { roundModeReg := io.apb.pwdata(2, 0) }
      is(TensorCoreRegisters.irqEnable.U) { irqEnableReg := io.apb.pwdata(0) }
    }
  }

  private val kFitsAddressing = !kReg(31, 30).orR
  private val kBytes = kReg << 2
  private val aLastAddress =
    (aBaseReg +& (aStrideReg * (rows - 1).U)) +& ((kReg - 1.U) << 2)
  private val bLastAddress =
    (bBaseReg +& (bStrideReg * (kReg - 1.U))) +& ((cols - 1) * 4).U
  private val cLastAddress =
    (cBaseReg +& (cStrideReg * (rows - 1).U)) +& ((cols - 1) * 4).U
  private val dmaAddressesValid =
    aLastAddress <= "hfffffffc".U &&
      bLastAddress <= "hfffffffc".U &&
      cLastAddress <= "hfffffffc".U
  private val configurationValid =
    kReg =/= 0.U && kFitsAddressing &&
      !aBaseReg(1, 0).orR && !bBaseReg(1, 0).orR && !cBaseReg(1, 0).orR &&
      !aStrideReg(1, 0).orR && !bStrideReg(1, 0).orR && !cStrideReg(1, 0).orR &&
      aStrideReg >= kBytes &&
      bStrideReg >= (cols * 4).U && cStrideReg >= (cols * 4).U &&
      roundModeReg <= 4.U && dmaAddressesValid

  private def completeCommand(hasError: Bool): Unit = {
    busyReg := false.B
    doneReg := true.B
    errorReg := hasError
    irqPendingReg := true.B
    state := sIdle
  }

  private def failCommand(): Unit = completeCommand(true.B)

  when(controlWrite && !busyReg && io.apb.pwdata(1)) {
    doneReg := false.B
    errorReg := false.B
    irqPendingReg := false.B
    state := sSoftReset
  }.elsewhen(controlWrite && !busyReg && io.apb.pwdata(0)) {
    doneReg := false.B
    errorReg := false.B
    irqPendingReg := false.B
    when(configurationValid) {
      busyReg := true.B
      cycleCountReg := 0.U
      rowIndex := 0.U
      colIndex := 0.U
      kIndex := 0.U
      writeRowIndex := 0.U
      writeColIndex := 0.U
      aColumnBase := aBaseReg
      bRowBase := bBaseReg
      cRowBase := cBaseReg
      readAddress := aBaseReg
      writeAddress := cBaseReg
      state := sCoreReset
    }.otherwise {
      completeCommand(true.B)
    }
  }

  when(busyReg) {
    cycleCountReg := cycleCountReg + 1.U
  }

  io.axi.awid := 0.U
  io.axi.awaddr := writeAddress
  io.axi.awlen := 0.U
  io.axi.awsize := 2.U
  io.axi.awburst := 1.U
  io.axi.awlock := false.B
  io.axi.awcache := 0.U
  io.axi.awprot := 0.U
  io.axi.awqos := 0.U
  io.axi.awregion := 0.U
  io.axi.awvalid := state === sWrite && !writeAddressAccepted

  io.axi.wdata := tensorCore.io.result(writeRowIndex)(writeColIndex)
  io.axi.wstrb := "b1111".U
  io.axi.wlast := true.B
  io.axi.wvalid := state === sWrite && !writeDataAccepted
  io.axi.bready := state === sWriteResponse

  io.axi.arid := 0.U
  io.axi.araddr := readAddress
  io.axi.arlen := 0.U
  io.axi.arsize := 2.U
  io.axi.arburst := 1.U
  io.axi.arlock := false.B
  io.axi.arcache := 0.U
  io.axi.arprot := 0.U
  io.axi.arqos := 0.U
  io.axi.arregion := 0.U
  io.axi.arvalid := state === sReadAAddress || state === sReadBAddress
  io.axi.rready := state === sReadAData || state === sReadBData

  when(state === sSoftReset) {
    state := sIdle
  }.elsewhen(state === sCoreReset) {
    state := sReadAAddress
  }.elsewhen(state === sReadAAddress) {
    when(io.axi.arvalid && io.axi.arready) {
      state := sReadAData
    }
  }.elsewhen(state === sReadAData) {
    when(io.axi.rvalid && io.axi.rready) {
      when(io.axi.rresp =/= 0.U || !io.axi.rlast || io.axi.rid =/= 0.U) {
        failCommand()
      }.otherwise {
        aValues(rowIndex) := io.axi.rdata
        when(rowIndex === (rows - 1).U) {
          rowIndex := 0.U
          colIndex := 0.U
          readAddress := bRowBase
          state := sReadBAddress
        }.otherwise {
          rowIndex := rowIndex + 1.U
          readAddress := readAddress + aStrideReg
          state := sReadAAddress
        }
      }
    }
  }.elsewhen(state === sReadBAddress) {
    when(io.axi.arvalid && io.axi.arready) {
      state := sReadBData
    }
  }.elsewhen(state === sReadBData) {
    when(io.axi.rvalid && io.axi.rready) {
      when(io.axi.rresp =/= 0.U || !io.axi.rlast || io.axi.rid =/= 0.U) {
        failCommand()
      }.otherwise {
        bValues(colIndex) := io.axi.rdata
        when(colIndex === (cols - 1).U) {
          colIndex := 0.U
          state := sIssue
        }.otherwise {
          colIndex := colIndex + 1.U
          readAddress := readAddress + 4.U
          state := sReadBAddress
        }
      }
    }
  }.elsewhen(state === sIssue) {
    when(kIndex === kReg - 1.U) {
      drainCounter := drainCycles.U
      state := sDrain
    }.otherwise {
      kIndex := kIndex + 1.U
      aColumnBase := aColumnBase + 4.U
      bRowBase := bRowBase + bStrideReg
      readAddress := aColumnBase + 4.U
      state := sReadAAddress
    }
  }.elsewhen(state === sDrain) {
    when(drainCounter === 0.U) {
      writeRowIndex := 0.U
      writeColIndex := 0.U
      cRowBase := cBaseReg
      writeAddress := cBaseReg
      writeAddressAccepted := false.B
      writeDataAccepted := false.B
      state := sWrite
    }.otherwise {
      drainCounter := drainCounter - 1.U
    }
  }.elsewhen(state === sWrite) {
    when(io.axi.awvalid && io.axi.awready) {
      writeAddressAccepted := true.B
    }
    when(io.axi.wvalid && io.axi.wready) {
      writeDataAccepted := true.B
    }
    when((writeAddressAccepted || io.axi.awready) &&
      (writeDataAccepted || io.axi.wready)) {
      state := sWriteResponse
    }
  }.elsewhen(state === sWriteResponse) {
    when(io.axi.bvalid && io.axi.bready) {
      when(io.axi.bresp =/= 0.U || io.axi.bid =/= 0.U) {
        failCommand()
      }.elsewhen(writeColIndex === (cols - 1).U && writeRowIndex === (rows - 1).U) {
        completeCommand(false.B)
      }.otherwise {
        writeAddressAccepted := false.B
        writeDataAccepted := false.B
        when(writeColIndex === (cols - 1).U) {
          writeColIndex := 0.U
          writeRowIndex := writeRowIndex + 1.U
          cRowBase := cRowBase + cStrideReg
          writeAddress := cRowBase + cStrideReg
        }.otherwise {
          writeColIndex := writeColIndex + 1.U
          writeAddress := writeAddress + 4.U
        }
        state := sWrite
      }
    }
  }

  io.interrupt := irqEnableReg && irqPendingReg
}
