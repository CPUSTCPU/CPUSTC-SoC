package chisel

import chisel3._
import chisel3.util._

/** 8x8 LED 点阵控制器寄存器偏移。 */
object DotMatrixRegisters {
  val patternLow: Int = 0x300
  val patternHigh: Int = 0x304
  val control: Int = 0x308
  val scanDivider: Int = 0x30c
  val status: Int = 0x310
}

class DotMatrixControllerIO extends Bundle {
  val apb: APB3IO = Flipped(new APB3IO(addrWidth = 20))
  val columns: UInt = Output(UInt(8.W))
  val rows: UInt = Output(UInt(8.W))
}

/** CPU 可编程的 8x8 LED 点阵扫描控制器。
  *
  * 64 位图案按低位优先组织，bit `row * 8 + column` 对应 R1..R8 与 C1..C8。
  * 模块提供逐行扫描、换行单周期消隐、8 位 PWM 和可配置的行列有效电平。
  */
class DotMatrixController(defaultScanDivider: Int = 33000) extends Module {
  require(defaultScanDivider > 0)

  override def desiredName: String = "dot_matrix_controller"

  val io: DotMatrixControllerIO = IO(new DotMatrixControllerIO)

  private val patternReg = RegInit(0.U(64.W))
  private val enableReg = RegInit(false.B)
  private val rowActiveLowReg = RegInit(false.B)
  private val columnActiveLowReg = RegInit(true.B)
  private val brightnessReg = RegInit(255.U(8.W))
  private val scanDividerReg = RegInit(defaultScanDivider.U(32.W))

  private val rowIndexReg = RegInit(0.U(3.W))
  private val scanCounterReg = RegInit(0.U(32.W))
  private val blankingReg = RegInit(false.B)
  private val pwmCounterReg = RegInit(0.U(8.W))

  private val registerOffset = io.apb.paddr(11, 0)
  private val knownRegister = Seq(
    DotMatrixRegisters.patternLow,
    DotMatrixRegisters.patternHigh,
    DotMatrixRegisters.control,
    DotMatrixRegisters.scanDivider,
    DotMatrixRegisters.status
  ).map(offset => registerOffset === offset.U).reduce(_ || _)
  private val apbAccess = io.apb.psel && io.apb.penable
  private val apbWrite = apbAccess && io.apb.pwrite
  private val readOnlyWrite = apbWrite &&
    registerOffset === DotMatrixRegisters.status.U

  private val controlValue = Cat(
    0.U(16.W),
    brightnessReg,
    0.U(5.W),
    columnActiveLowReg,
    rowActiveLowReg,
    enableReg
  )
  private val statusValue = Cat(
    0.U(19.W),
    enableReg,
    pwmCounterReg,
    blankingReg,
    rowIndexReg
  )

  io.apb.prdata := MuxLookup(registerOffset, 0.U)(Seq(
    DotMatrixRegisters.patternLow.U -> patternReg(31, 0),
    DotMatrixRegisters.patternHigh.U -> patternReg(63, 32),
    DotMatrixRegisters.control.U -> controlValue,
    DotMatrixRegisters.scanDivider.U -> scanDividerReg,
    DotMatrixRegisters.status.U -> statusValue
  ))
  io.apb.pready := true.B
  io.apb.pslverr := apbAccess && (!knownRegister || readOnlyWrite)

  when(apbWrite && knownRegister && !readOnlyWrite) {
    switch(registerOffset) {
      is(DotMatrixRegisters.patternLow.U) {
        patternReg := Cat(patternReg(63, 32), io.apb.pwdata)
      }
      is(DotMatrixRegisters.patternHigh.U) {
        patternReg := Cat(io.apb.pwdata, patternReg(31, 0))
      }
      is(DotMatrixRegisters.control.U) {
        enableReg := io.apb.pwdata(0)
        rowActiveLowReg := io.apb.pwdata(1)
        columnActiveLowReg := io.apb.pwdata(2)
        brightnessReg := io.apb.pwdata(15, 8)
      }
      is(DotMatrixRegisters.scanDivider.U) {
        scanDividerReg := Mux(io.apb.pwdata === 0.U, 1.U, io.apb.pwdata)
      }
    }
  }

  when(!enableReg) {
    rowIndexReg := 0.U
    scanCounterReg := 0.U
    blankingReg := false.B
    pwmCounterReg := 0.U
  }.otherwise {
    pwmCounterReg := pwmCounterReg + 1.U

    when(blankingReg) {
      blankingReg := false.B
    }.elsewhen(scanCounterReg >= scanDividerReg - 1.U) {
      scanCounterReg := 0.U
      rowIndexReg := rowIndexReg + 1.U
      blankingReg := true.B
    }.otherwise {
      scanCounterReg := scanCounterReg + 1.U
    }
  }

  private val rowShift = Cat(rowIndexReg, 0.U(3.W))
  private val rowPattern = (patternReg >> rowShift)(7, 0)
  private val pwmEnabled = brightnessReg === 255.U ||
    pwmCounterReg < brightnessReg
  private val driveActive = enableReg && !blankingReg && pwmEnabled
  private val logicalRows = Mux(driveActive, UIntToOH(rowIndexReg, 8), 0.U(8.W))
  private val logicalColumns = Mux(driveActive, rowPattern, 0.U(8.W))

  io.rows := Mux(rowActiveLowReg, ~logicalRows, logicalRows)
  io.columns := Mux(columnActiveLowReg, ~logicalColumns, logicalColumns)
}
