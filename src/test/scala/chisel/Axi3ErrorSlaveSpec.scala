package chisel

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import chisel.axiSlaveMux.fallback.Axi3ErrorSlave
import org.scalatest.freespec.AnyFreeSpec

private object Axi3ErrorSlaveSpec {
  def initialize(dut: Axi3ErrorSlave): Unit = {
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
  }

  def reset(dut: Axi3ErrorSlave): Unit = {
    initialize(dut)
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.clock.step()
  }

  def driveAw(dut: Axi3ErrorSlave, id: Int, address: BigInt, length: Int): Unit = {
    dut.io.axi.awid.poke(id.U)
    dut.io.axi.awaddr.poke(address.U)
    dut.io.axi.awlen.poke(length.U)
    dut.io.axi.awsize.poke(2.U)
    dut.io.axi.awburst.poke(1.U)
    dut.io.axi.awlock.poke(0.U)
    dut.io.axi.awcache.poke(3.U)
    dut.io.axi.awprot.poke(2.U)
    dut.io.axi.awvalid.poke(true.B)
  }

  def driveW(dut: Axi3ErrorSlave, id: Int, data: BigInt, last: Boolean): Unit = {
    dut.io.axi.wid.poke(id.U)
    dut.io.axi.wdata.poke(data.U)
    dut.io.axi.wstrb.poke("hf".U)
    dut.io.axi.wlast.poke(last.B)
    dut.io.axi.wvalid.poke(true.B)
  }

  def driveAr(dut: Axi3ErrorSlave, id: Int, address: BigInt, length: Int): Unit = {
    dut.io.axi.arid.poke(id.U)
    dut.io.axi.araddr.poke(address.U)
    dut.io.axi.arlen.poke(length.U)
    dut.io.axi.arsize.poke(2.U)
    dut.io.axi.arburst.poke(1.U)
    dut.io.axi.arlock.poke(0.U)
    dut.io.axi.arcache.poke(5.U)
    dut.io.axi.arprot.poke(1.U)
    dut.io.axi.arvalid.poke(true.B)
  }

  def expectReadBeat(dut: Axi3ErrorSlave, id: Int, last: Boolean): Unit = {
    dut.io.axi.rvalid.expect(true.B)
    dut.io.axi.rid.expect(id.U)
    dut.io.axi.rdata.expect(0.U)
    dut.io.axi.rresp.expect(3.U)
    dut.io.axi.rlast.expect(last.B)
  }
}

class Axi3ErrorSlaveSpec extends AnyFreeSpec with ChiselScalatestTester {
  import Axi3ErrorSlaveSpec._

  private val annotations = Seq(VerilatorBackendAnnotation)

  "Axi3ErrorSlave should accept separated AW and W channels and hold DECERR under B backpressure" in {
    test(new Axi3ErrorSlave).withAnnotations(annotations) { dut =>
      reset(dut)

      driveAw(dut, id = 9, address = BigInt("1ff00040", 16), length = 2)
      dut.io.axi.awready.expect(true.B)
      dut.io.axi.wready.expect(true.B)
      dut.clock.step()
      dut.io.axi.awvalid.poke(false.B)

      dut.io.axi.awready.expect(false.B)
      dut.io.axi.wready.expect(true.B)
      dut.io.axi.bvalid.expect(false.B)

      for ((data, beat) <- Seq(
        BigInt("11112222", 16),
        BigInt("33334444", 16),
        BigInt("55556666", 16)
      ).zipWithIndex) {
        driveW(dut, id = 9, data = data, last = beat == 2)
        dut.io.axi.wready.expect(true.B)
        dut.io.axi.bvalid.expect(false.B)
        dut.clock.step()
        dut.io.axi.wvalid.poke(false.B)
        if (beat < 2) {
          dut.io.axi.wready.expect(true.B)
          dut.io.axi.bvalid.expect(false.B)
        }
      }

      dut.io.axi.bvalid.expect(true.B)
      dut.io.axi.bid.expect(9.U)
      dut.io.axi.bresp.expect(3.U)
      dut.io.axi.awready.expect(false.B)
      dut.io.axi.wready.expect(false.B)

      driveAw(dut, id = 3, address = BigInt("1ff00080", 16), length = 0)
      driveW(dut, id = 3, data = BigInt("deadbeef", 16), last = true)
      for (_ <- 0 until 2) {
        dut.io.axi.awready.expect(false.B)
        dut.io.axi.wready.expect(false.B)
        dut.io.axi.bvalid.expect(true.B)
        dut.io.axi.bid.expect(9.U)
        dut.io.axi.bresp.expect(3.U)
        dut.clock.step()
      }

      dut.io.axi.bready.poke(true.B)
      dut.clock.step()
      dut.io.axi.bvalid.expect(false.B)
      dut.io.axi.awready.expect(true.B)
      dut.io.axi.wready.expect(true.B)
      dut.io.axi.awvalid.poke(false.B)
      dut.io.axi.wvalid.poke(false.B)
      dut.io.axi.bready.poke(false.B)

      driveW(dut, id = 6, data = BigInt("cafef00d", 16), last = true)
      dut.io.axi.wready.expect(true.B)
      dut.clock.step()
      dut.io.axi.wvalid.poke(false.B)
      dut.io.axi.wready.expect(false.B)
      dut.io.axi.awready.expect(true.B)
      dut.io.axi.bvalid.expect(false.B)

      driveAw(dut, id = 6, address = BigInt("1ff000c0", 16), length = 0)
      dut.io.axi.awready.expect(true.B)
      dut.clock.step()
      dut.io.axi.awvalid.poke(false.B)
      dut.io.axi.bvalid.expect(true.B)
      dut.io.axi.bid.expect(6.U)
      dut.io.axi.bresp.expect(3.U)

      dut.io.axi.bready.poke(true.B)
      dut.clock.step()
      dut.io.axi.bvalid.expect(false.B)
    }
  }

  "Axi3ErrorSlave should return ARLEN plus one zero-data DECERR beats and preserve a stalled R beat" in {
    test(new Axi3ErrorSlave).withAnnotations(annotations) { dut =>
      reset(dut)

      driveAr(dut, id = 11, address = BigInt("1fe10020", 16), length = 3)
      dut.io.axi.arready.expect(true.B)
      dut.clock.step()
      dut.io.axi.arvalid.poke(false.B)

      expectReadBeat(dut, id = 11, last = false)
      dut.io.axi.arready.expect(false.B)

      driveAr(dut, id = 2, address = BigInt("1fe10080", 16), length = 0)
      for (_ <- 0 until 3) {
        dut.io.axi.arready.expect(false.B)
        expectReadBeat(dut, id = 11, last = false)
        dut.clock.step()
      }
      dut.io.axi.arvalid.poke(false.B)

      dut.io.axi.rready.poke(true.B)
      for (beat <- 0 until 4) {
        expectReadBeat(dut, id = 11, last = beat == 3)
        dut.clock.step()
      }

      dut.io.axi.rvalid.expect(false.B)
      dut.io.axi.arready.expect(true.B)
    }
  }

  "Axi3ErrorSlave should progress one read and one write transaction independently" in {
    test(new Axi3ErrorSlave).withAnnotations(annotations) { dut =>
      reset(dut)

      driveAw(dut, id = 4, address = BigInt("1ff00010", 16), length = 1)
      driveW(dut, id = 4, data = BigInt("01020304", 16), last = false)
      driveAr(dut, id = 13, address = BigInt("1fe10010", 16), length = 2)
      dut.io.axi.awready.expect(true.B)
      dut.io.axi.wready.expect(true.B)
      dut.io.axi.arready.expect(true.B)
      dut.clock.step()
      dut.io.axi.awvalid.poke(false.B)
      dut.io.axi.arvalid.poke(false.B)

      expectReadBeat(dut, id = 13, last = false)
      dut.io.axi.bvalid.expect(false.B)

      driveW(dut, id = 4, data = BigInt("05060708", 16), last = true)
      dut.io.axi.wready.expect(true.B)
      dut.clock.step()
      dut.io.axi.wvalid.poke(false.B)

      expectReadBeat(dut, id = 13, last = false)
      dut.io.axi.bvalid.expect(true.B)
      dut.io.axi.bid.expect(4.U)
      dut.io.axi.bresp.expect(3.U)

      dut.io.axi.rready.poke(true.B)
      dut.clock.step()
      expectReadBeat(dut, id = 13, last = false)
      dut.io.axi.bvalid.expect(true.B)

      dut.io.axi.bready.poke(true.B)
      dut.clock.step()
      expectReadBeat(dut, id = 13, last = true)
      dut.io.axi.bvalid.expect(false.B)

      dut.clock.step()
      dut.io.axi.rvalid.expect(false.B)
      dut.io.axi.arready.expect(true.B)
      dut.io.axi.awready.expect(true.B)
      dut.io.axi.wready.expect(true.B)
    }
  }
}
