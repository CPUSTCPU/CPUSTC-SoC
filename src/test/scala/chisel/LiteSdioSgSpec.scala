package chisel

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import chisel.axiInterconnect.sdio._
import org.scalatest.freespec.AnyFreeSpec

private object LiteSdioSgSpec {
  val annotations = Seq(VerilatorBackendAnnotation)

  def initializeCsr(dut: LiteSdioSgCsr): Unit = {
    dut.io.wishbone.adr.poke(0.U)
    dut.io.wishbone.datW.poke(0.U)
    dut.io.wishbone.sel.poke(0.U)
    dut.io.wishbone.cyc.poke(false.B)
    dut.io.wishbone.stb.poke(false.B)
    dut.io.wishbone.we.poke(false.B)
    dut.io.wishbone.cti.poke(0.U)
    dut.io.wishbone.bte.poke(0.U)
    dut.io.locked.poke(false.B)
    dut.io.fetchBusy.poke(false.B)
    dut.io.tableReady.poke(false.B)
    dut.io.tableOwner.poke(false.B)
    dut.io.prefetchError.poke(false.B)
    dut.io.prefetchErrorCode.poke(0.U)
    dut.io.prefetchErrorIndex.poke(0.U)
    dut.io.prefetchErrorDetail.poke(0.U)
    dut.io.fetchCycles.poke(0.U)
    dut.io.operationActive.poke(false.B)
    dut.io.operationDone.poke(false.B)
    dut.io.operationAborted.poke(false.B)
    dut.io.operationError.poke(false.B)
    dut.io.operationErrorCode.poke(0.U)
    dut.io.operationErrorIndex.poke(0.U)
    dut.io.operationErrorDetail.poke(0.U)
    dut.io.currentIndex.poke(0.U)
    dut.io.maxGapCycles.poke(0.U)
  }

  def initializePrefetch(dut: LiteSdioSgPrefetch): Unit = {
    dut.io.enable.poke(false.B)
    dut.io.arm.poke(false.B)
    dut.io.abort.poke(false.B)
    dut.io.clear.poke(false.B)
    dut.io.consume.poke(false.B)
    dut.io.canStart.poke(true.B)
    dut.io.tableAddress.poke(0.U)
    dut.io.entryCount.poke(0.U)
    dut.io.totalBytes.poke(0.U)
    dut.io.dmaDescReady.poke(false.B)
    dut.io.dmaStatusError.poke(0.U)
    dut.io.dmaStatusValid.poke(false.B)
    dut.io.dmaData.poke(0.U)
    dut.io.dmaKeep.poke(0.U)
    dut.io.dmaValid.poke(false.B)
    dut.io.dmaLast.poke(false.B)
  }

  def initializeDatapath(dut: LiteSdioSgDatapath): Unit = {
    dut.io.enable.poke(true.B)
    dut.io.tableReady.poke(true.B)
    dut.io.entryCount.poke(2.U)
    dut.io.totalBytes.poke(16.U)
    dut.io.abort.poke(false.B)
    dut.io.clearStats.poke(false.B)
    dut.io.block2memEnabled.poke(true.B)
    dut.io.mem2blockEnabled.poke(true.B)
    dut.io.mem2blockWriteActive.poke(false.B)
    dut.io.tableReadData.poke(0.U)
    dut.io.tableReadValid.poke(false.B)

    dut.io.rawWriteDescAddress.poke(0.U)
    dut.io.rawWriteDescLength.poke(0.U)
    dut.io.rawWriteDescValid.poke(false.B)
    dut.io.rawWriteData.poke(0.U)
    dut.io.rawWriteKeep.poke(0.U)
    dut.io.rawWriteValid.poke(false.B)
    dut.io.rawWriteLast.poke(false.B)
    dut.io.dmaWriteDescReady.poke(true.B)
    dut.io.dmaWriteStatusLength.poke(0.U)
    dut.io.dmaWriteStatusError.poke(0.U)
    dut.io.dmaWriteStatusValid.poke(false.B)
    dut.io.dmaWriteReady.poke(true.B)

    dut.io.rawReadDescAddress.poke(0.U)
    dut.io.rawReadDescLength.poke(0.U)
    dut.io.rawReadDescValid.poke(false.B)
    dut.io.rawReadReady.poke(true.B)
    dut.io.dmaReadDescReady.poke(true.B)
    dut.io.dmaReadStatusError.poke(0.U)
    dut.io.dmaReadStatusValid.poke(false.B)
    dut.io.dmaReadData.poke(0.U)
    dut.io.dmaReadKeep.poke(0.U)
    dut.io.dmaReadValid.poke(false.B)
    dut.io.dmaReadLast.poke(false.B)
  }

  def resetCsr(dut: LiteSdioSgCsr): Unit = {
    initializeCsr(dut)
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.clock.step()
  }

  def resetPrefetch(dut: LiteSdioSgPrefetch): Unit = {
    initializePrefetch(dut)
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.clock.step()
  }

  def resetDatapath(dut: LiteSdioSgDatapath): Unit = {
    initializeDatapath(dut)
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.clock.step()
  }

  def csrWrite(dut: LiteSdioSgCsr, address: Int, value: BigInt): Unit = {
    dut.io.wishbone.adr.poke(address.U)
    dut.io.wishbone.datW.poke(value.U)
    dut.io.wishbone.sel.poke("hf".U)
    dut.io.wishbone.we.poke(true.B)
    dut.io.wishbone.cyc.poke(true.B)
    dut.io.wishbone.stb.poke(true.B)
    dut.io.wishbone.ack.expect(true.B)
    dut.clock.step()
    dut.io.wishbone.cyc.poke(false.B)
    dut.io.wishbone.stb.poke(false.B)
    dut.io.wishbone.we.poke(false.B)
  }

  def waitFor(condition: => Boolean, dut: LiteSdioSgDatapath, limit: Int = 40): Unit = {
    var cycles = 0
    while (!condition && cycles < limit) {
      dut.clock.step()
      cycles += 1
    }
    assert(condition, s"condition was not reached within $limit cycles")
  }

  def provideTableEntry(
    dut: LiteSdioSgDatapath,
    expectedIndex: Int,
    address: BigInt,
    length: Int
  ): Unit = {
    waitFor(dut.io.tableReadEnable.peek().litToBoolean, dut)
    dut.io.tableReadIndex.expect(expectedIndex.U)
    dut.clock.step()
    dut.io.tableReadData.poke((address | (BigInt(length) << 32)).U)
    dut.io.tableReadValid.poke(true.B)
    dut.clock.step()
    dut.io.tableReadValid.poke(false.B)
  }
}

class LiteSdioSgSpec extends AnyFreeSpec with ChiselScalatestTester {
  import LiteSdioSgSpec._

  "Wishbone router should preserve old CSRs and select only the SG window" in {
    test(new LiteSdioWishboneRouter).withAnnotations(annotations) { dut =>
      dut.io.upstream.adr.poke("h220".U)
      dut.io.upstream.datW.poke("h12345678".U)
      dut.io.upstream.sel.poke("hf".U)
      dut.io.upstream.cyc.poke(true.B)
      dut.io.upstream.stb.poke(true.B)
      dut.io.upstream.we.poke(false.B)
      dut.io.upstream.cti.poke(0.U)
      dut.io.upstream.bte.poke(0.U)
      dut.io.raw.datR.poke("ha5a55a5a".U)
      dut.io.raw.ack.poke(true.B)
      dut.io.raw.err.poke(false.B)
      dut.io.sg.datR.poke("hfeedface".U)
      dut.io.sg.ack.poke(false.B)
      dut.io.sg.err.poke(false.B)

      dut.io.raw.cyc.expect(true.B)
      dut.io.sg.cyc.expect(false.B)
      dut.io.upstream.ack.expect(true.B)
      dut.io.upstream.datR.expect("ha5a55a5a".U)

      dut.io.upstream.adr.poke(LiteSdioSg.CsrCap.U)
      dut.io.raw.ack.poke(false.B)
      dut.io.sg.ack.poke(true.B)
      dut.io.raw.cyc.expect(false.B)
      dut.io.sg.cyc.expect(true.B)
      dut.io.upstream.ack.expect(true.B)
      dut.io.upstream.datR.expect("hfeedface".U)
    }
  }

  "SG CSR should expose ABI v1 and pulse ARM after programming a 256-entry table" in {
    test(new LiteSdioSgCsr).withAnnotations(annotations) { dut =>
      resetCsr(dut)

      dut.io.wishbone.adr.poke(LiteSdioSg.CsrCap.U)
      dut.io.wishbone.cyc.poke(true.B)
      dut.io.wishbone.stb.poke(true.B)
      dut.io.wishbone.datR.expect("h01080100".U)
      dut.clock.step()
      dut.io.wishbone.cyc.poke(false.B)
      dut.io.wishbone.stb.poke(false.B)

      csrWrite(dut, LiteSdioSg.CsrTableAddress, BigInt("81234000", 16))
      csrWrite(dut, LiteSdioSg.CsrEntryCount, 256)
      csrWrite(dut, LiteSdioSg.CsrTotalBytes, 1 << 20)
      csrWrite(dut, LiteSdioSg.CsrControl, 3)

      dut.io.enable.expect(true.B)
      dut.io.tableAddress.expect("h81234000".U)
      dut.io.entryCount.expect(256.U)
      dut.io.totalBytes.expect((1 << 20).U)
      dut.io.arm.expect(true.B)
      dut.clock.step()
      dut.io.arm.expect(false.B)

      dut.io.locked.poke(true.B)
      csrWrite(dut, LiteSdioSg.CsrTableAddress, BigInt("90000000", 16))
      dut.io.tableAddress.expect("h81234000".U)
    }
  }

  "SG table prefetch should validate and assemble two 64-bit entries" in {
    test(new LiteSdioSgPrefetch).withAnnotations(annotations) { dut =>
      resetPrefetch(dut)
      dut.io.enable.poke(true.B)
      dut.io.tableAddress.poke("h80001000".U)
      dut.io.entryCount.poke(2.U)
      dut.io.totalBytes.poke(1024.U)
      dut.io.arm.poke(true.B)
      dut.clock.step()
      dut.io.arm.poke(false.B)

      dut.io.dmaDescAddress.expect("h80001000".U)
      dut.io.dmaDescLength.expect(16.U)
      dut.io.dmaDescValid.expect(true.B)
      dut.io.dmaDescReady.poke(true.B)
      dut.clock.step()
      dut.io.dmaDescReady.poke(false.B)

      val words = Seq(
        BigInt("81000000", 16), BigInt(512),
        BigInt("82000000", 16), BigInt(512)
      )
      for ((word, index) <- words.zipWithIndex) {
        dut.io.dmaData.poke(word.U)
        dut.io.dmaKeep.poke("hf".U)
        dut.io.dmaLast.poke((index == words.size - 1).B)
        dut.io.dmaValid.poke(true.B)
        dut.io.dmaReady.expect(true.B)
        if ((index & 1) != 0) {
          dut.io.tableWriteEnable.expect(true.B)
          dut.io.tableWriteIndex.expect((index / 2).U)
        }
        dut.clock.step()
      }
      dut.io.dmaValid.poke(false.B)
      dut.io.dmaLast.poke(false.B)
      dut.io.dmaStatusValid.poke(true.B)
      dut.clock.step()
      dut.io.dmaStatusValid.poke(false.B)
      dut.clock.step()

      dut.io.busy.expect(false.B)
      dut.io.ready.expect(true.B)
      dut.io.error.expect(false.B)
    }
  }

  "SG table prefetch should reject a total that is not sector aligned" in {
    test(new LiteSdioSgPrefetch).withAnnotations(annotations) { dut =>
      resetPrefetch(dut)
      dut.io.enable.poke(true.B)
      dut.io.tableAddress.poke("h80001000".U)
      dut.io.entryCount.poke(1.U)
      dut.io.totalBytes.poke(516.U)
      dut.io.arm.poke(true.B)
      dut.clock.step()
      dut.io.arm.poke(false.B)
      dut.io.busy.expect(false.B)
      dut.io.ready.expect(false.B)
      dut.io.error.expect(true.B)
      dut.io.errorCode.expect(LiteSdioSg.ErrorTotalLength.U)
    }
  }

  "SG table prefetch should reject a payload range beyond 32-bit DMA space" in {
    test(new LiteSdioSgPrefetch).withAnnotations(annotations) { dut =>
      resetPrefetch(dut)
      dut.io.enable.poke(true.B)
      dut.io.tableAddress.poke("h80001000".U)
      dut.io.entryCount.poke(1.U)
      dut.io.totalBytes.poke(512.U)
      dut.io.arm.poke(true.B)
      dut.clock.step()
      dut.io.arm.poke(false.B)

      dut.io.dmaDescReady.poke(true.B)
      dut.clock.step()
      dut.io.dmaDescReady.poke(false.B)

      val words = Seq(BigInt("fffffe00", 16), BigInt(512))
      for ((word, index) <- words.zipWithIndex) {
        dut.io.dmaData.poke(word.U)
        dut.io.dmaKeep.poke("hf".U)
        dut.io.dmaLast.poke((index == words.size - 1).B)
        dut.io.dmaValid.poke(true.B)
        dut.clock.step()
      }
      dut.io.dmaValid.poke(false.B)
      dut.io.dmaLast.poke(false.B)
      dut.io.dmaStatusValid.poke(true.B)
      dut.clock.step()
      dut.io.dmaStatusValid.poke(false.B)
      dut.clock.step()

      dut.io.busy.expect(false.B)
      dut.io.ready.expect(false.B)
      dut.io.error.expect(true.B)
      dut.io.errorCode.expect(LiteSdioSg.ErrorAddressOverflow.U)
      dut.io.errorIndex.expect(0.U)
      dut.io.errorDetail.expect("hfffffe00".U)
    }
  }

  "SD-to-memory SG should insert per-segment tlast and report one logical status" in {
    test(new LiteSdioSgDatapath).withAnnotations(annotations) { dut =>
      resetDatapath(dut)
      dut.io.rawWriteDescAddress.poke("hdead0000".U)
      dut.io.rawWriteDescLength.poke(16.U)
      dut.io.rawWriteDescValid.poke(true.B)
      dut.io.rawWriteDescReady.expect(true.B)
      dut.clock.step()
      dut.io.rawWriteDescValid.poke(false.B)

      val entries = Seq((BigInt("81000000", 16), 8), (BigInt("82000000", 16), 8))
      var beat = 0
      for (((address, length), entryIndex) <- entries.zipWithIndex) {
        provideTableEntry(dut, entryIndex, address, length)
        waitFor(dut.io.dmaWriteDescValid.peek().litToBoolean, dut)
        dut.io.dmaWriteDescAddress.expect(address.U)
        dut.io.dmaWriteDescLength.expect(length.U)
        dut.clock.step()

        for (segmentBeat <- 0 until 2) {
          beat += 1
          dut.io.rawWriteData.poke((0x1000 + beat).U)
          dut.io.rawWriteKeep.poke("hf".U)
          dut.io.rawWriteLast.poke((beat == 4).B)
          dut.io.rawWriteValid.poke(true.B)
          if (beat == 1) {
            dut.io.dmaWriteReady.poke(false.B)
            dut.io.rawWriteReady.expect(false.B)
            dut.clock.step()
            dut.io.dmaWriteReady.poke(true.B)
          }
          dut.io.rawWriteReady.expect(true.B)
          dut.io.dmaWriteLast.expect((segmentBeat == 1).B)
          dut.clock.step()
        }
        dut.io.rawWriteValid.poke(false.B)
        dut.io.rawWriteLast.poke(false.B)
        dut.io.dmaWriteStatusLength.poke(length.U)
        dut.io.dmaWriteStatusValid.poke(true.B)
        dut.clock.step()
        dut.io.dmaWriteStatusValid.poke(false.B)
      }

      dut.io.rawWriteStatusValid.expect(true.B)
      dut.io.rawWriteStatusLength.expect(16.U)
      dut.io.rawWriteStatusError.expect(0.U)
      dut.io.completed.expect(true.B)
      dut.io.operationError.expect(false.B)
      dut.clock.step()
      dut.io.active.expect(false.B)
    }
  }

  "memory-to-SD SG should wait for the LiteSD write-data phase and hide intermediate tlast" in {
    test(new LiteSdioSgDatapath).withAnnotations(annotations) { dut =>
      resetDatapath(dut)
      dut.io.rawReadDescAddress.poke("hdead0000".U)
      dut.io.rawReadDescLength.poke(16.U)
      dut.io.rawReadDescValid.poke(true.B)
      dut.io.rawReadDescReady.expect(true.B)
      dut.clock.step()
      dut.io.rawReadDescValid.poke(false.B)

      for (_ <- 0 until 4) {
        dut.io.tableReadEnable.expect(false.B)
        dut.io.dmaReadDescValid.expect(false.B)
        dut.clock.step()
      }
      dut.io.mem2blockWriteActive.poke(true.B)

      val entries = Seq((BigInt("83000000", 16), 8), (BigInt("84000000", 16), 8))
      var beat = 0
      for (((address, length), entryIndex) <- entries.zipWithIndex) {
        provideTableEntry(dut, entryIndex, address, length)
        waitFor(dut.io.dmaReadDescValid.peek().litToBoolean, dut)
        dut.io.dmaReadDescAddress.expect(address.U)
        dut.io.dmaReadDescLength.expect(length.U)
        dut.clock.step()

        for (segmentBeat <- 0 until 2) {
          beat += 1
          dut.io.dmaReadData.poke((0x2000 + beat).U)
          dut.io.dmaReadKeep.poke("hf".U)
          dut.io.dmaReadLast.poke((segmentBeat == 1).B)
          dut.io.dmaReadValid.poke(true.B)
          if (beat == 1) {
            dut.io.rawReadReady.poke(false.B)
            dut.io.dmaReadReady.expect(false.B)
            dut.io.rawReadValid.expect(true.B)
            dut.clock.step()
            dut.io.rawReadReady.poke(true.B)
          }
          dut.io.dmaReadReady.expect(true.B)
          dut.io.rawReadValid.expect(true.B)
          dut.io.rawReadData.expect((0x2000 + beat).U)
          dut.io.rawReadLast.expect((beat == 4).B)
          dut.clock.step()
        }
        dut.io.dmaReadValid.poke(false.B)
        dut.io.dmaReadLast.poke(false.B)
        dut.io.dmaReadStatusValid.poke(true.B)
        dut.clock.step()
        dut.io.dmaReadStatusValid.poke(false.B)
      }

      dut.io.rawReadStatusValid.expect(true.B)
      dut.io.rawReadStatusError.expect(0.U)
      dut.io.completed.expect(true.B)
      dut.io.operationError.expect(false.B)
      assert(dut.io.maxGapCycles.peek().litValue > 0,
        "the inter-segment control gap was not measured")
      dut.clock.step()
      dut.io.active.expect(false.B)
    }
  }

  "disabling an active SG write should terminate the physical segment before reporting abort" in {
    test(new LiteSdioSgDatapath).withAnnotations(annotations) { dut =>
      resetDatapath(dut)
      dut.io.entryCount.poke(1.U)
      dut.io.totalBytes.poke(8.U)
      dut.io.rawWriteDescLength.poke(8.U)
      dut.io.rawWriteDescValid.poke(true.B)
      dut.clock.step()
      dut.io.rawWriteDescValid.poke(false.B)

      provideTableEntry(dut, 0, BigInt("85000000", 16), 8)
      waitFor(dut.io.dmaWriteDescValid.peek().litToBoolean, dut)
      dut.clock.step()

      dut.io.enable.poke(false.B)
      dut.io.rawWriteData.poke(0.U)
      dut.io.rawWriteKeep.poke(0.U)
      dut.io.rawWriteLast.poke(true.B)
      dut.io.rawWriteValid.poke(true.B)
      dut.io.dmaWriteLast.expect(true.B)
      dut.clock.step()
      dut.io.rawWriteValid.poke(false.B)
      dut.io.dmaWriteStatusLength.poke(0.U)
      dut.io.dmaWriteStatusValid.poke(true.B)
      dut.clock.step()
      dut.io.dmaWriteStatusValid.poke(false.B)

      dut.io.rawWriteStatusValid.expect(true.B)
      dut.io.rawWriteStatusError.expect(2.U)
      dut.io.aborted.expect(true.B)
      dut.io.operationError.expect(true.B)
      dut.io.operationErrorCode.expect(LiteSdioSg.ErrorAborted.U)
    }
  }
}
