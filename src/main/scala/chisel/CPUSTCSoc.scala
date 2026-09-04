package chisel

import chisel3._
import chisel3.experimental.{Analog, attach}
import chisel3.util.{Cat, log2Ceil}
import chisel.axiInterconnect._
import chisel.axiInterconnect.camera._
import chisel.axiInterconnect.ddr._
import chisel.axiInterconnect.ethernet._
import chisel.axiInterconnect.gpu._
import chisel.axiInterconnect.lcd._
import chisel.axiInterconnect.nand._
import chisel.axiInterconnect.sdio._
import chisel.axiInterconnect.tensorCore._
import chisel.axiInterconnect.usb._
import chisel.axiInterconnect.vga._
import chisel.axiSlaveMux._
import chisel.axiSlaveMux.apb._
import chisel.axiSlaveMux.apb.display._
import chisel.axiSlaveMux.apb.i2c._
import chisel.axiSlaveMux.apb.interrupt._
import chisel.axiSlaveMux.apb.uart._
import chisel.axiSlaveMux.confreg._
import chisel.axiSlaveMux.fallback._
import chisel.axiSlaveMux.spiFlash._
import chisel.common.axi._
import chisel.common.bus._
import chisel.common.cdc._
import chisel.common.clock._
import chisel.cpu._
import chisel.cpu.debug._

class GpioPort extends Bundle {
    val led: UInt = Output(UInt(16.W))
    val led_rg0: UInt = Output(UInt(2.W))
    val led_rg1: UInt = Output(UInt(2.W))
    val num_csn: UInt = Output(UInt(8.W))
    val num_a_g: UInt = Output(UInt(7.W))
    val switch: UInt = Input(UInt(8.W))
    val btn_key_col: UInt = Output(UInt(4.W))
    val btn_key_row: UInt = Input(UInt(4.W))
    val btn_step: UInt = Input(UInt(2.W))
}

class DotMatrixPort extends Bundle {
    // bit 0..7 分别对应板级 C1..C8 和 R1..R8。
    val columns: UInt = Output(UInt(8.W))
    val rows: UInt = Output(UInt(8.W))
}

class DDR3Port extends Bundle {
    val ddr3_addr: UInt = Output(UInt(13.W))
    val ddr3_ba: UInt = Output(UInt(3.W))
    val ddr3_ras_n: Bool = Output(Bool())
    val ddr3_cas_n: Bool = Output(Bool())
    val ddr3_we_n: Bool = Output(Bool())
    val ddr3_odt: Bool = Output(Bool())
    val ddr3_reset_n: Bool = Output(Bool())
    val ddr3_cke: Bool = Output(Bool())
    val ddr3_dm: UInt = Output(UInt(2.W))
    val ddr3_ck_p: Bool = Output(Bool())
    val ddr3_ck_n: Bool = Output(Bool())
}


class MacPort extends Bundle {
    val mtxclk_0: Clock = Input(Clock())
    val mtxen_0: Bool = Output(Bool())
    val mtxd_0: UInt = Output(UInt(4.W))
    val mtxerr_0: Bool = Output(Bool())

    val mrxclk_0: Clock = Input(Clock())
    val mrxdv_0: Bool = Input(Bool())
    val mrxd_0: UInt = Input(UInt(4.W))
    val mrxerr_0: Bool = Input(Bool())
    val mcoll_0: Bool = Input(Bool())
    val mcrs_0: Bool = Input(Bool())

    val mdc_0: Bool = Output(Bool())
    val md_i_0: Bool = Input(Bool())
    val md_o_0: Bool = Output(Bool())
    val md_oe_0: Bool = Output(Bool())
    val phy_rstn: Bool = Output(Bool())
}

class EjtagPort extends Bundle {
    val EJTAG_TRST: Bool = Input(Bool())
    val EJTAG_TCK: Clock = Input(Clock())
    val EJTAG_TDI: Bool = Input(Bool())
    val EJTAG_TMS: Bool = Input(Bool())
    val EJTAG_TDO: Bool = Output(Bool())
}

class DebugUartPort extends Bundle {
    val UART_RX2: Bool = Input(Bool())
    val UART_TX2: Bool = Output(Bool())
}

class SpiFlashPort extends Bundle {
    val csn_o: UInt = Output(UInt(4.W))
    val csn_en: UInt = Output(UInt(4.W))
    val sck_o: Bool = Output(Bool())
    val sdo_i: Bool = Input(Bool())
    val sdo_o: Bool = Output(Bool())
    val sdo_en: Bool = Output(Bool())
    val sdi_i: Bool = Input(Bool())
    val sdi_o: Bool = Output(Bool())
    val sdi_en: Bool = Output(Bool())
    val inta_o: Bool = Output(Bool())
}

class VGAPort extends Bundle {
    val vga_r: UInt = Output(UInt(4.W))
    val vga_g: UInt = Output(UInt(4.W))
    val vga_b: UInt = Output(UInt(4.W))
    val vga_vsync: Bool = Output(Bool())
    val vga_hsync: Bool = Output(Bool())
}

class LcdTouchPort extends Bundle {
    val i2c: I2cPadPort = new I2cPadPort
    val interrupt: Bool = Input(Bool())
    val interruptOut: Bool = Output(Bool())
    val interruptOutputEnable: Bool = Output(Bool())
    val reset: Bool = Output(Bool())
}

class Usb3500UtmiPort extends Bundle {
    val dataI:          UInt = Input(UInt(8.W))
    val dataO:          UInt = Output(UInt(8.W))
    val dataOe:         Bool = Output(Bool())
    val dataT:          UInt = Output(UInt(8.W))
    val txValid:        Bool = Output(Bool())
    val txReady:        Bool = Input(Bool())
    val rxValid:        Bool = Input(Bool())
    val rxActive:       Bool = Input(Bool())
    val rxError:        Bool = Input(Bool())
    val lineState:      UInt = Input(UInt(2.W))
    val xcvrSel:        UInt = Output(UInt(2.W))
    val termSel:        Bool = Output(Bool())
    val opMode:         UInt = Output(UInt(2.W))
    val suspendN:       Bool = Output(Bool())
    val dpPd:           Bool = Output(Bool())
    val dmPd:           Bool = Output(Bool())
    val vbusValid:      Bool = Input(Bool())
    val hostDisconnect: Bool = Input(Bool())
}

class SocTopIo extends Bundle {
    val resetn: Bool = Input(Bool())
    val clk: Clock = Input(Clock())

    val gpio: GpioPort = new GpioPort
    val dotMatrix: DotMatrixPort = new DotMatrixPort

    val ddr3: DDR3Port = new DDR3Port
    val ddr3_dq: Analog = Analog(16.W)
    val ddr3_dqs_p: Analog = Analog(2.W)
    val ddr3_dqs_n: Analog = Analog(2.W)

    val mac: MacPort = new MacPort
    val ejtag: EjtagPort = new EjtagPort
    val uart: ApbDevUart0Port = new ApbDevUart0Port
    val debugUart: DebugUartPort = new DebugUartPort
    val nand: NandSplitPort = new NandSplitPort
    val spiFlash: SpiFlashPort = new SpiFlashPort
    val vga: VGAPort = new VGAPort
    val lcd: LcdPort = new LcdPort
    val lcdTouch: LcdTouchPort = new LcdTouchPort
    val usb: Usb3500UtmiPort = new Usb3500UtmiPort
    val sdio: SdioPort = new SdioPort
    val camera: CameraDvpPort = new CameraDvpPort

    val usbPhyClk:            Clock = Input(Clock())
}

/** xc7a200t SoC 顶层。 */
class CPUSTCSoc(
    features: SocFeatureConfig = SocFeatureConfig.retirePcDebug
) extends Module {
    val io: SocTopIo = IO(new SocTopIo)
    //clock
    val clkPll33 = Module(new ClkPll33)
    clkPll33.clk_in1 := io.clk
    val cpuClk: Clock = clkPll33.clk_out1
    val aClk: Clock = clkPll33.clk_out2

    val clkWiz1 = Module(new ClkWiz1)
    clkWiz1.clk_in1 := io.clk
    val c1ClkRefIN: Clock = clkWiz1.clk_out1
    val vgaClk :Clock = clkWiz1.clk_out2


    /**
     * resetn
     * chiplab的设计是：io.resetn作为根复位信号，其余大部分系统逻辑的复位释放还受 MIG 就绪状态控制。
     */
    val migAxi32 = Module(new MigAxi32)
    migAxi32.io.sysClk := io.clk
    migAxi32.io.clkRef := c1ClkRefIN
    migAxi32.io.sysRst := io.resetn
    /**
     * chiplab的resetn由axi interconnect生成，
     * 但是新版的axi interconnect似乎已经不支持输出reset信号，所以这里手动添加不同时钟域的reset信号
     */
    val ddrUIClk: Clock = migAxi32.io.status.uiClk
    val interconnectAresetn = ResetnSync(ddrUIClk, !migAxi32.io.status.uiClkSyncRst && migAxi32.io.status.initCalibComplete, 1);
    val ddrAresetn = ResetnSync(ddrUIClk, interconnectAresetn, 3)
    val aresetn = ResetnSync(aClk, interconnectAresetn, 2)
    val lcdAxiResetn = ResetnSync(io.clk, interconnectAresetn, 2)
    val sdioAresetn = ResetnSync(io.clk, interconnectAresetn, 2)
    val cpuResetn = ResetnSync(cpuClk, interconnectAresetn, 2)
    migAxi32.io.aresetn := ddrAresetn


    val coreTop = Module(new CoreTop)
    coreTop.io.aclk := cpuClk
    coreTop.io.aresetn := cpuResetn

    val retirementWatchdog = if (features.retirePc) {
        val watchdog = withClockAndReset(cpuClk, (!cpuResetn).asAsyncReset) {
            Module(new RetirementStallWatchdog(laneCount = 3, thresholdCycles = 512))
        }
        watchdog.io.clear := false.B
        watchdog.io.commitValid := coreTop.io.retireValid
        watchdog.io.commitPc := coreTop.io.retirePc
        Some(watchdog)
    } else None

    val axiClockConverter0 = Module(new AxiClockConverter0)
    axiClockConverter0.io.mAxiClock := aClk
    axiClockConverter0.io.mAxiResetn := aresetn
    axiClockConverter0.io.sAxiClock := cpuClk
    axiClockConverter0.io.sAxiResetn := cpuResetn

    val axiSlaveMux = Module(new AxiSlaveMux)
    axiSlaveMux.io.clk := aClk
    axiSlaveMux.io.resetn := aresetn
    axiSlaveMux.io.spiBoot := true.B //该信号疑似遗留信号，chiplab中固定绑定1‘b0,实际没有参与axi_mux_syn的内部逻辑

    val axiInterconnect0 = Module(new AxiInterconnect0)
    axiInterconnect0.io.aclk := ddrUIClk
    axiInterconnect0.io.aresetn := interconnectAresetn

    val spiFlashCtrl = Module(new SpiFlashCtrl)
    spiFlashCtrl.io.aclk := aClk
    spiFlashCtrl.io.aresetn := aresetn
    spiFlashCtrl.io.spiAddr := "h1fe8".U(16.W) //spi寄存器地址
    spiFlashCtrl.io.powerDownReq := false.B
    // spiFlashCtrl.io.powerDownAck 不绑，因为chiplab对应接口也没有接线
    spiFlashCtrl.io.fastStartup := false.B

    val confreg0 = Module(new Confreg0)
    confreg0.io.aclk := aClk
    confreg0.io.aresetn := aresetn

    val axi2ApbBridge = Module(new Axi2ApbBridge)
    axi2ApbBridge.io.clk := aClk
    axi2ApbBridge.io.resetn := aresetn

    val apbMux2 = Module(new ApbMux2(ApbMux2Config(
        usb = features.usb,
        display = features.hasDisplayPeripheral,
        lcdTouch = features.lcdTouch,
        lcd = features.lcd,
        nand = features.nand,
        camera = features.camera
    )))
    apbMux2.io.clk := aClk
    apbMux2.io.resetn := aresetn

    val sdioController = if (features.sdio) {
        val controller = Module(new LiteSdioController)
        controller.io.clock := io.clk
        controller.io.resetn := sdioAresetn
        Some(controller)
    } else None
    val sdioControlClockConverter = if (features.sdio) {
        val converter = Module(new AxiClockConverter0)
        converter.io.sAxiClock := aClk
        converter.io.sAxiResetn := aresetn
        converter.io.mAxiClock := io.clk
        converter.io.mAxiResetn := sdioAresetn
        converter.io.mAxi <> sdioController.get.io.control
        Some(converter)
    } else None

    val cascadedInterruptCtrl = Module(new CascadedInterruptCtrl)
    cascadedInterruptCtrl.io.clock := aClk
    cascadedInterruptCtrl.io.reset := !aresetn

    val uartTop = Module(new UartTop)
    uartTop.io.clk := aClk
    uartTop.io.resetn := aresetn

    val nandModule = if (features.nand) {
        val controller = Module(new NandModule)
        controller.io.clk := aClk
        controller.io.resetn := aresetn
        Some(controller)
    } else None

    val ethernetTop = Module(new EthernetTop)
    ethernetTop.io.hclk := aClk
    ethernetTop.io.hrst := aresetn

    val nandDmaMaster = if (features.nand) {
        val dma = Module(new NandDMAMaster)
        dma.io.clk := aClk
        dma.io.resetn := aresetn
        Some(dma)
    } else None

    val vgaCtrl = if (features.vga) {
        val controller = Module(new VGACtrl)
        controller.io.axiClk := io.clk
        controller.io.resetn := io.resetn
        controller.io.vgaClk := vgaClk
        controller.io.apbClk := aClk
        Some(controller)
    } else None

    val twoDGpu = if (features.twoDGpu) {
        Some(withClockAndReset(aClk, (!aresetn).asAsyncReset) {
            Module(new TwoDGpu)
        })
    } else None
    val tensorCoreAccel = if (features.tensorCore) {
        Some(withClockAndReset(aClk, (!aresetn).asAsyncReset) {
            Module(new TensorCoreAccel)
        })
    } else None
    val dotMatrixController = if (features.dotMatrix) {
        Some(withClockAndReset(aClk, (!aresetn).asAsyncReset) {
            Module(new DotMatrixController)
        })
    } else None
    val displayApbMux = Module(new DisplayApbMux(DisplayApbMuxConfig(
        vga = features.vga,
        gpu = features.twoDGpu,
        tensorCore = features.tensorCore,
        dotMatrix = features.dotMatrix
    )))

    val lcdCtrl = if (features.lcd) {
        val controller = Module(new LcdCtrl)
        controller.io.apbClk := aClk
        controller.io.apbResetn := aresetn
        controller.io.axiClk := io.clk
        controller.io.axiResetn := lcdAxiResetn
        val lcdButtonPress = withClockAndReset(aClk, (!aresetn).asAsyncReset) {
            val previousButtonValue = RegNext(confreg0.io.buttonValue, 0.U)
            confreg0.io.buttonValue & ~previousButtonValue
        }
        controller.io.buttonPress := lcdButtonPress(3, 0)
        Some(controller)
    } else None

    val apbToWishboneBridge = if (features.lcdTouch) {
        val bridge = Module(new ApbToWishboneBridge)
        bridge.io.clk := aClk
        bridge.io.resetn := aresetn
        Some(bridge)
    } else None

    val i2cMaster = if (features.lcdTouch) {
        val controller = Module(new I2cMaster)
        controller.io.clk := aClk
        controller.io.resetn := aresetn
        apbToWishboneBridge.get.io.wishbone <> controller.io.wishbone
        Some(controller)
    } else None

    val cameraApbMux = Module(new CameraApbMux)
    val cameraCapture = if (features.camera) {
        val controller = Module(new CameraCapture)
        controller.io.aclk := aClk
        controller.io.aresetn := aresetn
        controller.io.pclk := io.camera.pclk
        controller.io.pclkResetn := ResetnSync(io.camera.pclk, aresetn, 3)
        controller.io.vsync := io.camera.vsync
        controller.io.href := io.camera.href
        controller.io.data := io.camera.data
        Some(controller)
    } else None

    val cameraApbToWishboneBridge = if (features.camera) {
        val bridge = Module(new ApbToWishboneBridge)
        bridge.io.clk := aClk
        bridge.io.resetn := aresetn
        Some(bridge)
    } else None

    val cameraI2cMaster = if (features.camera) {
        val controller = Module(new I2cMaster)
        controller.io.clk := aClk
        controller.io.resetn := aresetn
        cameraApbToWishboneBridge.get.io.wishbone <> controller.io.wishbone
        Some(controller)
    } else None

    val goodixStartup = if (features.lcdTouch) {
        Some(withClockAndReset(aClk, (!aresetn).asAsyncReset) {
            Module(new GoodixStartupSequencer(
                resetHoldCycles = 33_000_000 / 50,
                interruptHoldCycles = 33_000_000 * 3 / 50
            ))
        })
    } else None

    val lcdTouchInterrupt = if (features.lcdTouch) {
        val interruptSync = BoolSync(aClk, aresetn, io.lcdTouch.interrupt)
        interruptSync && lcdCtrl.get.io.touchEnabled
    } else false.B
    cascadedInterruptCtrl.io.inputs := Cat(
        0.U(1.W),
        cameraI2cMaster.map(_.io.interrupt).getOrElse(false.B),
        cameraCapture.map(_.io.interrupt).getOrElse(false.B),
        sdioController.map(_.io.interrupt).getOrElse(false.B),
        tensorCoreAccel.map(_.io.interrupt).getOrElse(false.B),
        vgaCtrl.map(_.io.interrupt).getOrElse(false.B),
        lcdCtrl.map(_.io.interrupt).getOrElse(false.B),
        lcdTouchInterrupt
    )

    val usbOhciAxi4Apb3Utmi = if (features.usb) {
        val controller = Module(new UsbOhciAxi4Apb3Utmi(withUsbIla = features.usbIla))
        controller.io.utmiClock := io.usbPhyClk
        controller.io.utmiReset := !ResetnSync(io.usbPhyClk, io.resetn, 2)
        controller.io.ctrlClock := aClk
        controller.io.ctrlReset := !aresetn
        controller.io.dmaClock := aClk
        controller.io.dmaReset := !aresetn
        Some(controller)
    } else None

    // switch[7] selects the hardware monitor. The fallback address is sampled
    // on the CPU's AXI read-address handshake in the CPU clock domain.
    val readAddress = withClockAndReset(cpuClk, (!cpuResetn).asAsyncReset) {
        val address = RegInit(0.U(32.W))
        when (coreTop.io.axi.arvalid && coreTop.io.axi.arready) {
            address := coreTop.io.axi.araddr
        }
        address
    }
    val monitorEnable = BoolSync(cpuClk, cpuResetn, io.gpio.switch(7))
    val debugMonitorLeds = withClockAndReset(cpuClk, (!cpuResetn).asAsyncReset) {
        val ledStepCount = RegInit(0.U(26.W))
        val ledIndex = RegInit(0.U(4.W))
        val monitorStalled = if (features.retirePc) {
            retirementWatchdog.get.io.trigger
        } else {
            val noReadCycles = RegInit(0.U(20.W))
            val readFire = coreTop.io.axi.arvalid && coreTop.io.axi.arready
            when (!monitorEnable) {
                noReadCycles := 0.U
            }.elsewhen (readFire) {
                noReadCycles := 0.U
            }.elsewhen (noReadCycles =/= ((1 << 20) - 1).U) {
                noReadCycles := noReadCycles + 1.U
            }
            noReadCycles === ((1 << 20) - 1).U
        }

        when (!monitorEnable) {
            ledStepCount := 0.U
            ledIndex := 0.U
        }.otherwise {
            when (ledStepCount === (50_000_000 - 1).U) {
                ledStepCount := 0.U
                ledIndex := Mux(ledIndex === 15.U, 0.U, ledIndex + 1.U)
            }.otherwise {
                ledStepCount := ledStepCount + 1.U
            }
        }

        val walkingPattern = (1.U(16.W) << (15.U - ledIndex))
        // Board red LEDs are active low: all zeros indicates a detected stall.
        Mux(monitorStalled, 0.U(16.W), ~walkingPattern)
    }

    io.gpio.led := Mux(monitorEnable, debugMonitorLeds, confreg0.io.gpio.led)
    io.gpio.led_rg0 := confreg0.io.gpio.led_rg0
    io.gpio.led_rg1 := confreg0.io.gpio.led_rg1
    val debugSevenSegmentDisplay = withClockAndReset(cpuClk, (!cpuResetn).asAsyncReset) {
        Module(new DebugSevenSegmentDisplay(clockHz = 50_000_000))
    }
    val debugDisplayValue = retirementWatchdog
        .map(_.io.lastCommitPc)
        .getOrElse(readAddress)
    debugSevenSegmentDisplay.io.value := debugDisplayValue
    io.gpio.num_csn := Mux(monitorEnable, debugSevenSegmentDisplay.io.csn, confreg0.io.gpio.num_csn)
    io.gpio.num_a_g := Mux(monitorEnable, debugSevenSegmentDisplay.io.aG, confreg0.io.gpio.num_a_g)
    confreg0.io.gpio.switch := io.gpio.switch
    io.gpio.btn_key_col := confreg0.io.gpio.btn_key_col
    confreg0.io.gpio.btn_key_row := io.gpio.btn_key_row
    confreg0.io.gpio.btn_step := io.gpio.btn_step

    if (features.dotMatrix) {
        io.dotMatrix.columns := dotMatrixController.get.io.columns
        io.dotMatrix.rows := dotMatrixController.get.io.rows
    } else {
        // 与控制器复位后的低有效列、高有效行关闭状态一致。
        io.dotMatrix.columns := "hff".U
        io.dotMatrix.rows := 0.U
    }

    //SocTop.io
    io.ddr3 <> migAxi32.io.ddr3
    attach(io.ddr3_dq, migAxi32.io.ddr3_dq)
    attach(io.ddr3_dqs_n, migAxi32.io.ddr3_dqs_n)
    attach(io.ddr3_dqs_p, migAxi32.io.ddr3_dqs_p)
    io.mac <> ethernetTop.io.mac
    io.ejtag.EJTAG_TDO := false.B
    io.uart <> uartTop.io.uart
    io.debugUart.UART_TX2 := true.B
    if (features.nand) {
        io.nand <> nandModule.get.io.nand
    } else {
        io.nand.cle := false.B
        io.nand.ale := false.B
        io.nand.rd := true.B
        io.nand.ce := "hf".U
        io.nand.wr := true.B
        io.nand.dat_o := 0.U
        io.nand.dat_oe := true.B
    }
    io.spiFlash <> spiFlashCtrl.io.spi
    if (features.vga) {
        io.vga <> vgaCtrl.get.io.vga
    } else {
        io.vga.vga_r := 0.U
        io.vga.vga_g := 0.U
        io.vga.vga_b := 0.U
        io.vga.vga_vsync := true.B
        io.vga.vga_hsync := true.B
    }
    if (features.lcd) {
        io.lcd <> lcdCtrl.get.io.lcd
    } else {
        io.lcd.data := 0.U
        io.lcd.resetn := false.B
        io.lcd.chipSelectn := true.B
        io.lcd.registerSelect := false.B
        io.lcd.writen := true.B
        io.lcd.readn := true.B
        io.lcd.backlightEnable := false.B
    }
    if (features.lcdTouch) {
        io.lcdTouch.i2c <> i2cMaster.get.io.pads
        io.lcdTouch.interruptOut := goodixStartup.get.io.interruptOut
        io.lcdTouch.interruptOutputEnable := goodixStartup.get.io.interruptOutputEnable
        io.lcdTouch.reset := goodixStartup.get.io.resetn
    } else {
        io.lcdTouch.i2c.sclPadO := false.B
        io.lcdTouch.i2c.sclPadOenO := true.B
        io.lcdTouch.i2c.sdaPadO := false.B
        io.lcdTouch.i2c.sdaPadOenO := true.B
        io.lcdTouch.interruptOut := false.B
        io.lcdTouch.interruptOutputEnable := false.B
        io.lcdTouch.reset := false.B
    }
    io.camera.xclk := vgaClk
    io.camera.pwdn := false.B
    if (features.camera) {
        val resetHoldCycles = 33_000_000 / 200
        val cameraResetReleased = withClockAndReset(aClk, (!aresetn).asAsyncReset) {
            val counter = RegInit(0.U(log2Ceil(resetHoldCycles + 1).W))
            when(counter =/= resetHoldCycles.U) {
                counter := counter + 1.U
            }
            counter === resetHoldCycles.U
        }
        io.camera.resetn := cameraResetReleased
        io.camera.sccb <> cameraI2cMaster.get.io.pads
    } else {
        io.camera.resetn := false.B
        io.camera.sccb.sclPadO := false.B
        io.camera.sccb.sclPadOenO := true.B
        io.camera.sccb.sdaPadO := false.B
        io.camera.sccb.sdaPadOenO := true.B
    }
    if (features.usb) {
        io.usb <> usbOhciAxi4Apb3Utmi.get.io.utmi
    } else {
        io.usb.dataO := 0.U
        io.usb.dataOe := false.B
        io.usb.dataT := "hff".U
        io.usb.txValid := false.B
        io.usb.xcvrSel := 1.U
        io.usb.termSel := false.B
        io.usb.opMode := 1.U
        io.usb.suspendN := true.B
        io.usb.dpPd := true.B
        io.usb.dmPd := true.B
    }
    if (features.sdio) {
        io.sdio.sdClock := sdioController.get.io.sdio.sdClock
        attach(io.sdio.command, sdioController.get.io.sdio.command)
        attach(io.sdio.data, sdioController.get.io.sdio.data)
        sdioController.get.io.sdio.cardDetectN := io.sdio.cardDetectN
    } else {
        io.sdio.sdClock := false.B
    }

    //axiClockConverter
    coreTop.io.axi <> axiClockConverter0.io.sAxi
    axiClockConverter0.io.mAxi <> axiSlaveMux.io.axiSlave

    //axiSlaveMux
    axiSlaveMux.io.axiMasters(0) <> axiInterconnect0.io.s0
    axiSlaveMux.io.axiMasters(1) <> spiFlashCtrl.io.axi
    axiSlaveMux.io.axiMasters(2) <> axi2ApbBridge.io.axi
    axiSlaveMux.io.axiMasters(3) <> confreg0.io.axi
    axiSlaveMux.io.axiMasters(4) <> ethernetTop.io.axiSlave
    if (features.sdio) {
        axiSlaveMux.io.axiMasters(5) <> sdioControlClockConverter.get.io.sAxi
    } else {
        val sdioError = withClockAndReset(aClk, (!aresetn).asAsyncReset) {
            Module(new Axi3ErrorSlave)
        }
        axiSlaveMux.io.axiMasters(5) <> sdioError.io.axi
    }


    //axiInterconnect0
    axiInterconnect0.io.s0Clock := aClk
    axiInterconnect0.io.s0Resetn := aresetn

    axiInterconnect0.io.s1Clock := aClk
    axiInterconnect0.io.s1Resetn := aresetn
    ethernetTop.io.axiMaster <> axiInterconnect0.io.s1

    axiInterconnect0.io.s2Clock := aClk
    axiInterconnect0.io.s2Resetn := aresetn
    if (features.nand) {
        nandDmaMaster.get.io.axi <> axiInterconnect0.io.s2
    } else {
        AXI3IO.tieOffOutputs(axiInterconnect0.io.s2)
    }

    axiInterconnect0.io.s3Clock := io.clk
    axiInterconnect0.io.s3Resetn := io.resetn
    if (features.vga) {
        vgaCtrl.get.io.axi <> axiInterconnect0.io.s3
    } else {
        AXI4IO.tieOffOutputs(axiInterconnect0.io.s3)
    }


    axiInterconnect0.io.s4Clock := aClk
    axiInterconnect0.io.s4Resetn := aresetn
    if (features.usb) {
        usbOhciAxi4Apb3Utmi.get.io.dma <> axiInterconnect0.io.s4
    } else {
        AXI4IO.tieOffOutputs(axiInterconnect0.io.s4)
    }

    axiInterconnect0.io.s5Clock := io.clk
    axiInterconnect0.io.s5Resetn := lcdAxiResetn
    if (features.lcd) {
        lcdCtrl.get.io.axi <> axiInterconnect0.io.s5
    } else {
        AXI4IO.tieOffOutputs(axiInterconnect0.io.s5)
    }

    axiInterconnect0.io.s6Clock := aClk
    axiInterconnect0.io.s6Resetn := aresetn
    if (features.twoDGpu) {
        twoDGpu.get.io.axi <> axiInterconnect0.io.s6
    } else {
        AXI4IO.tieOffOutputs(axiInterconnect0.io.s6)
    }

    axiInterconnect0.io.s7Clock := aClk
    axiInterconnect0.io.s7Resetn := aresetn
    if (features.tensorCore) {
        tensorCoreAccel.get.io.axi <> axiInterconnect0.io.s7
    } else {
        AXI4IO.tieOffOutputs(axiInterconnect0.io.s7)
    }

    axiInterconnect0.io.s8Clock := io.clk
    axiInterconnect0.io.s8Resetn := sdioAresetn
    if (features.sdio) {
        sdioController.get.io.dma <> axiInterconnect0.io.s8
    } else {
        AXI4IO.tieOffOutputs(axiInterconnect0.io.s8)
    }

    axiInterconnect0.io.s9Clock := aClk
    axiInterconnect0.io.s9Resetn := aresetn
    if (features.camera) {
        cameraCapture.get.io.axi <> axiInterconnect0.io.s9
    } else {
        AXI4IO.tieOffOutputs(axiInterconnect0.io.s9)
    }

    axiInterconnect0.io.m0Clock := ddrUIClk
    axiInterconnect0.io.m0Resetn := ddrAresetn
    AXI4PortAdapter.connectMasterToSlave(axiInterconnect0.io.m0, migAxi32.io.axi)

    //NAND DMA master
    if (features.nand) {
        confreg0.io.nandDma <> nandDmaMaster.get.io.confreg
        nandDmaMaster.get.io.nandApb.request := nandModule.get.io.dmaRequest
        nandModule.get.io.dmaAcknowledge := nandDmaMaster.get.io.nandApb.acknowledge
        nandDmaMaster.get.io.nandApb.mux <> apbMux2.io.dma
    } else {
        confreg0.io.nandDma.finishReadOrder := false.B
        confreg0.io.nandDma.writeDmaEnd := false.B
        apbMux2.io.dma.write := false.B
        apbMux2.io.dma.psel := false.B
        apbMux2.io.dma.penable := false.B
        apbMux2.io.dma.addr := 0.U
        apbMux2.io.dma.writeData := 0.U
        apbMux2.io.dma.valid := false.B
    }
    // apbmux2
    axi2ApbBridge.io.apb <> apbMux2.io.cpu
    apbMux2.io.apb0 <> uartTop.io.apb
    if (features.usb) {
        APB3IO.connectMasterToSlave(apbMux2.io.apb1, usbOhciAxi4Apb3Utmi.get.io.ctrl)
    } else {
        APB3IO.tieOffInputs(apbMux2.io.apb1)
    }
    APB3IO.connectMasterToSlave(apbMux2.io.apb2, displayApbMux.io.upstream)
    if (features.vga) {
        APB3IO.connectMasterToSlave(displayApbMux.io.vga, vgaCtrl.get.io.apb)
    } else {
        APB3IO.tieOffInputs(displayApbMux.io.vga)
    }
    if (features.twoDGpu) {
        APB3IO.connectMasterToSlave(displayApbMux.io.gpu, twoDGpu.get.io.apb)
    } else {
        APB3IO.tieOffInputs(displayApbMux.io.gpu)
    }
    if (features.tensorCore) {
        APB3IO.connectMasterToSlave(displayApbMux.io.tensorCore, tensorCoreAccel.get.io.apb)
    } else {
        APB3IO.tieOffInputs(displayApbMux.io.tensorCore)
    }
    if (features.dotMatrix) {
        APB3IO.connectMasterToSlave(displayApbMux.io.dotMatrix, dotMatrixController.get.io.apb)
    } else {
        APB3IO.tieOffInputs(displayApbMux.io.dotMatrix)
    }
    if (features.lcdTouch) {
        APB3IO.connectMasterToSlave(apbMux2.io.apb3, apbToWishboneBridge.get.io.apb)
    } else {
        APB3IO.tieOffInputs(apbMux2.io.apb3)
    }
    if (features.lcd) {
        APB3IO.connectMasterToSlave(apbMux2.io.apb4, lcdCtrl.get.io.apb)
    } else {
        APB3IO.tieOffInputs(apbMux2.io.apb4)
    }
    APB3IO.connectMasterToSlave(apbMux2.io.apb5, cascadedInterruptCtrl.io.apb)
    if (features.nand) {
        apbMux2.io.apb6 <> nandModule.get.io.apb
    } else {
        apbMux2.io.apb6.acknowledge := false.B
        apbMux2.io.apb6.readData := 0.U
    }
    APB3IO.connectMasterToSlave(apbMux2.io.apb7, cameraApbMux.io.upstream)
    if (features.camera) {
        APB3IO.connectMasterToSlave(cameraApbMux.io.capture, cameraCapture.get.io.apb)
        APB3IO.connectMasterToSlave(cameraApbMux.io.sccb,
            cameraApbToWishboneBridge.get.io.apb)
    } else {
        APB3IO.tieOffInputs(cameraApbMux.io.capture)
        APB3IO.tieOffInputs(cameraApbMux.io.sccb)
    }


    // 外设中断均为持续到软件清除的电平信号；先在外设域寄存，避免组合毛刺进入同步器。
    val peripheralInterruptsAClk = withClockAndReset(aClk, (!aresetn).asAsyncReset) {
        RegNext(Cat(
            cascadedInterruptCtrl.io.interrupt,
            i2cMaster.map(_.io.interrupt).getOrElse(false.B),
            usbOhciAxi4Apb3Utmi.map(_.io.interrupt).getOrElse(false.B),
            nandDmaMaster.map(_.io.interrupt).getOrElse(false.B),
            nandModule.map(_.io.interrupt).getOrElse(false.B),
            spiFlashCtrl.io.spi.inta_o,
            uartTop.io.interrupt,
            ethernetTop.io.interrupt
        ), 0.U(8.W))
    }

    // 寄存后的电平中断逐位同步到 CPU 时钟域。
    coreTop.io.intrpt := Cat(
        BoolSync(cpuClk, cpuResetn, peripheralInterruptsAClk(7)), // HWI7 / ESTAT.IS[9]
        BoolSync(cpuClk, cpuResetn, peripheralInterruptsAClk(6)), // HWI6 / ESTAT.IS[8]
        BoolSync(cpuClk, cpuResetn, peripheralInterruptsAClk(5)), // HWI5 / ESTAT.IS[7]
        BoolSync(cpuClk, cpuResetn, peripheralInterruptsAClk(4)), // HWI4 / ESTAT.IS[6]
        BoolSync(cpuClk, cpuResetn, peripheralInterruptsAClk(3)), // HWI3 / ESTAT.IS[5]
        BoolSync(cpuClk, cpuResetn, peripheralInterruptsAClk(2)), // HWI2 / ESTAT.IS[4]
        BoolSync(cpuClk, cpuResetn, peripheralInterruptsAClk(1)), // HWI1 / ESTAT.IS[3]
        BoolSync(cpuClk, cpuResetn, peripheralInterruptsAClk(0))  // HWI0 / ESTAT.IS[2]
    )
}
