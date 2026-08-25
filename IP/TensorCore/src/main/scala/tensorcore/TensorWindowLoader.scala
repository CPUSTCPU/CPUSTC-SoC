package tensorcore

import chisel3._
import chisel3.util._

/** Plans one kernel row of an NHWC convolution window over multiple cycles.
  *
  * The driver supplies byte strides computed once per job. This scanner then
  * advances one kernel column per cycle using only additions and comparisons.
  * The valid horizontal span remains contiguous in NHWC memory, so at most one
  * DMA descriptor is required for each kernel row.
  */
class TensorWindowLoader extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val busy = Output(Bool())
    val done = Output(Bool())

    val inputBase = Input(UInt(32.W))
    val inputHeight = Input(UInt(32.W))
    val inputWidth = Input(UInt(32.W))
    val inputChannels = Input(UInt(32.W))
    val kernelWidth = Input(UInt(32.W))
    val sourceY = Input(SInt(33.W))
    val sourceX = Input(SInt(33.W))
    val rowByteOffset = Input(SInt(33.W))
    val columnByteOffset = Input(SInt(33.W))
    val inputPixelBytes = Input(UInt(32.W))
    val rowBaseWords = Input(UInt(32.W))

    val localOffsetWords = Output(UInt(32.W))
    val leadingZeroWords = Output(UInt(32.W))
    val validWords = Output(UInt(32.W))
    val trailingZeroWords = Output(UInt(32.W))
    val rowWords = Output(UInt(32.W))
    val dmaAddress = Output(UInt(32.W))
    val rowInBounds = Output(Bool())
  })

  private val inputBaseReg = RegInit(0.U(32.W))
  private val inputHeightReg = RegInit(0.U(32.W))
  private val inputWidthReg = RegInit(0.U(32.W))
  private val inputChannelsReg = RegInit(0.U(32.W))
  private val kernelWidthReg = RegInit(0.U(32.W))
  private val sourceYReg = RegInit(0.S(33.W))
  private val sourceXReg = RegInit(0.S(33.W))
  private val rowByteOffsetReg = RegInit(0.S(33.W))
  private val columnByteOffsetReg = RegInit(0.S(33.W))
  private val inputPixelBytesReg = RegInit(0.U(32.W))
  private val rowBaseWordsReg = RegInit(0.U(32.W))

  private val columnIndexReg = RegInit(0.U(32.W))
  private val leadingWordsReg = RegInit(0.U(32.W))
  private val validWordsReg = RegInit(0.U(32.W))
  private val trailingWordsReg = RegInit(0.U(32.W))
  private val dmaAddressReg = RegInit(0.U(32.W))
  private val dmaAddressValidReg = RegInit(false.B)

  private val resultLocalOffsetWordsReg = RegInit(0.U(32.W))
  private val resultLeadingZeroWordsReg = RegInit(0.U(32.W))
  private val resultValidWordsReg = RegInit(0.U(32.W))
  private val resultTrailingZeroWordsReg = RegInit(0.U(32.W))
  private val resultRowWordsReg = RegInit(0.U(32.W))
  private val resultDmaAddressReg = RegInit(0.U(32.W))
  private val resultRowInBoundsReg = RegInit(false.B)
  private val busyReg = RegInit(false.B)
  private val doneReg = RegInit(false.B)

  io.busy := busyReg
  io.done := doneReg
  io.localOffsetWords := resultLocalOffsetWordsReg
  io.leadingZeroWords := resultLeadingZeroWordsReg
  io.validWords := resultValidWordsReg
  io.trailingZeroWords := resultTrailingZeroWordsReg
  io.rowWords := resultRowWordsReg
  io.dmaAddress := resultDmaAddressReg
  io.rowInBounds := resultRowInBoundsReg
  doneReg := false.B

  when(!busyReg && io.start) {
    inputBaseReg := io.inputBase
    inputHeightReg := io.inputHeight
    inputWidthReg := io.inputWidth
    inputChannelsReg := io.inputChannels
    kernelWidthReg := io.kernelWidth
    sourceYReg := io.sourceY
    sourceXReg := io.sourceX
    rowByteOffsetReg := io.rowByteOffset
    columnByteOffsetReg := io.columnByteOffset
    inputPixelBytesReg := io.inputPixelBytes
    rowBaseWordsReg := io.rowBaseWords
    columnIndexReg := 0.U
    leadingWordsReg := 0.U
    validWordsReg := 0.U
    trailingWordsReg := 0.U
    dmaAddressReg := 0.U
    dmaAddressValidReg := false.B
    busyReg := true.B
  }.elsewhen(busyReg) {
    val rowInBounds = sourceYReg >= 0.S && sourceYReg < inputHeightReg.zext
    val columnInBounds = sourceXReg >= 0.S && sourceXReg < inputWidthReg.zext
    val nextLeadingWords = WireDefault(leadingWordsReg)
    val nextValidWords = WireDefault(validWordsReg)
    val nextTrailingWords = WireDefault(trailingWordsReg)
    val nextDmaAddress = WireDefault(dmaAddressReg)
    val nextDmaAddressValid = WireDefault(dmaAddressValidReg)

    when(!rowInBounds || sourceXReg < 0.S) {
      nextLeadingWords := leadingWordsReg + inputChannelsReg
    }.elsewhen(columnInBounds) {
      nextValidWords := validWordsReg + inputChannelsReg
      when(!dmaAddressValidReg) {
        val byteOffset = rowByteOffsetReg +& columnByteOffsetReg
        val address = inputBaseReg.zext +& byteOffset

        nextDmaAddress := address.asUInt(31, 0)
        nextDmaAddressValid := true.B
      }
    }.otherwise {
      nextTrailingWords := trailingWordsReg + inputChannelsReg
    }

    leadingWordsReg := nextLeadingWords
    validWordsReg := nextValidWords
    trailingWordsReg := nextTrailingWords
    dmaAddressReg := nextDmaAddress
    dmaAddressValidReg := nextDmaAddressValid

    when(columnIndexReg === kernelWidthReg - 1.U) {
      val rowWords = nextLeadingWords + nextValidWords + nextTrailingWords

      resultLocalOffsetWordsReg := rowBaseWordsReg + nextLeadingWords
      resultLeadingZeroWordsReg := nextLeadingWords
      resultValidWordsReg := nextValidWords
      resultTrailingZeroWordsReg := nextTrailingWords
      resultRowWordsReg := rowWords
      resultDmaAddressReg := nextDmaAddress
      resultRowInBoundsReg := rowInBounds
      busyReg := false.B
      doneReg := true.B
    }.otherwise {
      columnIndexReg := columnIndexReg + 1.U
      sourceXReg := sourceXReg + 1.S
      columnByteOffsetReg := columnByteOffsetReg + inputPixelBytesReg.zext
    }
  }
}
