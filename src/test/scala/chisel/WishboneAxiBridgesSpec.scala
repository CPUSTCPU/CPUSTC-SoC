package chisel

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.freespec.AnyFreeSpec

private object WishboneAxiBridgesSpec {
  def initializeControl(dut: Axi3ToWishboneControlBridge): Unit = {
    dut.io.axi.awid.poke(0.U)
    dut.io.axi.awaddr.poke(0.U)
    dut.io.axi.awlen.poke(0.U)
    dut.io.axi.awsize.poke(2.U)
    dut.io.axi.awburst.poke(1.U)
    dut.io.axi.awlock.poke(0.U)
    dut.io.axi.awcache.poke(0.U)
    dut.io.axi.awprot.poke(0.U)
    dut.io.axi.awvalid.poke(false.B)
    dut.io.axi.wid.poke(0.U)
    dut.io.axi.wdata.poke(0.U)
    dut.io.axi.wstrb.poke(0.U)
    dut.io.axi.wlast.poke(false.B)
    dut.io.axi.wvalid.poke(false.B)
    dut.io.axi.bready.poke(false.B)
    dut.io.axi.arid.poke(0.U)
    dut.io.axi.araddr.poke(0.U)
    dut.io.axi.arlen.poke(0.U)
    dut.io.axi.arsize.poke(2.U)
    dut.io.axi.arburst.poke(1.U)
    dut.io.axi.arlock.poke(0.U)
    dut.io.axi.arcache.poke(0.U)
    dut.io.axi.arprot.poke(0.U)
    dut.io.axi.arvalid.poke(false.B)
    dut.io.axi.rready.poke(false.B)
    dut.io.wishbone.datR.poke(0.U)
    dut.io.wishbone.ack.poke(false.B)
    dut.io.wishbone.err.poke(false.B)
  }

  def initializeDma(dut: WishboneToAxi4DmaBridge): Unit = {
    dut.io.wishbone.adr.poke(0.U)
    dut.io.wishbone.datW.poke(0.U)
    dut.io.wishbone.sel.poke(0.U)
    dut.io.wishbone.cyc.poke(false.B)
    dut.io.wishbone.stb.poke(false.B)
    dut.io.wishbone.we.poke(false.B)
    dut.io.wishbone.cti.poke(0.U)
    dut.io.wishbone.bte.poke(0.U)
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
  }

  def resetControl(dut: Axi3ToWishboneControlBridge): Unit = {
    initializeControl(dut)
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.clock.step()
  }

  def resetDma(dut: WishboneToAxi4DmaBridge): Unit = {
    initializeDma(dut)
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.clock.step()
  }
}

class WishboneAxiBridgesSpec extends AnyFreeSpec with ChiselScalatestTester {
  import WishboneAxiBridgesSpec._

  private val annotations = Seq(VerilatorBackendAnnotation)

  "AXI3 control bridge should map the local CSR address and preserve backpressure" in {
    test(new Axi3ToWishboneControlBridge).withAnnotations(annotations) { dut =>
      resetControl(dut)

      dut.io.axi.awid.poke(5.U)
      dut.io.axi.awaddr.poke("h1fe10848".U)
      dut.io.axi.awvalid.poke(true.B)
      dut.io.axi.awready.expect(true.B)
      dut.clock.step()
      dut.io.axi.awvalid.poke(false.B)

      dut.io.axi.wid.poke(5.U)
      dut.io.axi.wdata.poke("h89abcdef".U)
      dut.io.axi.wstrb.poke("hf".U)
      dut.io.axi.wlast.poke(true.B)
      dut.io.axi.wvalid.poke(true.B)
      dut.io.axi.wready.expect(true.B)
      dut.clock.step()
      dut.io.axi.wvalid.poke(false.B)

      dut.io.wishbone.cyc.expect(true.B)
      dut.io.wishbone.stb.expect(true.B)
      dut.io.wishbone.we.expect(true.B)
      dut.io.wishbone.adr.expect("h212".U)
      dut.io.wishbone.datW.expect("h89abcdef".U)
      dut.io.wishbone.sel.expect("hf".U)
      dut.clock.step(2)
      dut.io.axi.bvalid.expect(false.B)

      dut.io.wishbone.ack.poke(true.B)
      dut.clock.step()
      dut.io.wishbone.ack.poke(false.B)
      dut.io.wishbone.cyc.expect(false.B)
      dut.io.axi.bvalid.expect(true.B)
      dut.io.axi.bid.expect(5.U)
      dut.io.axi.bresp.expect(0.U)
      dut.clock.step(2)
      dut.io.axi.bvalid.expect(true.B)

      dut.io.axi.bready.poke(true.B)
      dut.clock.step()
      dut.io.axi.bready.poke(false.B)
      dut.io.axi.bvalid.expect(false.B)

      dut.io.axi.arid.poke(9.U)
      dut.io.axi.araddr.poke("h1fe1081c".U)
      dut.io.axi.arvalid.poke(true.B)
      dut.io.axi.arready.expect(true.B)
      dut.clock.step()
      dut.io.axi.arvalid.poke(false.B)
      dut.io.wishbone.cyc.expect(true.B)
      dut.io.wishbone.we.expect(false.B)
      dut.io.wishbone.adr.expect("h207".U)

      dut.io.wishbone.datR.poke("h01234567".U)
      dut.io.wishbone.ack.poke(true.B)
      dut.clock.step()
      dut.io.wishbone.ack.poke(false.B)
      dut.io.axi.rvalid.expect(true.B)
      dut.io.axi.rid.expect(9.U)
      dut.io.axi.rdata.expect("h01234567".U)
      dut.io.axi.rresp.expect(0.U)
      dut.io.axi.rlast.expect(true.B)
      dut.clock.step(2)
      dut.io.axi.rvalid.expect(true.B)

      dut.io.axi.rready.poke(true.B)
      dut.clock.step()
      dut.io.axi.rready.poke(false.B)
      dut.io.axi.rvalid.expect(false.B)
    }
  }

  "AXI3 control bridge should reject bursts without issuing Wishbone cycles" in {
    test(new Axi3ToWishboneControlBridge).withAnnotations(annotations) { dut =>
      resetControl(dut)

      dut.io.axi.arid.poke(3.U)
      dut.io.axi.araddr.poke("h1fe10800".U)
      dut.io.axi.arlen.poke(2.U)
      dut.io.axi.arvalid.poke(true.B)
      dut.clock.step()
      dut.io.axi.arvalid.poke(false.B)
      dut.io.axi.rready.poke(true.B)
      for (beat <- 0 until 3) {
        dut.io.wishbone.cyc.expect(false.B)
        dut.io.axi.rvalid.expect(true.B)
        dut.io.axi.rid.expect(3.U)
        dut.io.axi.rresp.expect(3.U)
        dut.io.axi.rlast.expect((beat == 2).B)
        dut.clock.step()
      }
      dut.io.axi.rready.poke(false.B)

      dut.io.axi.awid.poke(7.U)
      dut.io.axi.awaddr.poke("h1fe10820".U)
      dut.io.axi.awlen.poke(1.U)
      dut.io.axi.awvalid.poke(true.B)
      dut.clock.step()
      dut.io.axi.awvalid.poke(false.B)
      for (beat <- 0 until 2) {
        dut.io.axi.wid.poke(7.U)
        dut.io.axi.wdata.poke((beat + 1).U)
        dut.io.axi.wstrb.poke("hf".U)
        dut.io.axi.wlast.poke((beat == 1).B)
        dut.io.axi.wvalid.poke(true.B)
        dut.clock.step()
      }
      dut.io.axi.wvalid.poke(false.B)
      dut.io.wishbone.cyc.expect(false.B)
      dut.io.axi.bvalid.expect(true.B)
      dut.io.axi.bid.expect(7.U)
      dut.io.axi.bresp.expect(3.U)
    }
  }

  "Wishbone DMA bridge should independently handshake AXI write address and data" in {
    test(new WishboneToAxi4DmaBridge).withAnnotations(annotations) { dut =>
      resetDma(dut)

      dut.io.wishbone.adr.poke("h12345".U)
      dut.io.wishbone.datW.poke("hdeadbeef".U)
      dut.io.wishbone.sel.poke("hb".U)
      dut.io.wishbone.we.poke(true.B)
      dut.io.wishbone.cyc.poke(true.B)
      dut.io.wishbone.stb.poke(true.B)
      dut.clock.step()

      dut.io.axi.awaddr.expect("h00048d14".U)
      dut.io.axi.awlen.expect(0.U)
      dut.io.axi.awsize.expect(2.U)
      dut.io.axi.awvalid.expect(true.B)
      dut.io.axi.wdata.expect("hdeadbeef".U)
      dut.io.axi.wstrb.expect("hb".U)
      dut.io.axi.wlast.expect(true.B)
      dut.io.axi.wvalid.expect(true.B)

      dut.io.axi.awready.poke(true.B)
      dut.clock.step()
      dut.io.axi.awready.poke(false.B)
      dut.io.axi.awvalid.expect(false.B)
      dut.io.axi.wvalid.expect(true.B)
      dut.io.wishbone.ack.expect(false.B)

      dut.io.axi.wready.poke(true.B)
      dut.clock.step()
      dut.io.axi.wready.poke(false.B)
      dut.io.axi.bready.expect(true.B)
      dut.io.axi.bresp.poke(0.U)
      dut.io.axi.bvalid.poke(true.B)
      dut.io.wishbone.ack.expect(true.B)
      dut.io.wishbone.err.expect(false.B)
      dut.clock.step()
      dut.io.axi.bvalid.poke(false.B)
      dut.io.wishbone.cyc.poke(false.B)
      dut.io.wishbone.stb.poke(false.B)
      dut.io.wishbone.ack.expect(false.B)
    }
  }

  "Wishbone DMA bridge should return read data and propagate AXI errors" in {
    test(new WishboneToAxi4DmaBridge).withAnnotations(annotations) { dut =>
      resetDma(dut)

      dut.io.wishbone.adr.poke("h0010203".U)
      dut.io.wishbone.we.poke(false.B)
      dut.io.wishbone.cyc.poke(true.B)
      dut.io.wishbone.stb.poke(true.B)
      dut.clock.step()
      dut.io.axi.araddr.expect("h004080c".U)
      dut.io.axi.arvalid.expect(true.B)

      dut.io.axi.arready.poke(true.B)
      dut.clock.step()
      dut.io.axi.arready.poke(false.B)
      dut.io.axi.rready.expect(true.B)
      dut.io.axi.rdata.poke("hcafef00d".U)
      dut.io.axi.rlast.poke(true.B)
      dut.io.axi.rresp.poke(2.U)
      dut.io.axi.rvalid.poke(true.B)
      dut.io.wishbone.datR.expect("hcafef00d".U)
      dut.io.wishbone.ack.expect(false.B)
      dut.io.wishbone.err.expect(true.B)
      dut.clock.step()
      dut.io.axi.rvalid.poke(false.B)
      dut.io.wishbone.cyc.poke(false.B)
      dut.io.wishbone.stb.poke(false.B)
      dut.io.wishbone.err.expect(false.B)
    }
  }
}
