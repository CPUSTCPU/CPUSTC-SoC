# OV7670 Camera Capture

本目录实现固定格式的 OV7670 DVP 采集数据通路：

```text
OV7670 640x480 RGB565 DVP
  -> cpustc_dvp_rx (PCLK domain)
  -> 8 KiB axis_async_fifo
  -> cpustc_camera_axi_writer (33 MHz aClk domain)
  -> DDR interconnect s9
  -> Linux V4L2 capture buffer
```

控制面由 APB3 提供 descriptor/completion 队列、状态、计数器和中断；SCCB
复用工程现有的 OpenCores I2C master。当前 RTL 固定输出 `640x480`、RGB565、
`bytesperline=1280`、`sizeimage=614400`。帧率由 OV7670 寄存器配置决定，目标为
5 fps，RTL 本身不丢帧限速。

## 数据通路

- `cpustc_dvp_rx.v` 在 `PCLK` 上升沿采样 `D[7:0]`，按两个字节组成一个
  RGB565 像素，并生成 SOF、pixel、EOF token。
- `axis_async_fifo.v` 以 64 位 AXI-Stream 跨越 `PCLK` 和 `aClk`，配置容量为
  8 KiB。OV7670 没有反压接口；FIFO 满时标记当前帧错误，writer 在下一帧边界
  重新同步。
- `cpustc_camera_axi_writer.v` 从四项 descriptor 队列取得 64 字节对齐的 DDR
  地址，以固定 16 beat、32 位 AXI4 burst 写入一帧。一次只允许一个 burst
  outstanding，收到最后一个 BRESP 后才生成 completion。
- completion 队列同样为四项。软件必须及时 pop；队列满时新帧无法取得
  descriptor，并计入丢帧。
- `CONTROL.BYTE_SWAP` 可交换每个 RGB565 像素的两个输入字节，不改变 DDR 中
  像素的 16 位格式。

## 地址与中断

Camera 占用 `ApbMux2` 的 8 KiB page 7：

| CPU 地址 | 功能 | 级联中断 |
| --- | --- | --- |
| `0x1fe0e000`-`0x1fe0efff` | capture/DMA 寄存器 | bit 5 |
| `0x1fe0f000`-`0x1fe0ffff` | SCCB I2C master | bit 6 |

两路中断经 `0x1fe0a000` 的级联控制器汇总到 HWI7。SCCB 窗口沿用现有
OpenCores I2C 寄存器布局，32 位 APB 访问通过 APB-to-Wishbone bridge 转换为
8 位寄存器访问，寄存器间隔为 4 字节。

Capture 寄存器基址为 `0x1fe0e000`：

| 偏移 | 名称 | 说明 |
| --- | --- | --- |
| `0x000` | `ID` | `0x43414d31` (`CAM1`) |
| `0x004` | `VERSION` | `0x00010000` |
| `0x008` | `CAPABILITIES` | bit 0 固定为 1；bit 1 表示 profiling 计数器已编译 |
| `0x00c` | `CONTROL` | bit 0 capture enable；bit 1 byte swap |
| `0x010` | `STATUS` | bit 0 descriptor 非空；bit 1 completion 非空；bit 2 writer busy；bits 18:5 FIFO depth |
| `0x014` | `IRQ_STATUS` | bit 0 completion 非空；bits 5:1 为 sticky 状态，写 1 清除 |
| `0x018` | `IRQ_ENABLE` | 对应位中断使能 |
| `0x01c` | `ABORT` | 写 bit 0 为 1，中止当前帧 |
| `0x020` | `FORMAT` | V4L2 fourcc `RGBP` (`V4L2_PIX_FMT_RGB565`) |
| `0x024` | `WIDTH` | 640 |
| `0x028` | `HEIGHT` | 480 |
| `0x02c` | `BYTES_PER_LINE` | 1280 |
| `0x030` | `FRAME_BYTES` | 614400 |
| `0x040` | `QUEUE_ADDR` | 待 push 的 DDR 物理地址，必须 64 字节对齐 |
| `0x044` | `QUEUE_TAG` | 待 push 的软件 tag |
| `0x048` | `QUEUE_PUSH` | 写 1 提交 descriptor |
| `0x04c` | `QUEUE_COUNT` | descriptor 数量，0..4 |
| `0x060` | `DONE_TAG` | 队首 completion tag |
| `0x064` | `DONE_STATUS` | 队首 completion 状态 |
| `0x068` | `DONE_BYTES` | 队首实际完成字节数 |
| `0x06c` | `DONE_POP` | 写 1 弹出队首 completion |
| `0x070` | `DONE_COUNT` | completion 数量，0..4 |
| `0x080` | `FRAMES_STARTED` | 已取得 descriptor 的帧数 |
| `0x084` | `FRAMES_COMPLETED` | 已生成 completion 的帧数 |
| `0x088` | `FRAMES_DROPPED` | 错误或无缓冲导致的丢帧数 |
| `0x08c` | `FIFO_OVERFLOWS` | FIFO overflow 事件数 |
| `0x090` | `AXI_ERRORS` | BRESP 错误帧数 |
| `0x094` | `NO_BUFFER_DROPS` | SOF 到达时无可用 descriptor/completion 空位的次数 |
| `0x098` | `QUEUE_ERRORS` | 非法 push/pop 次数 |
| `0x09c` | `LAST_FRAME_CYCLES` | 最近完成帧的 `aClk` 周期数 |
| `0x0a0` | `LAST_FIFO_WAIT_CYCLES` | 最近帧等待 FIFO 数据的周期数 |
| `0x0a4` | `LAST_AXI_ACTIVE_CYCLES` | 最近帧处于 AXI AW/W/B 阶段的周期数 |
| `0x0a8` | `LAST_AXI_STALL_CYCLES` | 最近帧 AXI handshake 停顿周期数 |
| `0x0ac` | `MAX_FIFO_DEPTH` | 运行以来最大 FIFO depth |

`DONE_STATUS` 位定义为：bit 0 FIFO overflow、bit 1 帧尺寸错误、bit 2 token
协议错误、bit 3 AXI BRESP 错误、bit 4 aborted。`IRQ_STATUS` bit 0 由
completion 队列是否非空实时产生，只能通过 `DONE_POP` 撤销；其余错误位为
sticky W1C。

## 计时开关

`CPUSTC_CAMERA_PROFILE` 默认未定义，此时 `0x09c`-`0x0ac` 读零，避免计时
寄存器和加法器进入生产硬件。Vivado 中可在 `sources_1` 的 Verilog defines 增加：

```tcl
set_property verilog_define {CPUSTC_CAMERA_PROFILE} \
    [get_filesets sources_1]
```

关闭时从 define 列表移除 `CPUSTC_CAMERA_PROFILE`。功能计数器
`FRAMES_*`、`FIFO_OVERFLOWS`、`AXI_ERRORS` 和队列错误计数始终存在。

## J13/J15 接线与时钟

当前 `soc_up.xdc` 固定以下信号：

| OV7670 信号 | FPGA PACKAGE_PIN | 方向 |
| --- | --- | --- |
| `SIOC` | `M25`（J13-3 / `EXT_IO0`） | 双向开漏 |
| `SIOD` | `P25`（J13-4 / `EXT_IO1`） | 双向开漏 |
| `VSYNC` | `T20` | 输入 |
| `HREF` | `AD25` | 输入 |
| `XCLK` | `AE25` | 输出 |
| `RESET#` | `AF25` | 输出 |
| `PCLK` | `U22` | 输入 |
| `PWDN` | `AF24` | 输出 |
| `D7..D0` | `U21,V24,V23,W23,AE23,V22,U25,U26` | 输入 |

信号电平约束为 LVCMOS33。J13-3/4 分别由板载 `R175/R183` 通过 4.7 kΩ
上拉到 3.3 V，SCCB 不需要另加上拉；其余 Camera 信号、电源和 GND 按板卡
J15 与 OV7670 模块丝印连接。不要再将 OV7670 `SIOC/SIOD` 接到 J15-3/4。
`XCLK` 由 25 MHz VGA clock 经 ODDR 输出；SoC 复位释放后继续保持 `RESET#`
低 5 ms。`PCLK` 是独立时钟域，当前按最大 25 MHz 和 falling-edge 数据稳定窗口
约束。

## 来源与验证

`axis_async_fifo.v` 和 `syn/axis_async_fifo.tcl` 来自 alexforencich/verilog-axis
commit `48ff7a7e2ef782cf778d47910cf85835c64b1bce`，许可证为 MIT，原文保存在
`LICENSE-verilog-axis`。

定向 RTL 仿真入口：

```bash
IP/CAMERA/tb/run_tests.sh
```

当前测试覆盖完整 VGA 帧、AXI AW/W/B 反压、BRESP、FIFO overflow、无缓冲、
completion 满、stop/abort、双域复位、行/帧长度错误和 IRQ/W1C。SoC 侧仍需
实现 Linux V4L2 capture 驱动、OV7670 SCCB 初始化表、设备树和 Buildroot 集成；
当前也没有 bitstream 或板级采集结论。
