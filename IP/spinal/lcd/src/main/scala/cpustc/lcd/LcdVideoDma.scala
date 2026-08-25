package cpustc.lcd

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4Config, Axi4ReadOnly}
import spinal.lib.graphic.{VideoDma, VideoDmaGeneric}

/** LCD DMA 的固定结构参数。 */
case class LcdVideoDmaParameter(
    beatPerAccess: Int = 4,
    pendingRequestMax: Int = 1,
    fifoSize: Int = 16
) {
  val axiAddressWidth: Int = 32
  val axiDataWidth: Int = 32
  val frameDataWidth: Int = 16
  val bytesPerBurst: Int = axiDataWidth / 8 * beatPerAccess
  val halfWordsPerBurst: Int = bytesPerBurst / (frameDataWidth / 8)
  val burstAddressWidth: Int = axiAddressWidth - log2Up(bytesPerBurst)

  require(isPow2(beatPerAccess))
  require(isPow2(fifoSize))
  require(isPow2(halfWordsPerBurst))
  require(fifoSize >= beatPerAccess * 4)
  require(pendingRequestMax >= 1)

  val axiConfig: Axi4Config = Axi4Config(
    addressWidth = axiAddressWidth,
    dataWidth = axiDataWidth,
    useId = false,
    useRegion = false,
    useBurst = false,
    useLock = false,
    useQos = false,
    useResp = true
  )
}

/** 按二维矩形读取 DDR，并在 frame 时钟域输出连续 16 位有效数据。
  *
  * 每行独立向下对齐到 16 字节 burst 边界，行首和行尾多读数据在本模块内丢弃。
  * 最后一行的最后一个有效数据会等待物理 burst 尾部排空后携带 last 输出。
  */
case class LcdVideoDma(
    parameter: LcdVideoDmaParameter,
    axiClockDomain: ClockDomain,
    frameClockDomain: ClockDomain
) extends Component {
  private val dmaGeneric = VideoDmaGeneric(
    addressWidth = parameter.burstAddressWidth,
    dataWidth = parameter.axiDataWidth,
    beatPerAccess = parameter.beatPerAccess,
    sizeWidth = parameter.burstAddressWidth,
    frameFragmentType = Bits(parameter.frameDataWidth bits),
    pendingRequetMax = parameter.pendingRequestMax,
    fifoSize = parameter.fifoSize,
    frameClock = frameClockDomain
  )

  val io = new Bundle {
    val start = in Bool()
    val baseAddress = in UInt(parameter.axiAddressWidth bits)
    val width = in UInt(32 bits)
    val height = in UInt(32 bits)
    val sourceStride = in UInt(32 bits)
    val busy = out Bool()
    val error = out Bool()
    val axi = master(Axi4ReadOnly(parameter.axiConfig))
    val frame = master(Stream(Fragment(Bits(parameter.frameDataWidth bits))))
  }

  val frameDoneToggle = Bool()

  val axiArea = new ClockingArea(axiClockDomain) {
    val dma = VideoDma(dmaGeneric)
    val busyReg = RegInit(False)
    val errorReg = RegInit(False)
    val startToggleReg = RegInit(False)
    val activeBaseReg = Reg(UInt(parameter.axiAddressWidth bits)) init (0)
    val activeWidthReg = Reg(UInt(32 bits)) init (0)
    val activeHeightReg = Reg(UInt(32 bits)) init (0)
    val activeStrideReg = Reg(UInt(32 bits)) init (0)
    val rowBaseReg = Reg(UInt(parameter.axiAddressWidth bits)) init (0)
    val rowsRemainingReg = Reg(UInt(32 bits)) init (0)
    val rowStartPendingReg = RegInit(False)
    val rowInFlightReg = RegInit(False)

    val frameDoneToggleSync = BufferCC(frameDoneToggle)
    val frameDoneToggleSeenReg = RegInit(False)

    val leadingHalfWords = rowBaseReg(log2Up(parameter.bytesPerBurst) - 1 downto 1)
    val totalHalfWords = activeWidthReg.resize(33) + leadingHalfWords.resize(33)
    val roundedTotalHalfWords = totalHalfWords + U(parameter.halfWordsPerBurst - 1, 33 bits)
    val burstShift = log2Up(parameter.halfWordsPerBurst)
    val burstCount = roundedTotalHalfWords(
      parameter.burstAddressWidth + burstShift downto burstShift
    )
    val rowBytes = io.width.resize(33) << 1
    val invalidConfiguration = io.baseAddress(0) || io.width === 0 || io.height === 0 ||
      (io.height =/= 1 && (io.sourceStride(0) || io.sourceStride.resize(33) < rowBytes))

    dma.io.start := False
    dma.io.base := rowBaseReg(parameter.axiAddressWidth - 1 downto log2Up(parameter.bytesPerBurst))
    dma.io.size := (burstCount - 1).resize(parameter.burstAddressWidth)

    when(!busyReg) {
      frameDoneToggleSeenReg := frameDoneToggleSync
      when(io.start) {
        when(invalidConfiguration) {
          errorReg := True
        } otherwise {
          busyReg := True
          errorReg := False
          startToggleReg := !startToggleReg
          activeBaseReg := io.baseAddress
          activeWidthReg := io.width
          activeHeightReg := io.height
          activeStrideReg := io.sourceStride
          rowBaseReg := io.baseAddress
          rowsRemainingReg := io.height
          rowStartPendingReg := True
          rowInFlightReg := False
        }
      }
    } otherwise {
      when(rowStartPendingReg && !dma.io.busy) {
        dma.io.start := True
        rowStartPendingReg := False
        rowInFlightReg := True
      }

      when(rowInFlightReg && !dma.io.busy) {
        rowInFlightReg := False
        when(rowsRemainingReg =/= 1) {
          rowBaseReg := rowBaseReg + activeStrideReg
          rowsRemainingReg := rowsRemainingReg - 1
          rowStartPendingReg := True
        }
      }

      when(frameDoneToggleSync =/= frameDoneToggleSeenReg) {
        frameDoneToggleSeenReg := frameDoneToggleSync
        busyReg := False
        rowStartPendingReg := False
        rowInFlightReg := False
      }
    }

    io.axi.readCmd.valid := dma.io.mem.cmd.valid
    io.axi.readCmd.addr := dma.io.mem.cmd.payload << log2Up(parameter.bytesPerBurst)
    io.axi.readCmd.prot := "010"
    io.axi.readCmd.cache := "1111"
    io.axi.readCmd.len := parameter.beatPerAccess - 1
    io.axi.readCmd.size := log2Up(parameter.axiDataWidth / 8)
    dma.io.mem.cmd.ready := io.axi.readCmd.ready

    dma.io.mem.rsp.valid := io.axi.readRsp.valid
    dma.io.mem.rsp.last := io.axi.readRsp.last
    dma.io.mem.rsp.fragment := io.axi.readRsp.data
    io.axi.readRsp.ready := True

    when(io.axi.readRsp.fire && io.axi.readRsp.resp(1)) {
      errorReg := True
    }
  }

  val frameArea = new ClockingArea(frameClockDomain) {
    val startToggleSync = BufferCC(axiArea.startToggleReg)
    val baseSync = BufferCC(axiArea.activeBaseReg)
    val widthSync = BufferCC(axiArea.activeWidthReg)
    val heightSync = BufferCC(axiArea.activeHeightReg)
    val strideSync = BufferCC(axiArea.activeStrideReg)

    val startToggleSeenReg = RegInit(False)
    val capturePendingReg = RegInit(False)
    val activeWidthReg = Reg(UInt(32 bits)) init (0)
    val activeStrideReg = Reg(UInt(32 bits)) init (0)
    val rowBaseReg = Reg(UInt(parameter.axiAddressWidth bits)) init (0)
    val rowsRemainingReg = Reg(UInt(32 bits)) init (0)
    val leadingRemainingReg = Reg(UInt(log2Up(parameter.halfWordsPerBurst) bits)) init (0)
    val validRemainingReg = Reg(UInt(32 bits)) init (0)
    val finalPixelReg = Reg(Bits(parameter.frameDataWidth bits)) init (0)
    val doneToggleReg = RegInit(False)

    val idleState = U(0, 3 bits)
    val skipHeadState = U(1, 3 bits)
    val emitPixelsState = U(2, 3 bits)
    val drainTailState = U(3, 3 bits)
    val emitFinalState = U(4, 3 bits)
    val stateReg = Reg(UInt(3 bits)) init (idleState)

    val nextRowBase = rowBaseReg + activeStrideReg
    val nextLeading = nextRowBase(log2Up(parameter.bytesPerBurst) - 1 downto 1)

    def advanceRow(): Unit = {
      rowBaseReg := nextRowBase
      rowsRemainingReg := rowsRemainingReg - 1
      leadingRemainingReg := nextLeading
      validRemainingReg := activeWidthReg
      stateReg := Mux(nextLeading === 0, emitPixelsState, skipHeadState)
    }

    when(startToggleSync =/= startToggleSeenReg) {
      startToggleSeenReg := startToggleSync
      capturePendingReg := True
      stateReg := idleState
    }

    when(capturePendingReg) {
      capturePendingReg := False
      activeWidthReg := widthSync
      activeStrideReg := strideSync
      rowBaseReg := baseSync
      rowsRemainingReg := heightSync
      leadingRemainingReg := baseSync(log2Up(parameter.bytesPerBurst) - 1 downto 1)
      validRemainingReg := widthSync
      stateReg := Mux(
        baseSync(log2Up(parameter.bytesPerBurst) - 1 downto 1) === 0,
        emitPixelsState,
        skipHeadState
      )
    }

    io.frame.valid := False
    io.frame.fragment := finalPixelReg
    io.frame.last := False
    axiArea.dma.io.frame.ready := False

    switch(stateReg) {
      is(skipHeadState) {
        axiArea.dma.io.frame.ready := True
        when(axiArea.dma.io.frame.fire) {
          leadingRemainingReg := leadingRemainingReg - 1
          when(leadingRemainingReg === 1) {
            stateReg := emitPixelsState
          }
        }
      }

      is(emitPixelsState) {
        val finalPixel = rowsRemainingReg === 1 && validRemainingReg === 1

        when(finalPixel) {
          axiArea.dma.io.frame.ready := True
          when(axiArea.dma.io.frame.fire) {
            finalPixelReg := axiArea.dma.io.frame.fragment
            validRemainingReg := 0
            stateReg := Mux(axiArea.dma.io.frame.last, emitFinalState, drainTailState)
          }
        } otherwise {
          io.frame.valid := axiArea.dma.io.frame.valid
          io.frame.fragment := axiArea.dma.io.frame.fragment
          axiArea.dma.io.frame.ready := io.frame.ready

          when(axiArea.dma.io.frame.fire) {
            validRemainingReg := validRemainingReg - 1
            when(validRemainingReg === 1) {
              when(axiArea.dma.io.frame.last) {
                advanceRow()
              } otherwise {
                stateReg := drainTailState
              }
            }
          }
        }
      }

      is(drainTailState) {
        axiArea.dma.io.frame.ready := True
        when(axiArea.dma.io.frame.fire && axiArea.dma.io.frame.last) {
          when(rowsRemainingReg === 1) {
            stateReg := emitFinalState
          } otherwise {
            advanceRow()
          }
        }
      }

      is(emitFinalState) {
        io.frame.valid := True
        io.frame.fragment := finalPixelReg
        io.frame.last := True
        when(io.frame.fire) {
          doneToggleReg := !doneToggleReg
          stateReg := idleState
        }
      }
    }
  }

  frameDoneToggle := frameArea.doneToggleReg
  io.busy := axiArea.busyReg
  io.error := axiArea.errorReg
}
