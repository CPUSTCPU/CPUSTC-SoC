package tensorcore

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TensorWindowLoaderSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "TensorWindowLoader"

  private def configure(dut: TensorWindowLoader): Unit = {
    dut.io.start.poke(false.B)
    dut.io.inputBase.poke("h1000".U)
    dut.io.inputHeight.poke(4.U)
    dut.io.inputWidth.poke(5.U)
    dut.io.inputChannels.poke(3.U)
    dut.io.kernelWidth.poke(3.U)
    dut.io.sourceY.poke(0.S)
    dut.io.sourceX.poke((-1).S)
    dut.io.rowByteOffset.poke(0.S)
    dut.io.columnByteOffset.poke((-12).S)
    dut.io.inputPixelBytes.poke(12.U)
    dut.io.rowBaseWords.poke(9.U)
  }

  private def run(dut: TensorWindowLoader): Unit = {
    dut.io.start.poke(true.B)
    dut.clock.step()
    dut.io.start.poke(false.B)
    var cycles = 0
    while (!dut.io.done.peek().litToBoolean && cycles < 16) {
      dut.clock.step()
      cycles += 1
    }
    cycles should be < 16
  }

  it should "plan one contiguous NHWC DMA span with horizontal clipping" in {
    test(new TensorWindowLoader) { dut =>
      configure(dut)
      run(dut)

      dut.io.rowInBounds.expect(true.B)
      dut.io.localOffsetWords.expect(12.U)
      dut.io.leadingZeroWords.expect(3.U)
      dut.io.validWords.expect(6.U)
      dut.io.trailingZeroWords.expect(0.U)
      dut.io.rowWords.expect(9.U)
      dut.io.dmaAddress.expect("h1000".U)
    }
  }

  it should "zero-fill a vertically padded kernel row without DMA data" in {
    test(new TensorWindowLoader) { dut =>
      configure(dut)
      dut.io.sourceY.poke((-1).S)
      dut.io.rowByteOffset.poke((-60).S)
      dut.io.rowBaseWords.poke(0.U)
      run(dut)

      dut.io.rowInBounds.expect(false.B)
      dut.io.localOffsetWords.expect(9.U)
      dut.io.leadingZeroWords.expect(9.U)
      dut.io.validWords.expect(0.U)
      dut.io.trailingZeroWords.expect(0.U)
      dut.io.rowWords.expect(9.U)
    }
  }

  it should "clip the right edge and retain the NHWC channel run" in {
    test(new TensorWindowLoader) { dut =>
      configure(dut)
      dut.io.sourceY.poke(3.S)
      dut.io.sourceX.poke(3.S)
      dut.io.rowByteOffset.poke(180.S)
      dut.io.columnByteOffset.poke(36.S)
      dut.io.rowBaseWords.poke(18.U)
      run(dut)

      dut.io.rowInBounds.expect(true.B)
      dut.io.localOffsetWords.expect(18.U)
      dut.io.leadingZeroWords.expect(0.U)
      dut.io.validWords.expect(6.U)
      dut.io.trailingZeroWords.expect(3.U)
      dut.io.rowWords.expect(9.U)
      dut.io.dmaAddress.expect("h10d8".U)
    }
  }
}
