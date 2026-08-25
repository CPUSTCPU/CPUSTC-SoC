package cpustc.usb.sim

import cpustc.usb.utmi.Usb3500UtmiIo
import spinal.core.ClockDomain
import spinal.core.sim._
import spinal.lib.com.usb.phy.UsbLsFsPhyAbstractIo

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/** 直接驱动 USB3500 Link 侧 UTMI 输入的第一阶段仿真代理。 */
final class Usb3500UtmiAgent(utmi: Usb3500UtmiIo, clockDomain: ClockDomain) {
  def initialize(): Unit = {
    utmi.dataI #= 0
    utmi.txReady #= false
    utmi.rxValid #= false
    utmi.rxActive #= false
    utmi.rxError #= false
    utmi.lineState #= 0
    utmi.vbusValid #= true
    utmi.hostDisconnect #= false
  }

  def connectLowSpeed(): Unit = {
    utmi.vbusValid #= true
    utmi.hostDisconnect #= false
    utmi.lineState #= 2
  }

  def disconnect(): Unit = {
    utmi.lineState #= 0
    utmi.hostDisconnect #= true
  }

  def waitCycles(cycles: Int): Unit = clockDomain.waitSampling(cycles)
}

/** USB3500 接收一个连续 TXVALID 区间后形成的 UTMI 包段。 */
final case class Usb3500UtmiTxRun(
    bytes: Vector[Int],
    lowSpeed: Boolean,
    preamble: Boolean,
    startCycle: Long,
    endCycle: Long
)

/** 测试侧物理发送记录，时间均以 60 MHz UTMI 采样周期计。 */
final case class Usb3500PhysicalTx(
    sourceRun: Int,
    bytes: Vector[Int],
    preamble: Boolean,
    startCycle: Long,
    packetStartCycle: Long,
    eopStartCycle: Long,
    eopEndCycle: Long,
    endCycle: Long
)

/** 测试专用 USB3500 UTMI PHY 行为模型。
  *
  * 该模型在 UTMI 侧实现 TXVALID/TXREADY 字节握手，在抽象 USB 侧生成
  * SYNC、NRZI、bit stuffing 和 EOP 波形，供 SpinalHDL 官方
  * `UsbLsFsPhyAbstractIoAgent` 与 `UsbDeviceAgent` 解码。模型只用于仿真，
  * 不参与可综合 RTL。
  */
final class Usb3500UtmiPhyAgent(
    utmi: Usb3500UtmiIo,
    usb: UsbLsFsPhyAbstractIo,
    clockDomain: ClockDomain,
    serializeHostPackets: Boolean = true,
    firstReadyLatencyCycles: Int = 2,
    firstReadyHighCycles: Int = 1
) {
  require(firstReadyLatencyCycles >= 0)
  require(firstReadyHighCycles > 0)

  private val fsBitCycles = 5
  private val lsBitCycles = fsBitCycles * 8

  val txRuns: ArrayBuffer[Usb3500UtmiTxRun] = ArrayBuffer.empty
  val physicalTx: ArrayBuffer[Usb3500PhysicalTx] = ArrayBuffer.empty
  val physicalRx: ArrayBuffer[Vector[Int]] = ArrayBuffer.empty
  var startsBeforePreviousPhysicalEop: Int = 0
  var cycle: Long = 0L

  private final class PendingPhysicalRun(
      val sourceRun: Int,
      val lowSpeed: Boolean,
      val preamble: Boolean
  ) {
    val bytes: mutable.Queue[Int] = mutable.Queue.empty
    val accepted: ArrayBuffer[Int] = ArrayBuffer.empty
    @volatile var active = false
    @volatile var closed = false
  }

  private val physicalQueue = mutable.Queue.empty[PendingPhysicalRun]
  private var currentPhysicalRun: PendingPhysicalRun = null
  private val currentBytes = ArrayBuffer.empty[Int]
  private var currentStartCycle = 0L
  private var currentLowSpeed = false
  private var currentPreamble = false
  private var txValidLast = false
  private var txReadyDriven = false
  private var readyCountdown = 0
  private var readyHighCyclesRemaining = 0
  private var firstReadyPending = false

  @volatile private var physicalBusy = false

  @volatile private var injectedRxActive = false
  @volatile private var injectedRxValid = false
  @volatile private var injectedRxError = false
  @volatile private var injectedRxData = 0

  private var physicalRxReceiving = false
  private var physicalRxActive = false
  private var physicalRxValid = false
  private var physicalRxError = false
  private var physicalRxData = 0
  private var physicalRxLowSpeed = false
  private var physicalRxLowSpeedPolarity = false
  private var physicalRxCountdown = 0
  private var physicalRxLastJ = true
  private var physicalRxOnes = 0
  private var physicalRxBitIndex = 0
  private var physicalRxByte = 0
  private var physicalRxSyncSeen = false
  private val physicalRxBytes = ArrayBuffer.empty[Int]

  def initialize(): Unit = {
    utmi.dataI #= 0
    utmi.txReady #= false
    utmi.rxValid #= false
    utmi.rxActive #= false
    utmi.rxError #= false
    utmi.lineState #= 0
    utmi.vbusValid #= true
    utmi.hostDisconnect #= false

    usb.tx.enable #= false
    usb.tx.se0 #= false
    usb.tx.data #= false
  }

  def start(): Unit = {
    clockDomain.onSamplings {
      cycle += 1

      val txValid = utmi.txValid.toBoolean
      val xcvrSel = utmi.xcvrSel.toInt
      val lowSpeed = xcvrSel == 2 || xcvrSel == 3
      val preamble = xcvrSel == 3

      if (txValid && !txValidLast) {
        currentBytes.clear()
        currentStartCycle = cycle
        currentLowSpeed = lowSpeed
        currentPreamble = preamble
        readyCountdown = firstReadyLatencyCycles
        readyHighCyclesRemaining = 0
        firstReadyPending = true
        if (physicalBusy || physicalQueue.nonEmpty) {
          startsBeforePreviousPhysicalEop += 1
        }
        if (serializeHostPackets) {
          currentPhysicalRun = new PendingPhysicalRun(txRuns.size, lowSpeed, preamble)
          physicalQueue.enqueue(currentPhysicalRun)
        }
      }

      if (txValid && txReadyDriven) {
        val byte = utmi.dataO.toInt & 0xff
        currentBytes += byte
        if (currentPhysicalRun != null) {
          currentPhysicalRun.bytes.enqueue(byte)
          currentPhysicalRun.accepted += byte
        }
      }

      if (!txValid && txValidLast) {
        val run = Usb3500UtmiTxRun(
          bytes = currentBytes.toVector,
          lowSpeed = currentLowSpeed,
          preamble = currentPreamble,
          startCycle = currentStartCycle,
          endCycle = cycle
        )
        txRuns += run
        if (currentPhysicalRun != null) {
          currentPhysicalRun.closed = true
          currentPhysicalRun = null
        }
      }

      val readyAllowed = !serializeHostPackets || (currentPhysicalRun != null && currentPhysicalRun.active)
      val nextReady = if (!txValid) {
        false
      } else if (!readyAllowed) {
        false
      } else if (txReadyDriven) {
        if (readyHighCyclesRemaining > 1) {
          readyHighCyclesRemaining -= 1
          true
        } else {
          readyHighCyclesRemaining = 0
          readyCountdown = bitCycles(currentLowSpeed) * 8 - 1
          false
        }
      } else if (readyCountdown == 0) {
        readyHighCyclesRemaining = if (firstReadyPending) firstReadyHighCycles else 1
        firstReadyPending = false
        true
      } else {
        readyCountdown -= 1
        false
      }
      utmi.txReady #= nextReady
      txReadyDriven = nextReady
      txValidLast = txValid

      val resetDrive = !physicalBusy && utmi.xcvrSel.toInt == 0 && !utmi.termSel.toBoolean
      if (!physicalBusy) {
        usb.tx.enable #= resetDrive
        usb.tx.se0 #= resetDrive
        usb.tx.data #= false
      }

      val dp = usb.rx.dp.toBoolean
      val dm = usb.rx.dm.toBoolean
      updatePhysicalRx(dp, dm)

      utmi.lineState #= ((if (dm) 2 else 0) | (if (dp) 1 else 0))
      utmi.rxActive #= (injectedRxActive || physicalRxActive)
      utmi.rxValid #= (injectedRxValid || physicalRxValid)
      utmi.rxError #= (injectedRxError || physicalRxError)
      val receivedData = if (injectedRxActive) injectedRxData else physicalRxData
      utmi.dataI #= (if (txValid) utmi.dataO.toInt else receivedData)
    }

    fork {
      while (true) {
        if (physicalQueue.isEmpty) {
          clockDomain.waitSampling()
        } else {
          emitPhysicalPacket(physicalQueue.dequeue())
        }
      }
    }
  }

  /** 直接从 USB3500 接收侧向 DUT 注入一个 UTMI 包。 */
  def emitRxPacket(bytes: Seq[Int], lowSpeed: Boolean, turnaroundBits: Int = 2): Unit = {
    require(turnaroundBits >= 0)
    val cyclesPerByte = bitCycles(lowSpeed) * 8
    clockDomain.waitSampling(bitCycles(lowSpeed) * turnaroundBits)
    injectedRxActive = true
    injectedRxValid = false
    for (byte <- bytes) {
      clockDomain.waitSampling(cyclesPerByte - 1)
      injectedRxData = byte & 0xff
      injectedRxValid = true
      clockDomain.waitSampling()
      injectedRxValid = false
    }
    clockDomain.waitSampling(bitCycles(lowSpeed) * 2)
    injectedRxActive = false
    injectedRxData = 0
  }

  private def bitCycles(lowSpeed: Boolean): Int = if (lowSpeed) lsBitCycles else fsBitCycles

  private def emitPhysicalPacket(run: PendingPhysicalRun): Unit = {
    physicalBusy = true
    run.active = true
    while (run.bytes.isEmpty && !run.closed) {
      clockDomain.waitSampling()
    }
    if (run.bytes.isEmpty) {
      run.active = false
      physicalBusy = false
      return
    }

    val start = cycle
    // XCVRSEL=11 uses low-speed timing on a full-speed segment without reversing J/K polarity.
    val packetLowSpeedPolarity = run.lowSpeed && !run.preamble
    var lineJ = true
    var consecutiveOnes = 0

    def emitEncodedBit(bit: Boolean, lowSpeed: Boolean): Unit = {
      lineJ ^= !bit
      usb.tx.data #= packetLowSpeedPolarity ^ lineJ
      clockDomain.waitSampling(bitCycles(lowSpeed))
    }

    def emitDataBit(bit: Boolean, lowSpeed: Boolean): Unit = {
      if (consecutiveOnes == 6) {
        emitEncodedBit(false, lowSpeed)
        consecutiveOnes = 0
      }
      emitEncodedBit(bit, lowSpeed)
      consecutiveOnes = if (bit) consecutiveOnes + 1 else 0
    }

    def emitByte(byte: Int, lowSpeed: Boolean): Unit = {
      for (bit <- 0 until 8) emitDataBit(((byte >> bit) & 1) != 0, lowSpeed)
    }

    usb.tx.enable #= true
    usb.tx.se0 #= false
    if (run.preamble) {
      emitByte(0x80, lowSpeed = false)
      emitByte(0x3c, lowSpeed = false)
      usb.tx.data #= true
      clockDomain.waitSampling(fsBitCycles * 4)
      lineJ = true
      consecutiveOnes = 0
    }

    val packetStart = cycle
    emitByte(0x80, run.lowSpeed)
    while (!run.closed || run.bytes.nonEmpty) {
      if (run.bytes.nonEmpty) {
        emitByte(run.bytes.dequeue(), run.lowSpeed)
      } else {
        clockDomain.waitSampling()
      }
    }

    val cyclesPerBit = bitCycles(run.lowSpeed)
    val eopStart = cycle
    usb.tx.se0 #= true
    usb.tx.data #= false
    clockDomain.waitSampling(cyclesPerBit * 2)

    usb.tx.se0 #= false
    usb.tx.data #= !packetLowSpeedPolarity
    clockDomain.waitSampling(cyclesPerBit)
    val eopEnd = cycle

    usb.tx.enable #= false
    clockDomain.waitSampling(cyclesPerBit * 2)
    val end = cycle
    physicalTx += Usb3500PhysicalTx(
      run.sourceRun,
      run.accepted.toVector,
      run.preamble,
      start,
      packetStart,
      eopStart,
      eopEnd,
      end
    )
    run.active = false
    physicalBusy = false
  }

  private def updatePhysicalRx(dp: Boolean, dm: Boolean): Unit = {
    physicalRxValid = false
    if (injectedRxActive) {
      physicalRxReceiving = false
      physicalRxActive = false
      physicalRxError = false
      return
    }

    val hostDriving = usb.tx.enable.toBoolean
    if (!physicalRxReceiving) {
      physicalRxActive = false
      physicalRxError = false
      val xcvrSel = utmi.xcvrSel.toInt
      val lowSpeed = xcvrSel == 2 || xcvrSel == 3
      val lowSpeedPolarity = xcvrSel == 2
      val lineK = if (lowSpeedPolarity) dp && !dm else !dp && dm
      if (!hostDriving && lineK) {
        physicalRxReceiving = true
        physicalRxActive = true
        physicalRxError = false
        physicalRxLowSpeed = lowSpeed
        physicalRxLowSpeedPolarity = lowSpeedPolarity
        physicalRxCountdown = bitCycles(lowSpeed) / 2 - 1
        physicalRxLastJ = true
        physicalRxOnes = 0
        physicalRxBitIndex = 0
        physicalRxByte = 0
        physicalRxSyncSeen = false
        physicalRxBytes.clear()
      }
      return
    }

    if (hostDriving) {
      physicalRxReceiving = false
      physicalRxActive = false
      physicalRxError = true
      return
    }

    if (physicalRxCountdown > 0) {
      physicalRxCountdown -= 1
      return
    }

    val se0 = !dp && !dm
    if (se0) {
      physicalRxError |= !physicalRxSyncSeen || physicalRxBitIndex != 0
      if (physicalRxSyncSeen) physicalRx += physicalRxBytes.toVector
      physicalRxReceiving = false
      physicalRxActive = false
      return
    }

    if (dp == dm) {
      physicalRxReceiving = false
      physicalRxActive = false
      physicalRxError = true
      return
    }

    val lineJ = if (physicalRxLowSpeedPolarity) !dp && dm else dp && !dm
    val bit = lineJ == physicalRxLastJ
    physicalRxLastJ = lineJ
    physicalRxCountdown = bitCycles(physicalRxLowSpeed) - 1

    if (physicalRxOnes == 6) {
      physicalRxError |= bit
      physicalRxOnes = 0
      return
    }

    if (bit) physicalRxByte |= 1 << physicalRxBitIndex
    physicalRxBitIndex += 1
    physicalRxOnes = if (bit) physicalRxOnes + 1 else 0

    if (physicalRxBitIndex == 8) {
      val byte = physicalRxByte
      physicalRxBitIndex = 0
      physicalRxByte = 0
      if (!physicalRxSyncSeen) {
        physicalRxSyncSeen = true
        physicalRxError |= byte != 0x80
      } else {
        physicalRxData = byte
        physicalRxValid = true
        physicalRxBytes += byte
      }
    }
  }

}
