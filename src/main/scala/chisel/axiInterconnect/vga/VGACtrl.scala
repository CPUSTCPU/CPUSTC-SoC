package chisel.axiInterconnect.vga

import chisel3._
import chisel3.util.{BitPat, Cat, Enum, ListLookup, Mux1H, ShiftRegister}
import chisel.VGAPort
import chisel.common.bus.{APB3IO, AXI4IO}
import chisel.common.cdc.{BoolSync, ResetnSync}


/** VGA 控制器 APB 寄存器表。 */
object VGACtrlRegisters {
    val hVisible: Int = 0x00
    val hFront: Int = 0x04
    val hSync: Int = 0x08
    val hBack: Int = 0x0c
    val vVisible: Int = 0x10
    val vFront: Int = 0x14
    val vSync: Int = 0x18
    val vBack: Int = 0x1c
    val frameBaseAddr: Int = 0x20
    val burstCountMax: Int = 0x24
    val irqStatus: Int = 0x28
    val irqEnable: Int = 0x2c
    val control: Int = 0x30
    val cursorIdentification: Int = 0x34
    val cursorCapabilities: Int = 0x38
    val cursorPosition: Int = 0x3c
    val cursorSource: Int = 0x40
    val cursorSize: Int = 0x44
    val cursorControl: Int = 0x48
    val cursorRamAddress: Int = 0x4c
    val cursorRamData: Int = 0x50
    val cursorStatus: Int = 0x54

    val cursorIdentificationValue: BigInt = BigInt("43555253", 16)
    val cursorCapabilitiesValue: BigInt = BigInt("07404001", 16)
    val cursorWidth: Int = 64
    val cursorHeight: Int = 64
}


class VGACtrlIO extends Bundle {
    val vgaClk: Clock = Input(Clock())
    val axiClk: Clock = Input(Clock())
    val apbClk: Clock = Input(Clock())
    val resetn: Bool = Input(Bool())
    val axi: AXI4IO = new AXI4IO
    val apb: APB3IO = Flipped(new APB3IO)
    val vga: VGAPort = new VGAPort
    val interrupt: Bool = Output(Bool())
}


/** VGA 控制器。
 *
 * 当前实现 双行像素行的bram缓存读写、 RGB565 译码和 640x480 VGA 时序。
 *
 */
class VGACtrl extends RawModule {
    override def desiredName: String = "VGACtrl"

    val io: VGACtrlIO = IO(new VGACtrlIO)

    val vgaResetn = ResetnSync(io.vgaClk, io.resetn, 2)
    val axiResetn = ResetnSync(io.axiClk,io.resetn,2)
    val apbResetn = ResetnSync(io.apbClk,io.resetn,2)
    val configAckToggleVGACDC: Bool = Wire(Bool())
    val configAckToggleAXICDC: Bool = Wire(Bool())
    val configAckToggleVGAAPB: Bool = BoolSync(io.apbClk, apbResetn, configAckToggleVGACDC)
    val configAckToggleAXIAPB: Bool = BoolSync(io.apbClk, apbResetn, configAckToggleAXICDC)
    val frameFlipBitVGACDC: Bool = Wire(Bool())
    val frameFlipBitVGAAPB: Bool = BoolSync(io.apbClk, apbResetn, frameFlipBitVGACDC)
    val cursorAckToggleVGACDC: Bool = Wire(Bool())
    val cursorActiveEnableVGACDC: Bool = Wire(Bool())
    val cursorActiveBankVGACDC: Bool = Wire(Bool())
    val cursorAckToggleVGAAPB: Bool = BoolSync(io.apbClk, apbResetn, cursorAckToggleVGACDC)
    val cursorActiveEnableVGAAPB: Bool = BoolSync(io.apbClk, apbResetn, cursorActiveEnableVGACDC)
    val cursorActiveBankVGAAPB: Bool = BoolSync(io.apbClk, apbResetn, cursorActiveBankVGACDC)

    val cursorMems: Seq[BlkMemGen0] = Seq.fill(8)(Module(new BlkMemGen0))

    val apbRegisterGroups =
      withClockAndReset(io.apbClk,(!apbResetn).asAsyncReset){
        val sIdle :: sSetup :: sAccess :: Nil = Enum(3)
        val apbState = RegInit(sIdle)

        val hVisibleReg = RegInit(640.U(32.W))
        val hFrontReg = RegInit(16.U(32.W))
        val hSyncReg = RegInit(96.U(32.W))
        val hBackReg = RegInit(48.U(32.W))
        val hSyncStartReg = RegInit(656.U(32.W))
        val hSyncEndReg = RegInit(752.U(32.W))
        val hTotalReg = RegInit(800.U(32.W))
        val vVisibleReg = RegInit(480.U(32.W))
        val vFrontReg = RegInit(10.U(32.W))
        val vSyncReg = RegInit(2.U(32.W))
        val vBackReg = RegInit(33.U(32.W))
        val vSyncStartReg = RegInit(490.U(32.W))
        val vSyncEndReg = RegInit(492.U(32.W))
        val vTotalReg = RegInit(525.U(32.W))
        val frameBaseAddrReg = RegInit("h87e00000".U(32.W))
        val burstCountMaxReg = RegInit(20.U(32.W))
        val irqStatusReg = RegInit(false.B)
        val irqEnableReg = RegInit(false.B)
        val displayEnableReg = RegInit(true.B)
        val configReqToggleReg = RegInit(false.B)
        val cursorPositionReg = RegInit(0.U(32.W))
        val cursorSourceReg = RegInit(0.U(32.W))
        val cursorSizeReg = RegInit(0.U(32.W))
        val cursorControlReg = RegInit(0.U(2.W))
        val cursorRamAddressReg = RegInit("h1000".U(13.W))
        val cursorReqToggleReg = RegInit(false.B)
        val cursorUploadErrorReg = RegInit(false.B)
        val frameFlipBitAPBR1 = RegNext(frameFlipBitVGAAPB, false.B)
        val vblankEvent = frameFlipBitVGAAPB ^ frameFlipBitAPBR1
        val registerValues = List(hVisibleReg, hFrontReg, hSyncReg, hBackReg,
            vVisibleReg, vFrontReg, vSyncReg, vBackReg, frameBaseAddrReg, burstCountMaxReg,
            irqStatusReg.asUInt, irqEnableReg.asUInt, displayEnableReg.asUInt)

        val totalIdle :: calcHSyncStart :: calcHSyncEnd :: calcHTotal :: calcVSyncStart :: calcVSyncEnd :: calcVTotal :: Nil = Enum(7)
        val totalState = RegInit(totalIdle)

        val y = true.B
        val n = false.B
        val default = List(n, n, n, n, n, n, n, n, n, n, n, n, n)
        val table: Array[(BitPat, List[Bool])] = Array(
            //                                                hVisible hFront hSync hBack vVisible vFront vSync vBack frameBaseAddr burstCountMax irqStatus irqEnable control
            BitPat(VGACtrlRegisters.hVisible.U(14.W))      -> List(y, n,       n,     n,    n,       n,     n,    n,    n,            n,            n,        n,        n),
            BitPat(VGACtrlRegisters.hFront.U(14.W))        -> List(n, y,       n,     n,    n,       n,     n,    n,    n,            n,            n,        n,        n),
            BitPat(VGACtrlRegisters.hSync.U(14.W))         -> List(n, n,       y,     n,    n,       n,     n,    n,    n,            n,            n,        n,        n),
            BitPat(VGACtrlRegisters.hBack.U(14.W))         -> List(n, n,       n,     y,    n,       n,     n,    n,    n,            n,            n,        n,        n),
            BitPat(VGACtrlRegisters.vVisible.U(14.W))      -> List(n, n,       n,     n,    y,       n,     n,    n,    n,            n,            n,        n,        n),
            BitPat(VGACtrlRegisters.vFront.U(14.W))        -> List(n, n,       n,     n,    n,       y,     n,    n,    n,            n,            n,        n,        n),
            BitPat(VGACtrlRegisters.vSync.U(14.W))         -> List(n, n,       n,     n,    n,       n,     y,    n,    n,            n,            n,        n,        n),
            BitPat(VGACtrlRegisters.vBack.U(14.W))         -> List(n, n,       n,     n,    n,       n,     n,    y,    n,            n,            n,        n,        n),
            BitPat(VGACtrlRegisters.frameBaseAddr.U(14.W)) -> List(n, n,       n,     n,    n,       n,     n,    n,    y,            n,            n,        n,        n),
            BitPat(VGACtrlRegisters.burstCountMax.U(14.W)) -> List(n, n,       n,     n,    n,       n,     n,    n,    n,            y,            n,        n,        n),
            BitPat(VGACtrlRegisters.irqStatus.U(14.W))     -> List(n, n,       n,     n,    n,       n,     n,    n,    n,            n,            y,        n,        n),
            BitPat(VGACtrlRegisters.irqEnable.U(14.W))     -> List(n, n,       n,     n,    n,       n,     n,    n,    n,            n,            n,        y,        n),
            BitPat(VGACtrlRegisters.control.U(14.W))       -> List(n, n,       n,     n,    n,       n,     n,    n,    n,            n,            n,        n,        y)
        )
        val legacyRegSelected = ListLookup(io.apb.paddr(13, 0), default, table)
        val regSelectedR1: List[Bool] =
            legacyRegSelected.map(selected => RegNext(selected, false.B))
        val cursorOffsets = Seq(
            VGACtrlRegisters.cursorIdentification,
            VGACtrlRegisters.cursorCapabilities,
            VGACtrlRegisters.cursorPosition,
            VGACtrlRegisters.cursorSource,
            VGACtrlRegisters.cursorSize,
            VGACtrlRegisters.cursorControl,
            VGACtrlRegisters.cursorRamAddress,
            VGACtrlRegisters.cursorRamData,
            VGACtrlRegisters.cursorStatus
        )
        val cursorSelectedR1: Seq[Bool] = cursorOffsets.map { offset =>
            RegNext(io.apb.paddr(13, 0) === offset.U, false.B)
        }
        val cursorIdentificationSelected = cursorSelectedR1(0)
        val cursorCapabilitiesSelected = cursorSelectedR1(1)
        val cursorPositionSelected = cursorSelectedR1(2)
        val cursorSourceSelected = cursorSelectedR1(3)
        val cursorSizeSelected = cursorSelectedR1(4)
        val cursorControlSelected = cursorSelectedR1(5)
        val cursorRamAddressSelected = cursorSelectedR1(6)
        val cursorRamDataSelected = cursorSelectedR1(7)
        val cursorStatusSelected = cursorSelectedR1(8)
        val legacyRegisterSelected = regSelectedR1.reduce(_ || _)
        val cursorRegisterSelected = cursorSelectedR1.reduce(_ || _)
        val registerSelected = legacyRegisterSelected || cursorRegisterSelected
        val cursorBusy = cursorAckToggleVGAAPB =/= cursorReqToggleReg
        val cursorStatusValue = Cat(
            0.U(28.W), cursorUploadErrorReg, cursorBusy,
            cursorActiveBankVGAAPB, cursorActiveEnableVGAAPB)
        val cursorReadData = Mux1H(Seq(
            cursorIdentificationSelected -> VGACtrlRegisters.cursorIdentificationValue.U(32.W),
            cursorCapabilitiesSelected -> VGACtrlRegisters.cursorCapabilitiesValue.U(32.W),
            cursorPositionSelected -> cursorPositionReg,
            cursorSourceSelected -> cursorSourceReg,
            cursorSizeSelected -> cursorSizeReg,
            cursorControlSelected -> cursorControlReg,
            cursorRamAddressSelected -> cursorRamAddressReg,
            cursorRamDataSelected -> 0.U(32.W),
            cursorStatusSelected -> cursorStatusValue
        ))

        io.apb.prdata := Mux(cursorRegisterSelected, cursorReadData,
            Mux1H(regSelectedR1, registerValues))
        io.apb.pready := false.B
        io.apb.pslverr := false.B
        val configBusy = totalState =/= totalIdle ||
            configAckToggleVGAAPB =/= configReqToggleReg ||
            configAckToggleAXIAPB =/= configReqToggleReg
        val configRegisterSelected = regSelectedR1.take(10).reduce(_ || _) || regSelectedR1(12)
        val cursorWritableSelected = cursorPositionSelected || cursorSourceSelected ||
            cursorSizeSelected || cursorControlSelected || cursorRamAddressSelected ||
            cursorRamDataSelected
        val access = apbState === sAccess && io.apb.psel && io.apb.penable
        val invalidBurstCountMaxWrite = io.apb.pwrite && regSelectedR1(9) &&
            (io.apb.pwdata === 0.U || io.apb.pwdata > 32.U)
        val invalidCursorRead = !io.apb.pwrite && cursorRamDataSelected
        val invalidCursorSourceWrite = io.apb.pwrite && cursorSourceSelected &&
            (io.apb.pwdata(31, 16).orR || io.apb.pwdata(7, 0) >= VGACtrlRegisters.cursorWidth.U ||
                io.apb.pwdata(15, 8) >= VGACtrlRegisters.cursorHeight.U)
        val invalidCursorSizeWrite = io.apb.pwrite && cursorSizeSelected &&
            (io.apb.pwdata(31, 16).orR || io.apb.pwdata(7, 0) > VGACtrlRegisters.cursorWidth.U ||
                io.apb.pwdata(15, 8) > VGACtrlRegisters.cursorHeight.U)
        val invalidCursorControlWrite = io.apb.pwrite && cursorControlSelected &&
            (io.apb.pwdata(31, 2).orR ||
                (io.apb.pwdata(0) &&
                    (cursorSizeReg(7, 0) === 0.U || cursorSizeReg(15, 8) === 0.U ||
                        cursorSourceReg(7, 0) +& cursorSizeReg(7, 0) > VGACtrlRegisters.cursorWidth.U ||
                        cursorSourceReg(15, 8) +& cursorSizeReg(15, 8) > VGACtrlRegisters.cursorHeight.U)))
        val invalidCursorRamAddressWrite = io.apb.pwrite && cursorRamAddressSelected &&
            io.apb.pwdata(31, 13).orR
        val cursorActiveBankWrite = io.apb.pwrite && cursorRamDataSelected &&
            cursorRamAddressReg(12) === cursorActiveBankVGAAPB
        val invalidCursorWrite = io.apb.pwrite &&
            (cursorIdentificationSelected || cursorCapabilitiesSelected || cursorStatusSelected ||
                invalidCursorSourceWrite || invalidCursorSizeWrite || invalidCursorControlWrite ||
                invalidCursorRamAddressWrite || cursorActiveBankWrite)
        val legacyWriteBlocked = access && io.apb.pwrite && configRegisterSelected &&
            !invalidBurstCountMaxWrite && configBusy
        val cursorWriteBlocked = access && io.apb.pwrite && cursorWritableSelected &&
            !invalidCursorWrite && cursorBusy
        val registerWriteBlocked = legacyWriteBlocked || cursorWriteBlocked
        val legacyRegisterWrite = access && io.apb.pwrite && legacyRegisterSelected &&
            !invalidBurstCountMaxWrite && (!configRegisterSelected || !configBusy)
        val cursorRegisterWrite = access && io.apb.pwrite && cursorWritableSelected &&
            !invalidCursorWrite && !cursorBusy
        val configRegisterWrite = legacyRegisterWrite && configRegisterSelected
        val timingRegisterWrite = legacyRegisterWrite && regSelectedR1.take(8).reduce(_ || _)
        val cursorRamDataWrite = cursorRegisterWrite && cursorRamDataSelected

        cursorMems.zipWithIndex.foreach { case (memory, index) =>
            memory.io.write.clk := io.apbClk
            memory.io.write.en := cursorRamDataWrite && cursorRamAddressReg(12, 10) === index.U
            memory.io.write.we := cursorRamDataWrite && cursorRamAddressReg(12, 10) === index.U
            memory.io.write.addr := cursorRamAddressReg(9, 0)
            memory.io.write.din := io.apb.pwdata
        }

        when(apbState === sIdle){
            when(io.apb.psel){
                //setup
                apbState := sSetup
            }
        }.elsewhen(apbState === sSetup){
            apbState := sAccess
        }.otherwise{
            when(!registerWriteBlocked) {
                apbState := sIdle
                io.apb.pready := true.B
                io.apb.pslverr := !registerSelected || invalidBurstCountMaxWrite ||
                    invalidCursorRead || invalidCursorWrite
                when(legacyRegisterWrite) {
                    registerValues.take(10).zip(regSelectedR1.take(10)).foreach { case (register, selected) =>
                        when(selected) {
                            register := io.apb.pwdata
                        }
                    }
                    when(regSelectedR1(10) && io.apb.pwdata(0)) {
                        irqStatusReg := false.B
                    }
                    when(regSelectedR1(11)) {
                        irqEnableReg := io.apb.pwdata(0)
                    }
                    when(regSelectedR1(12)) {
                        displayEnableReg := io.apb.pwdata(0)
                    }
                }
                when(cursorRegisterWrite) {
                    when(cursorPositionSelected) {
                        cursorPositionReg := io.apb.pwdata
                    }
                    when(cursorSourceSelected) {
                        cursorSourceReg := io.apb.pwdata
                    }
                    when(cursorSizeSelected) {
                        cursorSizeReg := io.apb.pwdata
                    }
                    when(cursorControlSelected) {
                        cursorControlReg := io.apb.pwdata(1, 0)
                        cursorReqToggleReg := !cursorReqToggleReg
                    }
                    when(cursorRamAddressSelected) {
                        cursorRamAddressReg := io.apb.pwdata(12, 0)
                    }
                    when(cursorRamDataSelected) {
                        cursorRamAddressReg := Cat(cursorRamAddressReg(12),
                            cursorRamAddressReg(11, 0) + 1.U)
                    }
                }
                when(access && cursorActiveBankWrite) {
                    cursorUploadErrorReg := true.B
                }
            }
        }

        when(vblankEvent) {
            irqStatusReg := true.B
        }

        io.interrupt := irqStatusReg && irqEnableReg

        when(timingRegisterWrite) {
            totalState := calcHSyncStart
        }.elsewhen(totalState === calcHSyncStart) {
            hSyncStartReg := hVisibleReg + hFrontReg
            totalState := calcHSyncEnd
        }.elsewhen(totalState === calcHSyncEnd) {
            hSyncEndReg := hSyncStartReg + hSyncReg
            totalState := calcHTotal
        }.elsewhen(totalState === calcHTotal) {
            hTotalReg := hSyncEndReg + hBackReg
            totalState := calcVSyncStart
        }.elsewhen(totalState === calcVSyncStart) {
            vSyncStartReg := vVisibleReg + vFrontReg
            totalState := calcVSyncEnd
        }.elsewhen(totalState === calcVSyncEnd) {
            vSyncEndReg := vSyncStartReg + vSyncReg
            totalState := calcVTotal
        }.elsewhen(totalState === calcVTotal) {
            vTotalReg := vSyncEndReg + vBackReg
            totalState := totalIdle
            configReqToggleReg := !configReqToggleReg
        }

        when(configRegisterWrite && !timingRegisterWrite) {
            configReqToggleReg := !configReqToggleReg
        }

        ((hVisibleReg, hFrontReg, hSyncReg, hBackReg, hSyncStartReg, hSyncEndReg, hTotalReg,
            vVisibleReg, vFrontReg, vSyncReg, vBackReg, vSyncStartReg, vSyncEndReg, vTotalReg,
            frameBaseAddrReg, burstCountMaxReg, displayEnableReg, configReqToggleReg),
            (cursorPositionReg, cursorSourceReg, cursorSizeReg, cursorControlReg,
                cursorReqToggleReg))
    }

    val configRegisters = apbRegisterGroups._1
    val cursorRegisters = apbRegisterGroups._2
    val hVisible = configRegisters._1
    val hFront = configRegisters._2
    val hSync = configRegisters._3
    val hBack = configRegisters._4
    val hSyncStart = configRegisters._5
    val hSyncEnd = configRegisters._6
    val hTotal = configRegisters._7
    val vVisible = configRegisters._8
    val vFront = configRegisters._9
    val vSync = configRegisters._10
    val vBack = configRegisters._11
    val vSyncStart = configRegisters._12
    val vSyncEnd = configRegisters._13
    val vTotal = configRegisters._14
    val frameBaseAddr = configRegisters._15
    val burstCountMax = configRegisters._16
    val displayEnable = configRegisters._17
    val configReqToggle = configRegisters._18
    val cursorPosition = cursorRegisters._1
    val cursorSource = cursorRegisters._2
    val cursorSize = cursorRegisters._3
    val cursorControl = cursorRegisters._4
    val cursorReqToggle = cursorRegisters._5

    val configReqToggleVGA: Bool = BoolSync(io.vgaClk, vgaResetn, configReqToggle)
    val configReqToggleAXI: Bool = BoolSync(io.axiClk, axiResetn, configReqToggle)
    val cursorReqToggleVGA: Bool = BoolSync(io.vgaClk, vgaResetn, cursorReqToggle)

    //bram设置了primative output reg，所以输出的data要打两拍
    //TODO：后续考虑改成纯chisel,以免误判输出周期。
    val blkMemGen0 = Module(new BlkMemGen0)
    val bramSize: Int = 512 //总大小：512*2个=1024
    val bramAddrLen: Int = 10 //10bit地址

    val bufferBusy0VGACDC: Bool = Wire(Bool())
    val bufferBusy1VGACDC: Bool = Wire(Bool())
    val lineFlipBitVGACDC = Wire(Bool())

    withClockAndReset(io.vgaClk, (!vgaResetn).asAsyncReset) {

        val hVisibleVGA: UInt = RegInit(640.U(32.W))
        val hSyncStartVGA: UInt = RegInit(656.U(32.W))
        val hSyncEndVGA: UInt = RegInit(752.U(32.W))
        val hTotalVGA: UInt = RegInit(800.U(32.W))
        val vVisibleVGA: UInt = RegInit(480.U(32.W))
        val vSyncStartVGA: UInt = RegInit(490.U(32.W))
        val vSyncEndVGA: UInt = RegInit(492.U(32.W))
        val vTotalVGA: UInt = RegInit(525.U(32.W))
        val hVisiblePendingVGA: UInt = RegInit(640.U(32.W))
        val hSyncStartPendingVGA: UInt = RegInit(656.U(32.W))
        val hSyncEndPendingVGA: UInt = RegInit(752.U(32.W))
        val hTotalPendingVGA: UInt = RegInit(800.U(32.W))
        val vVisiblePendingVGA: UInt = RegInit(480.U(32.W))
        val vSyncStartPendingVGA: UInt = RegInit(490.U(32.W))
        val vSyncEndPendingVGA: UInt = RegInit(492.U(32.W))
        val vTotalPendingVGA: UInt = RegInit(525.U(32.W))
        val displayEnableVGA: Bool = RegInit(true.B)
        val displayEnablePendingVGA: Bool = RegInit(true.B)
        val configPendingVGA: Bool = RegInit(false.B)
        val configAckToggleVGA: Bool = RegInit(false.B)
        val configCaptureVGA: Bool = configReqToggleVGA =/= configAckToggleVGA
        configAckToggleVGACDC := configAckToggleVGA
        val cursorPositionVGA: UInt = RegInit(0.U(32.W))
        val cursorSourceVGA: UInt = RegInit(0.U(32.W))
        val cursorSizeVGA: UInt = RegInit(0.U(32.W))
        val cursorControlVGA: UInt = RegInit(0.U(2.W))
        val cursorPositionPendingVGA: UInt = RegInit(0.U(32.W))
        val cursorSourcePendingVGA: UInt = RegInit(0.U(32.W))
        val cursorSizePendingVGA: UInt = RegInit(0.U(32.W))
        val cursorControlPendingVGA: UInt = RegInit(0.U(2.W))
        val cursorPendingVGA: Bool = RegInit(false.B)
        val cursorAckToggleVGA: Bool = RegInit(false.B)
        val cursorCaptureVGA: Bool = cursorReqToggleVGA =/= cursorAckToggleVGA
        cursorAckToggleVGACDC := cursorAckToggleVGA
        cursorActiveEnableVGACDC := cursorControlVGA(0)
        cursorActiveBankVGACDC := cursorControlVGA(1)

        val hCountVGA: UInt = RegInit(0.U(32.W))
        val vCountVGA: UInt = RegInit(0.U(32.W))

        val lineFlipBitVGA: Bool = RegInit(false.B)
        lineFlipBitVGACDC := lineFlipBitVGA
        val frameFlipBitVGA: Bool = RegInit(false.B)
        frameFlipBitVGACDC := frameFlipBitVGA
        val addrOffsetVGA = hCountVGA >> 1
        val vgaBankVGA: Bool = RegInit(false.B)
        val bufferBusy0VGACDCR = RegInit(false.B)
        bufferBusy0VGACDCR := Mux(vCountVGA < vVisibleVGA, !vgaBankVGA, false.B)
        bufferBusy0VGACDC := bufferBusy0VGACDCR
        val bufferBusy1VGACDCR = RegInit(false.B)
        bufferBusy1VGACDCR := Mux(vCountVGA < vVisibleVGA, vgaBankVGA, false.B)
        bufferBusy1VGACDC := bufferBusy1VGACDCR
        val vgaAddrVGA: UInt = Wire(UInt(bramAddrLen.W))
        vgaAddrVGA := vgaBankVGA.asUInt * bramSize.U + addrOffsetVGA
        val lineBoundaryVGA: Bool = hCountVGA === hTotalVGA - 1.U
        val vblankBoundaryVGA: Bool = lineBoundaryVGA && vCountVGA === vVisibleVGA - 1.U
        val frameBoundaryVGA: Bool = hCountVGA === hTotalVGA - 1.U && vCountVGA === vTotalVGA - 1.U

        when(hCountVGA === hTotalVGA - 1.U) {
            hCountVGA := 0.U
            when(vCountVGA === vTotalVGA - 1.U) {
                vCountVGA := 0.U
                vgaBankVGA := false.B //新帧开始，约定从第0个cacheline开始
            }.elsewhen(vCountVGA === vVisibleVGA - 1.U) {
                vCountVGA := vCountVGA + 1.U
                vgaBankVGA := !vgaBankVGA
                frameFlipBitVGA := !frameFlipBitVGA
            }.otherwise {
                vCountVGA := vCountVGA + 1.U
                vgaBankVGA := !vgaBankVGA
            }
        }.elsewhen(hCountVGA === hVisibleVGA - 1.U) {
            hCountVGA := hCountVGA + 1.U
            when(vCountVGA <= vVisibleVGA - 1.U) {
                lineFlipBitVGA := !lineFlipBitVGA
            } //只有visible行会结束翻转
        }.otherwise {
            hCountVGA := hCountVGA + 1.U
        }

        when(frameBoundaryVGA && configPendingVGA) {
            hVisibleVGA := hVisiblePendingVGA
            hSyncStartVGA := hSyncStartPendingVGA
            hSyncEndVGA := hSyncEndPendingVGA
            hTotalVGA := hTotalPendingVGA
            vVisibleVGA := vVisiblePendingVGA
            vSyncStartVGA := vSyncStartPendingVGA
            vSyncEndVGA := vSyncEndPendingVGA
            vTotalVGA := vTotalPendingVGA
            displayEnableVGA := displayEnablePendingVGA
            configPendingVGA := false.B
        }

        when(configCaptureVGA) {
            hVisiblePendingVGA := hVisible
            hSyncStartPendingVGA := hSyncStart
            hSyncEndPendingVGA := hSyncEnd
            hTotalPendingVGA := hTotal
            vVisiblePendingVGA := vVisible
            vSyncStartPendingVGA := vSyncStart
            vSyncEndPendingVGA := vSyncEnd
            vTotalPendingVGA := vTotal
            displayEnablePendingVGA := displayEnable
            configPendingVGA := true.B
            configAckToggleVGA := configReqToggleVGA
        }

        when(cursorCaptureVGA && !cursorPendingVGA) {
            cursorPositionPendingVGA := cursorPosition
            cursorSourcePendingVGA := cursorSource
            cursorSizePendingVGA := cursorSize
            cursorControlPendingVGA := cursorControl
            cursorPendingVGA := true.B
        }

        val cursorBankChangePendingVGA: Bool =
            cursorControlPendingVGA(1) =/= cursorControlVGA(1)
        when(cursorPendingVGA && lineBoundaryVGA &&
            (!cursorBankChangePendingVGA || vblankBoundaryVGA)) {
            cursorPositionVGA := cursorPositionPendingVGA
            cursorSourceVGA := cursorSourcePendingVGA
            cursorSizeVGA := cursorSizePendingVGA
            cursorControlVGA := cursorControlPendingVGA
            cursorPendingVGA := false.B
            cursorAckToggleVGA := cursorReqToggleVGA
        }


        blkMemGen0.io.read.clk := io.vgaClk
        blkMemGen0.io.read.en := true.B
        blkMemGen0.io.read.addr := vgaAddrVGA
        val cursorX = cursorPositionVGA(15, 0).asSInt.pad(32)
        val cursorY = cursorPositionVGA(31, 16).asSInt.pad(32)
        val cursorWidthVGA = cursorSizeVGA(7, 0)
        val cursorHeightVGA = cursorSizeVGA(15, 8)
        val cursorScreenX = hCountVGA.asSInt
        val cursorScreenY = vCountVGA.asSInt
        val cursorInsideVGA = cursorControlVGA(0) && cursorWidthVGA =/= 0.U &&
            cursorHeightVGA =/= 0.U && cursorScreenX >= cursorX && cursorScreenY >= cursorY &&
            cursorScreenX < cursorX + cursorWidthVGA.zext &&
            cursorScreenY < cursorY + cursorHeightVGA.zext
        val cursorDeltaX = (cursorScreenX - cursorX).asUInt
        val cursorDeltaY = (cursorScreenY - cursorY).asUInt
        val cursorPixelX = cursorSourceVGA(7, 0) + cursorDeltaX(7, 0)
        val cursorPixelY = cursorSourceVGA(15, 8) + cursorDeltaY(7, 0)
        val cursorPixelIndex = ((cursorPixelY << 6) + cursorPixelX)(11, 0)
        val cursorMemorySelect = Cat(cursorControlVGA(1), cursorPixelIndex(11, 10))
        val cursorMemoryReadEnable = (0 until cursorMems.length).map { index =>
            val selected = cursorInsideVGA && cursorMemorySelect === index.U
            selected || RegNext(selected, false.B)
        }
        cursorMems.zipWithIndex.foreach { case (memory, index) =>
            memory.io.read.clk := io.vgaClk
            // BRAM 的 primitive/output 两级寄存器需要额外一拍使能来送出行尾像素。
            memory.io.read.en := cursorMemoryReadEnable(index)
            memory.io.read.addr := cursorPixelIndex(9, 0)
        }
        val hCountVGAR2 = ShiftRegister(hCountVGA, 2)
        val vCountVGAR2 = ShiftRegister(vCountVGA, 2)
        val cursorInsideVGAR2 = ShiftRegister(cursorInsideVGA, 2)
        val cursorMemorySelectR2 = ShiftRegister(cursorMemorySelect, 2)
        val visibleVGA: Bool = displayEnableVGA && hCountVGAR2 < hVisibleVGA && vCountVGAR2 < vVisibleVGA
        val rawDataVGA = blkMemGen0.io.read.dout
        val vgaDataVGA: UInt = WireDefault(Mux(hCountVGAR2(0), rawDataVGA(31, 16), rawDataVGA(15, 0))) //小端序，offset最后一位为1,说明是高位
        val cursorDataVGA = VecInit(cursorMems.map(_.io.read.dout))(cursorMemorySelectR2)

        def expand5(value: UInt): UInt = Cat(value, value(4, 2))
        def expand6(value: UInt): UInt = Cat(value, value(5, 4))
        def blendChannel(source: UInt, destination: UInt, alpha: UInt): UInt = {
            val product = destination * (255.U(8.W) - alpha)
            val rounded = product + 128.U
            val divided = (rounded + (rounded >> 8)) >> 8
            val sum = source +& divided(7, 0)
            Mux(sum > 255.U, 255.U(8.W), sum(7, 0))
        }

        val backgroundR = expand5(vgaDataVGA(15, 11))
        val backgroundG = expand6(vgaDataVGA(10, 5))
        val backgroundB = expand5(vgaDataVGA(4, 0))
        val cursorAlpha = cursorDataVGA(31, 24)
        val cursorR = cursorDataVGA(23, 16)
        val cursorG = cursorDataVGA(15, 8)
        val cursorB = cursorDataVGA(7, 0)
        val blendedR = Mux(cursorAlpha === 0.U, backgroundR,
            Mux(cursorAlpha.andR, cursorR, blendChannel(cursorR, backgroundR, cursorAlpha)))
        val blendedG = Mux(cursorAlpha === 0.U, backgroundG,
            Mux(cursorAlpha.andR, cursorG, blendChannel(cursorG, backgroundG, cursorAlpha)))
        val blendedB = Mux(cursorAlpha === 0.U, backgroundB,
            Mux(cursorAlpha.andR, cursorB, blendChannel(cursorB, backgroundB, cursorAlpha)))
        val outputR = Mux(cursorInsideVGAR2, blendedR, backgroundR)
        val outputG = Mux(cursorInsideVGAR2, blendedG, backgroundG)
        val outputB = Mux(cursorInsideVGAR2, blendedB, backgroundB)

        //io
        io.vga.vga_r := Mux(visibleVGA, outputR(7, 4), 0.U(4.W))
        io.vga.vga_g := Mux(visibleVGA, outputG(7, 4), 0.U(4.W))
        io.vga.vga_b := Mux(visibleVGA, outputB(7, 4), 0.U(4.W))
        io.vga.vga_hsync := !(hCountVGAR2 >= hSyncStartVGA && hCountVGAR2 < hSyncEndVGA)
        io.vga.vga_vsync := !(vCountVGAR2 >= vSyncStartVGA && vCountVGAR2 < vSyncEndVGA)

    }


    withClockAndReset(io.axiClk, (!axiResetn).asAsyncReset) {
        val sIdle :: sAddr :: sData :: Nil = Enum(3)
        val MainStateAXI: UInt = RegInit(sIdle) //主状态机状态，用于axi访存得到下一行vga信号

        val vVisibleAXI: UInt = RegInit(480.U(32.W))
        val frameBaseAddrAXI: UInt = RegInit("h87e00000".U(32.W))
        val burstCountMaxAXI: UInt = RegInit(20.U(32.W))
        val displayEnableAXI: Bool = RegInit(true.B)
        val vVisiblePendingAXI: UInt = RegInit(480.U(32.W))
        val frameBaseAddrPendingAXI: UInt = RegInit("h87e00000".U(32.W))
        val burstCountMaxPendingAXI: UInt = RegInit(20.U(32.W))
        val displayEnablePendingAXI: Bool = RegInit(true.B)
        val configPendingAXI: Bool = RegInit(false.B)
        val configApplyReadyAXI: Bool = RegInit(false.B)
        val configAckToggleAXI: Bool = RegInit(false.B)
        val configCaptureAXI: Bool = configReqToggleAXI =/= configAckToggleAXI
        configAckToggleAXICDC := configAckToggleAXI

        val vCountAXI: UInt = RegInit(0.U(32.W))

        val bufferEmpty0: Bool = RegInit(true.B)//buffer为空或者数据已被读取
        val bufferBusy0: Bool = ShiftRegister(bufferBusy0VGACDC, 2)
        val bufferBusy0R1: Bool = ShiftRegister(bufferBusy0, 1)
        val buffervalid0: Bool = !bufferBusy0 & bufferEmpty0
        val readDone0: Bool = bufferBusy0R1 & !bufferBusy0

        val bufferEmpty1: Bool = RegInit(true.B)
        val bufferBusy1: Bool = ShiftRegister(bufferBusy1VGACDC, 2) //vga===false,bufferbusy0===true
        val bufferBusy1R1: Bool = ShiftRegister(bufferBusy1, 1)
        val buffervalid1: Bool = !bufferBusy1 & bufferEmpty1
        val readDone1: Bool = bufferBusy1R1 & !bufferBusy1

        val readCount: UInt = RegInit(0.U(32.W))
        val readValid = displayEnableAXI && (readCount <= vCountAXI+1.U )& (readCount < vVisibleAXI )//vCountAXI是vga将要读的行数，axi需要访问v和v+1行
        val arFire = io.axi.arready & io.axi.arvalid
        val rFire = io.axi.rready & io.axi.rvalid
        val rCount: UInt = RegInit(0.U(4.W)) //burst读16
        val rCountMax = 16
        val burstCount: UInt = RegInit(0.U(5.W)) //最多32 burst
        val readBuffer0: Bool = RegInit(true.B) //true表示当前正在读buffer0
        val bramAddr = (!readBuffer0).asUInt * bramSize.U + burstCount * rCountMax.U + rCount
        val nextLineAddrAXI: UInt = RegInit("h87e00000".U(32.W))
        val burstAddrAXI: UInt = RegInit("h87e00000".U(32.W))
        val lineStrideBytesAXI: UInt = (burstCountMaxAXI << 6)(31, 0)

        val lineFlipBitVGAR2: Bool = ShiftRegister(lineFlipBitVGACDC, 2) //打两拍比较稳
        val lineFlipBitVGAR3: Bool = ShiftRegister(lineFlipBitVGAR2, 1)
        val lineEnd: Bool = lineFlipBitVGAR2 ^ lineFlipBitVGAR3
        val frameFlipBitVGAR2: Bool = ShiftRegister(frameFlipBitVGACDC, 2)
        val frameFlipBitVGAR3: Bool = ShiftRegister(frameFlipBitVGAR2, 1)
        val frameEnd: Bool = frameFlipBitVGAR2 ^ frameFlipBitVGAR3

        when(frameEnd) { //vCountAXI是vga将要读的行数，axi需要访问v和v+1行
            vCountAXI := 0.U
            readCount := 0.U
            nextLineAddrAXI := frameBaseAddrAXI
        }.elsewhen(lineEnd) {
            vCountAXI := vCountAXI + 1.U
        }

        when(frameEnd && configPendingAXI) {
            configApplyReadyAXI := true.B
        }
        val configApplyAXI: Bool = MainStateAXI === sIdle && configApplyReadyAXI
        when(configApplyAXI) {
            vVisibleAXI := vVisiblePendingAXI
            frameBaseAddrAXI := frameBaseAddrPendingAXI
            burstCountMaxAXI := burstCountMaxPendingAXI
            displayEnableAXI := displayEnablePendingAXI
            nextLineAddrAXI := frameBaseAddrPendingAXI
            configPendingAXI := false.B
            configApplyReadyAXI := false.B
        }

        when(configCaptureAXI) {
            vVisiblePendingAXI := vVisible
            frameBaseAddrPendingAXI := frameBaseAddr
            burstCountMaxPendingAXI := burstCountMax
            displayEnablePendingAXI := displayEnable
            configPendingAXI := true.B
            configAckToggleAXI := configReqToggleAXI
        }

        when((readDone0|frameEnd) & !bufferEmpty0) {
            bufferEmpty0 := true.B
        }.elsewhen((MainStateAXI === sAddr) & arFire & readBuffer0){
            bufferEmpty0 := false.B
        }
        when((readDone1|frameEnd) & !bufferEmpty1) {
            bufferEmpty1 := true.B
        }.elsewhen((MainStateAXI === sAddr) & arFire & !readBuffer0){
            bufferEmpty1 := false.B
        }
        when(MainStateAXI === sIdle) {
            when(frameEnd || configApplyAXI) {
                MainStateAXI := sIdle
            }.elsewhen(buffervalid0 & readValid) {
                MainStateAXI := sAddr
                readCount := readCount + 1.U
                readBuffer0 := true.B
                burstAddrAXI := nextLineAddrAXI
                nextLineAddrAXI := nextLineAddrAXI + lineStrideBytesAXI
            }.elsewhen(buffervalid1 & readValid) {
                MainStateAXI := sAddr
                readCount := readCount + 1.U
                readBuffer0 := false.B
                burstAddrAXI := nextLineAddrAXI
                nextLineAddrAXI := nextLineAddrAXI + lineStrideBytesAXI
            }.otherwise {
                MainStateAXI := sIdle
            }
        }.elsewhen(MainStateAXI === sAddr) {
            when(arFire) {
                MainStateAXI := sData
            }
        }.elsewhen(MainStateAXI === sData) {
            when(rFire) {
                when((rCount === (rCountMax - 1).U) & io.axi.rlast) {
                    rCount := 0.U
                    when(burstCount === burstCountMaxAXI - 1.U) {
                        burstCount := 0.U
                        MainStateAXI := sIdle
                    }.otherwise {
                        burstCount := burstCount + 1.U
                        burstAddrAXI := burstAddrAXI + 64.U
                        MainStateAXI := sAddr
                    }
                }.otherwise {
                    rCount := rCount + 1.U
                }
            }
        }

        blkMemGen0.io.write.clk := io.axiClk
        blkMemGen0.io.write.en := rFire //?
        blkMemGen0.io.write.we := rFire
        blkMemGen0.io.write.addr := bramAddr
        blkMemGen0.io.write.din := io.axi.rdata

        io.axi.awid := 0.U
        io.axi.awaddr := 0.U
        io.axi.awlen := 0.U
        io.axi.awsize := 0.U
        io.axi.awburst := 0.U
        io.axi.awlock := 0.U
        io.axi.awcache := 0.U
        io.axi.awprot := 0.U
        io.axi.awqos := 0.U
        io.axi.awregion := 0.U
        io.axi.awvalid := false.B

        io.axi.wdata := 0.U
        io.axi.wstrb := 0.U
        io.axi.wlast := false.B
        io.axi.wvalid := false.B

        io.axi.bready := false.B

        io.axi.arid := 0.U
        io.axi.araddr := burstAddrAXI
        io.axi.arlen := (rCountMax - 1).U
        io.axi.arsize := 2.U
        io.axi.arburst := 1.U
        io.axi.arlock := 0.U
        io.axi.arcache := 0.U
        io.axi.arprot := 0.U
        io.axi.arqos := 0.U
        io.axi.arregion := 0.U
        io.axi.arvalid := MainStateAXI === sAddr

        io.axi.rready := MainStateAXI === sData
    }
}
