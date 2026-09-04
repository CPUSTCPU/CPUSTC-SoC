package chisel

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import chisel.axiInterconnect.sdio._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import tensorcore.{AxiDmaReadExt, AxiDmaWriteExt}

import scala.collection.mutable

private[chisel] class LiteSdioAxiDmaHarness extends Module {
  val io = IO(new Bundle {
    val readDescAddr = Input(UInt(32.W))
    val readDescLen = Input(UInt(21.W))
    val readDescValid = Input(Bool())
    val readDescReady = Output(Bool())
    val readStatusError = Output(UInt(4.W))
    val readStatusValid = Output(Bool())
    val readData = Output(UInt(32.W))
    val readKeep = Output(UInt(4.W))
    val readValid = Output(Bool())
    val readReady = Input(Bool())
    val readLast = Output(Bool())

    val writeDescAddr = Input(UInt(32.W))
    val writeDescLen = Input(UInt(21.W))
    val writeDescValid = Input(Bool())
    val writeDescReady = Output(Bool())
    val writeStatusLen = Output(UInt(21.W))
    val writeStatusError = Output(UInt(4.W))
    val writeStatusValid = Output(Bool())
    val writeData = Input(UInt(32.W))
    val writeKeep = Input(UInt(4.W))
    val writeValid = Input(Bool())
    val writeReady = Output(Bool())
    val writeLast = Input(Bool())

    val arid = Output(UInt(4.W))
    val araddr = Output(UInt(32.W))
    val arlen = Output(UInt(8.W))
    val arsize = Output(UInt(3.W))
    val arburst = Output(UInt(2.W))
    val arlock = Output(Bool())
    val arcache = Output(UInt(4.W))
    val arprot = Output(UInt(3.W))
    val arvalid = Output(Bool())
    val arready = Input(Bool())
    val rid = Input(UInt(4.W))
    val rdata = Input(UInt(32.W))
    val rresp = Input(UInt(2.W))
    val rlast = Input(Bool())
    val rvalid = Input(Bool())
    val rready = Output(Bool())

    val awid = Output(UInt(4.W))
    val awaddr = Output(UInt(32.W))
    val awlen = Output(UInt(8.W))
    val awsize = Output(UInt(3.W))
    val awburst = Output(UInt(2.W))
    val awlock = Output(Bool())
    val awcache = Output(UInt(4.W))
    val awprot = Output(UInt(3.W))
    val awvalid = Output(Bool())
    val awready = Input(Bool())
    val wdata = Output(UInt(32.W))
    val wstrb = Output(UInt(4.W))
    val wlast = Output(Bool())
    val wvalid = Output(Bool())
    val wready = Input(Bool())
    val bid = Input(UInt(4.W))
    val bresp = Input(UInt(2.W))
    val bvalid = Input(Bool())
    val bready = Output(Bool())
  })

  private val readDma = Module(new AxiDmaReadExt(axiIdWidth = 4, lenWidth = 21))
  readDma.clk := clock
  readDma.rst := reset.asBool
  readDma.enable := true.B
  readDma.s_axis_read_desc_addr := io.readDescAddr
  readDma.s_axis_read_desc_len := io.readDescLen
  readDma.s_axis_read_desc_tag := 0.U
  readDma.s_axis_read_desc_id := 0.U
  readDma.s_axis_read_desc_dest := 0.U
  readDma.s_axis_read_desc_user := 0.U
  readDma.s_axis_read_desc_valid := io.readDescValid
  io.readDescReady := readDma.s_axis_read_desc_ready
  io.readStatusError := readDma.m_axis_read_desc_status_error
  io.readStatusValid := readDma.m_axis_read_desc_status_valid
  io.readData := readDma.m_axis_read_data_tdata
  io.readKeep := readDma.m_axis_read_data_tkeep
  io.readValid := readDma.m_axis_read_data_tvalid
  readDma.m_axis_read_data_tready := io.readReady
  io.readLast := readDma.m_axis_read_data_tlast
  io.arid := readDma.m_axi_arid
  io.araddr := readDma.m_axi_araddr
  io.arlen := readDma.m_axi_arlen
  io.arsize := readDma.m_axi_arsize
  io.arburst := readDma.m_axi_arburst
  io.arlock := readDma.m_axi_arlock
  io.arcache := readDma.m_axi_arcache
  io.arprot := readDma.m_axi_arprot
  io.arvalid := readDma.m_axi_arvalid
  readDma.m_axi_arready := io.arready
  readDma.m_axi_rid := io.rid
  readDma.m_axi_rdata := io.rdata
  readDma.m_axi_rresp := io.rresp
  readDma.m_axi_rlast := io.rlast
  readDma.m_axi_rvalid := io.rvalid
  io.rready := readDma.m_axi_rready

  private val writeDma = Module(new AxiDmaWriteExt(axiIdWidth = 4, lenWidth = 21))
  writeDma.clk := clock
  writeDma.rst := reset.asBool
  writeDma.enable := true.B
  writeDma.abort := false.B
  writeDma.s_axis_write_desc_addr := io.writeDescAddr
  writeDma.s_axis_write_desc_len := io.writeDescLen
  writeDma.s_axis_write_desc_tag := 0.U
  writeDma.s_axis_write_desc_valid := io.writeDescValid
  io.writeDescReady := writeDma.s_axis_write_desc_ready
  io.writeStatusLen := writeDma.m_axis_write_desc_status_len
  io.writeStatusError := writeDma.m_axis_write_desc_status_error
  io.writeStatusValid := writeDma.m_axis_write_desc_status_valid
  writeDma.s_axis_write_data_tdata := io.writeData
  writeDma.s_axis_write_data_tkeep := io.writeKeep
  writeDma.s_axis_write_data_tvalid := io.writeValid
  io.writeReady := writeDma.s_axis_write_data_tready
  writeDma.s_axis_write_data_tlast := io.writeLast
  writeDma.s_axis_write_data_tid := 0.U
  writeDma.s_axis_write_data_tdest := 0.U
  writeDma.s_axis_write_data_tuser := 0.U
  io.awid := writeDma.m_axi_awid
  io.awaddr := writeDma.m_axi_awaddr
  io.awlen := writeDma.m_axi_awlen
  io.awsize := writeDma.m_axi_awsize
  io.awburst := writeDma.m_axi_awburst
  io.awlock := writeDma.m_axi_awlock
  io.awcache := writeDma.m_axi_awcache
  io.awprot := writeDma.m_axi_awprot
  io.awvalid := writeDma.m_axi_awvalid
  writeDma.m_axi_awready := io.awready
  io.wdata := writeDma.m_axi_wdata
  io.wstrb := writeDma.m_axi_wstrb
  io.wlast := writeDma.m_axi_wlast
  io.wvalid := writeDma.m_axi_wvalid
  writeDma.m_axi_wready := io.wready
  writeDma.m_axi_bid := io.bid
  writeDma.m_axi_bresp := io.bresp
  writeDma.m_axi_bvalid := io.bvalid
  io.bready := writeDma.m_axi_bready
}

class LiteSdioAxiDmaSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "LiteSD AXI DMA engines"

  private val annotations = Seq(VerilatorBackendAnnotation)

  private final case class Burst(address: BigInt, beats: Int, id: BigInt, ordinal: Int)
  private final case class Response(id: BigInt, code: Int, availableAt: Int)
  private final case class WriteBeat(address: BigInt, data: BigInt, strobe: Int, last: Boolean)
  private final case class ReadResult(
    bursts: Seq[Burst],
    bytes: Seq[Int],
    lastOffsets: Seq[Int],
    statusError: Int
  )
  private final case class WriteResult(
    bursts: Seq[Burst],
    beats: Seq[WriteBeat],
    memory: Map[BigInt, Int],
    statusLength: Int,
    statusError: Int,
    statusCycle: Int,
    lastResponseCycle: Int
  )

  private def asserted(signal: Bool): Boolean = signal.peek().litValue == 1

  private def bytePattern(index: Int): Int =
    ((index * 73 + 0x19) ^ (index >>> 1)) & 0xff

  private def packBytes(bytes: Seq[Int]): BigInt =
    bytes.zipWithIndex.foldLeft(BigInt(0)) { case (word, (byte, lane)) =>
      word | (BigInt(byte & 0xff) << (lane * 8))
    }

  private def readWord(memory: Map[BigInt, Int], address: BigInt): BigInt =
    packBytes((0 until 4).map(lane => memory.getOrElse(address + lane, 0)))

  private def initialize(dut: LiteSdioAxiDmaHarness): Unit = {
    dut.io.readDescAddr.poke(0.U)
    dut.io.readDescLen.poke(0.U)
    dut.io.readDescValid.poke(false.B)
    dut.io.readReady.poke(false.B)
    dut.io.writeDescAddr.poke(0.U)
    dut.io.writeDescLen.poke(0.U)
    dut.io.writeDescValid.poke(false.B)
    dut.io.writeData.poke(0.U)
    dut.io.writeKeep.poke(0.U)
    dut.io.writeValid.poke(false.B)
    dut.io.writeLast.poke(false.B)
    dut.io.arready.poke(false.B)
    dut.io.rid.poke(0.U)
    dut.io.rdata.poke(0.U)
    dut.io.rresp.poke(0.U)
    dut.io.rlast.poke(false.B)
    dut.io.rvalid.poke(false.B)
    dut.io.awready.poke(false.B)
    dut.io.wready.poke(false.B)
    dut.io.bid.poke(0.U)
    dut.io.bresp.poke(0.U)
    dut.io.bvalid.poke(false.B)
    dut.reset.poke(true.B)
    dut.clock.step(3)
    dut.reset.poke(false.B)
    dut.clock.step(2)
  }

  private def checkBurst(burst: Burst): Unit = {
    withClue(f"burst at 0x${burst.address}%x: ") {
      burst.beats should (be > 0 and be <= 16)
      ((burst.address & 0xfff) + burst.beats * 4) should be <= BigInt(4096)
    }
  }

  private def checkContiguous(bursts: Seq[Burst], address: BigInt, length: Int): Unit = {
    bursts should not be empty
    bursts.foreach(checkBurst)
    bursts.head.address shouldBe address
    bursts.sliding(2).foreach {
      case Seq(left, right) => right.address shouldBe left.address + left.beats * 4
      case _ =>
    }
    bursts.map(_.beats).sum * 4 shouldBe length
  }

  private def runRead(
    dut: LiteSdioAxiDmaHarness,
    address: BigInt,
    length: Int,
    memory: Map[BigInt, Int],
    errorBeat: Option[(Int, Int)] = None
  ): ReadResult = {
    val pendingBursts = mutable.Queue.empty[Burst]
    val bursts = mutable.ArrayBuffer.empty[Burst]
    val outputBytes = mutable.ArrayBuffer.empty[Int]
    val lastOffsets = mutable.ArrayBuffer.empty[Int]
    var activeBurst = Option.empty[Burst]
    var activeBeat = 0
    var readBeatNumber = 0
    var nextBurstOrdinal = 0
    var descriptorPending = true
    var descriptorAccepted = 0
    var statusError = Option.empty[Int]
    var cycle = 0

    while ((statusError.isEmpty || lastOffsets.isEmpty) && cycle < 5000) {
      dut.io.readDescAddr.poke(address.U)
      dut.io.readDescLen.poke(length.U)
      dut.io.readDescValid.poke(descriptorPending.B)
      val streamReady = cycle % 5 != 2
      dut.io.readReady.poke(streamReady.B)

      val arReady = cycle % 3 != 1
      dut.io.arready.poke(arReady.B)
      val rValid = activeBurst.nonEmpty && cycle % 4 != 2
      val rId = activeBurst.map(_.id).getOrElse(BigInt(0))
      val rAddress = activeBurst.map(burst => burst.address + activeBeat * 4).getOrElse(BigInt(0))
      val rResponse = errorBeat.collect {
        case (beat, response) if beat == readBeatNumber => response
      }.getOrElse(0)
      dut.io.rid.poke(rId.U)
      dut.io.rdata.poke(readWord(memory, rAddress).U)
      dut.io.rresp.poke(rResponse.U)
      dut.io.rlast.poke(activeBurst.exists(burst => activeBeat == burst.beats - 1).B)
      dut.io.rvalid.poke(rValid.B)

      val descriptorFire = descriptorPending && asserted(dut.io.readDescReady)
      val arFire = arReady && asserted(dut.io.arvalid)
      val rFire = rValid && asserted(dut.io.rready)
      val streamFire = streamReady && asserted(dut.io.readValid)
      val acceptedBurst = if (arFire) {
        dut.io.arsize.expect(2.U)
        dut.io.arburst.expect(1.U)
        val burst = Burst(
          dut.io.araddr.peek().litValue,
          dut.io.arlen.peek().litValue.toInt + 1,
          dut.io.arid.peek().litValue,
          nextBurstOrdinal
        )
        checkBurst(burst)
        Some(burst)
      } else None

      if (streamFire) {
        val data = dut.io.readData.peek().litValue
        val keep = dut.io.readKeep.peek().litValue.toInt
        for (lane <- 0 until 4 if (keep & (1 << lane)) != 0) {
          outputBytes += ((data >> (lane * 8)) & 0xff).toInt
        }
        if (asserted(dut.io.readLast)) {
          lastOffsets += outputBytes.size
        }
      }
      if (asserted(dut.io.readStatusValid)) {
        statusError shouldBe empty
        statusError = Some(dut.io.readStatusError.peek().litValue.toInt)
      }

      dut.clock.step()
      cycle += 1

      if (descriptorFire) {
        descriptorPending = false
        descriptorAccepted += 1
      }
      acceptedBurst.foreach { burst =>
        pendingBursts.enqueue(burst)
        bursts += burst
        nextBurstOrdinal += 1
      }
      if (rFire) {
        val burst = activeBurst.get
        readBeatNumber += 1
        if (activeBeat == burst.beats - 1) {
          activeBurst = None
          activeBeat = 0
        } else {
          activeBeat += 1
        }
      }
      if (activeBurst.isEmpty && pendingBursts.nonEmpty) {
        activeBurst = Some(pendingBursts.dequeue())
      }
    }

    withClue("read DMA timed out: ") { cycle should be < 5000 }
    descriptorAccepted shouldBe 1
    statusError should not be empty
    ReadResult(bursts.toSeq, outputBytes.toSeq, lastOffsets.toSeq, statusError.get)
  }

  private def runWrite(
    dut: LiteSdioAxiDmaHarness,
    address: BigInt,
    descriptorLength: Int,
    inputBytes: Seq[Int],
    responseCodes: Map[Int, Int] = Map.empty,
    responseDelay: Int = 0
  ): WriteResult = {
    val streamWords = inputBytes.grouped(4).map { bytes =>
      packBytes(bytes) -> ((1 << bytes.length) - 1)
    }.toIndexedSeq
    val pendingBursts = mutable.Queue.empty[Burst]
    val responses = mutable.Queue.empty[Response]
    val bursts = mutable.ArrayBuffer.empty[Burst]
    val beats = mutable.ArrayBuffer.empty[WriteBeat]
    val memory = mutable.Map.empty[BigInt, Int]
    var activeBurst = Option.empty[Burst]
    var activeBeat = 0
    var nextBurstOrdinal = 0
    var streamIndex = 0
    var descriptorPending = true
    var descriptorAccepted = 0
    var statusLength = Option.empty[Int]
    var statusError = Option.empty[Int]
    var statusCycle = -1
    var lastResponseCycle = -1
    var cycle = 0

    while (statusError.isEmpty && cycle < 5000) {
      dut.io.writeDescAddr.poke(address.U)
      dut.io.writeDescLen.poke(descriptorLength.U)
      dut.io.writeDescValid.poke(descriptorPending.B)

      val streamValid = streamIndex < streamWords.length && cycle % 5 != 1
      val (streamData, streamKeep) =
        if (streamIndex < streamWords.length) streamWords(streamIndex) else BigInt(0) -> 0
      dut.io.writeData.poke(streamData.U)
      dut.io.writeKeep.poke(streamKeep.U)
      dut.io.writeValid.poke(streamValid.B)
      dut.io.writeLast.poke((streamIndex == streamWords.length - 1).B)

      val awReady = cycle % 4 != 1
      val wReady = activeBurst.nonEmpty && cycle % 3 != 2
      val bValid = responses.headOption.exists(_.availableAt <= cycle) && cycle % 5 != 3
      dut.io.awready.poke(awReady.B)
      dut.io.wready.poke(wReady.B)
      dut.io.bid.poke(responses.headOption.map(_.id).getOrElse(BigInt(0)).U)
      dut.io.bresp.poke(responses.headOption.map(_.code).getOrElse(0).U)
      dut.io.bvalid.poke(bValid.B)

      val descriptorFire = descriptorPending && asserted(dut.io.writeDescReady)
      val streamFire = streamValid && asserted(dut.io.writeReady)
      val awFire = awReady && asserted(dut.io.awvalid)
      val wFire = wReady && asserted(dut.io.wvalid)
      val bFire = bValid && asserted(dut.io.bready)
      val acceptedBurst = if (awFire) {
        dut.io.awsize.expect(2.U)
        dut.io.awburst.expect(1.U)
        val burst = Burst(
          dut.io.awaddr.peek().litValue,
          dut.io.awlen.peek().litValue.toInt + 1,
          dut.io.awid.peek().litValue,
          nextBurstOrdinal
        )
        checkBurst(burst)
        Some(burst)
      } else None
      val acceptedBeat = if (wFire) {
        val burst = activeBurst.get
        dut.io.wlast.expect((activeBeat == burst.beats - 1).B)
        Some(WriteBeat(
          burst.address + activeBeat * 4,
          dut.io.wdata.peek().litValue,
          dut.io.wstrb.peek().litValue.toInt,
          asserted(dut.io.wlast)
        ))
      } else None

      if (asserted(dut.io.writeStatusValid)) {
        statusError shouldBe empty
        statusLength = Some(dut.io.writeStatusLen.peek().litValue.toInt)
        statusError = Some(dut.io.writeStatusError.peek().litValue.toInt)
        statusCycle = cycle
      }

      dut.clock.step()
      cycle += 1

      if (descriptorFire) {
        descriptorPending = false
        descriptorAccepted += 1
      }
      if (streamFire) {
        streamIndex += 1
      }
      acceptedBurst.foreach { burst =>
        pendingBursts.enqueue(burst)
        bursts += burst
        nextBurstOrdinal += 1
      }
      acceptedBeat.foreach { beat =>
        beats += beat
        for (lane <- 0 until 4 if (beat.strobe & (1 << lane)) != 0) {
          memory(beat.address + lane) = ((beat.data >> (lane * 8)) & 0xff).toInt
        }
        val burst = activeBurst.get
        if (activeBeat == burst.beats - 1) {
          responses.enqueue(Response(
            burst.id,
            responseCodes.getOrElse(burst.ordinal, 0),
            cycle + responseDelay
          ))
          activeBurst = None
          activeBeat = 0
        } else {
          activeBeat += 1
        }
      }
      if (bFire) {
        responses.dequeue()
        lastResponseCycle = cycle - 1
      }
      if (activeBurst.isEmpty && pendingBursts.nonEmpty) {
        activeBurst = Some(pendingBursts.dequeue())
      }
    }

    withClue("write DMA timed out: ") { cycle should be < 5000 }
    descriptorAccepted shouldBe 1
    streamIndex shouldBe streamWords.length
    statusLength should not be empty
    statusError should not be empty
    WriteResult(
      bursts.toSeq,
      beats.toSeq,
      memory.toMap,
      statusLength.get,
      statusError.get,
      statusCycle,
      lastResponseCycle
    )
  }

  it should "read 512 bytes across a 4 KiB boundary without changing byte order" in {
    test(new LiteSdioAxiDmaHarness).withAnnotations(annotations) { dut =>
      initialize(dut)
      val address = BigInt("0ff0", 16)
      val expected = (0 until 512).map(bytePattern)
      expected.take(4) should not equal expected.take(4).reverse
      val memory = expected.zipWithIndex.map { case (byte, index) => address + index -> byte }.toMap

      val result = runRead(dut, address, expected.length, memory)

      result.statusError shouldBe 0
      result.bytes shouldBe expected
      result.lastOffsets shouldBe Seq(expected.length)
      checkContiguous(result.bursts, address, expected.length)
      result.bursts.head.beats shouldBe 4
      result.bursts(1).address shouldBe BigInt("1000", 16)
    }
  }

  it should "write 512 bytes across a 4 KiB boundary without changing byte order" in {
    test(new LiteSdioAxiDmaHarness).withAnnotations(annotations) { dut =>
      initialize(dut)
      val address = BigInt("0ff0", 16)
      val expected = (0 until 512).map(index => bytePattern(index + 17))
      expected.take(4) should not equal expected.take(4).reverse

      val result = runWrite(dut, address, expected.length, expected)

      result.statusLength shouldBe expected.length
      result.statusError shouldBe 0
      checkContiguous(result.bursts, address, expected.length)
      result.bursts.head.beats shouldBe 4
      result.bursts(1).address shouldBe BigInt("1000", 16)
      result.beats.foreach(_.strobe shouldBe 0xf)
      expected.indices.foreach { index => result.memory(address + index) shouldBe expected(index) }
      result.statusCycle should be > result.lastResponseCycle
    }
  }

  it should "map a read DECERR response to DMA error code 5" in {
    test(new LiteSdioAxiDmaHarness).withAnnotations(annotations) { dut =>
      initialize(dut)
      val address = BigInt("3000", 16)
      val expected = (0 until 64).map(index => bytePattern(index + 31))
      val memory = expected.zipWithIndex.map { case (byte, index) => address + index -> byte }.toMap

      val result = runRead(dut, address, expected.length, memory, errorBeat = Some(3 -> 3))

      result.statusError shouldBe 5
      result.bytes shouldBe expected
    }
  }

  it should "map a write SLVERR response to DMA error code 6" in {
    test(new LiteSdioAxiDmaHarness).withAnnotations(annotations) { dut =>
      initialize(dut)
      val address = BigInt("4000", 16)
      val expected = (0 until 64).map(index => bytePattern(index + 47))

      val result = runWrite(dut, address, expected.length, expected, responseCodes = Map(0 -> 2))

      result.statusLength shouldBe expected.length
      result.statusError shouldBe 6
    }
  }

  it should "finish an early write frame with zero strobes and wait for its B response" in {
    test(new LiteSdioAxiDmaHarness).withAnnotations(annotations) { dut =>
      initialize(dut)
      val address = BigInt("5000", 16)
      val input = (0 until 12).map(index => bytePattern(index + 63))

      val result = runWrite(
        dut,
        address,
        descriptorLength = 64,
        inputBytes = input,
        responseDelay = 8
      )

      result.bursts should have size 1
      result.bursts.head.beats shouldBe 16
      result.beats should have size 16
      result.beats.take(3).foreach(_.strobe shouldBe 0xf)
      result.beats.drop(3).foreach { beat =>
        beat.strobe shouldBe 0
        beat.data shouldBe 0
      }
      result.beats.last.last shouldBe true
      result.statusLength shouldBe input.length
      result.statusError shouldBe 0
      result.statusCycle should be > result.lastResponseCycle
      input.indices.foreach { index => result.memory(address + index) shouldBe input(index) }
      (input.length until 64).foreach { index => result.memory.contains(address + index) shouldBe false }
    }
  }

  it should "preserve bit 20 of a 1 MiB descriptor in both DMA engines" in {
    test(new LiteSdioAxiDmaHarness).withAnnotations(annotations) { dut =>
      initialize(dut)
      val address = BigInt("2000", 16)
      val length = 1 << 20
      var readPending = true
      var writePending = true
      var cycles = 0

      dut.io.readDescAddr.poke(address.U)
      dut.io.readDescLen.poke(length.U)
      dut.io.writeDescAddr.poke(address.U)
      dut.io.writeDescLen.poke(length.U)
      dut.io.writeData.poke(BigInt("44332211", 16).U)
      dut.io.writeKeep.poke(0xf.U)
      dut.io.writeValid.poke(true.B)
      dut.io.writeLast.poke(false.B)

      while ((readPending || writePending) && cycles < 20) {
        dut.io.readDescValid.poke(readPending.B)
        dut.io.writeDescValid.poke(writePending.B)
        val readFire = readPending && asserted(dut.io.readDescReady)
        val writeFire = writePending && asserted(dut.io.writeDescReady)
        dut.clock.step()
        if (readFire) readPending = false
        if (writeFire) writePending = false
        cycles += 1
      }

      withClue("1 MiB descriptors were not accepted: ") {
        readPending shouldBe false
        writePending shouldBe false
      }
      dut.io.readDescValid.poke(false.B)
      dut.io.writeDescValid.poke(false.B)

      var readBurstChecked = false
      var writeBurstChecked = false
      while ((!readBurstChecked || !writeBurstChecked) && cycles < 80) {
        if (asserted(dut.io.arvalid)) {
          dut.io.araddr.expect(address.U)
          dut.io.arlen.expect(15.U)
          dut.io.arsize.expect(2.U)
          dut.io.arburst.expect(1.U)
          readBurstChecked = true
        }
        if (asserted(dut.io.awvalid)) {
          dut.io.awaddr.expect(address.U)
          dut.io.awlen.expect(15.U)
          dut.io.awsize.expect(2.U)
          dut.io.awburst.expect(1.U)
          writeBurstChecked = true
        }
        dut.clock.step()
        cycles += 1
      }

      withClue("1 MiB descriptors did not produce 16-beat first bursts: ") {
        readBurstChecked shouldBe true
        writeBurstChecked shouldBe true
      }
    }
  }
}
