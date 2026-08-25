package cpustc.interrupt

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import spinal.lib.bus.amba3.apb.sim.Apb3Driver

class CascadedInterruptCtrlSpec extends AnyFunSuite {
  private val width = 8
  private val pendingAddress = BigInt(0x0)
  private val maskAddress = BigInt(0x4)
  private val implementedBits = (BigInt(1) << width) - 1

  private lazy val compiled = SimConfig.withVerilator
    .workspacePath("/tmp/cpustc-interrupt-sim")
    .workspaceName("CascadedInterruptCtrlSpec")
    .compile(CascadedInterruptCtrl(width))

  private def initialize(dut: CascadedInterruptCtrl): Apb3Driver = {
    dut.io.inputs #= 0
    val apb = Apb3Driver(dut.io.apb, dut.clockDomain)
    dut.clockDomain.forkStimulus(10)
    dut.clockDomain.waitSampling(2)
    apb
  }

  private def pulseInputs(dut: CascadedInterruptCtrl, bits: BigInt): Unit = {
    dut.io.inputs #= bits
    dut.clockDomain.waitSampling()
    dut.io.inputs #= 0
    dut.clockDomain.waitSampling()
  }

  private def assertOutputs(
      dut: CascadedInterruptCtrl,
      expectedPendings: BigInt,
      expectedInterrupt: Boolean,
      clue: String
  ): Unit = {
    sleep(1)
    assert(
      dut.io.pendings.toBigInt == expectedPendings,
      f"$clue: pendings=0x${dut.io.pendings.toBigInt}%x, expected=0x$expectedPendings%x"
    )
    assert(
      dut.io.interrupt.toBoolean == expectedInterrupt,
      s"$clue: interrupt=${dut.io.interrupt.toBoolean}, expected=$expectedInterrupt"
    )
  }

  private def assertReadyWithoutError(dut: CascadedInterruptCtrl, phase: String): Unit = {
    assert(dut.io.apb.PREADY.toBoolean, s"PREADY was low during $phase")
    assert(!dut.io.apb.PSLVERROR.toBoolean, s"PSLVERROR was high during $phase")
  }

  private def checkedRead(dut: CascadedInterruptCtrl, address: BigInt): BigInt = {
    val bus = dut.io.apb
    bus.PSEL #= 1
    bus.PENABLE #= false
    bus.PWRITE #= false
    bus.PADDR #= address
    bus.PWDATA #= 0
    sleep(1)
    assertReadyWithoutError(dut, f"read setup at 0x$address%x")

    dut.clockDomain.waitSampling()
    bus.PENABLE #= true
    sleep(1)
    assertReadyWithoutError(dut, f"read access at 0x$address%x")
    dut.clockDomain.waitSamplingWhere(bus.PREADY.toBoolean)
    val data = bus.PRDATA.toBigInt
    assertReadyWithoutError(dut, f"read completion at 0x$address%x")

    bus.PSEL #= 0
    bus.PENABLE #= false
    sleep(1)
    data
  }

  private def checkedWrite(
      dut: CascadedInterruptCtrl,
      address: BigInt,
      data: BigInt,
      beforeAccess: () => Unit = () => ()
  ): Unit = {
    val bus = dut.io.apb
    bus.PSEL #= 1
    bus.PENABLE #= false
    bus.PWRITE #= true
    bus.PADDR #= address
    bus.PWDATA #= data
    sleep(1)
    assertReadyWithoutError(dut, f"write setup at 0x$address%x")

    dut.clockDomain.waitSampling()
    beforeAccess()
    bus.PENABLE #= true
    sleep(1)
    assertReadyWithoutError(dut, f"write access at 0x$address%x")
    dut.clockDomain.waitSamplingWhere(bus.PREADY.toBoolean)
    assertReadyWithoutError(dut, f"write completion at 0x$address%x")

    bus.PSEL #= 0
    bus.PENABLE #= false
    sleep(1)
  }

  test("masked events remain latched and enabled pending bits obey W1C priority") {
    compiled.doSim("interrupt-behavior") { dut =>
      val apb = initialize(dut)
      val maskedBit = BigInt(1) << 2

      assert(apb.read(maskAddress) == 0, "mask did not reset to zero")
      assert(apb.read(pendingAddress) == 0, "pending did not reset to zero")
      assertOutputs(dut, 0, expectedInterrupt = false, "after reset")

      pulseInputs(dut, maskedBit)
      assert(apb.read(pendingAddress) == 0, "masked pending was visible before enable")
      assertOutputs(dut, 0, expectedInterrupt = false, "masked input pulse")

      apb.write(maskAddress, maskedBit)
      assert(apb.read(maskAddress) == maskedBit, "mask readback did not match")
      assert(apb.read(pendingAddress) == maskedBit, "latched event was lost while masked")
      assertOutputs(dut, maskedBit, expectedInterrupt = true, "enabling a latched event")

      apb.write(pendingAddress, maskedBit)
      assert(apb.read(pendingAddress) == 0, "single pending bit did not clear")
      assertOutputs(dut, 0, expectedInterrupt = false, "clearing the masked event")

      val simultaneous = (BigInt(1) << 0) | (BigInt(1) << 3) | (BigInt(1) << 7)
      apb.write(maskAddress, simultaneous)
      pulseInputs(dut, simultaneous)
      assert(apb.read(pendingAddress) == simultaneous, "simultaneous inputs were not all latched")
      assertOutputs(dut, simultaneous, expectedInterrupt = true, "simultaneous inputs")

      apb.write(pendingAddress, BigInt(1) << 3)
      val afterMiddleClear = (BigInt(1) << 0) | (BigInt(1) << 7)
      assert(apb.read(pendingAddress) == afterMiddleClear, "W1C changed an unselected bit")
      assertOutputs(dut, afterMiddleClear, expectedInterrupt = true, "clearing bit 3 only")

      apb.write(pendingAddress, BigInt(1) << 0)
      assert(apb.read(pendingAddress) == (BigInt(1) << 7), "bit 0 W1C affected bit 7")
      assertOutputs(dut, BigInt(1) << 7, expectedInterrupt = true, "one enabled pending remains")

      apb.write(pendingAddress, BigInt(1) << 7)
      assert(apb.read(pendingAddress) == 0, "last enabled pending did not clear")
      assertOutputs(dut, 0, expectedInterrupt = false, "all enabled pendings cleared")

      val levelBit = BigInt(1) << 5
      apb.write(maskAddress, levelBit)
      checkedWrite(
        dut,
        pendingAddress,
        levelBit,
        beforeAccess = () => dut.io.inputs #= levelBit
      )
      assert(apb.read(pendingAddress) == levelBit, "same-cycle input did not win over W1C")
      assertOutputs(dut, levelBit, expectedInterrupt = true, "input and clear on the same cycle")

      apb.write(pendingAddress, levelBit)
      dut.clockDomain.waitSampling(3)
      assert(apb.read(pendingAddress) == levelBit, "active level input was cleared")
      assertOutputs(dut, levelBit, expectedInterrupt = true, "clear while level input stays high")

      dut.io.inputs #= 0
      dut.clockDomain.waitSampling()
      apb.write(pendingAddress, levelBit)
      assert(apb.read(pendingAddress) == 0, "inactive level input could not be cleared")
      assertOutputs(dut, 0, expectedInterrupt = false, "clear after level input deasserts")
    }
  }

  test("APB3 accesses complete without errors and reserve upper register bits") {
    compiled.doSim("apb-registers") { dut =>
      val apb = initialize(dut)
      val fullWord = (BigInt(1) << 32) - 1
      val reservedOnly = fullWord & ~implementedBits
      val pendingBits = (BigInt(1) << 0) | (BigInt(1) << 7)

      apb.write(maskAddress, fullWord)
      assert(apb.read(maskAddress) == implementedBits, "mask reserved bits did not read as zero")

      pulseInputs(dut, pendingBits)
      assert(apb.read(pendingAddress) == pendingBits, "pending register readback was incorrect")
      apb.write(pendingAddress, reservedOnly)
      assert(apb.read(pendingAddress) == pendingBits, "reserved W1C bits changed implemented pending bits")

      assert(checkedRead(dut, maskAddress) == implementedBits, "checked mask readback was incorrect")
      assert(checkedRead(dut, pendingAddress) == pendingBits, "checked pending readback was incorrect")

      checkedWrite(dut, BigInt(0xc), fullWord)
      assert(apb.read(maskAddress) == implementedBits, "unmapped write changed the mask")
      assert(apb.read(pendingAddress) == pendingBits, "unmapped write changed pending bits")
      assert(checkedRead(dut, BigInt(0x8)) == 0, "unmapped read did not return zero")

      apb.write(pendingAddress, fullWord)
      assert(apb.read(pendingAddress) == 0, "implemented W1C bits did not clear")
      assertOutputs(dut, 0, expectedInterrupt = false, "full-word W1C")

      dut.io.apb.PSEL #= 0
      dut.io.apb.PENABLE #= false
      sleep(1)
      assertReadyWithoutError(dut, "idle")
    }
  }
}
