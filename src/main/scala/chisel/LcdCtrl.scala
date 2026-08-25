package chisel

import chisel3._
import chisel3.util.{Cat, Enum, MuxLookup, is, log2Ceil, switch}

/** LCD 控制器 APB 寄存器表。 */
object LcdCtrlRegisters {
  val command: Int = 0x00
  val data: Int = 0x04
  val control: Int = 0x08
  val status: Int = 0x0c
  val dmaBase: Int = 0x10
  val dmaLength: Int = 0x14
  val writeTiming: Int = 0x18
  val irqEnable: Int = 0x1c
  val dmaWidth: Int = 0x20
  val dmaHeight: Int = 0x24
  val dmaSourceStride: Int = 0x28
  val powerControl: Int = 0x2c

  val maximumDmaWidth: Int = 480
  val maximumDmaHeight: Int = 800
}

/** 16 位 8080 LCD 写接口。
  *
  * 控制器只写命令和数据；readn 保持无效，不提供数据读回或外部等待输入。
  */
class LcdPort extends Bundle {
  val data: UInt = Output(UInt(16.W))
  val resetn: Bool = Output(Bool())
  val chipSelectn: Bool = Output(Bool())
  val registerSelect: Bool = Output(Bool())
  val writen: Bool = Output(Bool())
  val readn: Bool = Output(Bool())
  val backlightEnable: Bool = Output(Bool())
}

class LcdApbDomainIO extends Bundle {
  val apb: APB3IO = Flipped(new APB3IO(addrWidth = 20))
  val lcd: LcdPort = new LcdPort
  val dmaFrame: LcdDmaFrameIO = Flipped(new LcdDmaFrameIO)
  val dmaRequest: LcdDmaRequest = Output(new LcdDmaRequest)
  val dmaStatus: LcdDmaStatus = Input(new LcdDmaStatus)
  val buttonPress: UInt = Input(UInt(4.W))
  val dmaSoftReset: Bool = Output(Bool())
  val touchEnabled: Bool = Output(Bool())
  val interrupt: Bool = Output(Bool())
}

/** 33 MHz APB寄存器与8080写引擎。
  *
  * CMD/DATA 访问等待物理写周期完成；DMA期间拒绝CMD/DATA访问，并写出DMA提供的有效数据流。
  */
class LcdApbDomain(displayOnDelayCycles: Int = 330000) extends Module {
  require(displayOnDelayCycles >= 1)

  val io: LcdApbDomainIO = IO(new LcdApbDomainIO)

  val panelResetnReg = RegInit(false.B)
  val dmaBaseReg = RegInit(0.U(32.W))
  val dmaLengthReg = RegInit(0.U(32.W))
  val dmaWidthReg = RegInit(0.U(32.W))
  val dmaHeightReg = RegInit(0.U(32.W))
  val dmaSourceStrideReg = RegInit(0.U(32.W))
  val writeTimingReg = RegInit("h00050503".U(32.W))
  val irqEnableReg = RegInit(0.U(3.W))
  val backlightEnableReg = RegInit(true.B)
  val displayEnableReg = RegInit(true.B)
  val displayActiveReg = RegInit(true.B)
  val touchEnableReg = RegInit(true.B)
  val controlChangedReg = RegInit(false.B)
  val recoveryPendingReg = RegInit(false.B)
  val recoveryInProgressReg = RegInit(false.B)
  val recoveryResetCounterReg = RegInit(0.U(4.W))
  val displayOnDelayCounterReg = RegInit(0.U(log2Ceil(displayOnDelayCycles + 1).W))
  val displayOnDelayActiveReg = RegInit(false.B)

  val dmaBusyReg = RegInit(false.B)
  val dmaDoneReg = RegInit(false.B)
  val dmaErrorReg = RegInit(false.B)
  val dmaStartToggleReg = RegInit(false.B)
  val activeBaseReg = RegInit(0.U(32.W))
  val activeWidthReg = RegInit(0.U(32.W))
  val activeHeightReg = RegInit(0.U(32.W))
  val activeSourceStrideReg = RegInit(0.U(32.W))
  val physicalLastSeenReg = RegInit(false.B)
  val finalWriteDoneReg = RegInit(false.B)

  val errorToggleMetaReg = RegNext(io.dmaStatus.errorToggle, false.B)
  val errorToggleSyncReg = RegNext(errorToggleMetaReg, false.B)
  val errorToggleSeenReg = RegInit(false.B)

  val writerDataReg = RegInit(0.U(16.W))
  val writerDataSelectReg = RegInit(false.B)
  val writerIsDmaReg = RegInit(false.B)
  val writerIsControlReg = RegInit(false.B)
  val writerControlTargetReg = RegInit(false.B)
  val writerLastDesiredReg = RegInit(false.B)
  val timingCounterReg = RegInit(0.U(8.W))
  val wIdle :: wSetup :: wLow :: wHold :: wDirectResponse :: Nil = Enum(5)
  val writerStateReg = RegInit(wIdle)

  val registerOffset = io.apb.paddr(13, 0)
  val apbSetup = io.apb.psel && !io.apb.penable
  val apbAccess = io.apb.psel && io.apb.penable
  val alignedAccess = io.apb.paddr(1, 0) === 0.U

  val commandSelected = registerOffset === LcdCtrlRegisters.command.U
  val dataSelected = registerOffset === LcdCtrlRegisters.data.U
  val controlSelected = registerOffset === LcdCtrlRegisters.control.U
  val statusSelected = registerOffset === LcdCtrlRegisters.status.U
  val dmaBaseSelected = registerOffset === LcdCtrlRegisters.dmaBase.U
  val dmaLengthSelected = registerOffset === LcdCtrlRegisters.dmaLength.U
  val writeTimingSelected = registerOffset === LcdCtrlRegisters.writeTiming.U
  val irqEnableSelected = registerOffset === LcdCtrlRegisters.irqEnable.U
  val dmaWidthSelected = registerOffset === LcdCtrlRegisters.dmaWidth.U
  val dmaHeightSelected = registerOffset === LcdCtrlRegisters.dmaHeight.U
  val dmaSourceStrideSelected = registerOffset === LcdCtrlRegisters.dmaSourceStride.U
  val powerControlSelected = registerOffset === LcdCtrlRegisters.powerControl.U
  val directSelected = commandSelected || dataSelected

  val readAllowed = controlSelected || statusSelected || dmaBaseSelected ||
    dmaLengthSelected || writeTimingSelected || irqEnableSelected || dmaWidthSelected ||
    dmaHeightSelected || dmaSourceStrideSelected || powerControlSelected
  val writeAllowed = directSelected || controlSelected || statusSelected || dmaBaseSelected ||
    dmaLengthSelected || writeTimingSelected || irqEnableSelected || dmaWidthSelected ||
    dmaHeightSelected || dmaSourceStrideSelected || powerControlSelected
  val accessAllowed = Mux(io.apb.pwrite, writeAllowed, readAllowed)

  val legacyEndAddress = Wire(UInt(34.W))
  legacyEndAddress := Cat(0.U(2.W), dmaBaseReg) + (Cat(0.U(2.W), dmaLengthReg) << 1)
  val legacyConfigurationValid = !dmaBaseReg(0) && dmaLengthReg =/= 0.U &&
    legacyEndAddress <= (BigInt(1) << 32).U(34.W)

  val dmaRowBytes = Cat(0.U(1.W), dmaWidthReg) << 1
  val dmaHeightMinusOne = dmaHeightReg(9, 0) - 1.U
  val dmaLastRowOffset = dmaHeightMinusOne * dmaSourceStrideReg
  val dmaTwoDimensionalEndAddress = dmaBaseReg.pad(44) +
    dmaLastRowOffset.pad(44) + dmaRowBytes.pad(44)
  val dmaTwoDimensionalConfigurationValid = !dmaBaseReg(0) && dmaWidthReg =/= 0.U &&
    dmaHeightReg =/= 0.U && dmaWidthReg <= LcdCtrlRegisters.maximumDmaWidth.U &&
    dmaHeightReg <= LcdCtrlRegisters.maximumDmaHeight.U && !dmaSourceStrideReg(0) &&
    dmaSourceStrideReg.pad(34) >= dmaRowBytes &&
    dmaTwoDimensionalEndAddress <= (BigInt(1) << 32).U(44.W)

  val startRequested = controlSelected && io.apb.pwrite && io.apb.pwdata(1)
  val twoDimensionalStart = io.apb.pwdata(2)
  val dmaConfigurationValid = Mux(
    twoDimensionalStart,
    dmaTwoDimensionalConfigurationValid,
    legacyConfigurationValid
  )
  val displayCommandRequired = displayEnableReg =/= displayActiveReg
  val displayTransitionPending = !recoveryPendingReg &&
    (displayCommandRequired || displayOnDelayActiveReg)
  val refreshBlocked = !displayEnableReg || displayTransitionPending ||
    recoveryInProgressReg || recoveryPendingReg
  val invalidStart = startRequested && (dmaBusyReg || !dmaConfigurationValid || refreshBlocked)
  val invalidDirect = directSelected && (!io.apb.pwrite || dmaBusyReg ||
    displayTransitionPending || recoveryInProgressReg)
  val invalidAccess = !alignedAccess || !accessAllowed || invalidStart || invalidDirect

  val lcdBusy = writerStateReg =/= wIdle
  val physicalBacklightEnable = backlightEnableReg && displayActiveReg &&
    !displayTransitionPending && !recoveryInProgressReg && !recoveryPendingReg && panelResetnReg
  val statusValue = Cat(
    0.U(24.W),
    displayTransitionPending,
    recoveryInProgressReg,
    recoveryPendingReg,
    controlChangedReg,
    dmaErrorReg,
    dmaDoneReg,
    dmaBusyReg,
    lcdBusy
  )
  val powerControlValue = Cat(
    0.U(18.W),
    recoveryPendingReg,
    recoveryInProgressReg,
    refreshBlocked,
    displayTransitionPending,
    displayActiveReg,
    physicalBacklightEnable,
    0.U(5.W),
    touchEnableReg,
    displayEnableReg,
    backlightEnableReg
  )
  val readData = MuxLookup(registerOffset, 0.U(32.W))(Seq(
    LcdCtrlRegisters.control.U -> Cat(0.U(30.W), false.B, panelResetnReg),
    LcdCtrlRegisters.status.U -> statusValue,
    LcdCtrlRegisters.dmaBase.U -> dmaBaseReg,
    LcdCtrlRegisters.dmaLength.U -> dmaLengthReg,
    LcdCtrlRegisters.writeTiming.U -> writeTimingReg,
    LcdCtrlRegisters.irqEnable.U -> irqEnableReg,
    LcdCtrlRegisters.dmaWidth.U -> dmaWidthReg,
    LcdCtrlRegisters.dmaHeight.U -> dmaHeightReg,
    LcdCtrlRegisters.dmaSourceStride.U -> dmaSourceStrideReg,
    LcdCtrlRegisters.powerControl.U -> powerControlValue
  ))

  val directResponse = writerStateReg === wDirectResponse
  io.apb.prdata := readData
  io.apb.pready := Mux(directSelected && !invalidAccess, directResponse, true.B)
  io.apb.pslverr := apbAccess && invalidAccess
  val apbWriteFire = apbAccess && io.apb.pready && io.apb.pwrite && !io.apb.pslverr

  io.lcd.data := writerDataReg
  io.lcd.resetn := panelResetnReg
  io.lcd.chipSelectn := !(writerStateReg === wSetup || writerStateReg === wLow || writerStateReg === wHold)
  io.lcd.registerSelect := writerDataSelectReg
  io.lcd.writen := writerStateReg =/= wLow
  io.lcd.readn := true.B
  io.lcd.backlightEnable := physicalBacklightEnable

  io.dmaRequest.startToggle := dmaStartToggleReg
  io.dmaRequest.baseAddress := activeBaseReg
  io.dmaRequest.lineWidth := activeWidthReg
  io.dmaRequest.height := activeHeightReg
  io.dmaRequest.sourceStride := activeSourceStrideReg
  io.dmaSoftReset := recoveryInProgressReg
  io.touchEnabled := touchEnableReg
  io.interrupt := (dmaDoneReg && irqEnableReg(0)) ||
    (controlChangedReg && irqEnableReg(1)) ||
    (recoveryPendingReg && irqEnableReg(2))

  val setupCycles = Mux(writeTimingReg(7, 0) === 0.U, 1.U, writeTimingReg(7, 0))
  val lowCycles = Mux(writeTimingReg(15, 8) === 0.U, 1.U, writeTimingReg(15, 8))
  val holdCycles = Mux(writeTimingReg(23, 16) === 0.U, 1.U, writeTimingReg(23, 16))
  def timingComplete(cycles: UInt): Bool = timingCounterReg === cycles - 1.U

  io.dmaFrame.ready := false.B
  val dmaFrameFire = io.dmaFrame.valid && io.dmaFrame.ready

  when(errorToggleSyncReg =/= errorToggleSeenReg) {
    errorToggleSeenReg := errorToggleSyncReg
    when(dmaBusyReg) {
      dmaErrorReg := true.B
    }
  }

  when(apbWriteFire && !directSelected) {
    switch(registerOffset) {
      is(LcdCtrlRegisters.control.U) {
        panelResetnReg := io.apb.pwdata(0)
        when(io.apb.pwdata(1)) {
          activeBaseReg := dmaBaseReg
          activeWidthReg := Mux(twoDimensionalStart, dmaWidthReg, dmaLengthReg)
          activeHeightReg := Mux(twoDimensionalStart, dmaHeightReg, 1.U)
          activeSourceStrideReg := Mux(twoDimensionalStart, dmaSourceStrideReg, 0.U)
          physicalLastSeenReg := false.B
          finalWriteDoneReg := false.B
          dmaBusyReg := true.B
          dmaDoneReg := false.B
          dmaErrorReg := false.B
          dmaStartToggleReg := !dmaStartToggleReg
        }
      }
      is(LcdCtrlRegisters.status.U) {
        when(io.apb.pwdata(2)) { dmaDoneReg := false.B }
        when(io.apb.pwdata(3)) { dmaErrorReg := false.B }
        when(io.apb.pwdata(4)) { controlChangedReg := false.B }
        when(io.apb.pwdata(5) && recoveryPendingReg) {
          recoveryPendingReg := false.B
          displayActiveReg := displayEnableReg
          displayOnDelayActiveReg := false.B
        }
      }
      is(LcdCtrlRegisters.dmaBase.U) {
        dmaBaseReg := io.apb.pwdata
      }
      is(LcdCtrlRegisters.dmaLength.U) {
        dmaLengthReg := io.apb.pwdata
      }
      is(LcdCtrlRegisters.writeTiming.U) {
        writeTimingReg := io.apb.pwdata
      }
      is(LcdCtrlRegisters.irqEnable.U) {
        irqEnableReg := io.apb.pwdata(2, 0)
      }
      is(LcdCtrlRegisters.dmaWidth.U) {
        dmaWidthReg := io.apb.pwdata
      }
      is(LcdCtrlRegisters.dmaHeight.U) {
        dmaHeightReg := io.apb.pwdata
      }
      is(LcdCtrlRegisters.dmaSourceStride.U) {
        dmaSourceStrideReg := io.apb.pwdata
      }
      is(LcdCtrlRegisters.powerControl.U) {
        backlightEnableReg := io.apb.pwdata(0)
        displayEnableReg := io.apb.pwdata(1)
        touchEnableReg := io.apb.pwdata(2)
      }
    }
  }

  val softwareRecoveryRequest = apbWriteFire && powerControlSelected && io.apb.pwdata(3)

  when(io.buttonPress(0)) {
    backlightEnableReg := !backlightEnableReg
    controlChangedReg := true.B
  }
  when(io.buttonPress(1)) {
    displayEnableReg := !displayEnableReg
    controlChangedReg := true.B
  }
  when(io.buttonPress(2)) {
    touchEnableReg := !touchEnableReg
    controlChangedReg := true.B
  }

  when(apbSetup && directSelected && !invalidAccess && writerStateReg === wIdle) {
    writerDataReg := io.apb.pwdata(15, 0)
    writerDataSelectReg := dataSelected
    writerIsDmaReg := false.B
    writerIsControlReg := false.B
    writerLastDesiredReg := false.B
    timingCounterReg := 0.U
    writerStateReg := wSetup
  }

  switch(writerStateReg) {
    is(wIdle) {
      when(displayCommandRequired && !dmaBusyReg && !recoveryInProgressReg && !recoveryPendingReg) {
        writerDataReg := Mux(displayEnableReg, "h2900".U, "h2800".U)
        writerDataSelectReg := false.B
        writerIsDmaReg := false.B
        writerIsControlReg := true.B
        writerControlTargetReg := displayEnableReg
        writerLastDesiredReg := false.B
        timingCounterReg := 0.U
        writerStateReg := wSetup
        when(!displayEnableReg) {
          displayOnDelayActiveReg := false.B
        }
      }.elsewhen(dmaBusyReg && io.dmaFrame.valid) {
        io.dmaFrame.ready := true.B
        when(dmaFrameFire) {
          writerDataReg := io.dmaFrame.data
          writerDataSelectReg := true.B
          writerIsDmaReg := true.B
          writerIsControlReg := false.B
          writerLastDesiredReg := io.dmaFrame.last
          timingCounterReg := 0.U
          writerStateReg := wSetup
          when(io.dmaFrame.last) {
            physicalLastSeenReg := true.B
          }
        }
      }
    }

    is(wSetup) {
      when(timingComplete(setupCycles)) {
        timingCounterReg := 0.U
        writerStateReg := wLow
      }.otherwise {
        timingCounterReg := timingCounterReg + 1.U
      }
    }

    is(wLow) {
      when(timingComplete(lowCycles)) {
        timingCounterReg := 0.U
        writerStateReg := wHold
      }.otherwise {
        timingCounterReg := timingCounterReg + 1.U
      }
    }

    is(wHold) {
      when(timingComplete(holdCycles)) {
        timingCounterReg := 0.U
        when(writerIsDmaReg) {
          when(writerLastDesiredReg) {
            finalWriteDoneReg := true.B
          }
          writerStateReg := wIdle
        }.elsewhen(writerIsControlReg) {
          displayActiveReg := writerControlTargetReg
          writerIsControlReg := false.B
          writerStateReg := wIdle
          when(writerControlTargetReg) {
            displayOnDelayCounterReg := (displayOnDelayCycles - 1).U
            displayOnDelayActiveReg := true.B
          }.otherwise {
            controlChangedReg := true.B
          }
        }.otherwise {
          writerStateReg := wDirectResponse
        }
      }.otherwise {
        timingCounterReg := timingCounterReg + 1.U
      }
    }

    is(wDirectResponse) {
      when(apbAccess) {
        writerStateReg := wIdle
      }
    }
  }

  when(dmaBusyReg && finalWriteDoneReg && physicalLastSeenReg && writerStateReg === wIdle) {
    dmaBusyReg := false.B
    dmaDoneReg := true.B
    finalWriteDoneReg := false.B
    physicalLastSeenReg := false.B
  }

  when(displayOnDelayActiveReg) {
    when(displayOnDelayCounterReg === 0.U) {
      displayOnDelayActiveReg := false.B
      controlChangedReg := true.B
    }.otherwise {
      displayOnDelayCounterReg := displayOnDelayCounterReg - 1.U
    }
  }

  when(recoveryInProgressReg) {
    when(recoveryResetCounterReg === 0.U) {
      recoveryInProgressReg := false.B
      recoveryPendingReg := true.B
    }.otherwise {
      recoveryResetCounterReg := recoveryResetCounterReg - 1.U
    }
  }

  val recoveryRequest = (io.buttonPress(3) || softwareRecoveryRequest) &&
    !recoveryInProgressReg && !recoveryPendingReg

  when(recoveryRequest) {
    recoveryInProgressReg := true.B
    recoveryPendingReg := false.B
    recoveryResetCounterReg := 15.U
    panelResetnReg := false.B
    displayActiveReg := false.B
    displayOnDelayActiveReg := false.B
    dmaBusyReg := false.B
    dmaDoneReg := false.B
    dmaErrorReg := false.B
    dmaStartToggleReg := false.B
    errorToggleSeenReg := errorToggleSyncReg
    physicalLastSeenReg := false.B
    finalWriteDoneReg := false.B
    writerIsDmaReg := false.B
    writerIsControlReg := false.B
    writerStateReg := wIdle
  }

}

class LcdCtrlIO extends Bundle {
  val apbClk: Clock = Input(Clock())
  val apbResetn: Bool = Input(Bool())
  val axiClk: Clock = Input(Clock())
  val axiResetn: Bool = Input(Bool())
  val apb: APB3IO = Flipped(new APB3IO(addrWidth = 20))
  val axi: AXI4IO = new AXI4IO(
    idWidth = 3,
    addrWidth = 32,
    lenWidth = 8,
    lockWidth = 1,
    dataWidth = 32,
    strbWidth = 4
  )
  val lcd: LcdPort = new LcdPort
  val buttonPress: UInt = Input(UInt(4.W))
  val touchEnabled: Bool = Output(Bool())
  val interrupt: Bool = Output(Bool())
}

/** LCD控制器顶层。
  *
  * APB寄存器和8080工作在33 MHz，framebuffer reader工作在100 MHz；跨时钟缓存由VideoDma内部实现。
  */
class LcdCtrl extends RawModule {
  override def desiredName: String = "LcdCtrl"

  val io: LcdCtrlIO = IO(new LcdCtrlIO)
  val apbDomain: LcdApbDomain = withClockAndReset(io.apbClk, (!io.apbResetn).asAsyncReset) {
    Module(new LcdApbDomain)
  }
  val videoDma: LcdVideoDma = Module(new LcdVideoDma)

  videoDma.io.axiClk := io.axiClk
  videoDma.io.axiResetn := io.axiResetn
  videoDma.io.frameClk := io.apbClk
  videoDma.io.frameResetn := io.apbResetn
  videoDma.io.softReset := apbDomain.io.dmaSoftReset

  io.apb <> apbDomain.io.apb
  io.axi <> videoDma.io.axi
  io.lcd <> apbDomain.io.lcd
  apbDomain.io.buttonPress := io.buttonPress
  io.touchEnabled := apbDomain.io.touchEnabled
  io.interrupt := apbDomain.io.interrupt
  videoDma.io.request := apbDomain.io.dmaRequest
  apbDomain.io.dmaStatus := videoDma.io.status
  apbDomain.io.dmaFrame <> videoDma.io.frame
}
