package chisel

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.freespec.AnyFreeSpec

private class DisplayApbMuxTestHarness extends Module {
  val io = IO(new Bundle {
    val upstream = Flipped(new APB3IO(addrWidth = 20))
    val vga = new APB3IO(addrWidth = 20)
    val gpu = new APB3IO(addrWidth = 20)
    val tensorCore = new APB3IO(addrWidth = 20)
    val dotMatrix = new APB3IO(addrWidth = 20)
  })

  private val mux = Module(new DisplayApbMux)
  io.upstream <> mux.io.upstream
  io.vga <> mux.io.vga
  io.gpu <> mux.io.gpu
  io.tensorCore <> mux.io.tensorCore
  io.dotMatrix <> mux.io.dotMatrix
}

private class DisplayApbMuxDotMatrixHarness extends Module {
  val io = IO(new Bundle {
    val upstream = Flipped(new APB3IO(addrWidth = 20))
    val tensorCore = new APB3IO(addrWidth = 20)
    val dotMatrixPsel = Output(Bool())
    val dotMatrixPenable = Output(Bool())
    val dotMatrixAddress = Output(UInt(20.W))
    val rows = Output(UInt(8.W))
    val columns = Output(UInt(8.W))
  })

  private val mux = Module(new DisplayApbMux)
  private val dotMatrix = Module(new DotMatrixController(defaultScanDivider = 32))

  io.upstream <> mux.io.upstream
  APB3IO.tieOffInputs(mux.io.vga)
  APB3IO.tieOffInputs(mux.io.gpu)
  io.tensorCore <> mux.io.tensorCore
  APB3IO.connectMasterToSlave(mux.io.dotMatrix, dotMatrix.io.apb)

  io.dotMatrixPsel := mux.io.dotMatrix.psel
  io.dotMatrixPenable := mux.io.dotMatrix.penable
  io.dotMatrixAddress := mux.io.dotMatrix.paddr
  io.rows := dotMatrix.io.rows
  io.columns := dotMatrix.io.columns
}

private object DisplayApbMuxSpec {
  final case class ApbResponse(
    data: BigInt,
    error: Boolean,
    setupError: Boolean,
    dotMatrixSetupSelected: Boolean,
    dotMatrixAccessSelected: Boolean,
    dotMatrixAccessEnabled: Boolean,
    dotMatrixSetupAddress: BigInt,
    dotMatrixAccessAddress: BigInt
  )

  final case class Route(name: String, base: Int, selectedPort: Int)

  val routes: Seq[Route] = Seq(
    Route("VGA", base = 0x000, selectedPort = 0),
    Route("GPU", base = 0x100, selectedPort = 1),
    Route("TensorCore", base = 0x200, selectedPort = 2),
    Route("dot matrix", base = 0x300, selectedPort = 3)
  )

  def ports(dut: DisplayApbMuxTestHarness): Seq[APB3IO] =
    Seq(dut.io.vga, dut.io.gpu, dut.io.tensorCore, dut.io.dotMatrix)

  def initialize(dut: DisplayApbMuxTestHarness): Unit = {
    dut.io.upstream.psel.poke(false.B)
    dut.io.upstream.penable.poke(false.B)
    dut.io.upstream.pwrite.poke(false.B)
    dut.io.upstream.paddr.poke(0.U)
    dut.io.upstream.pwdata.poke(0.U)

    for (port <- ports(dut)) {
      port.prdata.poke(0.U)
      port.pready.poke(false.B)
      port.pslverr.poke(false.B)
    }
  }

  def driveUpstream(
    dut: DisplayApbMuxTestHarness,
    address: Int,
    writeData: BigInt,
    penable: Boolean
  ): Unit = {
    dut.io.upstream.psel.poke(true.B)
    dut.io.upstream.penable.poke(penable.B)
    dut.io.upstream.pwrite.poke(true.B)
    dut.io.upstream.paddr.poke(address.U)
    dut.io.upstream.pwdata.poke(writeData.U)
  }

  def expectRoute(
    dut: DisplayApbMuxTestHarness,
    selectedPort: Int,
    address: Int,
    writeData: BigInt,
    penable: Boolean
  ): Unit = {
    for ((port, index) <- ports(dut).zipWithIndex) {
      port.psel.expect((index == selectedPort).B)
      port.penable.expect(penable.B)
      port.pwrite.expect(true.B)
      port.paddr.expect(address.U)
      port.pwdata.expect(writeData.U)
    }
  }

  def expectNoSelection(
    dut: DisplayApbMuxTestHarness,
    address: Int,
    writeData: BigInt,
    penable: Boolean
  ): Unit = {
    for (port <- ports(dut)) {
      port.psel.expect(false.B)
      port.penable.expect(penable.B)
      port.pwrite.expect(true.B)
      port.paddr.expect(address.U)
      port.pwdata.expect(writeData.U)
    }
  }

  final class IntegratedDriver(dut: DisplayApbMuxDotMatrixHarness) {
    def initialize(): Unit = {
      driveIdle()
      dut.io.tensorCore.prdata.poke(0.U)
      dut.io.tensorCore.pready.poke(false.B)
      dut.io.tensorCore.pslverr.poke(false.B)
    }

    def reset(): Unit = {
      initialize()
      dut.reset.poke(true.B)
      dut.clock.step(3)
      dut.reset.poke(false.B)
    }

    def apbWrite(address: Int, data: BigInt): ApbResponse =
      apbAccess(address, write = true, data)

    def apbRead(address: Int): ApbResponse =
      apbAccess(address, write = false, 0)

    def driveSetup(address: Int, write: Boolean, data: BigInt): Unit = {
      dut.io.upstream.psel.poke(true.B)
      dut.io.upstream.penable.poke(false.B)
      dut.io.upstream.pwrite.poke(write.B)
      dut.io.upstream.paddr.poke(address.U)
      dut.io.upstream.pwdata.poke(data.U)
    }

    def driveAccess(): Unit = dut.io.upstream.penable.poke(true.B)

    def idle(): Unit = driveIdle()

    private def apbAccess(address: Int, write: Boolean, data: BigInt): ApbResponse = {
      driveSetup(address, write, data)
      val setupError = dut.io.upstream.pslverr.peek().litToBoolean
      val dotMatrixSetupSelected = dut.io.dotMatrixPsel.peek().litToBoolean
      val dotMatrixSetupAddress = dut.io.dotMatrixAddress.peek().litValue
      dut.clock.step()

      driveAccess()
      dut.io.upstream.pready.expect(true.B)
      val response = ApbResponse(
        data = dut.io.upstream.prdata.peek().litValue,
        error = dut.io.upstream.pslverr.peek().litToBoolean,
        setupError = setupError,
        dotMatrixSetupSelected = dotMatrixSetupSelected,
        dotMatrixAccessSelected = dut.io.dotMatrixPsel.peek().litToBoolean,
        dotMatrixAccessEnabled = dut.io.dotMatrixPenable.peek().litToBoolean,
        dotMatrixSetupAddress = dotMatrixSetupAddress,
        dotMatrixAccessAddress = dut.io.dotMatrixAddress.peek().litValue
      )
      dut.clock.step()
      driveIdle()
      response
    }

    private def driveIdle(): Unit = {
      dut.io.upstream.psel.poke(false.B)
      dut.io.upstream.penable.poke(false.B)
      dut.io.upstream.pwrite.poke(false.B)
      dut.io.upstream.paddr.poke(0.U)
      dut.io.upstream.pwdata.poke(0.U)
    }
  }
}

class DisplayApbMuxSpec extends AnyFreeSpec with ChiselScalatestTester {
  import DisplayApbMuxSpec._

  private val annotations = Seq(VerilatorBackendAnnotation)

  "DisplayApbMux should route both ends of every valid 256-byte subwindow" in {
    test(new DisplayApbMuxTestHarness).withAnnotations(annotations) { dut =>
      initialize(dut)

      for ((route, routeIndex) <- routes.zipWithIndex; offset <- Seq(0x00, 0xff)) {
        val address = route.base + offset
        val writeData = BigInt("a5000000", 16) | BigInt(routeIndex << 8 | offset)
        val readData = BigInt("5a000000", 16) | BigInt(routeIndex << 8 | offset)

        for ((port, index) <- ports(dut).zipWithIndex) {
          port.prdata.poke((BigInt("d0000000", 16) | BigInt(index)).U)
          port.pready.poke(true.B)
          port.pslverr.poke(true.B)
        }
        val selected = ports(dut)(route.selectedPort)
        selected.prdata.poke(readData.U)
        selected.pready.poke(false.B)
        selected.pslverr.poke(false.B)

        driveUpstream(dut, address, writeData, penable = false)
        expectRoute(dut, route.selectedPort, address, writeData, penable = false)
        dut.io.upstream.prdata.expect(readData.U)
        dut.io.upstream.pready.expect(false.B)
        dut.io.upstream.pslverr.expect(false.B)

        driveUpstream(dut, address, writeData, penable = true)
        expectRoute(dut, route.selectedPort, address, writeData, penable = true)
        dut.io.upstream.prdata.expect(readData.U)
        dut.io.upstream.pready.expect(false.B)
        dut.io.upstream.pslverr.expect(false.B)

        selected.pready.poke(true.B)
        selected.pslverr.poke(true.B)
        dut.io.upstream.prdata.expect(readData.U)
        dut.io.upstream.pready.expect(true.B)
        dut.io.upstream.pslverr.expect(true.B)
      }
    }
  }

  "DisplayApbMux should immediately reject 0x400 and higher local subwindows" in {
    test(new DisplayApbMuxTestHarness).withAnnotations(annotations) { dut =>
      initialize(dut)

      for (port <- ports(dut)) {
        port.prdata.poke("hffffffff".U)
        port.pready.poke(false.B)
        port.pslverr.poke(false.B)
      }

      for (address <- Seq(0x400, 0x4ff, 0x500, 0x1f00, 0x1fff)) {
        val writeData = BigInt("cafe0000", 16) | BigInt(address)

        driveUpstream(dut, address, writeData, penable = false)
        expectNoSelection(dut, address, writeData, penable = false)
        dut.io.upstream.prdata.expect(0.U)
        dut.io.upstream.pready.expect(true.B)
        dut.io.upstream.pslverr.expect(false.B)

        driveUpstream(dut, address, writeData, penable = true)
        expectNoSelection(dut, address, writeData, penable = true)
        dut.io.upstream.prdata.expect(0.U)
        dut.io.upstream.pready.expect(true.B)
        dut.io.upstream.pslverr.expect(true.B)
      }
    }
  }

  "DisplayApbMux should isolate TensorCore 0x200 and complete real dot-matrix accesses at 0x300" in {
    test(new DisplayApbMuxDotMatrixHarness).withAnnotations(annotations) { dut =>
      val driver = new IntegratedDriver(dut)
      driver.reset()

      assert(DotMatrixRegisters.patternLow == 0x300)
      assert(DotMatrixRegisters.control == 0x308)
      dut.io.rows.expect(0.U)
      dut.io.columns.expect("hff".U)

      val tensorWriteData = BigInt("deadbeef", 16)
      val tensorReadData = BigInt("5a5ac3c3", 16)
      driver.driveSetup(address = 0x200, write = true, data = tensorWriteData)
      dut.io.tensorCore.psel.expect(true.B)
      dut.io.tensorCore.penable.expect(false.B)
      dut.io.tensorCore.paddr.expect("h200".U)
      dut.io.tensorCore.pwdata.expect(tensorWriteData.U)
      dut.io.dotMatrixPsel.expect(false.B)
      dut.io.upstream.pready.expect(false.B)
      dut.clock.step()

      driver.driveAccess()
      dut.io.tensorCore.psel.expect(true.B)
      dut.io.tensorCore.penable.expect(true.B)
      dut.io.dotMatrixPsel.expect(false.B)
      dut.io.upstream.pready.expect(false.B)
      dut.io.upstream.pslverr.expect(false.B)
      dut.clock.step()

      dut.io.tensorCore.prdata.poke(tensorReadData.U)
      dut.io.tensorCore.pready.poke(true.B)
      dut.io.tensorCore.pslverr.poke(true.B)
      dut.io.upstream.prdata.expect(tensorReadData.U)
      dut.io.upstream.pready.expect(true.B)
      dut.io.upstream.pslverr.expect(true.B)
      dut.clock.step()
      driver.idle()
      dut.io.tensorCore.pslverr.poke(false.B)

      val untouchedControl = driver.apbRead(0x308)
      assert(!untouchedControl.setupError && !untouchedControl.error)
      assert(untouchedControl.data == 0x0000ff04)
      val untouchedPattern = driver.apbRead(0x300)
      assert(!untouchedPattern.setupError && !untouchedPattern.error)
      assert(untouchedPattern.data == 0)

      val patternWrite = driver.apbWrite(0x300, 0x000000a5)
      assert(patternWrite.dotMatrixSetupSelected && patternWrite.dotMatrixAccessSelected)
      assert(patternWrite.dotMatrixAccessEnabled)
      assert(patternWrite.dotMatrixSetupAddress == 0x300)
      assert(patternWrite.dotMatrixAccessAddress == 0x300)
      assert(!patternWrite.setupError && !patternWrite.error)

      val controlWrite = driver.apbWrite(0x308, 0x0000ff05)
      assert(controlWrite.dotMatrixSetupSelected && controlWrite.dotMatrixAccessSelected)
      assert(controlWrite.dotMatrixAccessEnabled)
      assert(controlWrite.dotMatrixSetupAddress == 0x308)
      assert(controlWrite.dotMatrixAccessAddress == 0x308)
      assert(!controlWrite.setupError && !controlWrite.error)

      val patternRead = driver.apbRead(0x300)
      assert(patternRead.dotMatrixSetupSelected && patternRead.dotMatrixAccessSelected)
      assert(!patternRead.setupError && !patternRead.error)
      assert(patternRead.data == 0x000000a5)
      dut.io.rows.expect(0x01.U)
      dut.io.columns.expect(0x5a.U)
    }
  }

  "DisplayApbMux should preserve 0x2ff and 0x3ff boundaries and reject unmapped subwindows" in {
    test(new DisplayApbMuxDotMatrixHarness).withAnnotations(annotations) { dut =>
      val driver = new IntegratedDriver(dut)
      driver.reset()

      val tensorBoundaryData = BigInt("2468ace0", 16)
      dut.io.tensorCore.prdata.poke(tensorBoundaryData.U)
      dut.io.tensorCore.pready.poke(true.B)
      driver.driveSetup(address = 0x2ff, write = false, data = 0)
      dut.io.tensorCore.psel.expect(true.B)
      dut.io.tensorCore.paddr.expect("h2ff".U)
      dut.io.dotMatrixPsel.expect(false.B)
      dut.clock.step()
      driver.driveAccess()
      dut.io.upstream.pready.expect(true.B)
      dut.io.upstream.prdata.expect(tensorBoundaryData.U)
      dut.io.upstream.pslverr.expect(false.B)
      dut.io.dotMatrixPsel.expect(false.B)
      dut.clock.step()
      driver.idle()
      dut.io.tensorCore.pready.poke(false.B)

      val dotMatrixBoundary = driver.apbRead(0x3ff)
      assert(dotMatrixBoundary.dotMatrixSetupSelected && dotMatrixBoundary.dotMatrixAccessSelected)
      assert(!dotMatrixBoundary.setupError && dotMatrixBoundary.error)
      assert(dotMatrixBoundary.data == 0)

      for (address <- Seq(0x400, 0x1fff)) {
        val invalid = driver.apbRead(address)
        assert(!invalid.dotMatrixSetupSelected && !invalid.dotMatrixAccessSelected)
        assert(!invalid.setupError && invalid.error)
        assert(invalid.data == 0)
      }
    }
  }
}
