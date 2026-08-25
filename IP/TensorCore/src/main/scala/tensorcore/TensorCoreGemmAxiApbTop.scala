package tensorcore

import chisel3._
import chisel3.util._

object TensorCoreGemmRegisters {
  val status: Int = 0x200
  val control: Int = 0x204
  val aBase: Int = 0x208
  val bBase: Int = 0x20c
  val cBase: Int = 0x210
  val m: Int = 0x214
  val n: Int = 0x218
  val k: Int = 0x21c
  val aStride: Int = 0x220
  val cStride: Int = 0x224
  val roundMode: Int = 0x228
  val irqEnable: Int = 0x22c
  val irqStatus: Int = 0x230
  val errorCode: Int = 0x234
  val totalCycles: Int = 0x238
  val bReadCycles: Int = 0x23c
  val aReadCycles: Int = 0x240
  val computeCycles: Int = 0x244
  val cWriteCycles: Int = 0x248
  val identification: Int = 0x250
  val capabilities0: Int = 0x254
  val capabilities1: Int = 0x258
  val version: Int = 0x25c
  val mode: Int = 0x260
  val inputHeight: Int = 0x264
  val inputWidth: Int = 0x268
  val inputChannels: Int = 0x26c
  val outputHeight: Int = 0x270
  val outputWidth: Int = 0x274
  val kernelHeight: Int = 0x278
  val kernelWidth: Int = 0x27c
  val strideY: Int = 0x280
  val strideX: Int = 0x284
  val padTop: Int = 0x288
  val padLeft: Int = 0x28c
  val preluBase: Int = 0x290
  val windowCycles: Int = 0x294
  val postCycles: Int = 0x298
  val capabilities2: Int = 0x29c
  val sourceExtentBytes: Int = 0x2a0
  val sourceRowBytes: Int = 0x2a4
  val sourcePixelBytes: Int = 0x2a8
  val sourceStepYBytes: Int = 0x2ac
  val sourceStepXBytes: Int = 0x2b0
  val sourcePadTopBytes: Int = 0x2b4
  val sourcePadLeftBytes: Int = 0x2b8
  val resultRows: Int = 0x2bc
}

object TensorCoreInputMode {
  val matrix: UInt = 0.U(2.W)
  val nhwcWindow: UInt = 1.U(2.W)
}

object TensorCorePostMode {
  val none: UInt = 0.U(2.W)
  val prelu: UInt = 1.U(2.W)
  val preluPool2x2Ceil: UInt = 2.U(2.W)
}

object TensorCoreGemmError {
  val none: UInt = 0.U(32.W)
  val invalidConfiguration: UInt = 1.U(32.W)
  val readProtocol: UInt = "h00000300".U(32.W)
  val writeProtocol: UInt = "h00000400".U(32.W)

  def readDma(code: UInt): UInt = "h00000100".U | code
  def writeDma(code: UInt): UInt = "h00000200".U | code
}

/**
  * Variable-size FP32 GEMM controller around the existing 1x4 TensorCore.
  *
  * A and C are row-major. B is packed in groups of four output columns:
  * `packed[((group * K + k) * 4) + lane]`. One B block of up to 32 columns
  * remains in four local banks while all M rows are processed.
  */
class TensorCoreGemmAxiApbTop(
  expWidth: Int = 8,
  precision: Int = 24
) extends Module {
  override def desiredName: String = "tensor_core_gemm_axi_apb_top"

  require(expWidth + precision == 32)

  val io: TensorCoreAxiApbTopIO = IO(new TensorCoreAxiApbTopIO)

  private val MaxK = 256
  private val TileColumns = 32
  private val TensorColumns = 4
  private val GroupsPerTile = TileColumns / TensorColumns
  private val BBankDepth = MaxK * GroupsPerTile

  private val aBaseReg = RegInit(0.U(32.W))
  private val bBaseReg = RegInit(0.U(32.W))
  private val cBaseReg = RegInit(0.U(32.W))
  private val mReg = RegInit(0.U(32.W))
  private val nReg = RegInit(0.U(32.W))
  private val kReg = RegInit(0.U(32.W))
  private val aStrideReg = RegInit(0.U(32.W))
  private val cStrideReg = RegInit(0.U(32.W))
  private val roundModeReg = RegInit(0.U(3.W))
  private val irqEnableReg = RegInit(false.B)
  private val inputModeReg = RegInit(TensorCoreInputMode.matrix)
  private val postModeReg = RegInit(TensorCorePostMode.none)
  private val appendOneReg = RegInit(false.B)
  private val inputHeightReg = RegInit(0.U(32.W))
  private val inputWidthReg = RegInit(0.U(32.W))
  private val inputChannelsReg = RegInit(0.U(32.W))
  private val outputHeightReg = RegInit(0.U(32.W))
  private val outputWidthReg = RegInit(0.U(32.W))
  private val kernelHeightReg = RegInit(0.U(32.W))
  private val kernelWidthReg = RegInit(0.U(32.W))
  private val strideYReg = RegInit(0.U(32.W))
  private val strideXReg = RegInit(0.U(32.W))
  private val padTopReg = RegInit(0.U(32.W))
  private val padLeftReg = RegInit(0.U(32.W))
  private val preluBaseReg = RegInit(0.U(32.W))
  private val sourceExtentBytesReg = RegInit(0.U(32.W))
  private val sourceRowBytesReg = RegInit(0.U(32.W))
  private val sourcePixelBytesReg = RegInit(0.U(32.W))
  private val sourceStepYBytesReg = RegInit(0.U(32.W))
  private val sourceStepXBytesReg = RegInit(0.U(32.W))
  private val sourcePadTopBytesReg = RegInit(0.U(32.W))
  private val sourcePadLeftBytesReg = RegInit(0.U(32.W))
  private val resultRowsReg = RegInit(0.U(32.W))

  private val busyReg = RegInit(false.B)
  private val doneReg = RegInit(false.B)
  private val errorReg = RegInit(false.B)
  private val irqPendingReg = RegInit(false.B)
  private val errorCodeReg = RegInit(0.U(32.W))
  private val totalCyclesReg = RegInit(0.U(32.W))
  private val bReadCyclesReg = RegInit(0.U(32.W))
  private val aReadCyclesReg = RegInit(0.U(32.W))
  private val computeCyclesReg = RegInit(0.U(32.W))
  private val cWriteCyclesReg = RegInit(0.U(32.W))
  private val windowCyclesReg = RegInit(0.U(32.W))
  private val postCyclesReg = RegInit(0.U(32.W))

  private val states = Enum(25)
  private val sIdle = states(0)
  private val sBPlan = states(1)
  private val sBDesc = states(2)
  private val sBLoad = states(3)
  private val sAlphaDesc = states(4)
  private val sAlphaLoad = states(5)
  private val sADesc = states(6)
  private val sALoad = states(7)
  private val sWindowPrepare = states(8)
  private val sWindowWait = states(9)
  private val sWindowLeadZero = states(10)
  private val sWindowDesc = states(11)
  private val sWindowLoad = states(12)
  private val sWindowTrailZero = states(13)
  private val sWindowAppendOne = states(14)
  private val sCoreReset = states(15)
  private val sCorePrime = states(16)
  private val sCoreIssue = states(17)
  private val sCoreDrain = states(18)
  private val sPostStart = states(19)
  private val sPostWait = states(20)
  private val sCDesc = states(21)
  private val sCData = states(22)
  private val sCWait = states(23)
  private val sReserved = states(24)
  private val state = RegInit(sIdle)

  private val rowIndexReg = RegInit(0.U(32.W))
  private val workYReg = RegInit(0.U(32.W))
  private val workXReg = RegInit(0.U(32.W))
  private val sampleIndexReg = RegInit(0.U(2.W))
  private val groupBaseReg = RegInit(0.U(32.W))
  private val groupLocalReg = RegInit(0.U(4.W))
  private val issueKReg = RegInit(0.U(8.W))
  private val captureMaskReg = RegInit(0.U(4.W))
  private val cWriteIndexReg = RegInit(0.U(5.W))
  private val postIndexReg = RegInit(0.U(5.W))
  private val windowKernelRowReg = RegInit(0.U(32.W))
  private val windowFillIndexReg = RegInit(0.U(32.W))
  private val windowRowBaseWordsReg = RegInit(0.U(32.W))
  private val windowSourceYReg = RegInit(0.S(33.W))
  private val windowRowByteOffsetReg = RegInit(0.S(33.W))

  private val bTileAddressReg = RegInit(0.U(32.W))
  private val bTileBytesReg = RegInit(0.U(32.W))
  private val bPlanIndexReg = RegInit(0.U(4.W))
  private val bGroupReadBaseReg = RegInit(0.U(11.W))
  private val cGroupBaseAddressReg = RegInit(0.U(32.W))
  private val currentCAddressReg = RegInit(0.U(32.W))
  private val alphaTileAddressReg = RegInit(0.U(32.W))
  private val matrixRowAddressReg = RegInit(0.U(32.W))
  private val matrixPoolRowBaseAddressReg = RegInit(0.U(32.W))
  private val sourceBaseYReg = RegInit(0.S(33.W))
  private val sourceBaseXReg = RegInit(0.S(33.W))
  private val sourceBaseYByteOffsetReg = RegInit(0.S(33.W))
  private val sourceBaseXByteOffsetReg = RegInit(0.S(33.W))

  private val readWordIndexReg = RegInit(0.U(20.W))
  private val readExpectedWordsReg = RegInit(0.U(20.W))
  private val readExpectedTagReg = RegInit(0.U(8.W))
  private val readDataDoneReg = RegInit(false.B)
  private val readStatusDoneReg = RegInit(false.B)
  private val readProtocolErrorReg = RegInit(false.B)
  private val readDmaErrorReg = RegInit(0.U(4.W))

  private val writeStatusDoneReg = RegInit(false.B)
  private val writeProtocolErrorReg = RegInit(false.B)
  private val writeDmaErrorReg = RegInit(0.U(4.W))

  private val aMemory = SyncReadMem(MaxK, UInt(32.W))
  private val bMemories = Seq.fill(TensorColumns)(SyncReadMem(BBankDepth, UInt(32.W)))
  private val cRow = Reg(Vec(TileColumns, UInt(32.W)))
  private val alphaRow = Reg(Vec(TileColumns, UInt(32.W)))
  private val poolRow = Reg(Vec(TileColumns, UInt(32.W)))

  private val aWriteEnable = WireDefault(false.B)
  private val aWriteAddress = WireDefault(0.U(8.W))
  private val aWriteData = WireDefault(0.U(32.W))

  private val coreResetActive = state === sCoreReset
  private val tensorCore = withReset(reset.asBool || coreResetActive) {
    Module(new TensorCore(expWidth, precision, 1, TensorColumns))
  }

  private val readDma = Module(new AxiDmaReadExt)
  private val writeDma = Module(new AxiDmaWriteExt)
  readDma.clk := clock
  readDma.rst := reset.asBool
  readDma.enable := true.B
  writeDma.clk := clock
  writeDma.rst := reset.asBool
  writeDma.enable := true.B
  writeDma.abort := false.B

  private val poolEnabled = postModeReg === TensorCorePostMode.preluPool2x2Ceil
  private val poolOutputHeight = (outputHeightReg + 1.U) >> 1
  private val poolOutputWidth = (outputWidthReg + 1.U) >> 1
  private val sampleYOffset = Mux(
    poolEnabled && sampleIndexReg(1),
    strideYReg.zext,
    0.S(33.W)
  )
  private val sampleXOffset = Mux(
    poolEnabled && sampleIndexReg(0),
    strideXReg.zext,
    0.S(33.W)
  )
  private val sampleYByteOffset = Mux(
    poolEnabled && sampleIndexReg(1),
    sourceStepYBytesReg.zext,
    0.S(33.W)
  )
  private val sampleXByteOffset = Mux(
    poolEnabled && sampleIndexReg(0),
    sourceStepXBytesReg.zext,
    0.S(33.W)
  )
  private val currentSourceY = sourceBaseYReg + sampleYOffset
  private val currentSourceX = sourceBaseXReg + sampleXOffset
  private val currentSourceYByteOffset = sourceBaseYByteOffsetReg + sampleYByteOffset
  private val currentSourceXByteOffset = sourceBaseXByteOffsetReg + sampleXByteOffset

  private val windowLoader = Module(new TensorWindowLoader)
  windowLoader.io.start := state === sWindowPrepare
  windowLoader.io.inputBase := aBaseReg
  windowLoader.io.inputHeight := inputHeightReg
  windowLoader.io.inputWidth := inputWidthReg
  windowLoader.io.inputChannels := inputChannelsReg
  windowLoader.io.kernelWidth := kernelWidthReg
  windowLoader.io.sourceY := windowSourceYReg
  windowLoader.io.sourceX := currentSourceX
  windowLoader.io.rowByteOffset := windowRowByteOffsetReg
  windowLoader.io.columnByteOffset := currentSourceXByteOffset
  windowLoader.io.inputPixelBytes := sourcePixelBytesReg
  windowLoader.io.rowBaseWords := windowRowBaseWordsReg

  private val postProcessor = Module(new TensorPostProcessor(expWidth, precision))
  postProcessor.io.start := state === sPostStart
  postProcessor.io.value := cRow(postIndexReg)
  postProcessor.io.alpha := alphaRow(postIndexReg)
  postProcessor.io.preluEnable := postModeReg =/= TensorCorePostMode.none
  postProcessor.io.accumulateMax :=
    postModeReg === TensorCorePostMode.preluPool2x2Ceil && sampleIndexReg =/= 0.U
  postProcessor.io.accumulator := poolRow(postIndexReg)
  postProcessor.io.roundMode := roundModeReg

  private val totalGroups = (nReg + 3.U) >> 2
  private val groupsRemaining = totalGroups - groupBaseReg
  private val currentGroupCount = Wire(UInt(4.W))
  currentGroupCount := Mux(groupsRemaining > GroupsPerTile.U, GroupsPerTile.U, groupsRemaining)
  private val columnsRemaining = nReg - (groupBaseReg << 2)
  private val currentColumnCount = Wire(UInt(6.W))
  currentColumnCount := Mux(columnsRemaining > TileColumns.U, TileColumns.U, columnsRemaining)

  private val currentBAddress = bTileAddressReg
  private val currentBBytes = bTileBytesReg
  private val matrixSampleYOffset = Mux(
    poolEnabled && sampleIndexReg(1),
    sourceStepYBytesReg,
    0.U
  )
  private val matrixSampleXOffset = Mux(
    poolEnabled && sampleIndexReg(0),
    sourceStepXBytesReg,
    0.U
  )
  private val currentAAddress =
    (matrixRowAddressReg + matrixSampleYOffset + matrixSampleXOffset)(31, 0)
  private val currentABytes = kReg << 2
  private val currentAlphaAddress = alphaTileAddressReg
  private val currentAlphaBytes = currentColumnCount << 2
  private val currentCAddress = currentCAddressReg
  private val currentCBytes = currentColumnCount << 2
  private val outputRows = resultRowsReg
  private val inputModeValid = inputModeReg === TensorCoreInputMode.matrix ||
    inputModeReg === TensorCoreInputMode.nhwcWindow
  private val postModeValid = postModeReg <= TensorCorePostMode.preluPool2x2Ceil
  private val spatialConfigurationValid =
    outputHeightReg =/= 0.U && outputWidthReg =/= 0.U &&
      outputHeightReg <= 65535.U && outputWidthReg <= 65535.U &&
      resultRowsReg =/= 0.U && resultRowsReg <= mReg &&
      Mux(poolEnabled, resultRowsReg <= mReg, resultRowsReg === mReg)
  private val derivedSourceValid =
    sourceExtentBytesReg =/= 0.U && sourceRowBytesReg =/= 0.U &&
      sourcePixelBytesReg =/= 0.U && sourceStepYBytesReg =/= 0.U &&
      sourceStepXBytesReg =/= 0.U &&
      !sourceExtentBytesReg(1, 0).orR && !sourceRowBytesReg(1, 0).orR &&
      !sourcePixelBytesReg(1, 0).orR && !sourceStepYBytesReg(1, 0).orR &&
      !sourceStepXBytesReg(1, 0).orR && !sourcePadTopBytesReg(1, 0).orR &&
      !sourcePadLeftBytesReg(1, 0).orR
  private val matrixConfigurationValid =
    inputModeReg =/= TensorCoreInputMode.matrix ||
      (aStrideReg >= (kReg << 2) && (!poolEnabled || derivedSourceValid))
  private val windowConfigurationValid =
    inputModeReg =/= TensorCoreInputMode.nhwcWindow ||
      (spatialConfigurationValid && inputHeightReg =/= 0.U && inputWidthReg =/= 0.U &&
        inputHeightReg <= 65535.U && inputWidthReg <= 65535.U &&
        inputChannelsReg =/= 0.U && kernelHeightReg =/= 0.U && kernelWidthReg =/= 0.U &&
        kernelHeightReg <= MaxK.U && kernelWidthReg <= MaxK.U &&
        strideYReg =/= 0.U && strideXReg =/= 0.U && derivedSourceValid &&
        sourcePixelBytesReg === (inputChannelsReg << 2))
  private val postConfigurationValid =
    postModeReg === TensorCorePostMode.none ||
      (!preluBaseReg(1, 0).orR &&
        (!poolEnabled || spatialConfigurationValid))
  private val configurationValid =
    mReg =/= 0.U && mReg <= 65535.U &&
      nReg =/= 0.U && nReg <= 65535.U &&
      kReg =/= 0.U && kReg <= MaxK.U &&
      resultRowsReg =/= 0.U && resultRowsReg <= mReg &&
      !aBaseReg(1, 0).orR && !bBaseReg(1, 0).orR && !cBaseReg(1, 0).orR &&
      !aStrideReg(1, 0).orR && !cStrideReg(1, 0).orR &&
      cStrideReg >= (nReg << 2) && inputModeValid && postModeValid &&
      matrixConfigurationValid && windowConfigurationValid && postConfigurationValid &&
      roundModeReg <= 4.U

  private def completeCommand(code: UInt): Unit = {
    busyReg := false.B
    doneReg := true.B
    errorReg := code =/= TensorCoreGemmError.none
    errorCodeReg := code
    irqPendingReg := true.B
    state := sIdle
  }

  private def beginCurrentA(): Unit = {
    windowKernelRowReg := 0.U
    windowFillIndexReg := 0.U
    windowRowBaseWordsReg := 0.U
    windowSourceYReg := currentSourceY
    windowRowByteOffsetReg := currentSourceYByteOffset
    state := Mux(inputModeReg === TensorCoreInputMode.nhwcWindow, sWindowPrepare, sADesc)
  }

  private def beginCore(): Unit = {
    groupLocalReg := 0.U
    bGroupReadBaseReg := 0.U
    issueKReg := 0.U
    captureMaskReg := 0.U
    state := sCoreReset
  }

  private def finishWindowKernelRow(): Unit = {
    val completedWindowWords = windowRowBaseWordsReg + windowLoader.io.rowWords

    when(windowKernelRowReg === kernelHeightReg - 1.U) {
      when(completedWindowWords + appendOneReg.asUInt =/= kReg) {
        completeCommand(TensorCoreGemmError.invalidConfiguration)
      }.elsewhen(appendOneReg) {
        state := sWindowAppendOne
      }.otherwise {
        beginCore()
      }
    }.otherwise {
      windowKernelRowReg := windowKernelRowReg + 1.U
      windowFillIndexReg := 0.U
      windowRowBaseWordsReg := completedWindowWords
      windowSourceYReg := windowSourceYReg + 1.S
      windowRowByteOffsetReg := windowRowByteOffsetReg + sourceRowBytesReg.zext
      state := sWindowPrepare
    }
  }

  // APB register interface. Hardware sees the enclosing display window offset.
  private val registerOffset = io.apb.paddr(13, 0)
  private val knownOffsets = Seq(
    TensorCoreGemmRegisters.status,
    TensorCoreGemmRegisters.control,
    TensorCoreGemmRegisters.aBase,
    TensorCoreGemmRegisters.bBase,
    TensorCoreGemmRegisters.cBase,
    TensorCoreGemmRegisters.m,
    TensorCoreGemmRegisters.n,
    TensorCoreGemmRegisters.k,
    TensorCoreGemmRegisters.aStride,
    TensorCoreGemmRegisters.cStride,
    TensorCoreGemmRegisters.roundMode,
    TensorCoreGemmRegisters.irqEnable,
    TensorCoreGemmRegisters.irqStatus,
    TensorCoreGemmRegisters.errorCode,
    TensorCoreGemmRegisters.totalCycles,
    TensorCoreGemmRegisters.bReadCycles,
    TensorCoreGemmRegisters.aReadCycles,
    TensorCoreGemmRegisters.computeCycles,
    TensorCoreGemmRegisters.cWriteCycles,
    TensorCoreGemmRegisters.identification,
    TensorCoreGemmRegisters.capabilities0,
    TensorCoreGemmRegisters.capabilities1,
    TensorCoreGemmRegisters.version,
    TensorCoreGemmRegisters.mode,
    TensorCoreGemmRegisters.inputHeight,
    TensorCoreGemmRegisters.inputWidth,
    TensorCoreGemmRegisters.inputChannels,
    TensorCoreGemmRegisters.outputHeight,
    TensorCoreGemmRegisters.outputWidth,
    TensorCoreGemmRegisters.kernelHeight,
    TensorCoreGemmRegisters.kernelWidth,
    TensorCoreGemmRegisters.strideY,
    TensorCoreGemmRegisters.strideX,
    TensorCoreGemmRegisters.padTop,
    TensorCoreGemmRegisters.padLeft,
    TensorCoreGemmRegisters.preluBase,
    TensorCoreGemmRegisters.windowCycles,
    TensorCoreGemmRegisters.postCycles,
    TensorCoreGemmRegisters.capabilities2,
    TensorCoreGemmRegisters.sourceExtentBytes,
    TensorCoreGemmRegisters.sourceRowBytes,
    TensorCoreGemmRegisters.sourcePixelBytes,
    TensorCoreGemmRegisters.sourceStepYBytes,
    TensorCoreGemmRegisters.sourceStepXBytes,
    TensorCoreGemmRegisters.sourcePadTopBytes,
    TensorCoreGemmRegisters.sourcePadLeftBytes,
    TensorCoreGemmRegisters.resultRows
  )
  private val readOnlyOffsets = Seq(
    TensorCoreGemmRegisters.errorCode,
    TensorCoreGemmRegisters.totalCycles,
    TensorCoreGemmRegisters.bReadCycles,
    TensorCoreGemmRegisters.aReadCycles,
    TensorCoreGemmRegisters.computeCycles,
    TensorCoreGemmRegisters.cWriteCycles,
    TensorCoreGemmRegisters.identification,
    TensorCoreGemmRegisters.capabilities0,
    TensorCoreGemmRegisters.capabilities1,
    TensorCoreGemmRegisters.version,
    TensorCoreGemmRegisters.windowCycles,
    TensorCoreGemmRegisters.postCycles,
    TensorCoreGemmRegisters.capabilities2
  )
  private val knownRegister = knownOffsets.map(registerOffset === _.U).reduce(_ || _)
  private val apbAccess = io.apb.psel && io.apb.penable
  private val apbWrite = apbAccess && io.apb.pwrite
  private val statusWrite = apbWrite && registerOffset === TensorCoreGemmRegisters.status.U
  private val controlWrite = apbWrite && registerOffset === TensorCoreGemmRegisters.control.U
  private val irqStatusWrite = apbWrite && registerOffset === TensorCoreGemmRegisters.irqStatus.U
  private val readOnlyWrite = apbWrite && readOnlyOffsets.map(registerOffset === _.U).reduce(_ || _)
  private val blockedWrite = apbWrite && busyReg && !statusWrite && !irqStatusWrite
  private val statusValue = Cat(0.U(29.W), errorReg, doneReg, busyReg)

  io.apb.prdata := MuxLookup(registerOffset, 0.U)(Seq(
    TensorCoreGemmRegisters.status.U -> statusValue,
    TensorCoreGemmRegisters.control.U -> 0.U,
    TensorCoreGemmRegisters.aBase.U -> aBaseReg,
    TensorCoreGemmRegisters.bBase.U -> bBaseReg,
    TensorCoreGemmRegisters.cBase.U -> cBaseReg,
    TensorCoreGemmRegisters.m.U -> mReg,
    TensorCoreGemmRegisters.n.U -> nReg,
    TensorCoreGemmRegisters.k.U -> kReg,
    TensorCoreGemmRegisters.aStride.U -> aStrideReg,
    TensorCoreGemmRegisters.cStride.U -> cStrideReg,
    TensorCoreGemmRegisters.roundMode.U -> roundModeReg,
    TensorCoreGemmRegisters.irqEnable.U -> irqEnableReg,
    TensorCoreGemmRegisters.irqStatus.U -> irqPendingReg,
    TensorCoreGemmRegisters.errorCode.U -> errorCodeReg,
    TensorCoreGemmRegisters.totalCycles.U -> totalCyclesReg,
    TensorCoreGemmRegisters.bReadCycles.U -> bReadCyclesReg,
    TensorCoreGemmRegisters.aReadCycles.U -> aReadCyclesReg,
    TensorCoreGemmRegisters.computeCycles.U -> computeCyclesReg,
    TensorCoreGemmRegisters.cWriteCycles.U -> cWriteCyclesReg,
    TensorCoreGemmRegisters.identification.U -> "h54434734".U,
    TensorCoreGemmRegisters.capabilities0.U -> Cat(TileColumns.U(8.W), TensorColumns.U(8.W), MaxK.U(16.W)),
    TensorCoreGemmRegisters.capabilities1.U -> (BBankDepth * TensorColumns * 4).U,
    TensorCoreGemmRegisters.version.U -> 4.U,
    TensorCoreGemmRegisters.mode.U -> Cat(0.U(27.W), appendOneReg, postModeReg, inputModeReg),
    TensorCoreGemmRegisters.inputHeight.U -> inputHeightReg,
    TensorCoreGemmRegisters.inputWidth.U -> inputWidthReg,
    TensorCoreGemmRegisters.inputChannels.U -> inputChannelsReg,
    TensorCoreGemmRegisters.outputHeight.U -> outputHeightReg,
    TensorCoreGemmRegisters.outputWidth.U -> outputWidthReg,
    TensorCoreGemmRegisters.kernelHeight.U -> kernelHeightReg,
    TensorCoreGemmRegisters.kernelWidth.U -> kernelWidthReg,
    TensorCoreGemmRegisters.strideY.U -> strideYReg,
    TensorCoreGemmRegisters.strideX.U -> strideXReg,
    TensorCoreGemmRegisters.padTop.U -> padTopReg,
    TensorCoreGemmRegisters.padLeft.U -> padLeftReg,
    TensorCoreGemmRegisters.preluBase.U -> preluBaseReg,
    TensorCoreGemmRegisters.windowCycles.U -> windowCyclesReg,
    TensorCoreGemmRegisters.postCycles.U -> postCyclesReg,
    TensorCoreGemmRegisters.capabilities2.U -> "h0000000f".U,
    TensorCoreGemmRegisters.sourceExtentBytes.U -> sourceExtentBytesReg,
    TensorCoreGemmRegisters.sourceRowBytes.U -> sourceRowBytesReg,
    TensorCoreGemmRegisters.sourcePixelBytes.U -> sourcePixelBytesReg,
    TensorCoreGemmRegisters.sourceStepYBytes.U -> sourceStepYBytesReg,
    TensorCoreGemmRegisters.sourceStepXBytes.U -> sourceStepXBytesReg,
    TensorCoreGemmRegisters.sourcePadTopBytes.U -> sourcePadTopBytesReg,
    TensorCoreGemmRegisters.sourcePadLeftBytes.U -> sourcePadLeftBytesReg,
    TensorCoreGemmRegisters.resultRows.U -> resultRowsReg
  ))
  io.apb.pready := true.B
  io.apb.pslverr := apbAccess &&
    (!knownRegister || readOnlyWrite || blockedWrite || io.apb.paddr(1, 0).orR)

  when(statusWrite) {
    when(io.apb.pwdata(1)) { doneReg := false.B }
    when(io.apb.pwdata(2)) {
      errorReg := false.B
      errorCodeReg := 0.U
    }
  }
  when(irqStatusWrite && io.apb.pwdata(0)) {
    irqPendingReg := false.B
  }
  when(apbWrite && !busyReg && !readOnlyWrite) {
    switch(registerOffset) {
      is(TensorCoreGemmRegisters.aBase.U) { aBaseReg := io.apb.pwdata }
      is(TensorCoreGemmRegisters.bBase.U) { bBaseReg := io.apb.pwdata }
      is(TensorCoreGemmRegisters.cBase.U) { cBaseReg := io.apb.pwdata }
      is(TensorCoreGemmRegisters.m.U) { mReg := io.apb.pwdata }
      is(TensorCoreGemmRegisters.n.U) { nReg := io.apb.pwdata }
      is(TensorCoreGemmRegisters.k.U) { kReg := io.apb.pwdata }
      is(TensorCoreGemmRegisters.aStride.U) { aStrideReg := io.apb.pwdata }
      is(TensorCoreGemmRegisters.cStride.U) { cStrideReg := io.apb.pwdata }
      is(TensorCoreGemmRegisters.roundMode.U) { roundModeReg := io.apb.pwdata(2, 0) }
      is(TensorCoreGemmRegisters.irqEnable.U) { irqEnableReg := io.apb.pwdata(0) }
      is(TensorCoreGemmRegisters.mode.U) {
        inputModeReg := io.apb.pwdata(1, 0)
        postModeReg := io.apb.pwdata(3, 2)
        appendOneReg := io.apb.pwdata(4)
      }
      is(TensorCoreGemmRegisters.inputHeight.U) { inputHeightReg := io.apb.pwdata }
      is(TensorCoreGemmRegisters.inputWidth.U) { inputWidthReg := io.apb.pwdata }
      is(TensorCoreGemmRegisters.inputChannels.U) { inputChannelsReg := io.apb.pwdata }
      is(TensorCoreGemmRegisters.outputHeight.U) { outputHeightReg := io.apb.pwdata }
      is(TensorCoreGemmRegisters.outputWidth.U) { outputWidthReg := io.apb.pwdata }
      is(TensorCoreGemmRegisters.kernelHeight.U) { kernelHeightReg := io.apb.pwdata }
      is(TensorCoreGemmRegisters.kernelWidth.U) { kernelWidthReg := io.apb.pwdata }
      is(TensorCoreGemmRegisters.strideY.U) { strideYReg := io.apb.pwdata }
      is(TensorCoreGemmRegisters.strideX.U) { strideXReg := io.apb.pwdata }
      is(TensorCoreGemmRegisters.padTop.U) { padTopReg := io.apb.pwdata }
      is(TensorCoreGemmRegisters.padLeft.U) { padLeftReg := io.apb.pwdata }
      is(TensorCoreGemmRegisters.preluBase.U) { preluBaseReg := io.apb.pwdata }
      is(TensorCoreGemmRegisters.sourceExtentBytes.U) { sourceExtentBytesReg := io.apb.pwdata }
      is(TensorCoreGemmRegisters.sourceRowBytes.U) { sourceRowBytesReg := io.apb.pwdata }
      is(TensorCoreGemmRegisters.sourcePixelBytes.U) { sourcePixelBytesReg := io.apb.pwdata }
      is(TensorCoreGemmRegisters.sourceStepYBytes.U) { sourceStepYBytesReg := io.apb.pwdata }
      is(TensorCoreGemmRegisters.sourceStepXBytes.U) { sourceStepXBytesReg := io.apb.pwdata }
      is(TensorCoreGemmRegisters.sourcePadTopBytes.U) { sourcePadTopBytesReg := io.apb.pwdata }
      is(TensorCoreGemmRegisters.sourcePadLeftBytes.U) { sourcePadLeftBytesReg := io.apb.pwdata }
      is(TensorCoreGemmRegisters.resultRows.U) { resultRowsReg := io.apb.pwdata }
    }
  }

  when(controlWrite && !busyReg && io.apb.pwdata(0)) {
    doneReg := false.B
    errorReg := false.B
    errorCodeReg := 0.U
    irqPendingReg := false.B
    totalCyclesReg := 0.U
    bReadCyclesReg := 0.U
    aReadCyclesReg := 0.U
    computeCyclesReg := 0.U
    cWriteCyclesReg := 0.U
    windowCyclesReg := 0.U
    postCyclesReg := 0.U
    rowIndexReg := 0.U
    workYReg := 0.U
    workXReg := 0.U
    sampleIndexReg := 0.U
    groupBaseReg := 0.U
    groupLocalReg := 0.U
    bTileAddressReg := bBaseReg
    bTileBytesReg := 0.U
    bPlanIndexReg := 0.U
    bGroupReadBaseReg := 0.U
    cGroupBaseAddressReg := cBaseReg
    currentCAddressReg := cBaseReg
    alphaTileAddressReg := preluBaseReg
    matrixRowAddressReg := aBaseReg
    matrixPoolRowBaseAddressReg := aBaseReg
    sourceBaseYReg := 0.S(33.W) - padTopReg.zext
    sourceBaseXReg := 0.S(33.W) - padLeftReg.zext
    sourceBaseYByteOffsetReg := 0.S(33.W) - sourcePadTopBytesReg.zext
    sourceBaseXByteOffsetReg := 0.S(33.W) - sourcePadLeftBytesReg.zext
    when(configurationValid) {
      busyReg := true.B
      state := sBPlan
    }.otherwise {
      completeCommand(TensorCoreGemmError.invalidConfiguration)
    }
  }

  when(busyReg) {
    totalCyclesReg := totalCyclesReg + 1.U
    when(state === sBPlan || state === sBDesc || state === sBLoad ||
      state === sAlphaDesc || state === sAlphaLoad) {
      bReadCyclesReg := bReadCyclesReg + 1.U
    }
    when(state === sADesc || state === sALoad ||
      state === sWindowPrepare || state === sWindowWait ||
      state === sWindowLeadZero || state === sWindowDesc ||
      state === sWindowLoad || state === sWindowTrailZero || state === sWindowAppendOne) {
      aReadCyclesReg := aReadCyclesReg + 1.U
    }
    when(state === sCoreReset || state === sCorePrime ||
      state === sCoreIssue || state === sCoreDrain) {
      computeCyclesReg := computeCyclesReg + 1.U
    }
    when(state === sCDesc || state === sCData || state === sCWait) {
      cWriteCyclesReg := cWriteCyclesReg + 1.U
    }
    when(state === sWindowPrepare || state === sWindowWait ||
      state === sWindowLeadZero || state === sWindowDesc ||
      state === sWindowLoad || state === sWindowTrailZero || state === sWindowAppendOne) {
      windowCyclesReg := windowCyclesReg + 1.U
    }
    when(state === sAlphaDesc || state === sAlphaLoad ||
      state === sPostStart || state === sPostWait) {
      postCyclesReg := postCyclesReg + 1.U
    }
  }

  // Build the current B tile byte count with one adder over at most eight cycles.
  when(state === sBPlan) {
    val nextTileBytes = bTileBytesReg + (kReg << 4)

    bTileBytesReg := nextTileBytes
    when(bPlanIndexReg === currentGroupCount - 1.U) {
      state := sBDesc
    }.otherwise {
      bPlanIndexReg := bPlanIndexReg + 1.U
    }
  }
  when(state === sReserved) {
    beginCurrentA()
  }

  // Read DMA descriptor and stream routing.
  readDma.s_axis_read_desc_addr := MuxCase(currentAAddress, Seq(
    (state === sBDesc) -> currentBAddress,
    (state === sAlphaDesc) -> currentAlphaAddress,
    (state === sWindowDesc) -> windowLoader.io.dmaAddress
  ))
  readDma.s_axis_read_desc_len := MuxCase(currentABytes, Seq(
    (state === sBDesc) -> currentBBytes,
    (state === sAlphaDesc) -> currentAlphaBytes,
    (state === sWindowDesc) -> (windowLoader.io.validWords << 2)
  ))
  readDma.s_axis_read_desc_tag := MuxCase("ha0".U, Seq(
    (state === sBDesc) -> "hb0".U,
    (state === sAlphaDesc) -> "hd0".U,
    (state === sWindowDesc) -> "ha1".U
  ))
  readDma.s_axis_read_desc_id := 0.U
  readDma.s_axis_read_desc_dest := 0.U
  readDma.s_axis_read_desc_user := 0.U
  readDma.s_axis_read_desc_valid := state === sBDesc || state === sAlphaDesc ||
    state === sADesc || state === sWindowDesc
  private val readLoadActive = state === sBLoad || state === sAlphaLoad ||
    state === sALoad || state === sWindowLoad
  readDma.m_axis_read_data_tready := readLoadActive

  private val readDataFire =
    readDma.m_axis_read_data_tvalid && readDma.m_axis_read_data_tready
  private val readLastExpected = readWordIndexReg === readExpectedWordsReg - 1.U
  private val readProtocolErrorNow = readDataFire &&
    (readDma.m_axis_read_data_tkeep =/= "hf".U ||
      readDma.m_axis_read_data_tlast =/= readLastExpected)
  private val readDataCompletesNow = readDataFire && readDma.m_axis_read_data_tlast
  private val readStatusCompletesNow = readDma.m_axis_read_desc_status_valid
  private val readStatusProtocolErrorNow = readStatusCompletesNow &&
    readDma.m_axis_read_desc_status_tag =/= readExpectedTagReg

  when(state === sBDesc && readDma.s_axis_read_desc_ready) {
    readWordIndexReg := 0.U
    readExpectedWordsReg := bTileBytesReg >> 2
    readExpectedTagReg := "hb0".U
    readDataDoneReg := false.B
    readStatusDoneReg := false.B
    readProtocolErrorReg := false.B
    readDmaErrorReg := 0.U
    state := sBLoad
  }.elsewhen(state === sAlphaDesc && readDma.s_axis_read_desc_ready) {
    readWordIndexReg := 0.U
    readExpectedWordsReg := currentColumnCount
    readExpectedTagReg := "hd0".U
    readDataDoneReg := false.B
    readStatusDoneReg := false.B
    readProtocolErrorReg := false.B
    readDmaErrorReg := 0.U
    state := sAlphaLoad
  }.elsewhen(state === sADesc && readDma.s_axis_read_desc_ready) {
    readWordIndexReg := 0.U
    readExpectedWordsReg := kReg
    readExpectedTagReg := "ha0".U
    readDataDoneReg := false.B
    readStatusDoneReg := false.B
    readProtocolErrorReg := false.B
    readDmaErrorReg := 0.U
    state := sALoad
  }.elsewhen(state === sWindowDesc && readDma.s_axis_read_desc_ready) {
    readWordIndexReg := 0.U
    readExpectedWordsReg := windowLoader.io.validWords
    readExpectedTagReg := "ha1".U
    readDataDoneReg := false.B
    readStatusDoneReg := false.B
    readProtocolErrorReg := false.B
    readDmaErrorReg := 0.U
    state := sWindowLoad
  }

  when(readLoadActive && readDataFire) {
    when(state === sBLoad) {
      switch(readWordIndexReg(1, 0)) {
        is(0.U) { bMemories(0).write(readWordIndexReg >> 2, readDma.m_axis_read_data_tdata) }
        is(1.U) { bMemories(1).write(readWordIndexReg >> 2, readDma.m_axis_read_data_tdata) }
        is(2.U) { bMemories(2).write(readWordIndexReg >> 2, readDma.m_axis_read_data_tdata) }
        is(3.U) { bMemories(3).write(readWordIndexReg >> 2, readDma.m_axis_read_data_tdata) }
      }
    }.elsewhen(state === sAlphaLoad) {
      alphaRow(readWordIndexReg(4, 0)) := readDma.m_axis_read_data_tdata
    }.elsewhen(state === sWindowLoad) {
      aWriteEnable := true.B
      aWriteAddress := (windowLoader.io.localOffsetWords + readWordIndexReg)(7, 0)
      aWriteData := readDma.m_axis_read_data_tdata
    }.otherwise {
      aWriteEnable := true.B
      aWriteAddress := readWordIndexReg(7, 0)
      aWriteData := readDma.m_axis_read_data_tdata
    }
    readWordIndexReg := readWordIndexReg + 1.U
    when(readDma.m_axis_read_data_tlast) { readDataDoneReg := true.B }
    when(readProtocolErrorNow) { readProtocolErrorReg := true.B }
  }
  when(readLoadActive && readStatusCompletesNow) {
    readStatusDoneReg := true.B
    readDmaErrorReg := readDma.m_axis_read_desc_status_error
    when(readStatusProtocolErrorNow) { readProtocolErrorReg := true.B }
  }

  private val readTransferComplete =
    (readDataDoneReg || readDataCompletesNow) &&
      (readStatusDoneReg || readStatusCompletesNow)
  private val effectiveReadProtocolError =
    readProtocolErrorReg || readProtocolErrorNow || readStatusProtocolErrorNow
  private val effectiveReadDmaError =
    Mux(readStatusCompletesNow, readDma.m_axis_read_desc_status_error, readDmaErrorReg)

  when(readLoadActive && readTransferComplete) {
    when(effectiveReadProtocolError) {
      completeCommand(TensorCoreGemmError.readProtocol)
    }.elsewhen(effectiveReadDmaError =/= 0.U) {
      completeCommand(TensorCoreGemmError.readDma(effectiveReadDmaError))
    }.elsewhen(state === sBLoad) {
      rowIndexReg := 0.U
      workYReg := 0.U
      workXReg := 0.U
      sampleIndexReg := 0.U
      bGroupReadBaseReg := 0.U
      currentCAddressReg := cGroupBaseAddressReg
      matrixRowAddressReg := aBaseReg
      matrixPoolRowBaseAddressReg := aBaseReg
      sourceBaseYReg := 0.S(33.W) - padTopReg.zext
      sourceBaseXReg := 0.S(33.W) - padLeftReg.zext
      sourceBaseYByteOffsetReg := 0.S(33.W) - sourcePadTopBytesReg.zext
      sourceBaseXByteOffsetReg := 0.S(33.W) - sourcePadLeftBytesReg.zext
      when(postModeReg === TensorCorePostMode.none) {
        beginCurrentA()
      }.otherwise {
        state := sAlphaDesc
      }
    }.elsewhen(state === sAlphaLoad) {
      state := sReserved
    }.elsewhen(state === sALoad) {
      beginCore()
    }.otherwise {
      when(windowLoader.io.trailingZeroWords =/= 0.U) {
        windowFillIndexReg := 0.U
        state := sWindowTrailZero
      }.otherwise {
        finishWindowKernelRow()
      }
    }
  }

  // Build one virtual im2col row directly in local A BRAM.
  private val plannedWindowEndWords = windowRowBaseWordsReg + windowLoader.io.rowWords
  private val plannedWindowDmaEnd =
    windowLoader.io.dmaAddress.pad(33) + ((windowLoader.io.validWords << 2).pad(33))
  private val plannedInputEnd = aBaseReg.pad(33) + sourceExtentBytesReg.pad(33)
  private val plannedWindowSpanValid =
    !plannedInputEnd(32) && !plannedWindowDmaEnd(32) &&
      windowLoader.io.dmaAddress >= aBaseReg && plannedWindowDmaEnd <= plannedInputEnd

  when(state === sWindowPrepare) {
    state := sWindowWait
  }.elsewhen(state === sWindowWait && windowLoader.io.done) {
    windowFillIndexReg := 0.U
    when(plannedWindowEndWords + appendOneReg.asUInt > kReg) {
      completeCommand(TensorCoreGemmError.invalidConfiguration)
    }.elsewhen(windowLoader.io.validWords =/= 0.U && !plannedWindowSpanValid) {
      completeCommand(TensorCoreGemmError.invalidConfiguration)
    }.elsewhen(windowLoader.io.leadingZeroWords =/= 0.U) {
      state := sWindowLeadZero
    }.elsewhen(windowLoader.io.validWords =/= 0.U) {
      state := sWindowDesc
    }.elsewhen(windowLoader.io.trailingZeroWords =/= 0.U) {
      state := sWindowTrailZero
    }.otherwise {
      finishWindowKernelRow()
    }
  }.elsewhen(state === sWindowLeadZero) {
    aWriteEnable := true.B
    aWriteAddress := (windowRowBaseWordsReg + windowFillIndexReg)(7, 0)
    aWriteData := 0.U
    when(windowFillIndexReg === windowLoader.io.leadingZeroWords - 1.U) {
      windowFillIndexReg := 0.U
      when(windowLoader.io.validWords =/= 0.U) {
        state := sWindowDesc
      }.elsewhen(windowLoader.io.trailingZeroWords =/= 0.U) {
        state := sWindowTrailZero
      }.otherwise {
        finishWindowKernelRow()
      }
    }.otherwise {
      windowFillIndexReg := windowFillIndexReg + 1.U
    }
  }.elsewhen(state === sWindowTrailZero) {
    aWriteEnable := true.B
    aWriteAddress :=
      (windowLoader.io.localOffsetWords + windowLoader.io.validWords + windowFillIndexReg)(7, 0)
    aWriteData := 0.U
    when(windowFillIndexReg === windowLoader.io.trailingZeroWords - 1.U) {
      windowFillIndexReg := 0.U
      finishWindowKernelRow()
    }.otherwise {
      windowFillIndexReg := windowFillIndexReg + 1.U
    }
  }.elsewhen(state === sWindowAppendOne) {
    aWriteEnable := true.B
    aWriteAddress := (kReg - 1.U)(7, 0)
    aWriteData := "h3f800000".U
    beginCore()
  }

  when(aWriteEnable) {
    aMemory.write(aWriteAddress, aWriteData)
  }

  // BRAM read pipeline feeds one A and four B values every core issue cycle.
  private val coreReadEnable = state === sCorePrime ||
    (state === sCoreIssue && issueKReg =/= kReg - 1.U)
  private val coreReadK = Mux(state === sCorePrime, 0.U, issueKReg + 1.U)
  private val aReadData = aMemory.read(coreReadK, coreReadEnable)
  private val bReadAddress = bGroupReadBaseReg + coreReadK
  private val bReadData = bMemories.map(_.read(bReadAddress, coreReadEnable))
  tensorCore.io.a(0) := aReadData
  for (lane <- 0 until TensorColumns) {
    tensorCore.io.b(lane) := bReadData(lane)
  }
  tensorCore.io.valid := (state === sCoreIssue).asUInt
  tensorCore.io.rm := roundModeReg

  when(state === sCoreReset) {
    issueKReg := 0.U
    captureMaskReg := 0.U
    state := sCorePrime
  }.elsewhen(state === sCorePrime) {
    state := sCoreIssue
  }.elsewhen(state === sCoreIssue) {
    when(issueKReg === kReg - 1.U) {
      state := sCoreDrain
    }.otherwise {
      issueKReg := issueKReg + 1.U
    }
  }

  private val readyBits = VecInit((0 until TensorColumns).map { lane =>
    tensorCore.io.ready(0)(lane).asBool
  }).asUInt
  private val nextCaptureMask = captureMaskReg | readyBits
  private val poolHasRight = ((workXReg << 1) + 1.U) < outputWidthReg
  private val poolHasBottom = ((workYReg << 1) + 1.U) < outputHeightReg
  private val nextPoolSampleValid = WireDefault(false.B)
  private val nextPoolSample = WireDefault(0.U(2.W))
  switch(sampleIndexReg) {
    is(0.U) {
      when(poolHasRight) {
        nextPoolSampleValid := true.B
        nextPoolSample := 1.U
      }.elsewhen(poolHasBottom) {
        nextPoolSampleValid := true.B
        nextPoolSample := 2.U
      }
    }
    is(1.U) {
      when(poolHasBottom) {
        nextPoolSampleValid := true.B
        nextPoolSample := 2.U
      }
    }
    is(2.U) {
      when(poolHasRight) {
        nextPoolSampleValid := true.B
        nextPoolSample := 3.U
      }
    }
  }
  when(state === sCoreDrain) {
    for (lane <- 0 until TensorColumns) {
      when(tensorCore.io.ready(0)(lane).asBool && !captureMaskReg(lane)) {
        cRow(((groupLocalReg << 2) + lane.U)(4, 0)) := tensorCore.io.result(0)(lane)
      }
    }
    captureMaskReg := nextCaptureMask
    when(nextCaptureMask.andR) {
      when(groupLocalReg === currentGroupCount - 1.U) {
        when(postModeReg === TensorCorePostMode.none) {
          state := sCDesc
        }.otherwise {
          postIndexReg := 0.U
          state := sPostStart
        }
      }.otherwise {
        groupLocalReg := groupLocalReg + 1.U
        bGroupReadBaseReg := (bGroupReadBaseReg + kReg)(10, 0)
        state := sCoreReset
      }
    }
  }

  when(state === sPostStart) {
    state := sPostWait
  }.elsewhen(state === sPostWait && postProcessor.io.done) {
    cRow(postIndexReg) := postProcessor.io.result
    when(poolEnabled) {
      poolRow(postIndexReg) := postProcessor.io.result
    }
    when(postIndexReg === currentColumnCount - 1.U) {
      postIndexReg := 0.U
      when(poolEnabled && nextPoolSampleValid) {
        sampleIndexReg := nextPoolSample
        state := sReserved
      }.otherwise {
        state := sCDesc
      }
    }.otherwise {
      postIndexReg := postIndexReg + 1.U
      state := sPostStart
    }
  }

  // Write the completed C row segment through the independent AXI write DMA.
  writeDma.s_axis_write_desc_addr := currentCAddress
  writeDma.s_axis_write_desc_len := currentCBytes
  writeDma.s_axis_write_desc_tag := "hc0".U
  writeDma.s_axis_write_desc_valid := state === sCDesc
  writeDma.s_axis_write_data_tdata := cRow(cWriteIndexReg)
  writeDma.s_axis_write_data_tkeep := "hf".U
  writeDma.s_axis_write_data_tvalid := state === sCData
  writeDma.s_axis_write_data_tlast := cWriteIndexReg === currentColumnCount - 1.U
  writeDma.s_axis_write_data_tid := 0.U
  writeDma.s_axis_write_data_tdest := 0.U
  writeDma.s_axis_write_data_tuser := 0.U

  when(state === sCDesc && writeDma.s_axis_write_desc_ready) {
    cWriteIndexReg := 0.U
    writeStatusDoneReg := false.B
    writeProtocolErrorReg := false.B
    writeDmaErrorReg := 0.U
    state := sCData
  }
  when((state === sCData || state === sCWait) &&
    writeDma.m_axis_write_desc_status_valid) {
    writeStatusDoneReg := true.B
    writeDmaErrorReg := writeDma.m_axis_write_desc_status_error
    when(writeDma.m_axis_write_desc_status_tag =/= "hc0".U ||
      writeDma.m_axis_write_desc_status_len =/= currentCBytes) {
      writeProtocolErrorReg := true.B
    }
  }
  when(state === sCData && writeDma.s_axis_write_data_tvalid &&
    writeDma.s_axis_write_data_tready) {
    when(writeDma.s_axis_write_data_tlast) {
      state := sCWait
    }.otherwise {
      cWriteIndexReg := cWriteIndexReg + 1.U
    }
  }
  when(state === sCWait && writeStatusDoneReg) {
    when(writeProtocolErrorReg) {
      completeCommand(TensorCoreGemmError.writeProtocol)
    }.elsewhen(writeDmaErrorReg =/= 0.U) {
      completeCommand(TensorCoreGemmError.writeDma(writeDmaErrorReg))
    }.elsewhen(rowIndexReg =/= outputRows - 1.U) {
      rowIndexReg := rowIndexReg + 1.U
      sampleIndexReg := 0.U
      currentCAddressReg := currentCAddressReg + cStrideReg
      when(inputModeReg === TensorCoreInputMode.nhwcWindow || poolEnabled) {
        val activeWidth = Mux(poolEnabled, poolOutputWidth, outputWidthReg)
        val coordinateStepX = Mux(poolEnabled, strideXReg << 1, strideXReg)
        val coordinateStepY = Mux(poolEnabled, strideYReg << 1, strideYReg)
        val byteStepX = Mux(poolEnabled, sourceStepXBytesReg << 1, sourceStepXBytesReg)
        val byteStepY = Mux(poolEnabled, sourceStepYBytesReg << 1, sourceStepYBytesReg)

        when(workXReg === activeWidth - 1.U) {
          workXReg := 0.U
          workYReg := workYReg + 1.U
          sourceBaseXReg := 0.S(33.W) - padLeftReg.zext
          sourceBaseXByteOffsetReg := 0.S(33.W) - sourcePadLeftBytesReg.zext
          sourceBaseYReg := sourceBaseYReg + coordinateStepY.zext
          sourceBaseYByteOffsetReg := sourceBaseYByteOffsetReg + byteStepY.zext
          when(poolEnabled) {
            val nextMatrixPoolRow = matrixPoolRowBaseAddressReg + (sourceStepYBytesReg << 1)

            matrixPoolRowBaseAddressReg := nextMatrixPoolRow
            matrixRowAddressReg := nextMatrixPoolRow
          }
        }.otherwise {
          workXReg := workXReg + 1.U
          sourceBaseXReg := sourceBaseXReg + coordinateStepX.zext
          sourceBaseXByteOffsetReg := sourceBaseXByteOffsetReg + byteStepX.zext
          when(poolEnabled) {
            matrixRowAddressReg := matrixRowAddressReg + (sourceStepXBytesReg << 1)
          }
        }
      }.otherwise {
        matrixRowAddressReg := matrixRowAddressReg + aStrideReg
      }
      state := sReserved
    }.elsewhen(groupBaseReg + currentGroupCount =/= totalGroups) {
      val nextColumnByteOffset = currentColumnCount << 2
      val nextCGroupBase = cGroupBaseAddressReg + nextColumnByteOffset

      groupBaseReg := groupBaseReg + currentGroupCount
      rowIndexReg := 0.U
      workYReg := 0.U
      workXReg := 0.U
      sampleIndexReg := 0.U
      bTileAddressReg := bTileAddressReg + bTileBytesReg
      bTileBytesReg := 0.U
      bPlanIndexReg := 0.U
      bGroupReadBaseReg := 0.U
      cGroupBaseAddressReg := nextCGroupBase
      currentCAddressReg := nextCGroupBase
      alphaTileAddressReg := alphaTileAddressReg + nextColumnByteOffset
      matrixRowAddressReg := aBaseReg
      matrixPoolRowBaseAddressReg := aBaseReg
      sourceBaseYReg := 0.S(33.W) - padTopReg.zext
      sourceBaseXReg := 0.S(33.W) - padLeftReg.zext
      sourceBaseYByteOffsetReg := 0.S(33.W) - sourcePadTopBytesReg.zext
      sourceBaseXByteOffsetReg := 0.S(33.W) - sourcePadLeftBytesReg.zext
      state := sBPlan
    }.otherwise {
      completeCommand(TensorCoreGemmError.none)
    }
  }

  // Read and write DMA modules own disjoint AXI channels.
  io.axi.arid := readDma.m_axi_arid
  io.axi.araddr := readDma.m_axi_araddr
  io.axi.arlen := readDma.m_axi_arlen
  io.axi.arsize := readDma.m_axi_arsize
  io.axi.arburst := readDma.m_axi_arburst
  io.axi.arlock := readDma.m_axi_arlock
  io.axi.arcache := readDma.m_axi_arcache
  io.axi.arprot := readDma.m_axi_arprot
  io.axi.arqos := 0.U
  io.axi.arregion := 0.U
  io.axi.arvalid := readDma.m_axi_arvalid
  readDma.m_axi_arready := io.axi.arready
  readDma.m_axi_rid := io.axi.rid
  readDma.m_axi_rdata := io.axi.rdata
  readDma.m_axi_rresp := io.axi.rresp
  readDma.m_axi_rlast := io.axi.rlast
  readDma.m_axi_rvalid := io.axi.rvalid
  io.axi.rready := readDma.m_axi_rready

  io.axi.awid := writeDma.m_axi_awid
  io.axi.awaddr := writeDma.m_axi_awaddr
  io.axi.awlen := writeDma.m_axi_awlen
  io.axi.awsize := writeDma.m_axi_awsize
  io.axi.awburst := writeDma.m_axi_awburst
  io.axi.awlock := writeDma.m_axi_awlock
  io.axi.awcache := writeDma.m_axi_awcache
  io.axi.awprot := writeDma.m_axi_awprot
  io.axi.awqos := 0.U
  io.axi.awregion := 0.U
  io.axi.awvalid := writeDma.m_axi_awvalid
  writeDma.m_axi_awready := io.axi.awready
  io.axi.wdata := writeDma.m_axi_wdata
  io.axi.wstrb := writeDma.m_axi_wstrb
  io.axi.wlast := writeDma.m_axi_wlast
  io.axi.wvalid := writeDma.m_axi_wvalid
  writeDma.m_axi_wready := io.axi.wready
  writeDma.m_axi_bid := io.axi.bid
  writeDma.m_axi_bresp := io.axi.bresp
  writeDma.m_axi_bvalid := io.axi.bvalid
  io.axi.bready := writeDma.m_axi_bready

  io.interrupt := irqEnableReg && irqPendingReg
}
