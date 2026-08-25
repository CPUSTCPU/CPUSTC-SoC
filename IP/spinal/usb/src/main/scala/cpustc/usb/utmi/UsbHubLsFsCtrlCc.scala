package cpustc.usb.utmi

import spinal.core._
import spinal.lib._
import spinal.lib.com.usb.phy.UsbHubLsFs

object UsbHubLsFsCtrlCc {
  object RxEventKind extends SpinalEnum {
    val Start, StartData, Data, End = newElement()
  }

  case class RxEvent() extends Bundle {
    val kind = RxEventKind()
    val data = Bits(8 bits)
    val stuffingError = Bool()
  }
}

/**
  * 保留上游 UsbHubLsFs.CtrlCc 的控制、发送和端口 CDC，仅将 RX 包改为有序事件 FIFO。
  */
case class UsbHubLsFsCtrlCc(
    portCount: Int,
    cdInput: ClockDomain,
    cdOutput: ClockDomain,
    rxFifoDepth: Int = 8
) extends Component {
  import UsbHubLsFsCtrlCc._

  require(isPow2(rxFifoDepth) && rxFifoDepth >= 2)

  val input: UsbHubLsFs.Ctrl = slave(UsbHubLsFs.Ctrl(portCount))
  val output: UsbHubLsFs.Ctrl = master(UsbHubLsFs.Ctrl(portCount))

  private val inputLowSpeed = cdInput on new Area {
    val registered = RegNext(input.lowSpeed) init False
  }

  output.lowSpeed := cdOutput(BufferCC.withTag(inputLowSpeed.registered))
  output.usbReset := cdOutput(BufferCC.withTag(input.usbReset))
  output.usbResume := cdOutput(BufferCC.withTag(input.usbResume))
  input.overcurrent := cdInput(BufferCC(output.overcurrent))

  output.tx << cdOutput(input.tx.ccToggle(cdInput, cdOutput).stage())
  input.txEop := PulseCCByToggle(output.txEop, cdOutput, cdInput)

  private val rxEventFifo = new StreamFifoCC(
    dataType = RxEvent(),
    depth = rxFifoDepth,
    pushClock = cdOutput,
    popClock = cdInput
  )

  private val rxPush = cdOutput on new Area {
    val activeLast = RegNext(output.rx.active) init False
    val start = output.rx.active && !activeLast
    val data = output.rx.flow.valid
    val end = !output.rx.active && activeLast
    val event = start || data || end
    val collision = (start && end) || (data && end)

    rxEventFifo.io.push.valid := event
    rxEventFifo.io.push.kind := RxEventKind.Data
    rxEventFifo.io.push.data := output.rx.flow.data
    rxEventFifo.io.push.stuffingError := output.rx.flow.stuffingError
    when(start && data) {
      rxEventFifo.io.push.kind := RxEventKind.StartData
    } elsewhen (start) {
      rxEventFifo.io.push.kind := RxEventKind.Start
    } elsewhen (end) {
      rxEventFifo.io.push.kind := RxEventKind.End
    }

    val overflowSticky = RegInit(False)
    val collisionSticky = RegInit(False)
    when(event && !rxEventFifo.io.push.ready) {
      overflowSticky := True
    }
    when(collision) {
      collisionSticky := True
    }
  }

  private val rxPop = cdInput on new Area {
    val active = RegInit(False)
    val holdAfterStart = RegInit(False)
    val startDataSecondPhase = RegInit(False)
    val isStartData =
      rxEventFifo.io.pop.valid && rxEventFifo.io.pop.kind === RxEventKind.StartData

    rxEventFifo.io.pop.ready :=
      !holdAfterStart && (!isStartData || startDataSecondPhase)
    input.rx.active := active
    input.rx.flow.valid :=
      rxEventFifo.io.pop.valid &&
        (rxEventFifo.io.pop.kind === RxEventKind.Data ||
          (rxEventFifo.io.pop.kind === RxEventKind.StartData && startDataSecondPhase)) &&
        !holdAfterStart
    input.rx.flow.data := rxEventFifo.io.pop.data
    input.rx.flow.stuffingError := rxEventFifo.io.pop.stuffingError

    holdAfterStart := False
    when(isStartData && !startDataSecondPhase && !holdAfterStart) {
      active := True
      startDataSecondPhase := True
      holdAfterStart := True
    }
    when(rxEventFifo.io.pop.fire) {
      switch(rxEventFifo.io.pop.kind) {
        is(RxEventKind.Start) {
          active := True
          holdAfterStart := True
        }
        is(RxEventKind.End) {
          active := False
        }
        is(RxEventKind.StartData) {
          startDataSecondPhase := False
        }
      }
    }
  }

  val rxEventOverflow = out Bool()
  val rxEventCollision = out Bool()
  rxEventOverflow := rxPush.overflowSticky
  rxEventCollision := rxPush.collisionSticky

  input.tick := PulseCCByToggle(output.tick, cdOutput, cdInput)

  for ((pi, po) <- (input.ports, output.ports).zipped) {
    po.removable := cdOutput(BufferCC.withTag(pi.removable))
    po.power := cdOutput(BufferCC.withTag(pi.power))
    pi.lowSpeed := cdInput(BufferCC.withTag(po.lowSpeed))
    pi.overcurrent := cdInput(BufferCC(po.overcurrent))

    pi.connect := PulseCCByToggle(po.connect, cdOutput, cdInput)
    pi.disconnect := PulseCCByToggle(po.disconnect, cdOutput, cdInput)
    pi.remoteResume := PulseCCByToggle(po.remoteResume, cdOutput, cdInput)

    po.reset << pi.reset.ccToggleInputWait(cdInput, cdOutput)
    po.suspend << pi.suspend.ccToggleInputWait(cdInput, cdOutput)
    po.resume << pi.resume.ccToggleInputWait(cdInput, cdOutput)
    po.disable << pi.disable.ccToggleInputWait(cdInput, cdOutput)
  }
}
