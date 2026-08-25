package chisel

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.freespec.AnyFreeSpec

private class AxiSlaveMuxTestHarness extends Module {
  val io = IO(new Bundle {
    val upstream = Flipped(new AXI3IO())
    val downstream = Vec(AxiSlaveMuxAddressMap.outputCount, new AXI3IO())
  })

  private val mux = Module(new AxiSlaveMux(outstandingDepth = 2))
  mux.io.clk := clock
  mux.io.resetn := !reset.asBool
  mux.io.spiBoot := false.B

  io.upstream <> mux.io.axiSlave
  io.downstream <> mux.io.axiMasters
}

private object AxiSlaveMuxSpec {
  final case class Route(name: String, address: BigInt, output: Int)

  val routes: Seq[Route] = Seq(
    Route("DDR default", BigInt("80000000", 16), AxiSlaveMuxAddressMap.ddr),
    Route("SPI flash", BigInt("1c000000", 16), AxiSlaveMuxAddressMap.spiFlash),
    Route("APB", BigInt("1fe00000", 16), AxiSlaveMuxAddressMap.apb),
    Route("confreg", BigInt("1fd00000", 16), AxiSlaveMuxAddressMap.confreg),
    Route("Ethernet", BigInt("1ff00000", 16), AxiSlaveMuxAddressMap.ethernet),
    Route("SDIO", BigInt("1fe10000", 16), AxiSlaveMuxAddressMap.sdio)
  )

  def initialize(dut: AxiSlaveMuxTestHarness): Unit = {
    val upstream = dut.io.upstream
    upstream.awid.poke(0.U)
    upstream.awaddr.poke(0.U)
    upstream.awlen.poke(0.U)
    upstream.awsize.poke(2.U)
    upstream.awburst.poke(1.U)
    upstream.awlock.poke(0.U)
    upstream.awcache.poke(0.U)
    upstream.awprot.poke(0.U)
    upstream.awvalid.poke(false.B)
    upstream.wid.poke(0.U)
    upstream.wdata.poke(0.U)
    upstream.wstrb.poke(0.U)
    upstream.wlast.poke(false.B)
    upstream.wvalid.poke(false.B)
    upstream.bready.poke(false.B)
    upstream.arid.poke(0.U)
    upstream.araddr.poke(0.U)
    upstream.arlen.poke(0.U)
    upstream.arsize.poke(2.U)
    upstream.arburst.poke(1.U)
    upstream.arlock.poke(0.U)
    upstream.arcache.poke(0.U)
    upstream.arprot.poke(0.U)
    upstream.arvalid.poke(false.B)
    upstream.rready.poke(false.B)

    for (port <- dut.io.downstream) {
      port.awready.poke(false.B)
      port.wready.poke(false.B)
      port.bid.poke(0.U)
      port.bresp.poke(0.U)
      port.bvalid.poke(false.B)
      port.arready.poke(false.B)
      port.rid.poke(0.U)
      port.rdata.poke(0.U)
      port.rresp.poke(0.U)
      port.rlast.poke(false.B)
      port.rvalid.poke(false.B)
    }
  }

  def reset(dut: AxiSlaveMuxTestHarness): Unit = {
    initialize(dut)
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.clock.step()
  }

  def expectOnlyAw(dut: AxiSlaveMuxTestHarness, selected: Int, valid: Boolean): Unit = {
    for ((port, index) <- dut.io.downstream.zipWithIndex) {
      port.awvalid.expect((valid && index == selected).B)
    }
  }

  def expectOnlyW(dut: AxiSlaveMuxTestHarness, selected: Int, valid: Boolean): Unit = {
    for ((port, index) <- dut.io.downstream.zipWithIndex) {
      port.wvalid.expect((valid && index == selected).B)
    }
  }

  def expectOnlyAr(dut: AxiSlaveMuxTestHarness, selected: Int, valid: Boolean): Unit = {
    for ((port, index) <- dut.io.downstream.zipWithIndex) {
      port.arvalid.expect((valid && index == selected).B)
    }
  }

  def driveAw(
    dut: AxiSlaveMuxTestHarness,
    id: Int,
    address: BigInt,
    length: Int = 0
  ): Unit = {
    val upstream = dut.io.upstream
    upstream.awid.poke(id.U)
    upstream.awaddr.poke(address.U)
    upstream.awlen.poke(length.U)
    upstream.awsize.poke(2.U)
    upstream.awburst.poke(1.U)
    upstream.awlock.poke(0.U)
    upstream.awcache.poke(3.U)
    upstream.awprot.poke(2.U)
    upstream.awvalid.poke(true.B)
  }

  def driveW(
    dut: AxiSlaveMuxTestHarness,
    id: Int,
    data: BigInt,
    last: Boolean
  ): Unit = {
    val upstream = dut.io.upstream
    upstream.wid.poke(id.U)
    upstream.wdata.poke(data.U)
    upstream.wstrb.poke("hf".U)
    upstream.wlast.poke(last.B)
    upstream.wvalid.poke(true.B)
  }

  def driveAr(
    dut: AxiSlaveMuxTestHarness,
    id: Int,
    address: BigInt,
    length: Int
  ): Unit = {
    val upstream = dut.io.upstream
    upstream.arid.poke(id.U)
    upstream.araddr.poke(address.U)
    upstream.arlen.poke(length.U)
    upstream.arsize.poke(2.U)
    upstream.arburst.poke(1.U)
    upstream.arlock.poke(0.U)
    upstream.arcache.poke(5.U)
    upstream.arprot.poke(1.U)
    upstream.arvalid.poke(true.B)
  }
}

class AxiSlaveMuxSpec extends AnyFreeSpec with ChiselScalatestTester {
  import AxiSlaveMuxSpec._

  private val annotations = Seq(VerilatorBackendAnnotation)

  "AxiSlaveMux should decode all six outputs including SDIO and DDR default" in {
    test(new AxiSlaveMuxTestHarness).withAnnotations(annotations) { dut =>
      reset(dut)
      for (port <- dut.io.downstream) {
        port.awready.poke(true.B)
        port.wready.poke(true.B)
      }

      for ((route, index) <- routes.zipWithIndex) {
        driveAw(dut, id = index, address = route.address)
        dut.io.upstream.awready.expect(true.B, route.name)
        expectOnlyAw(dut, route.output, valid = true)
        dut.io.downstream(route.output).awid.expect(index.U)
        dut.io.downstream(route.output).awaddr.expect(route.address.U)
        dut.clock.step()
        dut.io.upstream.awvalid.poke(false.B)

        val data = BigInt("a5000000", 16) | BigInt(index)
        driveW(dut, id = index, data = data, last = true)
        dut.io.upstream.wready.expect(true.B, route.name)
        expectOnlyW(dut, route.output, valid = true)
        dut.io.downstream(route.output).wdata.expect(data.U)
        dut.io.downstream(route.output).wlast.expect(true.B)
        dut.clock.step()
        dut.io.upstream.wvalid.poke(false.B)
      }

      // Exercise the second SPI window and both inclusive ends of the SDIO window.
      for (address <- Seq(BigInt("1fe8ffff", 16), BigInt("1fe10000", 16), BigInt("1fe1ffff", 16))) {
        val output = if (address == BigInt("1fe8ffff", 16)) {
          AxiSlaveMuxAddressMap.spiFlash
        } else {
          AxiSlaveMuxAddressMap.sdio
        }
        driveAw(dut, id = 7, address = address)
        dut.io.upstream.awready.expect(true.B)
        expectOnlyAw(dut, output, valid = true)
        dut.clock.step()
        dut.io.upstream.awvalid.poke(false.B)
        driveW(dut, id = 7, data = address, last = true)
        expectOnlyW(dut, output, valid = true)
        dut.clock.step()
        dut.io.upstream.wvalid.poke(false.B)
      }
    }
  }

  "AxiSlaveMux should preserve AW order and W payload under route and slave backpressure" in {
    test(new AxiSlaveMuxTestHarness).withAnnotations(annotations) { dut =>
      reset(dut)
      for (port <- dut.io.downstream) {
        port.awready.poke(true.B)
        port.wready.poke(true.B)
      }

      val sdio = AxiSlaveMuxAddressMap.sdio
      val ddr = AxiSlaveMuxAddressMap.ddr
      val apb = AxiSlaveMuxAddressMap.apb

      dut.io.downstream(sdio).awready.poke(false.B)
      driveAw(dut, id = 1, address = BigInt("1fe10020", 16), length = 1)
      dut.io.upstream.awready.expect(false.B)
      expectOnlyAw(dut, sdio, valid = true)
      dut.clock.step(2)
      dut.io.downstream(sdio).awready.poke(true.B)
      dut.io.upstream.awready.expect(true.B)
      dut.clock.step()
      dut.io.upstream.awvalid.poke(false.B)

      driveAw(dut, id = 2, address = BigInt("40001000", 16))
      dut.io.upstream.awready.expect(true.B)
      expectOnlyAw(dut, ddr, valid = true)
      dut.clock.step()

      // Both route entries are occupied. This third AW must remain asserted and stable.
      driveAw(dut, id = 3, address = BigInt("1fe00040", 16))
      dut.io.upstream.awready.expect(false.B)
      expectOnlyAw(dut, apb, valid = false)

      dut.io.downstream(sdio).wready.poke(false.B)
      val firstData = BigInt("11112222", 16)
      driveW(dut, id = 1, data = firstData, last = false)
      dut.io.upstream.wready.expect(false.B)
      expectOnlyW(dut, sdio, valid = true)
      dut.io.downstream(sdio).wdata.expect(firstData.U)
      dut.clock.step(2)
      dut.io.downstream(sdio).wready.poke(true.B)
      dut.io.upstream.wready.expect(true.B)
      dut.clock.step()

      val secondData = BigInt("33334444", 16)
      driveW(dut, id = 1, data = secondData, last = true)
      expectOnlyW(dut, sdio, valid = true)
      dut.clock.step()
      dut.io.upstream.wvalid.poke(false.B)

      // The held third AW is accepted only after the first write route retires.
      dut.io.upstream.awready.expect(true.B)
      expectOnlyAw(dut, apb, valid = true)
      dut.io.downstream(apb).awaddr.expect(BigInt("1fe00040", 16).U)
      dut.clock.step()
      dut.io.upstream.awvalid.poke(false.B)

      driveW(dut, id = 2, data = BigInt("55556666", 16), last = true)
      expectOnlyW(dut, ddr, valid = true)
      dut.clock.step()
      dut.io.upstream.wvalid.poke(false.B)

      dut.io.downstream(apb).wready.poke(false.B)
      val finalData = BigInt("77778888", 16)
      driveW(dut, id = 3, data = finalData, last = true)
      dut.io.upstream.wready.expect(false.B)
      expectOnlyW(dut, apb, valid = true)
      dut.io.downstream(apb).wdata.expect(finalData.U)
      dut.clock.step(2)
      dut.io.downstream(apb).wready.poke(true.B)
      dut.io.upstream.wready.expect(true.B)
      dut.clock.step()
      dut.io.upstream.wvalid.poke(false.B)
    }
  }

  "AxiSlaveMux should return multi-beat reads in AR order" in {
    test(new AxiSlaveMuxTestHarness).withAnnotations(annotations) { dut =>
      reset(dut)
      for (port <- dut.io.downstream) {
        port.arready.poke(true.B)
      }

      val sdio = AxiSlaveMuxAddressMap.sdio
      val ddr = AxiSlaveMuxAddressMap.ddr
      dut.io.downstream(sdio).arready.poke(false.B)
      driveAr(dut, id = 4, address = BigInt("1fe10010", 16), length = 1)
      dut.io.upstream.arready.expect(false.B)
      expectOnlyAr(dut, sdio, valid = true)
      dut.clock.step(2)
      dut.io.downstream(sdio).arready.poke(true.B)
      dut.io.upstream.arready.expect(true.B)
      dut.clock.step()
      dut.io.upstream.arvalid.poke(false.B)

      driveAr(dut, id = 5, address = BigInt("80000100", 16), length = 0)
      dut.io.upstream.arready.expect(true.B)
      expectOnlyAr(dut, ddr, valid = true)
      dut.clock.step()
      dut.io.upstream.arvalid.poke(false.B)

      // DDR responds early and must remain backpressured until the SDIO burst ends.
      dut.io.downstream(ddr).rid.poke(5.U)
      dut.io.downstream(ddr).rdata.poke("hdddd0001".U)
      dut.io.downstream(ddr).rresp.poke(0.U)
      dut.io.downstream(ddr).rlast.poke(true.B)
      dut.io.downstream(ddr).rvalid.poke(true.B)

      dut.io.downstream(sdio).rid.poke(4.U)
      dut.io.downstream(sdio).rdata.poke("h51000001".U)
      dut.io.downstream(sdio).rresp.poke(0.U)
      dut.io.downstream(sdio).rlast.poke(false.B)
      dut.io.downstream(sdio).rvalid.poke(true.B)
      dut.io.upstream.rready.poke(false.B)

      dut.io.upstream.rvalid.expect(true.B)
      dut.io.upstream.rid.expect(4.U)
      dut.io.upstream.rdata.expect("h51000001".U)
      dut.io.upstream.rlast.expect(false.B)
      dut.io.downstream(sdio).rready.expect(false.B)
      dut.io.downstream(ddr).rready.expect(false.B)
      dut.clock.step(2)

      dut.io.upstream.rready.poke(true.B)
      dut.io.downstream(sdio).rready.expect(true.B)
      dut.io.downstream(ddr).rready.expect(false.B)
      dut.clock.step()

      dut.io.downstream(sdio).rdata.poke("h51000002".U)
      dut.io.downstream(sdio).rlast.poke(true.B)
      dut.io.upstream.rready.poke(false.B)
      dut.io.upstream.rdata.expect("h51000002".U)
      dut.io.upstream.rlast.expect(true.B)
      dut.clock.step(2)
      dut.io.upstream.rready.poke(true.B)
      dut.clock.step()
      dut.io.downstream(sdio).rvalid.poke(false.B)

      dut.io.upstream.rvalid.expect(true.B)
      dut.io.upstream.rid.expect(5.U)
      dut.io.upstream.rdata.expect("hdddd0001".U)
      dut.io.upstream.rlast.expect(true.B)
      dut.io.downstream(ddr).rready.expect(true.B)
      dut.clock.step()
      dut.io.downstream(ddr).rvalid.poke(false.B)
      dut.io.upstream.rready.poke(false.B)
      dut.io.upstream.rvalid.expect(false.B)
    }
  }

  "AxiSlaveMux should hold an arbitrated B response stable while CPU bready is low" in {
    test(new AxiSlaveMuxTestHarness).withAnnotations(annotations) { dut =>
      reset(dut)
      val contenders = Seq(AxiSlaveMuxAddressMap.spiFlash, AxiSlaveMuxAddressMap.ethernet)
      val ids = Map(contenders.head -> 6, contenders.last -> 11)
      val responses = Map(contenders.head -> 1, contenders.last -> 2)

      for (index <- contenders) {
        val port = dut.io.downstream(index)
        port.bid.poke(ids(index).U)
        port.bresp.poke(responses(index).U)
        port.bvalid.poke(true.B)
      }
      dut.io.upstream.bready.poke(false.B)

      val granted = contenders.filter(index => dut.io.downstream(index).bready.peek().litToBoolean)
      assert(granted.size == 1, s"expected exactly one B grant, got $granted")
      val first = granted.head
      val second = contenders.find(_ != first).get
      dut.clock.step()
      dut.io.downstream(first).bvalid.poke(false.B)

      dut.io.upstream.bvalid.expect(true.B)
      dut.io.upstream.bid.expect(ids(first).U)
      dut.io.upstream.bresp.expect(responses(first).U)
      for (_ <- 0 until 3) {
        dut.io.upstream.bvalid.expect(true.B)
        dut.io.upstream.bid.expect(ids(first).U)
        dut.io.upstream.bresp.expect(responses(first).U)
        dut.io.downstream(second).bready.expect(false.B)
        dut.clock.step()
      }

      dut.io.upstream.bready.poke(true.B)
      dut.clock.step()
      dut.io.upstream.bready.poke(false.B)
      dut.io.upstream.bvalid.expect(false.B)
      dut.io.downstream(second).bready.expect(true.B)
      dut.clock.step()
      dut.io.downstream(second).bvalid.poke(false.B)

      dut.io.upstream.bvalid.expect(true.B)
      dut.io.upstream.bid.expect(ids(second).U)
      dut.io.upstream.bresp.expect(responses(second).U)
      dut.clock.step(2)
      dut.io.upstream.bid.expect(ids(second).U)
      dut.io.upstream.bresp.expect(responses(second).U)
      dut.io.upstream.bready.poke(true.B)
      dut.clock.step()
    }
  }
}
