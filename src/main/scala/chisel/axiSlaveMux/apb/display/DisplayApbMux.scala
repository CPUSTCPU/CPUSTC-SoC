package chisel.axiSlaveMux.apb.display

import chisel3._
import chisel3.util.MuxCase
import chisel.common.bus.APB3IO

/** 显示 APB 子窗口的编译期挂载配置。 */
final case class DisplayApbMuxConfig(
  vga: Boolean = true,
  gpu: Boolean = true,
  tensorCore: Boolean = true,
  dotMatrix: Boolean = true
)

class DisplayApbMuxIO extends Bundle {
  val upstream: APB3IO = Flipped(new APB3IO(addrWidth = 20))
  val vga: APB3IO = new APB3IO(addrWidth = 20)
  val gpu: APB3IO = new APB3IO(addrWidth = 20)
  val tensorCore: APB3IO = new APB3IO(addrWidth = 20)
  val dotMatrix: APB3IO = new APB3IO(addrWidth = 20)
}

/** 显示 APB 窗口内的组合子地址选择器。
  *
  * VGA、2D_GPU、TensorCore 和 8x8 LED 点阵分别占用 `0x000`、`0x100`、`0x200`
  * 和 `0x300` 子窗口。
  * 本模块不跨时钟域，也不改变 APB 传输时序。
  */
class DisplayApbMux(config: DisplayApbMuxConfig = DisplayApbMuxConfig()) extends RawModule {
  val io: DisplayApbMuxIO = IO(new DisplayApbMuxIO)

  private val subWindow = io.upstream.paddr(12, 8)
  private val vgaSelected = subWindow === 0.U
  private val gpuSelected = subWindow === 1.U
  private val tensorCoreSelected = subWindow === 2.U
  private val dotMatrixSelected = subWindow === 3.U
  private val vgaEnabled = config.vga.B
  private val gpuEnabled = config.gpu.B
  private val tensorCoreEnabled = config.tensorCore.B
  private val dotMatrixEnabled = config.dotMatrix.B

  io.vga.psel := io.upstream.psel && vgaSelected && vgaEnabled
  io.vga.penable := io.upstream.penable
  io.vga.pwrite := io.upstream.pwrite
  io.vga.paddr := io.upstream.paddr
  io.vga.pwdata := io.upstream.pwdata

  io.gpu.psel := io.upstream.psel && gpuSelected && gpuEnabled
  io.gpu.penable := io.upstream.penable
  io.gpu.pwrite := io.upstream.pwrite
  io.gpu.paddr := io.upstream.paddr
  io.gpu.pwdata := io.upstream.pwdata

  io.tensorCore.psel := io.upstream.psel && tensorCoreSelected && tensorCoreEnabled
  io.tensorCore.penable := io.upstream.penable
  io.tensorCore.pwrite := io.upstream.pwrite
  io.tensorCore.paddr := io.upstream.paddr
  io.tensorCore.pwdata := io.upstream.pwdata

  io.dotMatrix.psel := io.upstream.psel && dotMatrixSelected && dotMatrixEnabled
  io.dotMatrix.penable := io.upstream.penable
  io.dotMatrix.pwrite := io.upstream.pwrite
  io.dotMatrix.paddr := io.upstream.paddr
  io.dotMatrix.pwdata := io.upstream.pwdata

  io.upstream.prdata := MuxCase(0.U, Seq(
    vgaSelected -> Mux(vgaEnabled, io.vga.prdata, 0.U),
    gpuSelected -> Mux(gpuEnabled, io.gpu.prdata, 0.U),
    tensorCoreSelected -> Mux(tensorCoreEnabled, io.tensorCore.prdata, 0.U),
    dotMatrixSelected -> Mux(dotMatrixEnabled, io.dotMatrix.prdata, 0.U)
  ))
  io.upstream.pready := MuxCase(true.B, Seq(
    vgaSelected -> Mux(vgaEnabled, io.vga.pready, true.B),
    gpuSelected -> Mux(gpuEnabled, io.gpu.pready, true.B),
    tensorCoreSelected -> Mux(tensorCoreEnabled, io.tensorCore.pready, true.B),
    dotMatrixSelected -> Mux(dotMatrixEnabled, io.dotMatrix.pready, true.B)
  ))
  private val errorAccess = io.upstream.psel && io.upstream.penable
  io.upstream.pslverr := MuxCase(
    errorAccess,
    Seq(
      vgaSelected -> Mux(vgaEnabled, io.vga.pslverr, errorAccess),
      gpuSelected -> Mux(gpuEnabled, io.gpu.pslverr, errorAccess),
      tensorCoreSelected -> Mux(tensorCoreEnabled, io.tensorCore.pslverr, errorAccess),
      dotMatrixSelected -> Mux(dotMatrixEnabled, io.dotMatrix.pslverr, errorAccess)
    )
  )
}
