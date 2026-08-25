package tensorcore

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

class TensorCoreGemmAxiApbTopSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "TensorCoreGemmAxiApbTop"

  private val annotations = Seq(VerilatorBackendAnnotation)

  private def asserted(signal: Bool): Boolean = signal.peek().litValue == 1

  private def fp32(value: Float): BigInt =
    BigInt(java.lang.Float.floatToRawIntBits(value).toLong & 0xffffffffL)

  private final case class Burst(address: BigInt, beats: Int, id: BigInt)

  /** AXI memory model with independent channel backpressure and queued bursts. */
  private final class AxiMemory(
    dut: TensorCoreGemmAxiApbTop,
    val words: mutable.Map[BigInt, BigInt]
  ) {
    private val readQueue = mutable.Queue.empty[Burst]
    private val writeQueue = mutable.Queue.empty[Burst]
    private val responseQueue = mutable.Queue.empty[BigInt]
    private var activeRead: Option[Burst] = None
    private var activeWrite: Option[Burst] = None
    private var readBeat = 0
    private var writeBeat = 0
    private var cycle = 0

    val readAddresses = mutable.ArrayBuffer.empty[(BigInt, Int)]
    val writeAddresses = mutable.ArrayBuffer.empty[(BigInt, Int)]

    def tick(): Unit = {
      val arReady = readQueue.size < 8 && cycle % 3 != 1
      val awReady = writeQueue.size < 8 && cycle % 4 != 1
      val rValid = activeRead.nonEmpty && cycle % 4 != 2
      val wReady = activeWrite.nonEmpty && cycle % 3 != 2
      val bValid = responseQueue.nonEmpty && cycle % 5 != 3

      dut.io.axi.arready.poke(arReady.B)
      dut.io.axi.rvalid.poke(rValid.B)
      dut.io.axi.rid.poke(activeRead.map(_.id).getOrElse(BigInt(0)).U)
      dut.io.axi.rdata.poke(activeRead.map { burst =>
        words.getOrElse(burst.address + readBeat * 4, BigInt(0))
      }.getOrElse(BigInt(0)).U)
      dut.io.axi.rresp.poke(0.U)
      dut.io.axi.rlast.poke(activeRead.exists(burst => readBeat == burst.beats - 1).B)

      dut.io.axi.awready.poke(awReady.B)
      dut.io.axi.wready.poke(wReady.B)
      dut.io.axi.bvalid.poke(bValid.B)
      dut.io.axi.bid.poke(responseQueue.headOption.getOrElse(BigInt(0)).U)
      dut.io.axi.bresp.poke(0.U)

      val arFire = arReady && asserted(dut.io.axi.arvalid)
      val rFire = rValid && asserted(dut.io.axi.rready)
      val awFire = awReady && asserted(dut.io.axi.awvalid)
      val wFire = wReady && asserted(dut.io.axi.wvalid)
      val bFire = bValid && asserted(dut.io.axi.bready)

      val acceptedRead = if (arFire) {
        dut.io.axi.arsize.expect(2.U)
        dut.io.axi.arburst.expect(1.U)
        dut.io.axi.arlen.peek().litValue.toInt should be <= 15
        Some(Burst(
          dut.io.axi.araddr.peek().litValue,
          dut.io.axi.arlen.peek().litValue.toInt + 1,
          dut.io.axi.arid.peek().litValue
        ))
      } else None
      val acceptedWrite = if (awFire) {
        dut.io.axi.awsize.expect(2.U)
        dut.io.axi.awburst.expect(1.U)
        dut.io.axi.awlen.peek().litValue.toInt should be <= 15
        Some(Burst(
          dut.io.axi.awaddr.peek().litValue,
          dut.io.axi.awlen.peek().litValue.toInt + 1,
          dut.io.axi.awid.peek().litValue
        ))
      } else None
      val writeData = if (wFire) {
        dut.io.axi.wstrb.expect("hf".U)
        Some(dut.io.axi.wdata.peek().litValue)
      } else None

      dut.clock.step()
      cycle += 1

      acceptedRead.foreach { burst =>
        readQueue.enqueue(burst)
        readAddresses += burst.address -> burst.beats
      }
      acceptedWrite.foreach { burst =>
        writeQueue.enqueue(burst)
        writeAddresses += burst.address -> burst.beats
      }

      if (rFire) {
        val burst = activeRead.get
        if (readBeat == burst.beats - 1) {
          activeRead = None
          readBeat = 0
        } else {
          readBeat += 1
        }
      }
      if (wFire) {
        val burst = activeWrite.get
        words(burst.address + writeBeat * 4) = writeData.get
        if (writeBeat == burst.beats - 1) {
          responseQueue.enqueue(burst.id)
          activeWrite = None
          writeBeat = 0
        } else {
          writeBeat += 1
        }
      }
      if (bFire) {
        responseQueue.dequeue()
      }
      if (activeRead.isEmpty && readQueue.nonEmpty) {
        activeRead = Some(readQueue.dequeue())
      }
      if (activeWrite.isEmpty && writeQueue.nonEmpty) {
        activeWrite = Some(writeQueue.dequeue())
      }
    }
  }

  private def initialize(dut: TensorCoreGemmAxiApbTop): Unit = {
    dut.io.apb.psel.poke(false.B)
    dut.io.apb.penable.poke(false.B)
    dut.io.apb.pwrite.poke(false.B)
    dut.io.apb.paddr.poke(0.U)
    dut.io.apb.pwdata.poke(0.U)
    dut.io.axi.arready.poke(false.B)
    dut.io.axi.rid.poke(0.U)
    dut.io.axi.rdata.poke(0.U)
    dut.io.axi.rresp.poke(0.U)
    dut.io.axi.rlast.poke(false.B)
    dut.io.axi.rvalid.poke(false.B)
    dut.io.axi.awready.poke(false.B)
    dut.io.axi.wready.poke(false.B)
    dut.io.axi.bid.poke(0.U)
    dut.io.axi.bresp.poke(0.U)
    dut.io.axi.bvalid.poke(false.B)
    dut.reset.poke(true.B)
    dut.clock.step(3)
    dut.reset.poke(false.B)
    dut.clock.step(2)
  }

  private def apbWrite(
    dut: TensorCoreGemmAxiApbTop,
    memory: AxiMemory,
    address: Int,
    value: BigInt
  ): Unit = {
    dut.io.apb.paddr.poke(address.U)
    dut.io.apb.pwrite.poke(true.B)
    dut.io.apb.pwdata.poke(value.U)
    dut.io.apb.psel.poke(true.B)
    dut.io.apb.penable.poke(false.B)
    memory.tick()
    dut.io.apb.penable.poke(true.B)
    dut.io.apb.pready.expect(true.B)
    dut.io.apb.pslverr.expect(false.B)
    memory.tick()
    dut.io.apb.psel.poke(false.B)
    dut.io.apb.penable.poke(false.B)
    dut.io.apb.pwrite.poke(false.B)
    memory.tick()
  }

  private def apbRead(
    dut: TensorCoreGemmAxiApbTop,
    memory: AxiMemory,
    address: Int
  ): BigInt = {
    dut.io.apb.paddr.poke(address.U)
    dut.io.apb.pwrite.poke(false.B)
    dut.io.apb.psel.poke(true.B)
    dut.io.apb.penable.poke(false.B)
    memory.tick()
    dut.io.apb.penable.poke(true.B)
    dut.io.apb.pready.expect(true.B)
    dut.io.apb.pslverr.expect(false.B)
    val result = dut.io.apb.prdata.peek().litValue
    memory.tick()
    dut.io.apb.psel.poke(false.B)
    dut.io.apb.penable.poke(false.B)
    memory.tick()
    result
  }

  it should "compute a variable GEMM with a partial four-column group" in {
    test(new TensorCoreGemmAxiApbTop).withAnnotations(annotations) { dut =>
      initialize(dut)
      val aBase = BigInt("1000", 16)
      val bBase = BigInt("2000", 16)
      val cBase = BigInt("3000", 16)
      val memoryWords = mutable.Map.empty[BigInt, BigInt]
      val memory = new AxiMemory(dut, memoryWords)
      val a = Seq(1f, 2f, 3f, 4f, 5f, 6f)
      val b = Seq(
        1f, 2f, 3f, 4f, 5f,
        6f, 7f, 8f, 9f, 10f,
        11f, 12f, 13f, 14f, 15f
      )

      a.indices.foreach(index => memoryWords(aBase + index * 4) = fp32(a(index)))
      for {
        group <- 0 until 2
        inner <- 0 until 3
        lane <- 0 until 4
      } {
        val column = group * 4 + lane
        val value = if (column < 5) b(inner * 5 + column) else 0f
        val packedIndex = (group * 3 + inner) * 4 + lane
        memoryWords(bBase + packedIndex * 4) = fp32(value)
      }

      apbRead(dut, memory, TensorCoreGemmRegisters.identification) shouldBe
        BigInt("54434734", 16)
      apbRead(dut, memory, TensorCoreGemmRegisters.version) shouldBe 4

      Seq(
        TensorCoreGemmRegisters.aBase -> aBase,
        TensorCoreGemmRegisters.bBase -> bBase,
        TensorCoreGemmRegisters.cBase -> cBase,
        TensorCoreGemmRegisters.m -> BigInt(2),
        TensorCoreGemmRegisters.n -> BigInt(5),
        TensorCoreGemmRegisters.k -> BigInt(3),
        TensorCoreGemmRegisters.aStride -> BigInt(12),
        TensorCoreGemmRegisters.cStride -> BigInt(20),
        TensorCoreGemmRegisters.resultRows -> BigInt(2),
        TensorCoreGemmRegisters.roundMode -> BigInt(0),
        TensorCoreGemmRegisters.irqEnable -> BigInt(1)
      ).foreach { case (address, value) => apbWrite(dut, memory, address, value) }
      apbWrite(dut, memory, TensorCoreGemmRegisters.control, 1)

      var status = BigInt(0)
      var cycles = 0
      while ((status & 2) == 0 && cycles < 5000) {
        memory.tick()
        status = apbRead(dut, memory, TensorCoreGemmRegisters.status)
        cycles += 1
      }
      withClue("GEMM did not complete: ") { cycles should be < 5000 }
      status shouldBe 2
      dut.io.interrupt.expect(true.B)
      apbRead(dut, memory, TensorCoreGemmRegisters.errorCode) shouldBe 0

      val expected = Seq(46f, 52f, 58f, 64f, 70f, 100f, 115f, 130f, 145f, 160f)
      expected.indices.foreach { index =>
        memoryWords(cBase + index * 4) shouldBe fp32(expected(index))
      }
      memory.readAddresses.head shouldBe (bBase -> 16)
      memory.readAddresses(1) shouldBe (bBase + 64 -> 8)
      memory.writeAddresses.map(_._1) should contain allOf (cBase, cBase + 20)
      apbRead(dut, memory, TensorCoreGemmRegisters.bReadCycles) should be > BigInt(0)
      apbRead(dut, memory, TensorCoreGemmRegisters.aReadCycles) should be > BigInt(0)
      apbRead(dut, memory, TensorCoreGemmRegisters.computeCycles) should be > BigInt(0)
      apbRead(dut, memory, TensorCoreGemmRegisters.cWriteCycles) should be > BigInt(0)

      // Reuse the same instance and cross the 32-column local B-tile boundary.
      apbWrite(dut, memory, TensorCoreGemmRegisters.status, 2)
      apbWrite(dut, memory, TensorCoreGemmRegisters.irqStatus, 1)
      dut.io.interrupt.expect(false.B)
      memory.readAddresses.clear()
      memory.writeAddresses.clear()
      memoryWords(aBase) = fp32(2f)
      for {
        group <- 0 until 9
        lane <- 0 until 4
      } {
        val column = group * 4 + lane
        val value = if (column < 33) (column + 1).toFloat else 0f
        memoryWords(bBase + (group * 4 + lane) * 4) = fp32(value)
      }

      Seq(
        TensorCoreGemmRegisters.m -> BigInt(1),
        TensorCoreGemmRegisters.n -> BigInt(33),
        TensorCoreGemmRegisters.k -> BigInt(1),
        TensorCoreGemmRegisters.aStride -> BigInt(4),
        TensorCoreGemmRegisters.cStride -> BigInt(132),
        TensorCoreGemmRegisters.resultRows -> BigInt(1)
      ).foreach { case (address, value) => apbWrite(dut, memory, address, value) }
      apbWrite(dut, memory, TensorCoreGemmRegisters.control, 1)

      status = 0
      cycles = 0
      while ((status & 2) == 0 && cycles < 5000) {
        memory.tick()
        status = apbRead(dut, memory, TensorCoreGemmRegisters.status)
        cycles += 1
      }
      withClue("multi-tile GEMM did not complete: ") { cycles should be < 5000 }
      status shouldBe 2
      (0 until 33).foreach { column =>
        memoryWords(cBase + column * 4) shouldBe fp32(2f * (column + 1))
      }
      memory.readAddresses.filter(_._1 >= bBase).toSeq shouldBe Seq(
        bBase -> 16,
        bBase + 64 -> 16,
        bBase + 128 -> 4
      )
      memory.writeAddresses.toSeq shouldBe Seq(
        cBase -> 16,
        cBase + 64 -> 16,
        cBase + 128 -> 1
      )
    }
  }

  it should "reject K above the local A buffer without AXI traffic" in {
    test(new TensorCoreGemmAxiApbTop).withAnnotations(annotations) { dut =>
      initialize(dut)
      val memory = new AxiMemory(dut, mutable.Map.empty)
      Seq(
        TensorCoreGemmRegisters.aBase -> BigInt("1000", 16),
        TensorCoreGemmRegisters.bBase -> BigInt("2000", 16),
        TensorCoreGemmRegisters.cBase -> BigInt("3000", 16),
        TensorCoreGemmRegisters.m -> BigInt(1),
        TensorCoreGemmRegisters.n -> BigInt(4),
        TensorCoreGemmRegisters.k -> BigInt(257),
        TensorCoreGemmRegisters.aStride -> BigInt(1028),
        TensorCoreGemmRegisters.cStride -> BigInt(16),
        TensorCoreGemmRegisters.resultRows -> BigInt(1)
      ).foreach { case (address, value) => apbWrite(dut, memory, address, value) }
      apbWrite(dut, memory, TensorCoreGemmRegisters.control, 1)
      apbRead(dut, memory, TensorCoreGemmRegisters.status) shouldBe 6
      apbRead(dut, memory, TensorCoreGemmRegisters.errorCode) shouldBe 1
      memory.readAddresses shouldBe empty
      memory.writeAddresses shouldBe empty
    }
  }

  it should "generate NHWC windows in local A memory and append the bias constant" in {
    test(new TensorCoreGemmAxiApbTop).withAnnotations(annotations) { dut =>
      initialize(dut)
      val aBase = BigInt("1000", 16)
      val bBase = BigInt("2000", 16)
      val cBase = BigInt("3000", 16)
      val memoryWords = mutable.Map.empty[BigInt, BigInt]
      val memory = new AxiMemory(dut, memoryWords)

      Seq(1f, 2f, 3f, 4f, 5f, 6f).indices.foreach { index =>
        memoryWords(aBase + index * 4) = fp32(Seq(1f, 2f, 3f, 4f, 5f, 6f)(index))
      }
      val lane0Weights = Seq(1f, 10f, 100f, 1000f, 7f)
      for {
        k <- lane0Weights.indices
        lane <- 0 until 4
      } {
        memoryWords(bBase + (k * 4 + lane) * 4) = fp32(if (lane == 0) lane0Weights(k) else 0f)
      }

      Seq(
        TensorCoreGemmRegisters.aBase -> aBase,
        TensorCoreGemmRegisters.bBase -> bBase,
        TensorCoreGemmRegisters.cBase -> cBase,
        TensorCoreGemmRegisters.m -> BigInt(2),
        TensorCoreGemmRegisters.n -> BigInt(1),
        TensorCoreGemmRegisters.k -> BigInt(5),
        TensorCoreGemmRegisters.aStride -> BigInt(0),
        TensorCoreGemmRegisters.cStride -> BigInt(4),
        TensorCoreGemmRegisters.mode -> BigInt(0x11),
        TensorCoreGemmRegisters.inputHeight -> BigInt(2),
        TensorCoreGemmRegisters.inputWidth -> BigInt(3),
        TensorCoreGemmRegisters.inputChannels -> BigInt(1),
        TensorCoreGemmRegisters.outputHeight -> BigInt(1),
        TensorCoreGemmRegisters.outputWidth -> BigInt(2),
        TensorCoreGemmRegisters.kernelHeight -> BigInt(2),
        TensorCoreGemmRegisters.kernelWidth -> BigInt(2),
        TensorCoreGemmRegisters.strideY -> BigInt(1),
        TensorCoreGemmRegisters.strideX -> BigInt(1),
        TensorCoreGemmRegisters.padTop -> BigInt(0),
        TensorCoreGemmRegisters.padLeft -> BigInt(0),
        TensorCoreGemmRegisters.sourceExtentBytes -> BigInt(24),
        TensorCoreGemmRegisters.sourceRowBytes -> BigInt(12),
        TensorCoreGemmRegisters.sourcePixelBytes -> BigInt(4),
        TensorCoreGemmRegisters.sourceStepYBytes -> BigInt(12),
        TensorCoreGemmRegisters.sourceStepXBytes -> BigInt(4),
        TensorCoreGemmRegisters.sourcePadTopBytes -> BigInt(0),
        TensorCoreGemmRegisters.sourcePadLeftBytes -> BigInt(0),
        TensorCoreGemmRegisters.resultRows -> BigInt(2)
      ).foreach { case (address, value) => apbWrite(dut, memory, address, value) }
      apbWrite(dut, memory, TensorCoreGemmRegisters.control, 1)

      var status = BigInt(0)
      var cycles = 0
      while ((status & 2) == 0 && cycles < 10000) {
        memory.tick()
        status = apbRead(dut, memory, TensorCoreGemmRegisters.status)
        cycles += 1
      }
      withClue("NHWC GEMM did not complete: ") { cycles should be < 10000 }
      status shouldBe 2
      memoryWords(cBase) shouldBe fp32(5428f)
      memoryWords(cBase + 4) shouldBe fp32(6539f)

      memory.readAddresses.filter { case (address, _) => address >= aBase && address < bBase }.toSeq shouldBe Seq(
        aBase -> 2,
        (aBase + 12) -> 2,
        (aBase + 4) -> 2,
        (aBase + 16) -> 2
      )
      apbRead(dut, memory, TensorCoreGemmRegisters.windowCycles) should be > BigInt(0)
    }
  }

  it should "apply PReLU and ceil-pool an odd 3 by 3 output" in {
    test(new TensorCoreGemmAxiApbTop).withAnnotations(annotations) { dut =>
      initialize(dut)
      val aBase = BigInt("1000", 16)
      val bBase = BigInt("2000", 16)
      val alphaBase = BigInt("2800", 16)
      val cBase = BigInt("3000", 16)
      val memoryWords = mutable.Map.empty[BigInt, BigInt]
      val memory = new AxiMemory(dut, memoryWords)
      val values = Seq(-4f, 2f, -8f, 4f, -12f, 1f, -16f, 3f, -20f)

      values.indices.foreach(index => memoryWords(aBase + index * 4) = fp32(values(index)))
      (0 until 4).foreach { lane =>
        memoryWords(bBase + lane * 4) = fp32(if (lane == 0) 1f else 0f)
      }
      memoryWords(alphaBase) = fp32(0.25f)

      Seq(
        TensorCoreGemmRegisters.aBase -> aBase,
        TensorCoreGemmRegisters.bBase -> bBase,
        TensorCoreGemmRegisters.cBase -> cBase,
        TensorCoreGemmRegisters.m -> BigInt(9),
        TensorCoreGemmRegisters.n -> BigInt(1),
        TensorCoreGemmRegisters.k -> BigInt(1),
        TensorCoreGemmRegisters.aStride -> BigInt(4),
        TensorCoreGemmRegisters.cStride -> BigInt(4),
        TensorCoreGemmRegisters.mode -> BigInt(0x8),
        TensorCoreGemmRegisters.outputHeight -> BigInt(3),
        TensorCoreGemmRegisters.outputWidth -> BigInt(3),
        TensorCoreGemmRegisters.preluBase -> alphaBase,
        TensorCoreGemmRegisters.sourceExtentBytes -> BigInt(36),
        TensorCoreGemmRegisters.sourceRowBytes -> BigInt(12),
        TensorCoreGemmRegisters.sourcePixelBytes -> BigInt(4),
        TensorCoreGemmRegisters.sourceStepYBytes -> BigInt(12),
        TensorCoreGemmRegisters.sourceStepXBytes -> BigInt(4),
        TensorCoreGemmRegisters.sourcePadTopBytes -> BigInt(0),
        TensorCoreGemmRegisters.sourcePadLeftBytes -> BigInt(0),
        TensorCoreGemmRegisters.resultRows -> BigInt(4)
      ).foreach { case (address, value) => apbWrite(dut, memory, address, value) }
      apbWrite(dut, memory, TensorCoreGemmRegisters.control, 1)

      var status = BigInt(0)
      var cycles = 0
      while ((status & 2) == 0 && cycles < 20000) {
        memory.tick()
        status = apbRead(dut, memory, TensorCoreGemmRegisters.status)
        cycles += 1
      }
      withClue("PReLU/pool GEMM did not complete: ") { cycles should be < 20000 }
      status shouldBe 2
      Seq(4f, 1f, 3f, -5f).indices.foreach { index =>
        memoryWords(cBase + index * 4) shouldBe fp32(Seq(4f, 1f, 3f, -5f)(index))
      }
      apbRead(dut, memory, TensorCoreGemmRegisters.postCycles) should be > BigInt(0)
      memory.writeAddresses.toSeq shouldBe Seq(cBase -> 1, (cBase + 4) -> 1, (cBase + 8) -> 1, (cBase + 12) -> 1)
    }
  }
}
