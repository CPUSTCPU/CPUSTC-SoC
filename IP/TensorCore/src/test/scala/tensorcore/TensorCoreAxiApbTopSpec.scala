package tensorcore

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TensorCoreAxiApbTopSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "TensorCoreAxiApbTop"

  private val Status = TensorCoreRegisters.status
  private val Control = TensorCoreRegisters.control
  private val annotations = Seq(VerilatorBackendAnnotation)

  private def asserted(signal: Bool): Boolean = signal.peek().litValue == 1

  private def initialize(dut: TensorCoreAxiApbTop): Unit = {
    dut.io.apb.psel.poke(false.B)
    dut.io.apb.penable.poke(false.B)
    dut.io.apb.pwrite.poke(false.B)
    dut.io.apb.paddr.poke(0.U)
    dut.io.apb.pwdata.poke(0.U)

    dut.io.axi.awready.poke(false.B)
    dut.io.axi.wready.poke(false.B)
    dut.io.axi.bid.poke(0.U)
    dut.io.axi.bresp.poke(0.U)
    dut.io.axi.bvalid.poke(false.B)
    dut.io.axi.arready.poke(false.B)
    dut.io.axi.rid.poke(0.U)
    dut.io.axi.rdata.poke(0.U)
    dut.io.axi.rresp.poke(0.U)
    dut.io.axi.rlast.poke(false.B)
    dut.io.axi.rvalid.poke(false.B)

    dut.reset.poke(true.B)
    dut.clock.step(3)
    dut.reset.poke(false.B)
    dut.clock.step(2)
  }

  /** Perform an APB setup phase followed by exactly one access phase. */
  private def apbTransfer(
    dut: TensorCoreAxiApbTop,
    address: Int,
    write: Boolean,
    data: BigInt = 0
  ): (BigInt, Boolean) = {
    dut.io.apb.paddr.poke(address.U)
    dut.io.apb.pwrite.poke(write.B)
    dut.io.apb.pwdata.poke(data.U)
    dut.io.apb.psel.poke(true.B)
    dut.io.apb.penable.poke(false.B)
    dut.clock.step()

    dut.io.apb.penable.poke(true.B)
    dut.io.apb.pready.expect(true.B)
    val result = dut.io.apb.prdata.peek().litValue
    val error = asserted(dut.io.apb.pslverr)
    dut.clock.step()

    dut.io.apb.psel.poke(false.B)
    dut.io.apb.penable.poke(false.B)
    dut.io.apb.pwrite.poke(false.B)
    dut.clock.step()
    (result, error)
  }

  private def apbRead(dut: TensorCoreAxiApbTop, address: Int): BigInt = {
    val (data, error) = apbTransfer(dut, address, write = false)
    withClue(f"APB read at 0x$address%03x: ") { error shouldBe false }
    data
  }

  private def apbWrite(
    dut: TensorCoreAxiApbTop,
    address: Int,
    data: BigInt,
    expectError: Boolean = false
  ): Unit = {
    val (_, error) = apbTransfer(dut, address, write = true, data)
    withClue(f"APB write at 0x$address%03x: ") { error shouldBe expectError }
  }

  private def waitFor(dut: TensorCoreAxiApbTop, description: String, limit: Int = 200)(condition: => Boolean): Unit = {
    var cycles = 0
    while (!condition && cycles < limit) {
      dut.clock.step()
      cycles += 1
    }
    withClue(s"timeout waiting for $description after $limit cycles: ") {
      condition shouldBe true
    }
  }

  private def configure(
    dut: TensorCoreAxiApbTop,
    aBase: BigInt,
    bBase: BigInt,
    cBase: BigInt,
    k: Int = 2,
    aStride: Int = 8,
    bStride: Int = 8,
    cStride: Int = 8,
    roundMode: Int = 0
  ): Unit = {
    Seq(
      TensorCoreRegisters.aBase -> aBase,
      TensorCoreRegisters.bBase -> bBase,
      TensorCoreRegisters.cBase -> cBase,
      TensorCoreRegisters.k -> BigInt(k),
      TensorCoreRegisters.aStride -> BigInt(aStride),
      TensorCoreRegisters.bStride -> BigInt(bStride),
      TensorCoreRegisters.cStride -> BigInt(cStride),
      TensorCoreRegisters.roundMode -> BigInt(roundMode)
    ).foreach { case (address, value) => apbWrite(dut, address, value) }
  }

  private def fp32(value: Float): BigInt =
    BigInt(java.lang.Float.floatToRawIntBits(value).toLong & 0xffffffffL)

  private def checkReadAttributes(dut: TensorCoreAxiApbTop): Unit = {
    dut.io.axi.arid.expect(0.U)
    dut.io.axi.arlen.expect(0.U)
    dut.io.axi.arsize.expect(2.U)
    dut.io.axi.arburst.expect(1.U)
    dut.io.axi.arlock.expect(false.B)
    dut.io.axi.arcache.expect(0.U)
    dut.io.axi.arprot.expect(0.U)
    dut.io.axi.arqos.expect(0.U)
    dut.io.axi.arregion.expect(0.U)
  }

  /** Serve one AXI read, deliberately stalling both address and data phases. */
  private def serveRead(
    dut: TensorCoreAxiApbTop,
    expectedAddress: BigInt,
    data: BigInt,
    response: Int = 0,
    id: Int = 0,
    last: Boolean = true
  ): Unit = {
    waitFor(dut, f"ARVALID for 0x$expectedAddress%x") { asserted(dut.io.axi.arvalid) }
    dut.io.axi.araddr.expect(expectedAddress.U)
    checkReadAttributes(dut)

    // AR payload must remain stable until the address handshake.
    for (_ <- 0 until 2) {
      dut.io.axi.arready.poke(false.B)
      dut.clock.step()
      dut.io.axi.arvalid.expect(true.B)
      dut.io.axi.araddr.expect(expectedAddress.U)
      checkReadAttributes(dut)
    }
    dut.io.axi.arready.poke(true.B)
    dut.clock.step()
    dut.io.axi.arready.poke(false.B)
    dut.io.axi.arvalid.expect(false.B)
    dut.io.axi.rready.expect(true.B)

    // The master must wait without issuing another address while RVALID stalls.
    dut.clock.step(2)
    dut.io.axi.rready.expect(true.B)
    dut.io.axi.arvalid.expect(false.B)

    dut.io.axi.rid.poke(id.U)
    dut.io.axi.rdata.poke(data.U)
    dut.io.axi.rresp.poke(response.U)
    dut.io.axi.rlast.poke(last.B)
    dut.io.axi.rvalid.poke(true.B)
    dut.io.axi.rready.expect(true.B)
    dut.clock.step()
    dut.io.axi.rvalid.poke(false.B)
    dut.io.axi.rlast.poke(false.B)
    dut.io.axi.rresp.poke(0.U)
    dut.io.axi.rid.poke(0.U)
  }

  private def checkWriteAttributes(dut: TensorCoreAxiApbTop): Unit = {
    dut.io.axi.awid.expect(0.U)
    dut.io.axi.awlen.expect(0.U)
    dut.io.axi.awsize.expect(2.U)
    dut.io.axi.awburst.expect(1.U)
    dut.io.axi.awlock.expect(false.B)
    dut.io.axi.awcache.expect(0.U)
    dut.io.axi.awprot.expect(0.U)
    dut.io.axi.awqos.expect(0.U)
    dut.io.axi.awregion.expect(0.U)
    dut.io.axi.wstrb.expect("b1111".U)
    dut.io.axi.wlast.expect(true.B)
  }

  /** Serve one AXI write with AW and W accepted in the requested order. */
  private def serveWrite(
    dut: TensorCoreAxiApbTop,
    expectedAddress: BigInt,
    handshakeMode: Int,
    responseDelay: Int = 2,
    response: Int = 0,
    id: Int = 0
  ): BigInt = {
    require(handshakeMode >= 0 && handshakeMode <= 2)
    waitFor(dut, f"AWVALID/WVALID for 0x$expectedAddress%x") {
      asserted(dut.io.axi.awvalid) && asserted(dut.io.axi.wvalid)
    }
    dut.io.axi.awaddr.expect(expectedAddress.U)
    checkWriteAttributes(dut)
    val writeData = dut.io.axi.wdata.peek().litValue

    // Both payloads must remain stable under backpressure.
    for (_ <- 0 until 2) {
      dut.io.axi.awready.poke(false.B)
      dut.io.axi.wready.poke(false.B)
      dut.clock.step()
      dut.io.axi.awvalid.expect(true.B)
      dut.io.axi.wvalid.expect(true.B)
      dut.io.axi.awaddr.expect(expectedAddress.U)
      dut.io.axi.wdata.expect(writeData.U)
      checkWriteAttributes(dut)
    }

    if (handshakeMode == 0) {
      dut.io.axi.awready.poke(true.B)
      dut.clock.step()
      dut.io.axi.awready.poke(false.B)
      dut.io.axi.awvalid.expect(false.B)
      dut.io.axi.wvalid.expect(true.B)
      dut.io.axi.wdata.expect(writeData.U)
      dut.clock.step(2)
      dut.io.axi.wvalid.expect(true.B)
      dut.io.axi.wdata.expect(writeData.U)
      dut.io.axi.wready.poke(true.B)
      dut.clock.step()
      dut.io.axi.wready.poke(false.B)
    } else if (handshakeMode == 1) {
      dut.io.axi.wready.poke(true.B)
      dut.clock.step()
      dut.io.axi.wready.poke(false.B)
      dut.io.axi.wvalid.expect(false.B)
      dut.io.axi.awvalid.expect(true.B)
      dut.io.axi.awaddr.expect(expectedAddress.U)
      dut.clock.step(2)
      dut.io.axi.awvalid.expect(true.B)
      dut.io.axi.awaddr.expect(expectedAddress.U)
      dut.io.axi.awready.poke(true.B)
      dut.clock.step()
      dut.io.axi.awready.poke(false.B)
    } else {
      dut.io.axi.awready.poke(true.B)
      dut.io.axi.wready.poke(true.B)
      dut.clock.step()
      dut.io.axi.awready.poke(false.B)
      dut.io.axi.wready.poke(false.B)
    }

    dut.io.axi.awvalid.expect(false.B)
    dut.io.axi.wvalid.expect(false.B)
    dut.io.axi.bready.expect(true.B)
    dut.clock.step(responseDelay)
    dut.io.axi.bready.expect(true.B)
    dut.io.axi.bid.poke(id.U)
    dut.io.axi.bresp.poke(response.U)
    dut.io.axi.bvalid.poke(true.B)
    dut.clock.step()
    dut.io.axi.bvalid.poke(false.B)
    dut.io.axi.bresp.poke(0.U)
    dut.io.axi.bid.poke(0.U)
    writeData
  }

  private def runTwoByTwoTask(
    dut: TensorCoreAxiApbTop,
    aBase: BigInt,
    bBase: BigInt,
    cBase: BigInt,
    a: Seq[Float],
    b: Seq[Float],
    k: Int = 2,
    aStride: Int = 8,
    bStride: Int = 8,
    cStride: Int = 8
  ): Seq[BigInt] = {
    require(a.length == 2 * k && b.length == k * 2)
    val aMemory = (for {
      row <- 0 until 2
      index <- 0 until k
    } yield aBase + row * aStride + index * 4 -> fp32(a(row * k + index))).toMap
    val bMemory = (for {
      index <- 0 until k
      col <- 0 until 2
    } yield bBase + index * bStride + col * 4 -> fp32(b(index * 2 + col))).toMap
    val memory = aMemory ++ bMemory
    val readOrder = (0 until k).flatMap { index =>
      Seq(
        aBase + index * 4,
        aBase + aStride + index * 4,
        bBase + index * bStride,
        bBase + index * bStride + 4
      )
    }
    readOrder.foreach(address => serveRead(dut, address, memory(address)))

    Seq.tabulate(4) { index =>
      serveWrite(
        dut,
        cBase + (index / 2) * cStride + (index % 2) * 4,
        handshakeMode = Seq(0, 1, 2, 0)(index),
        responseDelay = if (index == 3) 5 else 2
      )
    }
  }

  it should "implement the APB register map and reject illegal accesses" in {
    test(new TensorCoreAxiApbTop(rows = 2, cols = 2)).withAnnotations(annotations) { dut =>
      initialize(dut)

      apbRead(dut, TensorCoreRegisters.identification) shouldBe BigInt("54434f52", 16)
      apbRead(dut, TensorCoreRegisters.capabilities) shouldBe BigInt("18080202", 16)
      apbRead(dut, TensorCoreRegisters.version) shouldBe 1

      apbWrite(dut, TensorCoreRegisters.aBase, BigInt("12345000", 16))
      apbRead(dut, TensorCoreRegisters.aBase) shouldBe BigInt("12345000", 16)

      val (unknownData, unknownError) = apbTransfer(dut, 0x240, write = false)
      unknownData shouldBe 0
      unknownError shouldBe true
      apbWrite(dut, 0x240, 0xdeadbeefL, expectError = true)
      apbWrite(dut, TensorCoreRegisters.aBase + 1, 0xaaaaaaaaL, expectError = true)
      apbRead(dut, TensorCoreRegisters.aBase) shouldBe BigInt("12345000", 16)

      apbWrite(dut, TensorCoreRegisters.identification, 0, expectError = true)
      apbRead(dut, TensorCoreRegisters.identification) shouldBe BigInt("54434f52", 16)
    }
  }

  it should "reject invalid configurations without issuing AXI traffic" in {
    test(new TensorCoreAxiApbTop(rows = 2, cols = 2)).withAnnotations(annotations) { dut =>
      initialize(dut)
      val aBase = BigInt("1000", 16)
      val bBase = BigInt("2000", 16)
      val cBase = BigInt("3000", 16)

      val invalidCases = Seq[(String, () => Unit)](
        "zero K" -> (() => configure(dut, aBase, bBase, cBase, k = 0)),
        "unaligned A base" -> (() => configure(dut, aBase + 2, bBase, cBase)),
        "short A stride" -> (() => configure(dut, aBase, bBase, cBase, aStride = 4)),
        "short B stride" -> (() => configure(dut, aBase, bBase, cBase, bStride = 4)),
        "unsupported rounding mode" -> (() => configure(dut, aBase, bBase, cBase, roundMode = 5)),
        "K address multiplication overflow" -> (() => configure(dut, aBase, bBase, cBase, k = 1 << 30, aStride = Int.MaxValue & ~3)),
        "A final address overflow" -> (() => configure(dut, BigInt("fffffffc", 16), bBase, cBase)),
        "B final address overflow" -> (() => configure(dut, aBase, BigInt("fffffffc", 16), cBase)),
        "C final address overflow" -> (() => configure(dut, aBase, bBase, BigInt("fffffffc", 16), cStride = 12))
      )

      invalidCases.foreach { case (description, program) =>
        withClue(s"$description: ") {
          program()
          apbWrite(dut, Control, 1)
          apbRead(dut, Status) shouldBe 6
          dut.io.axi.arvalid.expect(false.B)
          dut.io.axi.awvalid.expect(false.B)
          dut.io.axi.wvalid.expect(false.B)
          apbWrite(dut, Status, 6)
          apbRead(dut, Status) shouldBe 0
        }
      }
    }
  }

  it should "honor AXI stalls and compute two independent FP32 matrix products" in {
    test(new TensorCoreAxiApbTop(rows = 2, cols = 2)).withAnnotations(annotations) { dut =>
      initialize(dut)
      apbWrite(dut, TensorCoreRegisters.irqEnable, 1)

      val firstA = BigInt("1000", 16)
      val firstB = BigInt("2000", 16)
      val firstC = BigInt("3000", 16)
      configure(dut, firstA, firstB, firstC, k = 3, aStride = 12, bStride = 8, cStride = 12)
      apbWrite(dut, Control, 1)
      (apbRead(dut, Status) & 1) shouldBe 1

      // Configuration writes are rejected while busy and must not mutate state.
      apbWrite(dut, TensorCoreRegisters.aBase, BigInt("dead0000", 16), expectError = true)
      apbRead(dut, TensorCoreRegisters.aBase) shouldBe firstA

      val firstWrites = runTwoByTwoTask(
        dut, firstA, firstB, firstC,
        a = Seq(1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f),
        b = Seq(7.0f, 8.0f, 9.0f, 10.0f, 11.0f, 12.0f),
        k = 3,
        aStride = 12,
        bStride = 8,
        cStride = 12
      )
      firstWrites shouldBe Seq(58.0f, 64.0f, 139.0f, 154.0f).map(fp32)
      apbRead(dut, Status) shouldBe 2
      apbRead(dut, TensorCoreRegisters.irqStatus) shouldBe 1
      dut.io.interrupt.expect(true.B)
      apbRead(dut, TensorCoreRegisters.cycleCount) should be > BigInt(0)

      apbWrite(dut, Status, 2)
      apbWrite(dut, TensorCoreRegisters.irqStatus, 1)
      dut.io.interrupt.expect(false.B)

      // A second start must locally reset every PE accumulator.
      val secondA = BigInt("4000", 16)
      val secondB = BigInt("5000", 16)
      val secondC = BigInt("6000", 16)
      configure(dut, secondA, secondB, secondC)
      apbWrite(dut, Control, 1)
      val secondWrites = runTwoByTwoTask(
        dut, secondA, secondB, secondC,
        a = Seq(1.0f, 0.0f, 0.0f, 1.0f),
        b = Seq(5.0f, 6.0f, 7.0f, 8.0f)
      )
      secondWrites shouldBe Seq(5.0f, 6.0f, 7.0f, 8.0f).map(fp32)
      apbRead(dut, Status) shouldBe 2
    }
  }

  it should "compute the default 1x4 tile with stalled AXI and row-major writes" in {
    test(new TensorCoreAxiApbTop()).withAnnotations(annotations) { dut =>
      initialize(dut)

      apbRead(dut, TensorCoreRegisters.capabilities) shouldBe BigInt("18080401", 16)

      val aBase = BigInt("1000", 16)
      val bBase = BigInt("2000", 16)
      val cBase = BigInt("3000", 16)
      val k = 3
      val aStride = 12
      val bStride = 16
      val cStride = 16
      val a = Seq(1.5f, -2.0f, 0.5f)
      val b = Seq(
        2.0f, -1.0f, 4.0f, 0.0f,
        1.0f, 3.0f, -2.0f, 5.0f,
        6.0f, 0.5f, 1.0f, -4.0f
      )

      configure(
        dut,
        aBase,
        bBase,
        cBase,
        k = k,
        aStride = aStride,
        bStride = bStride,
        cStride = cStride
      )
      apbWrite(dut, Control, 1)
      (apbRead(dut, Status) & 1) shouldBe 1

      val memory =
        a.indices.map(index => aBase + index * 4 -> fp32(a(index))).toMap ++
          (for {
            index <- 0 until k
            col <- 0 until 4
          } yield bBase + index * bStride + col * 4 -> fp32(b(index * 4 + col))).toMap
      val readOrder = (0 until k).flatMap { index =>
        Seq(aBase + index * 4) ++ (0 until 4).map(col => bBase + index * bStride + col * 4)
      }
      readOrder.foreach(address => serveRead(dut, address, memory(address)))

      val writes = Seq.tabulate(4) { col =>
        serveWrite(
          dut,
          cBase + col * 4,
          handshakeMode = Seq(0, 1, 2, 0)(col),
          responseDelay = if (col == 3) 5 else 2
        )
      }
      writes shouldBe Seq(4.0f, -7.25f, 10.5f, -12.0f).map(fp32)
      apbRead(dut, Status) shouldBe 2
      apbRead(dut, TensorCoreRegisters.cycleCount) should be > BigInt(0)
    }
  }

  it should "terminate a command on AXI read and write error responses" in {
    test(new TensorCoreAxiApbTop(rows = 2, cols = 2)).withAnnotations(annotations) { dut =>
      initialize(dut)
      val aBase = BigInt("1000", 16)
      val bBase = BigInt("2000", 16)
      val cBase = BigInt("3000", 16)
      configure(dut, aBase, bBase, cBase)
      apbWrite(dut, Control, 1)
      serveRead(dut, aBase, fp32(1.0f), response = 2)
      apbRead(dut, Status) shouldBe 6
      dut.io.axi.arvalid.expect(false.B)
      dut.io.axi.awvalid.expect(false.B)

      apbWrite(dut, Status, 6)
      configure(dut, aBase, bBase, cBase)
      apbWrite(dut, Control, 1)
      val memory = Map(
        aBase -> fp32(1.0f), (aBase + 4) -> fp32(0.0f),
        (aBase + 8) -> fp32(0.0f), (aBase + 12) -> fp32(1.0f),
        bBase -> fp32(1.0f), (bBase + 4) -> fp32(2.0f),
        (bBase + 8) -> fp32(3.0f), (bBase + 12) -> fp32(4.0f)
      )
      Seq(
        aBase, aBase + 8, bBase, bBase + 4,
        aBase + 4, aBase + 12, bBase + 8, bBase + 12
      ).foreach(address => serveRead(dut, address, memory(address)))
      serveWrite(dut, cBase, handshakeMode = 1, response = 3)
      apbRead(dut, Status) shouldBe 6
      dut.io.axi.awvalid.expect(false.B)
      dut.io.axi.wvalid.expect(false.B)
    }
  }
}
