package chisel

import chisel3._
import chisel3.util._

object LiteSdioSg {
  val MaxEntries = 256
  val MaxBytes = 1 << 20
  val EntryBytes = 8

  val CsrCap = 0x240
  val CsrTableAddress = 0x241
  val CsrEntryCount = 0x242
  val CsrTotalBytes = 0x243
  val CsrControl = 0x244
  val CsrStatus = 0x245
  val CsrCurrentIndex = 0x246
  val CsrErrorIndex = 0x247
  val CsrCompleted = 0x248
  val CsrFetchCycles = 0x249
  val CsrMaxGapCycles = 0x24a
  val CsrErrorDetail = 0x24b

  val ErrorNone = 0
  val ErrorDisabled = 1
  val ErrorBusy = 2
  val ErrorTableAlignment = 3
  val ErrorEntryCount = 4
  val ErrorTotalLength = 5
  val ErrorTableDma = 6
  val ErrorTableStream = 7
  val ErrorEntryAddress = 8
  val ErrorEntryLength = 9
  val ErrorAddressOverflow = 10
  val ErrorTotalOverflow = 11
  val ErrorTotalMismatch = 12
  val ErrorLogicalLength = 13
  val ErrorPayloadStream = 14
  val ErrorAborted = 15
  val ErrorPayloadDma = 16
}

class LiteSdioWishboneRouterIO extends Bundle {
  val upstream: Wishbone32MasterIO = Flipped(new Wishbone32MasterIO)
  val raw: Wishbone32MasterIO = new Wishbone32MasterIO
  val sg: Wishbone32MasterIO = new Wishbone32MasterIO
}

/** Route the new 0x900 byte-offset SG window without moving LiteSD's CSR ABI. */
class LiteSdioWishboneRouter extends Module {
  val io: LiteSdioWishboneRouterIO = IO(new LiteSdioWishboneRouterIO)

  private val selectSg = io.upstream.adr >= LiteSdioSg.CsrCap.U &&
    io.upstream.adr <= LiteSdioSg.CsrErrorDetail.U

  private def driveRequest(port: Wishbone32MasterIO, selected: Bool): Unit = {
    port.adr := io.upstream.adr
    port.datW := io.upstream.datW
    port.sel := io.upstream.sel
    port.cyc := io.upstream.cyc && selected
    port.stb := io.upstream.stb && selected
    port.we := io.upstream.we
    port.cti := io.upstream.cti
    port.bte := io.upstream.bte
  }

  driveRequest(io.raw, !selectSg)
  driveRequest(io.sg, selectSg)
  io.upstream.datR := Mux(selectSg, io.sg.datR, io.raw.datR)
  io.upstream.ack := Mux(selectSg, io.sg.ack, io.raw.ack)
  io.upstream.err := Mux(selectSg, io.sg.err, io.raw.err)
}

class LiteSdioSgCsrIO extends Bundle {
  val wishbone: Wishbone32MasterIO = Flipped(new Wishbone32MasterIO)

  val locked: Bool = Input(Bool())
  val fetchBusy: Bool = Input(Bool())
  val tableReady: Bool = Input(Bool())
  val tableOwner: Bool = Input(Bool())
  val prefetchError: Bool = Input(Bool())
  val prefetchErrorCode: UInt = Input(UInt(8.W))
  val prefetchErrorIndex: UInt = Input(UInt(9.W))
  val prefetchErrorDetail: UInt = Input(UInt(32.W))
  val fetchCycles: UInt = Input(UInt(32.W))

  val operationActive: Bool = Input(Bool())
  val operationDone: Bool = Input(Bool())
  val operationAborted: Bool = Input(Bool())
  val operationError: Bool = Input(Bool())
  val operationErrorCode: UInt = Input(UInt(8.W))
  val operationErrorIndex: UInt = Input(UInt(9.W))
  val operationErrorDetail: UInt = Input(UInt(32.W))
  val currentIndex: UInt = Input(UInt(9.W))
  val maxGapCycles: UInt = Input(UInt(32.W))

  val enable: Bool = Output(Bool())
  val tableAddress: UInt = Output(UInt(32.W))
  val entryCount: UInt = Output(UInt(9.W))
  val totalBytes: UInt = Output(UInt(32.W))
  val arm: Bool = Output(Bool())
  val abort: Bool = Output(Bool())
  val clear: Bool = Output(Bool())
}

/** SG ABI v1 control/status registers at byte offsets 0x900 through 0x92c. */
class LiteSdioSgCsr extends Module {
  val io: LiteSdioSgCsrIO = IO(new LiteSdioSgCsrIO)

  private val enableReg = RegInit(false.B)
  private val tableAddressReg = RegInit(0.U(32.W))
  private val entryCountReg = RegInit(0.U(9.W))
  private val totalBytesReg = RegInit(0.U(32.W))
  private val armReg = RegInit(false.B)
  private val abortReg = RegInit(false.B)
  private val clearReg = RegInit(false.B)
  private val doneReg = RegInit(false.B)
  private val abortedReg = RegInit(false.B)
  private val operationErrorReg = RegInit(false.B)
  private val operationErrorCodeReg = RegInit(0.U(8.W))
  private val operationErrorIndexReg = RegInit(0.U(9.W))
  private val operationErrorDetailReg = RegInit(0.U(32.W))
  private val completedReg = RegInit(0.U(32.W))

  armReg := false.B
  abortReg := false.B
  clearReg := false.B

  when(io.operationDone) {
    doneReg := true.B
    abortedReg := io.operationAborted
    completedReg := completedReg + 1.U
    when(io.operationError) {
      operationErrorReg := true.B
      operationErrorCodeReg := io.operationErrorCode
      operationErrorIndexReg := io.operationErrorIndex
      operationErrorDetailReg := io.operationErrorDetail
    }
  }

  private val request = io.wishbone.cyc && io.wishbone.stb
  private val write = request && io.wishbone.we
  private val writeMask = Cat(
    Fill(8, io.wishbone.sel(3)),
    Fill(8, io.wishbone.sel(2)),
    Fill(8, io.wishbone.sel(1)),
    Fill(8, io.wishbone.sel(0))
  )
  private def merge(oldValue: UInt): UInt =
    (oldValue & ~writeMask) | (io.wishbone.datW & writeMask)

  when(write && io.wishbone.adr === LiteSdioSg.CsrTableAddress.U && !io.locked) {
    tableAddressReg := merge(tableAddressReg)
  }
  when(write && io.wishbone.adr === LiteSdioSg.CsrEntryCount.U && !io.locked) {
    entryCountReg := merge(entryCountReg)(8, 0)
  }
  when(write && io.wishbone.adr === LiteSdioSg.CsrTotalBytes.U && !io.locked) {
    totalBytesReg := merge(totalBytesReg)
  }
  when(write && io.wishbone.adr === LiteSdioSg.CsrControl.U) {
    val control = merge(Cat(0.U(31.W), enableReg))
    enableReg := control(0)
    when(control(1) && !io.locked) {
      armReg := true.B
      doneReg := false.B
      abortedReg := false.B
      operationErrorReg := false.B
    }
    when(control(2)) {
      abortReg := true.B
    }
    when(control(3)) {
      clearReg := true.B
      doneReg := false.B
      abortedReg := false.B
      operationErrorReg := false.B
    }
  }

  private val errorVisible = io.prefetchError || operationErrorReg
  private val selectedErrorCode = Mux(io.prefetchError,
    io.prefetchErrorCode, operationErrorCodeReg)
  private val selectedErrorIndex = Mux(io.prefetchError,
    io.prefetchErrorIndex, operationErrorIndexReg)
  private val selectedErrorDetail = Mux(io.prefetchError,
    io.prefetchErrorDetail, operationErrorDetailReg)
  private val lowStatus = Cat(
    io.tableOwner,
    abortedReg,
    errorVisible,
    doneReg,
    io.operationActive,
    io.tableReady,
    io.fetchBusy,
    enableReg
  )
  private val status = Cat(0.U(16.W), selectedErrorCode, lowStatus)
  private val readData = WireDefault(0.U(32.W))

  switch(io.wishbone.adr) {
    is(LiteSdioSg.CsrCap.U) { readData := "h01080100".U }
    is(LiteSdioSg.CsrTableAddress.U) { readData := tableAddressReg }
    is(LiteSdioSg.CsrEntryCount.U) { readData := entryCountReg }
    is(LiteSdioSg.CsrTotalBytes.U) { readData := totalBytesReg }
    is(LiteSdioSg.CsrControl.U) { readData := enableReg }
    is(LiteSdioSg.CsrStatus.U) { readData := status }
    is(LiteSdioSg.CsrCurrentIndex.U) { readData := io.currentIndex }
    is(LiteSdioSg.CsrErrorIndex.U) { readData := selectedErrorIndex }
    is(LiteSdioSg.CsrCompleted.U) { readData := completedReg }
    is(LiteSdioSg.CsrFetchCycles.U) { readData := io.fetchCycles }
    is(LiteSdioSg.CsrMaxGapCycles.U) { readData := io.maxGapCycles }
    is(LiteSdioSg.CsrErrorDetail.U) { readData := selectedErrorDetail }
  }

  io.wishbone.datR := readData
  io.wishbone.ack := request
  io.wishbone.err := false.B
  io.enable := enableReg
  io.tableAddress := tableAddressReg
  io.entryCount := entryCountReg
  io.totalBytes := totalBytesReg
  io.arm := armReg
  io.abort := abortReg
  io.clear := clearReg
}

class LiteSdioSgTableMemoryIO extends Bundle {
  val writeEnable: Bool = Input(Bool())
  val writeIndex: UInt = Input(UInt(8.W))
  val writeData: UInt = Input(UInt(64.W))
  val readEnable: Bool = Input(Bool())
  val readIndex: UInt = Input(UInt(8.W))
  val readData: UInt = Output(UInt(64.W))
  val readValid: Bool = Output(Bool())
}

class LiteSdioSgTableMemory extends Module {
  val io: LiteSdioSgTableMemoryIO = IO(new LiteSdioSgTableMemoryIO)
  private val memory = SyncReadMem(LiteSdioSg.MaxEntries, UInt(64.W))
  when(io.writeEnable) {
    memory.write(io.writeIndex, io.writeData)
  }
  io.readData := memory.read(io.readIndex, io.readEnable)
  io.readValid := RegNext(io.readEnable, false.B)
}

class LiteSdioSgPrefetchIO extends Bundle {
  val enable: Bool = Input(Bool())
  val arm: Bool = Input(Bool())
  val abort: Bool = Input(Bool())
  val clear: Bool = Input(Bool())
  val consume: Bool = Input(Bool())
  val canStart: Bool = Input(Bool())
  val tableAddress: UInt = Input(UInt(32.W))
  val entryCount: UInt = Input(UInt(9.W))
  val totalBytes: UInt = Input(UInt(32.W))

  val dmaDescAddress: UInt = Output(UInt(32.W))
  val dmaDescLength: UInt = Output(UInt(21.W))
  val dmaDescValid: Bool = Output(Bool())
  val dmaDescReady: Bool = Input(Bool())
  val dmaStatusError: UInt = Input(UInt(4.W))
  val dmaStatusValid: Bool = Input(Bool())
  val dmaData: UInt = Input(UInt(32.W))
  val dmaKeep: UInt = Input(UInt(4.W))
  val dmaValid: Bool = Input(Bool())
  val dmaReady: Bool = Output(Bool())
  val dmaLast: Bool = Input(Bool())

  val tableWriteEnable: Bool = Output(Bool())
  val tableWriteIndex: UInt = Output(UInt(8.W))
  val tableWriteData: UInt = Output(UInt(64.W))

  val busy: Bool = Output(Bool())
  val ready: Bool = Output(Bool())
  val error: Bool = Output(Bool())
  val errorCode: UInt = Output(UInt(8.W))
  val errorIndex: UInt = Output(UInt(9.W))
  val errorDetail: UInt = Output(UInt(32.W))
  val fetchCycles: UInt = Output(UInt(32.W))
}

/** Fetch and validate the complete SG table before any payload transaction. */
class LiteSdioSgPrefetch extends Module {
  val io: LiteSdioSgPrefetchIO = IO(new LiteSdioSgPrefetchIO)

  private val idle :: issue :: receive :: finish :: Nil = Enum(4)
  private val state = RegInit(idle)
  private val readyReg = RegInit(false.B)
  private val errorReg = RegInit(false.B)
  private val errorCodeReg = RegInit(0.U(8.W))
  private val errorIndexReg = RegInit(0.U(9.W))
  private val errorDetailReg = RegInit(0.U(32.W))
  private val fetchCyclesReg = RegInit(0.U(32.W))
  private val wordIndexReg = RegInit(0.U(10.W))
  private val lowWordReg = RegInit(0.U(32.W))
  private val totalReg = RegInit(0.U(21.W))
  private val statusSeenReg = RegInit(false.B)
  private val lastSeenReg = RegInit(false.B)
  private val abortSeenReg = RegInit(false.B)

  private val expectedWords = Cat(io.entryCount, 0.U(1.W))
  private val dataFire = io.dmaValid && io.dmaReady
  private val expectedLast = wordIndexReg === expectedWords - 1.U
  private val entryLength = io.dmaData
  private val nextTotal = totalReg +& entryLength
  private val addressEnd = Cat(0.U(1.W), lowWordReg) +& Cat(0.U(1.W), entryLength)

  io.dmaDescAddress := io.tableAddress
  io.dmaDescLength := Cat(io.entryCount, 0.U(3.W))
  io.dmaDescValid := state === issue
  io.dmaReady := state === receive
  io.tableWriteEnable := dataFire && wordIndexReg(0)
  io.tableWriteIndex := wordIndexReg(8, 1)
  io.tableWriteData := Cat(io.dmaData, lowWordReg)
  io.busy := state =/= idle
  io.ready := readyReg
  io.error := errorReg
  io.errorCode := errorCodeReg
  io.errorIndex := errorIndexReg
  io.errorDetail := errorDetailReg
  io.fetchCycles := fetchCyclesReg

  when(state =/= idle) {
    fetchCyclesReg := fetchCyclesReg + 1.U
  }
  when((io.abort || !io.enable) && state =/= idle) {
    abortSeenReg := true.B
  }
  when((io.clear || io.consume || !io.enable) && state === idle) {
    readyReg := false.B
    when(io.clear) {
      errorReg := false.B
    }
  }

  switch(state) {
    is(idle) {
      when(io.arm) {
        readyReg := false.B
        errorReg := false.B
        errorCodeReg := LiteSdioSg.ErrorNone.U
        errorIndexReg := 0.U
        errorDetailReg := 0.U
        fetchCyclesReg := 0.U
        wordIndexReg := 0.U
        totalReg := 0.U
        statusSeenReg := false.B
        lastSeenReg := false.B
        abortSeenReg := false.B
        when(!io.enable) {
          errorReg := true.B
          errorCodeReg := LiteSdioSg.ErrorDisabled.U
        }.elsewhen(!io.canStart) {
          errorReg := true.B
          errorCodeReg := LiteSdioSg.ErrorBusy.U
        }.elsewhen(io.tableAddress(2, 0) =/= 0.U) {
          errorReg := true.B
          errorCodeReg := LiteSdioSg.ErrorTableAlignment.U
          errorDetailReg := io.tableAddress
        }.elsewhen(io.entryCount === 0.U || io.entryCount > LiteSdioSg.MaxEntries.U) {
          errorReg := true.B
          errorCodeReg := LiteSdioSg.ErrorEntryCount.U
          errorDetailReg := io.entryCount
        }.elsewhen(io.totalBytes === 0.U ||
          io.totalBytes > LiteSdioSg.MaxBytes.U || io.totalBytes(8, 0) =/= 0.U) {
          errorReg := true.B
          errorCodeReg := LiteSdioSg.ErrorTotalLength.U
          errorDetailReg := io.totalBytes
        }.otherwise {
          state := issue
        }
      }
    }

    is(issue) {
      when(io.dmaDescReady) {
        state := receive
      }
    }

    is(receive) {
      when(io.dmaStatusValid) {
        statusSeenReg := true.B
        when(io.dmaStatusError =/= 0.U && !errorReg) {
          errorReg := true.B
          errorCodeReg := LiteSdioSg.ErrorTableDma.U
          errorIndexReg := wordIndexReg(8, 1)
          errorDetailReg := io.dmaStatusError
        }
      }

      when(dataFire) {
        when(!wordIndexReg(0)) {
          lowWordReg := io.dmaData
        }.otherwise {
          totalReg := nextTotal(20, 0)
        }

        when(!errorReg) {
          when(io.dmaKeep =/= "hf".U || io.dmaLast =/= expectedLast) {
            errorReg := true.B
            errorCodeReg := LiteSdioSg.ErrorTableStream.U
            errorIndexReg := wordIndexReg(8, 1)
            errorDetailReg := Cat(0.U(26.W), expectedLast, io.dmaLast, io.dmaKeep)
          }.elsewhen(wordIndexReg(0) && lowWordReg(1, 0) =/= 0.U) {
            errorReg := true.B
            errorCodeReg := LiteSdioSg.ErrorEntryAddress.U
            errorIndexReg := wordIndexReg(8, 1)
            errorDetailReg := lowWordReg
          }.elsewhen(wordIndexReg(0) &&
            (entryLength === 0.U || entryLength(1, 0) =/= 0.U ||
              entryLength > LiteSdioSg.MaxBytes.U)) {
            errorReg := true.B
            errorCodeReg := LiteSdioSg.ErrorEntryLength.U
            errorIndexReg := wordIndexReg(8, 1)
            errorDetailReg := entryLength
          }.elsewhen(wordIndexReg(0) && addressEnd > "hffffffff".U(34.W)) {
            errorReg := true.B
            errorCodeReg := LiteSdioSg.ErrorAddressOverflow.U
            errorIndexReg := wordIndexReg(8, 1)
            errorDetailReg := lowWordReg
          }.elsewhen(wordIndexReg(0) && nextTotal > LiteSdioSg.MaxBytes.U) {
            errorReg := true.B
            errorCodeReg := LiteSdioSg.ErrorTotalOverflow.U
            errorIndexReg := wordIndexReg(8, 1)
            errorDetailReg := nextTotal
          }.elsewhen(wordIndexReg(0) && expectedLast && nextTotal =/= io.totalBytes) {
            errorReg := true.B
            errorCodeReg := LiteSdioSg.ErrorTotalMismatch.U
            errorIndexReg := wordIndexReg(8, 1)
            errorDetailReg := nextTotal
          }
        }

        wordIndexReg := wordIndexReg + 1.U
        when(io.dmaLast) {
          lastSeenReg := true.B
        }
      }

      when((statusSeenReg || io.dmaStatusValid) &&
        (lastSeenReg || (dataFire && io.dmaLast))) {
        state := finish
      }
    }

    is(finish) {
      when(abortSeenReg && !errorReg) {
        errorReg := true.B
        errorCodeReg := LiteSdioSg.ErrorAborted.U
      }.elsewhen(!errorReg) {
        readyReg := true.B
      }
      state := idle
    }
  }
}

class LiteSdioSgDatapathIO extends Bundle {
  val enable: Bool = Input(Bool())
  val tableReady: Bool = Input(Bool())
  val entryCount: UInt = Input(UInt(9.W))
  val totalBytes: UInt = Input(UInt(32.W))
  val abort: Bool = Input(Bool())
  val clearStats: Bool = Input(Bool())
  val block2memEnabled: Bool = Input(Bool())
  val mem2blockEnabled: Bool = Input(Bool())
  val mem2blockWriteActive: Bool = Input(Bool())

  val tableReadEnable: Bool = Output(Bool())
  val tableReadIndex: UInt = Output(UInt(8.W))
  val tableReadData: UInt = Input(UInt(64.W))
  val tableReadValid: Bool = Input(Bool())

  val rawWriteDescAddress: UInt = Input(UInt(32.W))
  val rawWriteDescLength: UInt = Input(UInt(21.W))
  val rawWriteDescValid: Bool = Input(Bool())
  val rawWriteDescReady: Bool = Output(Bool())
  val rawWriteStatusLength: UInt = Output(UInt(21.W))
  val rawWriteStatusError: UInt = Output(UInt(4.W))
  val rawWriteStatusValid: Bool = Output(Bool())
  val rawWriteData: UInt = Input(UInt(32.W))
  val rawWriteKeep: UInt = Input(UInt(4.W))
  val rawWriteValid: Bool = Input(Bool())
  val rawWriteReady: Bool = Output(Bool())
  val rawWriteLast: Bool = Input(Bool())

  val dmaWriteDescAddress: UInt = Output(UInt(32.W))
  val dmaWriteDescLength: UInt = Output(UInt(21.W))
  val dmaWriteDescValid: Bool = Output(Bool())
  val dmaWriteDescReady: Bool = Input(Bool())
  val dmaWriteStatusLength: UInt = Input(UInt(21.W))
  val dmaWriteStatusError: UInt = Input(UInt(4.W))
  val dmaWriteStatusValid: Bool = Input(Bool())
  val dmaWriteData: UInt = Output(UInt(32.W))
  val dmaWriteKeep: UInt = Output(UInt(4.W))
  val dmaWriteValid: Bool = Output(Bool())
  val dmaWriteReady: Bool = Input(Bool())
  val dmaWriteLast: Bool = Output(Bool())

  val rawReadDescAddress: UInt = Input(UInt(32.W))
  val rawReadDescLength: UInt = Input(UInt(21.W))
  val rawReadDescValid: Bool = Input(Bool())
  val rawReadDescReady: Bool = Output(Bool())
  val rawReadStatusError: UInt = Output(UInt(4.W))
  val rawReadStatusValid: Bool = Output(Bool())
  val rawReadData: UInt = Output(UInt(32.W))
  val rawReadKeep: UInt = Output(UInt(4.W))
  val rawReadValid: Bool = Output(Bool())
  val rawReadReady: Bool = Input(Bool())
  val rawReadLast: Bool = Output(Bool())

  val dmaReadDescAddress: UInt = Output(UInt(32.W))
  val dmaReadDescLength: UInt = Output(UInt(21.W))
  val dmaReadDescValid: Bool = Output(Bool())
  val dmaReadDescReady: Bool = Input(Bool())
  val dmaReadStatusError: UInt = Input(UInt(4.W))
  val dmaReadStatusValid: Bool = Input(Bool())
  val dmaReadData: UInt = Input(UInt(32.W))
  val dmaReadKeep: UInt = Input(UInt(4.W))
  val dmaReadValid: Bool = Input(Bool())
  val dmaReadReady: Bool = Output(Bool())
  val dmaReadLast: Bool = Input(Bool())
  val dmaReadEnable: Bool = Output(Bool())

  val active: Bool = Output(Bool())
  val currentIndex: UInt = Output(UInt(9.W))
  val completed: Bool = Output(Bool())
  val aborted: Bool = Output(Bool())
  val operationError: Bool = Output(Bool())
  val operationErrorCode: UInt = Output(UInt(8.W))
  val operationErrorIndex: UInt = Output(UInt(9.W))
  val operationErrorDetail: UInt = Output(UInt(32.W))
  val consumeTable: Bool = Output(Bool())
  val maxGapCycles: UInt = Output(UInt(32.W))
}

/** Split one logical LiteSD descriptor into the validated physical SG entries. */
class LiteSdioSgDatapath extends Module {
  val io: LiteSdioSgDatapathIO = IO(new LiteSdioSgDatapathIO)

  private val Seq(idle, waitPayload, tableRequest, tableWait, issueDescriptor,
    streamPayload, waitStatus, report) = Enum(8)
  private val state = RegInit(idle)
  private val memToBlockReg = RegInit(false.B)
  private val logicalLengthReg = RegInit(0.U(21.W))
  private val logicalMismatchReg = RegInit(false.B)
  private val entryIndexReg = RegInit(0.U(9.W))
  private val entryAddressReg = RegInit(0.U(32.W))
  private val entryLengthReg = RegInit(0.U(21.W))
  private val segmentBytesReg = RegInit(0.U(21.W))
  private val totalBytesReg = RegInit(0.U(21.W))
  private val statusSeenReg = RegInit(false.B)
  private val statusErrorReg = RegInit(0.U(4.W))
  private val statusLengthReg = RegInit(0.U(21.W))
  private val firstDmaErrorReg = RegInit(0.U(4.W))
  private val streamErrorReg = RegInit(false.B)
  private val abortSeenReg = RegInit(false.B)
  private val gapRunningReg = RegInit(false.B)
  private val gapCounterReg = RegInit(0.U(32.W))
  private val maxGapCyclesReg = RegInit(0.U(32.W))

  private val isFinalEntry = entryIndexReg === io.entryCount - 1.U
  private val aborting = abortSeenReg || io.abort || !io.enable ||
    Mux(memToBlockReg, !io.mem2blockEnabled, !io.block2memEnabled)
  private val writeBeatBytes = PopCount(io.rawWriteKeep)
  private val writeNextSegment = segmentBytesReg +& writeBeatBytes
  private val writeNextTotal = totalBytesReg +& writeBeatBytes
  private val readBeatBytes = PopCount(io.dmaReadKeep)
  private val readNextSegment = segmentBytesReg +& readBeatBytes
  private val readNextTotal = totalBytesReg +& readBeatBytes

  io.tableReadEnable := state === tableRequest
  io.tableReadIndex := entryIndexReg(7, 0)

  io.rawWriteDescReady := state === idle && io.enable && io.tableReady
  io.rawReadDescReady := state === idle && io.enable && io.tableReady &&
    !io.rawWriteDescValid
  io.rawWriteStatusLength := totalBytesReg
  io.rawWriteStatusError := Mux(firstDmaErrorReg =/= 0.U, firstDmaErrorReg,
    Mux(streamErrorReg || logicalMismatchReg || aborting, 2.U, 0.U))
  io.rawWriteStatusValid := state === report && !memToBlockReg
  io.rawWriteReady := state === streamPayload && !memToBlockReg && io.dmaWriteReady

  io.dmaWriteDescAddress := entryAddressReg
  io.dmaWriteDescLength := entryLengthReg
  io.dmaWriteDescValid := state === issueDescriptor && !memToBlockReg
  io.dmaWriteData := io.rawWriteData
  io.dmaWriteKeep := io.rawWriteKeep
  io.dmaWriteValid := state === streamPayload && !memToBlockReg && io.rawWriteValid
  io.dmaWriteLast := writeNextSegment >= entryLengthReg || (aborting && io.rawWriteLast)

  io.rawReadStatusError := Mux(firstDmaErrorReg =/= 0.U, firstDmaErrorReg,
    Mux(streamErrorReg || logicalMismatchReg || aborting, 2.U, 0.U))
  io.rawReadStatusValid := state === report && memToBlockReg
  io.rawReadData := io.dmaReadData
  io.rawReadKeep := io.dmaReadKeep
  io.rawReadValid := state === streamPayload && memToBlockReg && io.dmaReadValid
  io.rawReadLast := io.dmaReadLast && isFinalEntry

  io.dmaReadDescAddress := entryAddressReg
  io.dmaReadDescLength := entryLengthReg
  io.dmaReadDescValid := state === issueDescriptor && memToBlockReg
  io.dmaReadReady := state === streamPayload && memToBlockReg &&
    Mux(aborting, true.B, io.rawReadReady)
  // Even an already-aborted logical request must get one physical descriptor:
  // the LiteSD reader frontend needs a drained tlast and status to terminate.
  io.dmaReadEnable := !memToBlockReg || state === issueDescriptor || !aborting

  io.active := state =/= idle
  io.currentIndex := entryIndexReg
  io.completed := state === report
  io.aborted := state === report && aborting
  io.operationError := state === report &&
    (aborting || logicalMismatchReg || streamErrorReg || firstDmaErrorReg =/= 0.U)
  io.operationErrorCode := Mux(aborting, LiteSdioSg.ErrorAborted.U,
    Mux(firstDmaErrorReg =/= 0.U, LiteSdioSg.ErrorPayloadDma.U,
      Mux(logicalMismatchReg, LiteSdioSg.ErrorLogicalLength.U,
        LiteSdioSg.ErrorPayloadStream.U)))
  io.operationErrorIndex := entryIndexReg
  io.operationErrorDetail := Mux(firstDmaErrorReg =/= 0.U, firstDmaErrorReg,
    Mux(logicalMismatchReg, Cat(0.U(11.W), logicalLengthReg),
      Cat(0.U(11.W), totalBytesReg)))
  io.consumeTable := state === report
  io.maxGapCycles := maxGapCyclesReg

  when(io.clearStats) {
    gapRunningReg := false.B
    gapCounterReg := 0.U
    maxGapCyclesReg := 0.U
  }.elsewhen(gapRunningReg) {
    gapCounterReg := gapCounterReg + 1.U
  }

  when(state =/= idle && io.abort) {
    abortSeenReg := true.B
  }
  when(state =/= idle &&
    Mux(memToBlockReg, !io.mem2blockEnabled, !io.block2memEnabled)) {
    abortSeenReg := true.B
  }

  when(state =/= idle && !memToBlockReg && io.dmaWriteStatusValid) {
    statusSeenReg := true.B
    statusErrorReg := io.dmaWriteStatusError
    statusLengthReg := io.dmaWriteStatusLength
  }
  when(state =/= idle && memToBlockReg && io.dmaReadStatusValid) {
    statusSeenReg := true.B
    statusErrorReg := io.dmaReadStatusError
  }

  switch(state) {
    is(idle) {
      when(io.rawWriteDescValid && io.rawWriteDescReady) {
        memToBlockReg := false.B
        logicalLengthReg := io.rawWriteDescLength
        logicalMismatchReg := io.rawWriteDescLength =/= io.totalBytes(20, 0)
        entryIndexReg := 0.U
        totalBytesReg := 0.U
        firstDmaErrorReg := 0.U
        streamErrorReg := false.B
        abortSeenReg := false.B
        state := tableRequest
      }.elsewhen(io.rawReadDescValid && io.rawReadDescReady) {
        memToBlockReg := true.B
        logicalLengthReg := io.rawReadDescLength
        logicalMismatchReg := io.rawReadDescLength =/= io.totalBytes(20, 0)
        entryIndexReg := 0.U
        totalBytesReg := 0.U
        firstDmaErrorReg := 0.U
        streamErrorReg := false.B
        abortSeenReg := false.B
        state := waitPayload
      }
    }

    is(waitPayload) {
      when(io.mem2blockWriteActive || aborting) {
        state := tableRequest
      }
    }

    is(tableRequest) {
      state := tableWait
    }

    is(tableWait) {
      when(io.tableReadValid) {
        entryAddressReg := io.tableReadData(31, 0)
        entryLengthReg := io.tableReadData(52, 32)
        segmentBytesReg := 0.U
        statusSeenReg := false.B
        statusErrorReg := 0.U
        statusLengthReg := 0.U
        state := issueDescriptor
      }
    }

    is(issueDescriptor) {
      val descriptorFire = Mux(memToBlockReg,
        io.dmaReadDescValid && io.dmaReadDescReady,
        io.dmaWriteDescValid && io.dmaWriteDescReady)
      when(descriptorFire) {
        when(gapRunningReg) {
          val completedGap = gapCounterReg + 1.U
          when(completedGap > maxGapCyclesReg) {
            maxGapCyclesReg := completedGap
          }
          gapRunningReg := false.B
          gapCounterReg := 0.U
        }
        state := streamPayload
      }
    }

    is(streamPayload) {
      when(!memToBlockReg && io.rawWriteValid && io.rawWriteReady) {
        segmentBytesReg := writeNextSegment(20, 0)
        totalBytesReg := writeNextTotal(20, 0)
        val expectedLogicalLast = isFinalEntry && writeNextTotal === logicalLengthReg
        when(io.rawWriteKeep =/= "hf".U || writeNextSegment > entryLengthReg ||
          io.rawWriteLast =/= expectedLogicalLast) {
          streamErrorReg := true.B
        }
        when(writeNextSegment >= entryLengthReg || (aborting && io.rawWriteLast)) {
          state := waitStatus
        }
      }

      when(memToBlockReg && io.dmaReadValid && io.dmaReadReady) {
        segmentBytesReg := readNextSegment(20, 0)
        totalBytesReg := readNextTotal(20, 0)
        when(io.dmaReadKeep =/= "hf".U || readNextSegment > entryLengthReg ||
          io.dmaReadLast =/= (readNextSegment === entryLengthReg)) {
          streamErrorReg := true.B
        }
        when(io.dmaReadLast) {
          state := waitStatus
        }
      }
    }

    is(waitStatus) {
      val statusAvailable = statusSeenReg ||
        Mux(memToBlockReg, io.dmaReadStatusValid, io.dmaWriteStatusValid)
      val currentStatusError = Mux(
        Mux(memToBlockReg, io.dmaReadStatusValid, io.dmaWriteStatusValid),
        Mux(memToBlockReg, io.dmaReadStatusError, io.dmaWriteStatusError),
        statusErrorReg
      )
      val currentStatusLength = Mux(io.dmaWriteStatusValid,
        io.dmaWriteStatusLength, statusLengthReg)

      when(statusAvailable) {
        when(firstDmaErrorReg === 0.U && currentStatusError =/= 0.U) {
          firstDmaErrorReg := currentStatusError
        }
        when(!memToBlockReg && currentStatusLength =/= segmentBytesReg) {
          streamErrorReg := true.B
        }
        when(!isFinalEntry && !aborting) {
          entryIndexReg := entryIndexReg + 1.U
          gapRunningReg := true.B
          gapCounterReg := 0.U
          state := tableRequest
        }.otherwise {
          state := report
        }
      }
    }

    is(report) {
      state := idle
    }
  }
}
