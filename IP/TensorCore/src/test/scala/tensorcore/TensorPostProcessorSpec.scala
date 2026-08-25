package tensorcore

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TensorPostProcessorSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "TensorPostProcessor"

  private def fp32(value: Float): BigInt =
    BigInt(java.lang.Float.floatToRawIntBits(value).toLong & 0xffffffffL)

  private def run(
    dut: TensorPostProcessor,
    value: Float,
    alpha: Float,
    accumulate: Boolean,
    accumulator: Float
  ): BigInt = {
    dut.io.value.poke(fp32(value).U)
    dut.io.alpha.poke(fp32(alpha).U)
    dut.io.preluEnable.poke(true.B)
    dut.io.accumulateMax.poke(accumulate.B)
    dut.io.accumulator.poke(fp32(accumulator).U)
    dut.io.roundMode.poke(0.U)
    dut.io.start.poke(true.B)
    dut.clock.step()
    dut.io.start.poke(false.B)
    var cycles = 0
    while (!dut.io.done.peek().litToBoolean && cycles < 16) {
      dut.clock.step()
      cycles += 1
    }
    cycles should be < 16
    dut.io.result.peek().litValue
  }

  it should "apply alpha only to negative values" in {
    test(new TensorPostProcessor) { dut =>
      dut.io.start.poke(false.B)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      run(dut, -4.0f, 0.25f, accumulate = false, 0.0f) shouldBe fp32(-1.0f)
      dut.clock.step()
      run(dut, 3.0f, 0.25f, accumulate = false, 0.0f) shouldBe fp32(3.0f)
    }
  }

  it should "max-accumulate post-PReLU values" in {
    test(new TensorPostProcessor) { dut =>
      dut.io.start.poke(false.B)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      run(dut, -4.0f, 0.25f, accumulate = true, -0.5f) shouldBe fp32(-0.5f)
      dut.clock.step()
      run(dut, 2.0f, 0.25f, accumulate = true, 1.5f) shouldBe fp32(2.0f)
    }
  }
}
