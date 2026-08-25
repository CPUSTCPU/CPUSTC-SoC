package chisel

import chisel3._
import chisel3.util._

/** 2D_GPU APB 寄存器偏移。 */
object TwoDGpuRegisters {
  val status: Int = 0x100
  val command: Int = 0x104
  val srcAddress: Int = 0x108
  val dstAddress: Int = 0x10c
  val srcStride: Int = 0x110
  val dstStride: Int = 0x114
  val srcXy: Int = 0x118
  val dstXy: Int = 0x11c
  val size: Int = 0x120
  val foreground: Int = 0x124
  val background: Int = 0x128
  val identification: Int = 0x12c
  val capabilities: Int = 0x130
  val version: Int = 0x134
  val srcSize: Int = 0x138
  val dstSize: Int = 0x13c
}

/** 2D_GPU 可由软件枚举的能力位。 */
object TwoDGpuCapabilities {
  val fillRect: Int = 1 << 0
  val copyArea: Int = 1 << 1
  val imageBlit1: Int = 1 << 2
  val yuyvScale: Int = 1 << 3
  val rgb565: Int = 1 << 8
  val overlapSafeCopy: Int = 1 << 9

  val value: Int =
    fillRect | copyArea | imageBlit1 | yuyvScale | rgb565 | overlapSafeCopy
  val interfaceVersion: Int = 2
}

/** 2D_GPU 固化命令编号。 */
object TwoDGpuCommands {
  val fillRect: Int = 1
  val copyArea: Int = 2
  val imageBlit1: Int = 3
  val yuyvScale: Int = 4
}

class TwoDGpuIO extends Bundle {
  val apb: APB3IO = Flipped(new APB3IO(addrWidth = 20))
  val axi: AXI4IO = new AXI4IO(
    idWidth = 3,
    addrWidth = 32,
    lenWidth = 8,
    lockWidth = 1,
    dataWidth = 32,
    strbWidth = 4
  )
}

/** 面向 RGB565 framebuffer 的小型二维图形加速器。
  *
  * 模块固化 FILL_RECT、COPY_AREA、MSB-first 1-bit IMAGE_BLIT1 和
  * YUYV_SCALE 操作。YUYV_SCALE 使用 BT.601 limited-range 转换、最近邻放大和
  * RGB565 输出；APB 负责同步命令提交，AXI4 master 负责访问 DDR。
  */
class TwoDGpu extends Module {
  override def desiredName: String = "TwoD_GPU"

  val io: TwoDGpuIO = IO(new TwoDGpuIO)

  private val statusBusyBit = 0
  private val statusDoneBit = 1
  private val statusErrorBit = 2

  private val commandReg = RegInit(0.U(32.W))
  private val srcAddressReg = RegInit(0.U(32.W))
  private val dstAddressReg = RegInit(0.U(32.W))
  private val srcStrideReg = RegInit(0.U(32.W))
  private val dstStrideReg = RegInit(0.U(32.W))
  private val srcXyReg = RegInit(0.U(32.W))
  private val dstXyReg = RegInit(0.U(32.W))
  private val sizeReg = RegInit(0.U(32.W))
  private val foregroundReg = RegInit(0.U(32.W))
  private val backgroundReg = RegInit(0.U(32.W))
  private val srcSizeReg = RegInit(0.U(32.W))
  private val dstSizeReg = RegInit(0.U(32.W))

  private val busyReg = RegInit(false.B)
  private val doneReg = RegInit(false.B)
  private val errorReg = RegInit(false.B)

  private val srcX = srcXyReg(15, 0)
  private val srcY = srcXyReg(31, 16)
  private val dstX = dstXyReg(15, 0)
  private val dstY = dstXyReg(31, 16)
  private val width = sizeReg(15, 0)
  private val height = sizeReg(31, 16)
  private val scaleSrcWidth = srcSizeReg(15, 0)
  private val scaleSrcHeight = srcSizeReg(31, 16)
  private val scaleDstWidth = dstSizeReg(15, 0)
  private val scaleDstHeight = dstSizeReg(31, 16)

  private val Seq(
    sIdle,
    sSetup,
    sChunkPrepare,
    sReadAddress,
    sReadData,
    sWriteAddress,
    sWriteData,
    sWriteResponse,
    sBlitPrepare,
    sScaleLoadAddress,
    sScaleLoadData,
    sScaleChunkPrepare,
    sScalePixelRead,
    sScalePixelStore
  ) = Enum(14)
  private val state = RegInit(sIdle)

  private val backwardReg = RegInit(false.B)
  private val sourceRowAddress = RegInit(0.U(33.W))
  private val destinationRowAddress = RegInit(0.U(33.W))
  private val currentSourcePixelAddress = RegInit(0.U(33.W))
  private val currentDestinationPixelAddress = RegInit(0.U(33.W))
  private val rowIndex = RegInit(0.U(16.W))
  private val pixelsRemaining = RegInit(0.U(16.W))
  private val pixelData = RegInit(0.U(16.W))

  private val glyphByteAddress = RegInit(0.U(32.W))
  private val glyphBitIndex = RegInit(0.U(3.W))
  private val glyphCacheValid = RegInit(false.B)
  private val glyphCacheAddress = RegInit(0.U(32.W))
  private val glyphCacheData = RegInit(0.U(32.W))

  private val chunkPixelCount = RegInit(0.U(6.W))
  private val sourceWordAddress = RegInit(0.U(32.W))
  private val destinationWordAddress = RegInit(0.U(32.W))
  private val sourceWordCount = RegInit(0.U(5.W))
  private val destinationWordCount = RegInit(0.U(5.W))
  private val sourceHalfOffset = RegInit(false.B)
  private val destinationHalfOffset = RegInit(false.B)
  private val readBeatIndex = RegInit(0.U(5.W))
  private val writeBeatIndex = RegInit(0.U(4.W))
  private val readErrorSeen = RegInit(false.B)
  private val copyBuffer = Reg(Vec(16, UInt(32.W)))

  private val scaleLoadAddress = RegInit(0.U(32.W))
  private val scaleLoadWordsRemaining = RegInit(0.U(10.W))
  private val scaleLoadPairIndex = RegInit(0.U(9.W))
  private val scaleSourcePixelIndex = RegInit(0.U(10.W))
  private val scaleXError = RegInit(0.U(17.W))
  private val scaleYError = RegInit(0.U(17.W))
  private val scalePreparePixelIndex = RegInit(0.U(6.W))
  private val scaleLineBuffer = SyncReadMem(512, UInt(32.W))
  private val scaleLineReadData = scaleLineBuffer.read(
    scaleSourcePixelIndex(9, 1),
    state === sScalePixelRead
  )
  private val scaleWriteBuffer = Reg(Vec(16, UInt(32.W)))

  private val registerOffset = io.apb.paddr(13, 0)
  private val registerOffsets = Seq(
    TwoDGpuRegisters.status,
    TwoDGpuRegisters.command,
    TwoDGpuRegisters.srcAddress,
    TwoDGpuRegisters.dstAddress,
    TwoDGpuRegisters.srcStride,
    TwoDGpuRegisters.dstStride,
    TwoDGpuRegisters.srcXy,
    TwoDGpuRegisters.dstXy,
    TwoDGpuRegisters.size,
    TwoDGpuRegisters.foreground,
    TwoDGpuRegisters.background,
    TwoDGpuRegisters.identification,
    TwoDGpuRegisters.capabilities,
    TwoDGpuRegisters.version,
    TwoDGpuRegisters.srcSize,
    TwoDGpuRegisters.dstSize
  )
  private val knownRegister = registerOffsets
    .map(offset => registerOffset === offset.U)
    .reduce(_ || _)
  private val apbAccess = io.apb.psel && io.apb.penable
  private val apbWrite = apbAccess && io.apb.pwrite
  private val statusWrite = apbWrite && registerOffset === TwoDGpuRegisters.status.U
  private val commandWrite = apbWrite && registerOffset === TwoDGpuRegisters.command.U
  private val readOnlyRegister =
    registerOffset === TwoDGpuRegisters.identification.U ||
      registerOffset === TwoDGpuRegisters.capabilities.U ||
      registerOffset === TwoDGpuRegisters.version.U
  private val readOnlyWrite = apbWrite && readOnlyRegister
  private val blockedWrite = apbWrite &&
    ((busyReg && !statusWrite) || readOnlyWrite)

  private val statusValue = Cat(
    0.U(29.W),
    errorReg,
    doneReg,
    busyReg
  )
  io.apb.prdata := MuxLookup(registerOffset, 0.U)(Seq(
    TwoDGpuRegisters.status.U -> statusValue,
    TwoDGpuRegisters.command.U -> commandReg,
    TwoDGpuRegisters.srcAddress.U -> srcAddressReg,
    TwoDGpuRegisters.dstAddress.U -> dstAddressReg,
    TwoDGpuRegisters.srcStride.U -> srcStrideReg,
    TwoDGpuRegisters.dstStride.U -> dstStrideReg,
    TwoDGpuRegisters.srcXy.U -> srcXyReg,
    TwoDGpuRegisters.dstXy.U -> dstXyReg,
    TwoDGpuRegisters.size.U -> sizeReg,
    TwoDGpuRegisters.foreground.U -> foregroundReg,
    TwoDGpuRegisters.background.U -> backgroundReg,
    TwoDGpuRegisters.identification.U -> "h32444750".U,
    TwoDGpuRegisters.capabilities.U -> TwoDGpuCapabilities.value.U,
    TwoDGpuRegisters.version.U -> TwoDGpuCapabilities.interfaceVersion.U,
    TwoDGpuRegisters.srcSize.U -> srcSizeReg,
    TwoDGpuRegisters.dstSize.U -> dstSizeReg
  ))
  io.apb.pready := true.B
  io.apb.pslverr := apbAccess && (!knownRegister || blockedWrite)

  when(statusWrite) {
    when(io.apb.pwdata(statusDoneBit)) {
      doneReg := false.B
    }
    when(io.apb.pwdata(statusErrorBit)) {
      errorReg := false.B
    }
  }

  when(apbWrite && !busyReg) {
    switch(registerOffset) {
      is(TwoDGpuRegisters.srcAddress.U) { srcAddressReg := io.apb.pwdata }
      is(TwoDGpuRegisters.dstAddress.U) { dstAddressReg := io.apb.pwdata }
      is(TwoDGpuRegisters.srcStride.U) { srcStrideReg := io.apb.pwdata }
      is(TwoDGpuRegisters.dstStride.U) { dstStrideReg := io.apb.pwdata }
      is(TwoDGpuRegisters.srcXy.U) { srcXyReg := io.apb.pwdata }
      is(TwoDGpuRegisters.dstXy.U) { dstXyReg := io.apb.pwdata }
      is(TwoDGpuRegisters.size.U) { sizeReg := io.apb.pwdata }
      is(TwoDGpuRegisters.foreground.U) { foregroundReg := io.apb.pwdata }
      is(TwoDGpuRegisters.background.U) { backgroundReg := io.apb.pwdata }
      is(TwoDGpuRegisters.srcSize.U) { srcSizeReg := io.apb.pwdata }
      is(TwoDGpuRegisters.dstSize.U) { dstSizeReg := io.apb.pwdata }
    }
  }

  private val requestedCommand = io.apb.pwdata
  private val requestedFill = requestedCommand === TwoDGpuCommands.fillRect.U
  private val requestedCopy = requestedCommand === TwoDGpuCommands.copyArea.U
  private val requestedBlit = requestedCommand === TwoDGpuCommands.imageBlit1.U
  private val requestedScale = requestedCommand === TwoDGpuCommands.yuyvScale.U
  private val requestedCommandValid =
    requestedFill || requestedCopy || requestedBlit || requestedScale

  private val srcRight = srcX +& width
  private val dstRight = dstX +& width
  private val srcBottom = srcY +& height
  private val dstBottom = dstY +& height
  private val dstRowBytes = dstRight << 1
  private val copySrcRowBytes = srcRight << 1
  private val glyphSrcRowBytes = (srcRight + 7.U) >> 3
  private val dstEndAddress = dstAddressReg +&
    ((dstBottom - 1.U) * dstStrideReg) +& dstRowBytes
  private val copySrcEndAddress = srcAddressReg +&
    ((srcBottom - 1.U) * srcStrideReg) +& copySrcRowBytes
  private val glyphSrcEndAddress = srcAddressReg +&
    ((srcBottom - 1.U) * srcStrideReg) +& glyphSrcRowBytes
  private val scaleSrcRight = srcX +& scaleSrcWidth
  private val scaleSrcBottom = srcY +& scaleSrcHeight
  private val scaleDstRight = dstX +& scaleDstWidth
  private val scaleDstBottom = dstY +& scaleDstHeight
  private val scaleSrcRowBytes = scaleSrcRight << 1
  private val scaleDstRowBytes = scaleDstRight << 1
  private val scaleSrcFirstAddress = srcAddressReg +&
    (srcY * srcStrideReg) +& (srcX << 1)
  private val scaleDstFirstAddress = dstAddressReg +&
    (dstY * dstStrideReg) +& (dstX << 1)
  private val scaleSrcEndAddress = srcAddressReg +&
    ((scaleSrcBottom - 1.U) * srcStrideReg) +& scaleSrcRowBytes
  private val scaleDstEndAddress = dstAddressReg +&
    ((scaleDstBottom - 1.U) * dstStrideReg) +& scaleDstRowBytes
  private val addressLimit = (BigInt(1) << 32).U
  private val copySourceFirstAddress = srcAddressReg +&
    (srcY * srcStrideReg) +& (srcX << 1)
  private val copyDestinationFirstAddress = dstAddressReg +&
    (dstY * dstStrideReg) +& (dstX << 1)
  private val copyAddressRangesOverlap =
    copySourceFirstAddress < dstEndAddress &&
      copyDestinationFirstAddress < copySrcEndAddress
  private val sameCopySurface =
    srcAddressReg === dstAddressReg && srcStrideReg === dstStrideReg
  private val unsafeAliasedCopy =
    requestedCopy && !sameCopySurface && copyAddressRangesOverlap
  private val scaleAddressRangesOverlap =
    scaleSrcFirstAddress < scaleDstEndAddress &&
      scaleDstFirstAddress < scaleSrcEndAddress

  private val requestedLegacyConfigurationValid =
    width =/= 0.U && height =/= 0.U &&
      dstStrideReg =/= 0.U && !dstAddressReg(0) && !dstStrideReg(0) &&
      dstRight <= 65536.U && dstBottom <= 65536.U &&
      dstRowBytes <= dstStrideReg && dstEndAddress <= addressLimit &&
      (!requestedCopy ||
        (srcStrideReg =/= 0.U && !srcAddressReg(0) && !srcStrideReg(0) &&
          srcRight <= 65536.U && srcBottom <= 65536.U &&
          copySrcRowBytes <= srcStrideReg && copySrcEndAddress <= addressLimit &&
          !unsafeAliasedCopy)) &&
      (!requestedBlit ||
        (srcStrideReg =/= 0.U && srcRight <= 65536.U && srcBottom <= 65536.U &&
          glyphSrcRowBytes <= srcStrideReg && glyphSrcEndAddress <= addressLimit))
  private val requestedScaleConfigurationValid =
    scaleSrcWidth =/= 0.U && scaleSrcHeight =/= 0.U &&
      scaleDstWidth =/= 0.U && scaleDstHeight =/= 0.U &&
      scaleSrcWidth <= 1024.U &&
      scaleDstWidth >= scaleSrcWidth && scaleDstHeight >= scaleSrcHeight &&
      !srcX(0) && !scaleSrcWidth(0) && !dstX(0) && !scaleDstWidth(0) &&
      srcStrideReg =/= 0.U && dstStrideReg =/= 0.U &&
      !srcAddressReg(1, 0).orR && !dstAddressReg(1, 0).orR &&
      !srcStrideReg(1, 0).orR && !dstStrideReg(1, 0).orR &&
      scaleSrcRight <= 65536.U && scaleSrcBottom <= 65536.U &&
      scaleDstRight <= 65536.U && scaleDstBottom <= 65536.U &&
      scaleSrcRowBytes <= srcStrideReg && scaleDstRowBytes <= dstStrideReg &&
      scaleSrcEndAddress <= addressLimit && scaleDstEndAddress <= addressLimit &&
      !scaleAddressRangesOverlap
  private val requestedConfigurationValid = Mux(
    requestedScale,
    requestedScaleConfigurationValid,
    requestedLegacyConfigurationValid
  )

  private val rectanglesOverlap =
    srcX < dstRight && dstX < srcRight && srcY < dstBottom && dstY < srcBottom
  private val copyBackward = requestedCopy && sameCopySurface && rectanglesOverlap &&
    (dstY > srcY || (dstY === srcY && dstX > srcX))

  when(commandWrite && !busyReg) {
    commandReg := requestedCommand
    doneReg := false.B
    errorReg := false.B
    glyphCacheValid := false.B

    when(requestedCommandValid && requestedConfigurationValid) {
      val sourceColumnOffset = Mux(
        requestedBlit,
        Cat(0.U(16.W), srcX) >> 3,
        Cat(0.U(15.W), srcX, 0.U(1.W))
      )
      val destinationColumnOffset = Cat(0.U(15.W), dstX, 0.U(1.W))
      val sourceStartY = Mux(copyBackward, srcY + height - 1.U, srcY)
      val destinationStartY = Mux(copyBackward, dstY + height - 1.U, dstY)
      val sourceRowStart = srcAddressReg +&
        (sourceStartY * srcStrideReg) +& sourceColumnOffset
      val destinationRowStart = dstAddressReg +&
        (destinationStartY * dstStrideReg) +& destinationColumnOffset
      val rowEndOffset = Cat(0.U(15.W), width, 0.U(1.W))

      busyReg := true.B
      backwardReg := copyBackward
      rowIndex := 0.U
      when(requestedScale) {
        sourceRowAddress := scaleSrcFirstAddress
        destinationRowAddress := scaleDstFirstAddress
        currentDestinationPixelAddress := scaleDstFirstAddress
        pixelsRemaining := scaleDstWidth
        scaleLoadAddress := scaleSrcFirstAddress(31, 0)
        scaleLoadWordsRemaining := scaleSrcWidth >> 1
        scaleLoadPairIndex := 0.U
        scaleSourcePixelIndex := 0.U
        scaleXError := 0.U
        scaleYError := 0.U
        state := sScaleLoadAddress
      }.otherwise {
        sourceRowAddress := Mux(
          copyBackward,
          sourceRowStart + rowEndOffset,
          sourceRowStart
        )
        destinationRowAddress := Mux(
          copyBackward,
          destinationRowStart + rowEndOffset,
          destinationRowStart
        )
        pixelsRemaining := width
        state := sSetup
      }
    }.otherwise {
      doneReg := true.B
      errorReg := true.B
    }
  }

  private def glyphPixel(word: UInt): UInt = {
    val bytes = VecInit((0 until 4).map(index => word(index * 8 + 7, index * 8)))
    val selectedByte = bytes(glyphByteAddress(1, 0))
    Mux(
      selectedByte(7.U(3.W) - glyphBitIndex),
      foregroundReg(15, 0),
      backgroundReg(15, 0)
    )
  }

  private def clampToByte(value: SInt): UInt = {
    val clamped = Wire(UInt(8.W))
    val unsigned = value.asUInt

    when(value < 0.S) {
      clamped := 0.U
    }.elsewhen(value > 255.S) {
      clamped := 255.U
    }.otherwise {
      clamped := unsigned(7, 0)
    }
    clamped
  }

  private def luminanceTerm(y: UInt): SInt = {
    val limited = Wire(SInt(32.W))

    limited := Mux(y < 16.U, 0.S, y.zext - 16.S)
    (limited << 8) + (limited << 5) + (limited << 3) + (limited << 1)
  }

  private def yuyvPixel(y: UInt, u: UInt, v: UInt): UInt = {
    val chromaU = Wire(SInt(32.W))
    val chromaV = Wire(SInt(32.W))
    val redChroma = Wire(SInt(32.W))
    val greenChroma = Wire(SInt(32.W))
    val blueChroma = Wire(SInt(32.W))
    val luminance = luminanceTerm(y)
    val red = Wire(SInt(32.W))
    val green = Wire(SInt(32.W))
    val blue = Wire(SInt(32.W))
    val redByte = Wire(UInt(8.W))
    val greenByte = Wire(UInt(8.W))
    val blueByte = Wire(UInt(8.W))

    chromaU := u.zext - 128.S
    chromaV := v.zext - 128.S
    redChroma :=
      (chromaV << 8) + (chromaV << 7) + (chromaV << 4) +
        (chromaV << 3) + chromaV
    greenChroma := -(
      (chromaU << 6) + (chromaU << 5) + (chromaU << 2) +
        (chromaV << 7) + (chromaV << 6) + (chromaV << 4)
    )
    blueChroma := (chromaU << 9) + (chromaU << 2)
    red := (luminance + redChroma + 128.S) >> 8
    green := (luminance + greenChroma + 128.S) >> 8
    blue := (luminance + blueChroma + 128.S) >> 8
    redByte := clampToByte(red)
    greenByte := clampToByte(green)
    blueByte := clampToByte(blue)
    Cat(redByte(7, 3), greenByte(7, 2), blueByte(7, 3))
  }

  private val convertedYuyvPair = Cat(
    yuyvPixel(io.axi.rdata(23, 16), io.axi.rdata(15, 8), io.axi.rdata(31, 24)),
    yuyvPixel(io.axi.rdata(7, 0), io.axi.rdata(15, 8), io.axi.rdata(31, 24))
  )

  private def completeCommand(hasError: Bool): Unit = {
    busyReg := false.B
    doneReg := true.B
    errorReg := hasError
    state := sIdle
  }

  private val operationFill = commandReg === TwoDGpuCommands.fillRect.U
  private val operationCopy = commandReg === TwoDGpuCommands.copyArea.U
  private val operationBlit = commandReg === TwoDGpuCommands.imageBlit1.U
  private val operationScale = commandReg === TwoDGpuCommands.yuyvScale.U

  private def minimum(first: UInt, second: UInt): UInt = Mux(first < second, first, second)

  /** 返回从当前半字地址向前发送、且不跨 4 KiB 的最大像素数。 */
  private def forwardPixelCapacity(address: UInt, maximumPixels: Int): UInt = {
    val alignedOffset = Cat(address(11, 2), 0.U(2.W))
    val wordsToBoundary = (4096.U(13.W) - Cat(0.U(1.W), alignedOffset)) >> 2
    val limitedWords = minimum(wordsToBoundary, 16.U)
    minimum((limitedWords << 1) - address(1), maximumPixels.U)
  }

  /** 返回从当前尾后地址向后发送、且不跨 4 KiB 的最大像素数。 */
  private def backwardPixelCapacity(address: UInt, maximumPixels: Int): UInt = {
    val bytesToBoundary = Mux(
      address(11, 0) === 0.U,
      4096.U(13.W),
      Cat(0.U(1.W), address(11, 0))
    )
    minimum(bytesToBoundary >> 1, maximumPixels.U)
  }

  private def bufferedPixel(pixelIndex: UInt): UInt = {
    val sourceIndex = pixelIndex + sourceHalfOffset
    val word = copyBuffer(sourceIndex(4, 1))
    Mux(sourceIndex(0), word(31, 16), word(15, 0))
  }

  private val destinationStreamIndex = Cat(0.U(1.W), writeBeatIndex, 0.U(1.W))
  private val lowerPixelIndex = Mux(
    destinationHalfOffset,
    Mux(writeBeatIndex === 0.U, 0.U, destinationStreamIndex - 1.U),
    destinationStreamIndex
  )
  private val upperPixelIndex = Mux(
    destinationHalfOffset,
    destinationStreamIndex,
    destinationStreamIndex + 1.U
  )
  private val lowerPixelValid =
    (!destinationHalfOffset || writeBeatIndex =/= 0.U) &&
      lowerPixelIndex < chunkPixelCount
  private val upperPixelValid = upperPixelIndex < chunkPixelCount
  private val copyWriteData = Cat(bufferedPixel(upperPixelIndex), bufferedPixel(lowerPixelIndex))
  private val burstWriteStrobe = Cat(
    Fill(2, upperPixelValid),
    Fill(2, lowerPixelValid)
  )
  private val scaleLoadBytesToBoundary = 4096.U(13.W) -
    Cat(0.U(1.W), scaleLoadAddress(11, 0))
  private val scaleLoadWordsToBoundary = scaleLoadBytesToBoundary >> 2
  private val scaleLoadBurstWords = minimum(
    scaleLoadWordsRemaining,
    minimum(scaleLoadWordsToBoundary, 16.U)
  )

  io.axi.awid := 0.U
  io.axi.awaddr := destinationWordAddress
  io.axi.awlen := Mux(destinationWordCount === 0.U, 0.U, destinationWordCount - 1.U)
  io.axi.awsize := 2.U
  io.axi.awburst := 1.U
  io.axi.awlock := 0.U
  io.axi.awcache := 0.U
  io.axi.awprot := 0.U
  io.axi.awqos := 0.U
  io.axi.awregion := 0.U
  io.axi.awvalid := state === sWriteAddress

  io.axi.wdata := Mux(
    operationScale,
    scaleWriteBuffer(writeBeatIndex),
    Mux(
      operationCopy,
      copyWriteData,
      Fill(2, Mux(operationFill, foregroundReg(15, 0), pixelData))
    )
  )
  io.axi.wstrb := Mux(
    operationScale,
    "b1111".U,
    Mux(
      operationBlit,
      Mux(destinationHalfOffset, "b1100".U, "b0011".U),
      burstWriteStrobe
    )
  )
  io.axi.wlast := writeBeatIndex === destinationWordCount - 1.U
  io.axi.wvalid := state === sWriteData

  io.axi.bready := state === sWriteResponse

  io.axi.arid := 0.U
  io.axi.araddr := Mux(operationScale, scaleLoadAddress, sourceWordAddress)
  io.axi.arlen := Mux(
    operationScale,
    Mux(scaleLoadBurstWords === 0.U, 0.U, scaleLoadBurstWords - 1.U),
    Mux(sourceWordCount === 0.U, 0.U, sourceWordCount - 1.U)
  )
  io.axi.arsize := 2.U
  io.axi.arburst := 1.U
  io.axi.arlock := 0.U
  io.axi.arcache := 0.U
  io.axi.arprot := 0.U
  io.axi.arqos := 0.U
  io.axi.arregion := 0.U
  io.axi.arvalid := state === sReadAddress || state === sScaleLoadAddress

  io.axi.rready := state === sReadData || state === sScaleLoadData

  when(state === sSetup) {
    currentSourcePixelAddress := sourceRowAddress
    currentDestinationPixelAddress := destinationRowAddress
    glyphByteAddress := sourceRowAddress(31, 0)
    glyphBitIndex := srcX(2, 0)
    state := Mux(operationBlit, sBlitPrepare, sChunkPrepare)
  }.elsewhen(state === sChunkPrepare) {
    val fillCapacity = forwardPixelCapacity(currentDestinationPixelAddress, 32)
    val copySourceCapacity = Mux(
      backwardReg,
      backwardPixelCapacity(currentSourcePixelAddress, 30),
      forwardPixelCapacity(currentSourcePixelAddress, 30)
    )
    val copyDestinationCapacity = Mux(
      backwardReg,
      backwardPixelCapacity(currentDestinationPixelAddress, 30),
      forwardPixelCapacity(currentDestinationPixelAddress, 30)
    )
    val copyCapacity = minimum(copySourceCapacity, copyDestinationCapacity)
    val selectedCapacity = Mux(operationFill, fillCapacity, copyCapacity)
    val selectedPixels = minimum(pixelsRemaining, selectedCapacity)
    val sourceChunkStart = Mux(
      backwardReg,
      currentSourcePixelAddress - (selectedPixels << 1),
      currentSourcePixelAddress
    )
    val destinationChunkStart = Mux(
      backwardReg,
      currentDestinationPixelAddress - (selectedPixels << 1),
      currentDestinationPixelAddress
    )
    val selectedSourceWords =
      (selectedPixels + sourceChunkStart(1) + 1.U) >> 1
    val selectedDestinationWords =
      (selectedPixels + destinationChunkStart(1) + 1.U) >> 1

    chunkPixelCount := selectedPixels
    destinationWordAddress := destinationChunkStart(31, 0) & "hfffffffc".U
    destinationWordCount := selectedDestinationWords
    destinationHalfOffset := destinationChunkStart(1)
    writeBeatIndex := 0.U
    when(operationFill) {
      state := sWriteAddress
    }.elsewhen(operationCopy) {
      sourceWordAddress := sourceChunkStart(31, 0) & "hfffffffc".U
      sourceWordCount := selectedSourceWords
      sourceHalfOffset := sourceChunkStart(1)
      readBeatIndex := 0.U
      readErrorSeen := false.B
      state := sReadAddress
    }.otherwise {
      completeCommand(true.B)
    }
  }.elsewhen(state === sBlitPrepare) {
    val glyphWordAddress = glyphByteAddress & "hfffffffc".U

    destinationWordAddress := currentDestinationPixelAddress & "hfffffffc".U
    destinationWordCount := 1.U
    destinationHalfOffset := currentDestinationPixelAddress(1)
    chunkPixelCount := 1.U
    writeBeatIndex := 0.U
    when(glyphCacheValid && glyphCacheAddress === glyphWordAddress) {
      pixelData := glyphPixel(glyphCacheData)
      state := sWriteAddress
    }.otherwise {
      sourceWordAddress := glyphWordAddress
      sourceWordCount := 1.U
      readBeatIndex := 0.U
      readErrorSeen := false.B
      state := sReadAddress
    }
  }.elsewhen(state === sScaleLoadAddress) {
    when(io.axi.arvalid && io.axi.arready) {
      sourceWordCount := scaleLoadBurstWords
      readBeatIndex := 0.U
      readErrorSeen := false.B
      state := sScaleLoadData
    }
  }.elsewhen(state === sScaleLoadData) {
    when(io.axi.rvalid && io.axi.rready) {
      val expectedLast = readBeatIndex === sourceWordCount - 1.U
      val beatError = io.axi.rresp =/= 0.U || io.axi.rid =/= 0.U ||
        io.axi.rlast =/= expectedLast
      val commandReadError = readErrorSeen || beatError
      val writeIndex = scaleLoadPairIndex + readBeatIndex

      readErrorSeen := commandReadError
      scaleLineBuffer.write(writeIndex(8, 0), convertedYuyvPair)
      when(io.axi.rlast) {
        when(commandReadError) {
          completeCommand(true.B)
        }.elsewhen(scaleLoadWordsRemaining === sourceWordCount) {
          scaleSourcePixelIndex := 0.U
          scaleXError := 0.U
          state := sScaleChunkPrepare
        }.otherwise {
          scaleLoadAddress := scaleLoadAddress + (sourceWordCount << 2)
          scaleLoadWordsRemaining := scaleLoadWordsRemaining - sourceWordCount
          scaleLoadPairIndex := scaleLoadPairIndex + sourceWordCount
          state := sScaleLoadAddress
        }
      }.otherwise {
        readBeatIndex := readBeatIndex + 1.U
      }
    }
  }.elsewhen(state === sScaleChunkPrepare) {
    val destinationCapacity = forwardPixelCapacity(currentDestinationPixelAddress, 32)
    val selectedPixels = minimum(pixelsRemaining, destinationCapacity)

    chunkPixelCount := selectedPixels
    destinationWordAddress := currentDestinationPixelAddress(31, 0)
    destinationWordCount := selectedPixels >> 1
    destinationHalfOffset := false.B
    scalePreparePixelIndex := 0.U
    state := sScalePixelRead
  }.elsewhen(state === sScalePixelRead) {
    state := sScalePixelStore
  }.elsewhen(state === sScalePixelStore) {
    val selectedPixel = Mux(
      scaleSourcePixelIndex(0),
      scaleLineReadData(31, 16),
      scaleLineReadData(15, 0)
    )
    val bufferIndex = scalePreparePixelIndex(4, 1)
    val nextXError = scaleXError +& scaleSrcWidth

    when(scalePreparePixelIndex(0)) {
      scaleWriteBuffer(bufferIndex) := Cat(
        selectedPixel,
        scaleWriteBuffer(bufferIndex)(15, 0)
      )
    }.otherwise {
      scaleWriteBuffer(bufferIndex) := Cat(0.U(16.W), selectedPixel)
    }
    when(nextXError >= scaleDstWidth) {
      scaleXError := nextXError - scaleDstWidth
      scaleSourcePixelIndex := scaleSourcePixelIndex + 1.U
    }.otherwise {
      scaleXError := nextXError
    }
    when(scalePreparePixelIndex === chunkPixelCount - 1.U) {
      writeBeatIndex := 0.U
      state := sWriteAddress
    }.otherwise {
      scalePreparePixelIndex := scalePreparePixelIndex + 1.U
      state := sScalePixelRead
    }
  }.elsewhen(state === sReadAddress) {
    when(io.axi.arvalid && io.axi.arready) {
      state := sReadData
    }
  }.elsewhen(state === sReadData) {
    when(io.axi.rvalid && io.axi.rready) {
      val expectedLast = readBeatIndex === sourceWordCount - 1.U
      val beatError = io.axi.rresp =/= 0.U || io.axi.rid =/= 0.U ||
        io.axi.rlast =/= expectedLast
      val commandReadError = readErrorSeen || beatError

      readErrorSeen := commandReadError
      when(operationCopy && readBeatIndex < sourceWordCount) {
        copyBuffer(readBeatIndex(3, 0)) := io.axi.rdata
      }.elsewhen(operationBlit) {
        glyphCacheValid := true.B
        glyphCacheAddress := sourceWordAddress
        glyphCacheData := io.axi.rdata
        pixelData := glyphPixel(io.axi.rdata)
      }
      when(io.axi.rlast) {
        when(commandReadError) {
          completeCommand(true.B)
        }.otherwise {
          writeBeatIndex := 0.U
          state := sWriteAddress
        }
      }.otherwise {
        readBeatIndex := readBeatIndex + 1.U
      }
    }
  }.elsewhen(state === sWriteAddress) {
    when(io.axi.awvalid && io.axi.awready) {
      state := sWriteData
    }
  }.elsewhen(state === sWriteData) {
    when(io.axi.wvalid && io.axi.wready) {
      when(io.axi.wlast) {
        state := sWriteResponse
      }.otherwise {
        writeBeatIndex := writeBeatIndex + 1.U
      }
    }
  }.elsewhen(state === sWriteResponse) {
    when(io.axi.bvalid && io.axi.bready) {
      when(io.axi.bresp =/= 0.U || io.axi.bid =/= 0.U) {
        completeCommand(true.B)
      }.otherwise {
        when(operationScale) {
          val rowComplete = pixelsRemaining === chunkPixelCount
          val commandComplete = rowComplete && rowIndex === scaleDstHeight - 1.U

          when(commandComplete) {
            completeCommand(false.B)
          }.elsewhen(rowComplete) {
            val nextSourceRowAddress = sourceRowAddress + srcStrideReg
            val nextDestinationRowAddress = destinationRowAddress + dstStrideReg
            val nextYError = scaleYError +& scaleSrcHeight
            val advanceSource = nextYError >= scaleDstHeight

            rowIndex := rowIndex + 1.U
            pixelsRemaining := scaleDstWidth
            destinationRowAddress := nextDestinationRowAddress
            currentDestinationPixelAddress := nextDestinationRowAddress
            scaleSourcePixelIndex := 0.U
            scaleXError := 0.U
            when(advanceSource) {
              sourceRowAddress := nextSourceRowAddress
              scaleLoadAddress := nextSourceRowAddress(31, 0)
              scaleLoadWordsRemaining := scaleSrcWidth >> 1
              scaleLoadPairIndex := 0.U
              scaleYError := nextYError - scaleDstHeight
              state := sScaleLoadAddress
            }.otherwise {
              scaleYError := nextYError
              state := sScaleChunkPrepare
            }
          }.otherwise {
            val chunkBytes = chunkPixelCount << 1

            pixelsRemaining := pixelsRemaining - chunkPixelCount
            currentDestinationPixelAddress :=
              currentDestinationPixelAddress + chunkBytes
            state := sScaleChunkPrepare
          }
        }.elsewhen(operationBlit) {
          val rowComplete = pixelsRemaining === 1.U
          val commandComplete = rowComplete && rowIndex === height - 1.U

          when(commandComplete) {
            completeCommand(false.B)
          }.elsewhen(rowComplete) {
            val nextSourceRowAddress = sourceRowAddress + srcStrideReg
            val nextDestinationRowAddress = destinationRowAddress + dstStrideReg

            rowIndex := rowIndex + 1.U
            pixelsRemaining := width
            sourceRowAddress := nextSourceRowAddress
            destinationRowAddress := nextDestinationRowAddress
            currentSourcePixelAddress := nextSourceRowAddress
            currentDestinationPixelAddress := nextDestinationRowAddress
            glyphByteAddress := nextSourceRowAddress(31, 0)
            glyphBitIndex := srcX(2, 0)
            state := sBlitPrepare
          }.otherwise {
            pixelsRemaining := pixelsRemaining - 1.U
            currentDestinationPixelAddress := currentDestinationPixelAddress + 2.U
            when(glyphBitIndex === 7.U) {
              glyphBitIndex := 0.U
              glyphByteAddress := glyphByteAddress + 1.U
            }.otherwise {
              glyphBitIndex := glyphBitIndex + 1.U
            }
            state := sBlitPrepare
          }
        }.otherwise {
          val rowComplete = pixelsRemaining === chunkPixelCount
          val commandComplete = rowComplete && rowIndex === height - 1.U

          when(commandComplete) {
            completeCommand(false.B)
          }.elsewhen(rowComplete) {
            val nextSourceRowAddress = Mux(
              backwardReg,
              sourceRowAddress - srcStrideReg,
              sourceRowAddress + srcStrideReg
            )
            val nextDestinationRowAddress = Mux(
              backwardReg,
              destinationRowAddress - dstStrideReg,
              destinationRowAddress + dstStrideReg
            )

            rowIndex := rowIndex + 1.U
            pixelsRemaining := width
            sourceRowAddress := nextSourceRowAddress
            destinationRowAddress := nextDestinationRowAddress
            currentSourcePixelAddress := nextSourceRowAddress
            currentDestinationPixelAddress := nextDestinationRowAddress
            state := sChunkPrepare
          }.otherwise {
            val chunkBytes = chunkPixelCount << 1

            pixelsRemaining := pixelsRemaining - chunkPixelCount
            currentSourcePixelAddress := Mux(
              backwardReg,
              currentSourcePixelAddress - chunkBytes,
              currentSourcePixelAddress + chunkBytes
            )
            currentDestinationPixelAddress := Mux(
              backwardReg,
              currentDestinationPixelAddress - chunkBytes,
              currentDestinationPixelAddress + chunkBytes
            )
            state := sChunkPrepare
          }
        }
      }
    }
  }
}
