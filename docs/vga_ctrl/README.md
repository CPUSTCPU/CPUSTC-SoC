# VGACtrl 单模块用户手册

## 1. 模块功能

`VGACtrl` 从 AXI 可见内存连续读取 RGB565 framebuffer，经双行缓存和可选的 64x64 ARGB8888 硬件光标叠加后输出 RGB444 VGA 信号。

模块包含三部分：

- APB 寄存器：配置显示时序、framebuffer 基地址、每行读取长度、中断和显示使能。
- AXI 只读 master：逐行读取 framebuffer。
- VGA 扫描逻辑：产生像素、低有效行同步和低有效场同步信号。
- 光标叠加：通过两个 64x64 ARGB8888 BRAM bank 保存图像，在扫描输出端执行 premultiplied alpha 混合。

模块持续产生 VGA 同步时序，支持关闭像素输出与 AXI framebuffer 读取，并在可见帧结束时产生可屏蔽的 vblank 中断。

## 2. 时钟与接口

| 接口 | 时钟域 | 说明 |
| --- | --- | --- |
| `apb` | `apbClk` | 32 位配置寄存器访问 |
| `axi` | `axiClk` | 32 位 framebuffer 读通道，写通道保持无效 |
| `vga` | `vgaClk` | RGB444、HSYNC、VSYNC 输出 |
| `interrupt` | `apbClk` | 高有效 vblank 中断，状态清除前保持有效 |
| `resetn` | 全局输入 | 低有效，各时钟域内分别同步释放 |

三个时钟可以异步。APB 配置通过请求/应答 toggle 传入 AXI 和 VGA 域，并在安全帧边界应用；新配置完成跨域捕获前，后续配置写会通过 APB wait state 阻塞。

## 3. APB 寄存器

寄存器使用 32 位读写，地址按字节编址。模块精确匹配 `paddr[13:0]`，未定义或未按 4 字节对齐的偏移返回 `PSLVERR`。

| 偏移 | 名称 | 访问 | 复位值 | 说明 |
| --- | --- | --- | --- | --- |
| `0x00` | `H_VISIBLE` | RW | `640` | 每行可见像素数 |
| `0x04` | `H_FRONT` | RW | `16` | 水平前肩像素数 |
| `0x08` | `H_SYNC` | RW | `96` | 水平同步低电平宽度 |
| `0x0c` | `H_BACK` | RW | `48` | 水平后肩像素数 |
| `0x10` | `V_VISIBLE` | RW | `480` | 每帧可见行数 |
| `0x14` | `V_FRONT` | RW | `10` | 垂直前肩行数 |
| `0x18` | `V_SYNC` | RW | `2` | 垂直同步低电平宽度 |
| `0x1c` | `V_BACK` | RW | `33` | 垂直后肩行数 |
| `0x20` | `FRAME_BASE` | RW | `0x87e00000` | framebuffer 的 AXI 字节地址 |
| `0x24` | `BURST_COUNT_MAX` | RW | `20` | 每行读取的 16-beat burst 数量 |
| `0x28` | `IRQ_STATUS` | RW1C | `0` | bit 0 为 vblank pending，写 1 清除 |
| `0x2c` | `IRQ_ENABLE` | RW | `0` | bit 0 使能 vblank 中断输出 |
| `0x30` | `CONTROL` | RW | `1` | bit 0 使能像素输出和 AXI framebuffer 读取 |
| `0x34` | `CURSOR_IDENTIFICATION` | RO | `0x43555253` | ASCII `CURS` |
| `0x38` | `CURSOR_CAPABILITIES` | RO | `0x07404001` | version 1、64x64、ARGB8888、premultiplied alpha、双 bank |
| `0x3c` | `CURSOR_POSITION` | RW | `0` | `{signed y[15:0], signed x[15:0]}` |
| `0x40` | `CURSOR_SOURCE` | RW | `0` | `[15:8] src_y`、`[7:0] src_x`，范围 `0..63` |
| `0x44` | `CURSOR_SIZE` | RW | `0` | `[15:8] height`、`[7:0] width`，范围 `0..64` |
| `0x48` | `CURSOR_CONTROL` | RW/commit | `0` | bit0 enable、bit1 image bank；写入提交 staging payload |
| `0x4c` | `CURSOR_RAM_ADDRESS` | RW | `0x1000` | bit12 bank、bit11:0 pixel index |
| `0x50` | `CURSOR_RAM_DATA` | WO | - | 写一个 ARGB8888 pixel，成功后 pixel index 自动加一 |
| `0x54` | `CURSOR_STATUS` | RO | `0` | bit0 active、bit1 active bank、bit2 commit busy、bit3 upload error |

`CURSOR_CAPABILITIES[7:0]` 为接口版本，`[15:8]` 和 `[23:16]` 分别为最大宽高，bit24 表示 ARGB8888，bit25 表示 premultiplied alpha，bit26 表示双图像 bank。

软件只可写 inactive cursor bank；写 active bank 的 `CURSOR_RAM_DATA` 访问返回 `PSLVERR` 并置位粘滞 `upload error`。位置、source、size 和 control 在 APB 域作为稳定 payload，通过独立 toggle/ack 传入 VGA 域。bank 不变的 control commit 在下一行边界生效，bank 改变的 commit 在下一 vblank 边界生效；上一 commit 未完成时，新的 cursor staging 或 RAM 写访问保持 `PREADY=0`。

偏移 `0x100`-`0x1ff` 由同一 APB 窗口内的 `2D_GPU` 使用，不会送入 `VGACtrl`。提交命令时，矩形必须完整位于对应 stride 和 32 位 AXI 地址空间内；不同 base 的源、目标物理区间重叠时 `COPY_AREA` 返回错误，避免无法判定二维别名方向时破坏源数据。`2D_GPU` 寄存器为：

| 偏移 | 名称 | 说明 |
| --- | --- | --- |
| `0x100` | `STATUS` | bit0 BUSY，bit1 DONE，bit2 ERROR；DONE/ERROR 写 1 清除 |
| `0x104` | `COMMAND` | 1 FILL_RECT，2 COPY_AREA，3 IMAGE_BLIT1；写入后启动 |
| `0x108`/`0x10c` | `SRC_ADDRESS`/`DST_ADDRESS` | AXI 字节基地址 |
| `0x110`/`0x114` | `SRC_STRIDE`/`DST_STRIDE` | 每行字节跨度 |
| `0x118`/`0x11c` | `SRC_XY`/`DST_XY` | `{y[15:0], x[15:0]}` |
| `0x120` | `SIZE` | `{height[15:0], width[15:0]}` |
| `0x124`/`0x128` | `FOREGROUND`/`BACKGROUND` | RGB565 色值 |
| `0x12c` | `IDENTIFICATION` | 只读固定值 `0x32444750`（ASCII `2DGP`） |
| `0x130` | `CAPABILITIES` | 只读；bit0 FILL_RECT、bit1 COPY_AREA、bit2 IMAGE_BLIT1、bit8 RGB565、bit9 同 surface 重叠安全复制 |
| `0x134` | `VERSION` | 只读寄存器接口版本，当前为 `1` |

Xorg 驱动应先验证 `IDENTIFICATION` 和 `VERSION`，再按 `CAPABILITIES` 逐项启用 EXA Solid/Copy；不支持的 ROP、planemask、像素格式和命令必须回退到软件渲染。

`BURST_COUNT_MAX` 的有效范围为 `1..32`。非法写入返回 `PSLVERR`，寄存器保持原值。`IRQ_STATUS` 和 `IRQ_ENABLE` 立即生效，不等待配置 CDC；其他显示配置在安全帧边界生效。模块不检查显示时序参数范围、总和溢出或 `FRAME_BASE` 对齐；软件应写入非零时序参数，并保证 `FRAME_BASE` 至少按 4 字节对齐。

## 4. 时序计算

内部派生寄存器按下式计算：

```text
H_SYNC_START = H_VISIBLE + H_FRONT
H_SYNC_END   = H_SYNC_START + H_SYNC
H_TOTAL      = H_SYNC_END + H_BACK

V_SYNC_START = V_VISIBLE + V_FRONT
V_SYNC_END   = V_SYNC_START + V_SYNC
V_TOTAL      = V_SYNC_END + V_BACK
```

任一时序寄存器写成功后，派生寄存器按 APB 时钟逐周期更新：

```text
第 1 周期：H_SYNC_START
第 2 周期：H_SYNC_END
第 3 周期：H_TOTAL
第 4 周期：V_SYNC_START
第 5 周期：V_SYNC_END
第 6 周期：V_TOTAL
```

计算期间新的配置写通过 APB wait state 等待。`FRAME_BASE`、`BURST_COUNT_MAX` 和 `CONTROL` 写入不触发时序计算。

扫描条件为：

```text
可见区域：hCount < H_VISIBLE 且 vCount < V_VISIBLE
HSYNC = 0：H_SYNC_START <= hCount < H_SYNC_END
VSYNC = 0：V_SYNC_START <= vCount < V_SYNC_END
```

一帧包含 `H_TOTAL * V_TOTAL` 个像素时钟，帧率为：

```text
frameRate = vgaClkFrequency / H_TOTAL / V_TOTAL
```

## 5. Framebuffer 与 AXI 读取

AXI reader 的每个 burst 长度固定，每行 burst 数量可配置：

| 项目 | 数值 |
| --- | --- |
| AXI 数据宽度 | 32 位 |
| 每个 burst | 16 beat，INCR |
| 每行 burst 数 | `BURST_COUNT_MAX`，范围 `1..32` |
| 每行数据量 | `BURST_COUNT_MAX * 64` 字节 |
| 每行像素容量 | `BURST_COUNT_MAX * 32` 个 RGB565 像素，最大 1024 |

地址计算为：

```text
lineStride   = BURST_COUNT_MAX * 64
lineAddress  = FRAME_BASE + line * lineStride
burstAddress = lineAddress + burst * 64
```

模块读取 `V_VISIBLE` 行。软件应满足 `H_VISIBLE <= BURST_COUNT_MAX * 32`；一行像素数不能填满最后一个 burst 时，framebuffer 仍需按完整 `lineStride` 排列。水平最大支持 1024 个 RGB565 像素。

AXI 写地址、写数据和写响应通道保持无效。读响应中的 `RRESP` 未参与错误处理。

## 6. 像素格式

每个 32 位 framebuffer 字保存两个连续 RGB565 像素：

| 像素横坐标 | 数据位置 |
| --- | --- |
| 偶数 `x` | `word[15:0]` |
| 奇数 `x` | `word[31:16]` |

像素地址为：

```text
pixelWordAddress = FRAME_BASE + y * lineStride + (x / 2) * 4
```

RGB565 到板级 RGB444 的转换为：

```text
R[3:0] = pixel[15:12]
G[3:0] = pixel[10:7]
B[3:0] = pixel[4:1]
```

不可见区域或 `CONTROL.ENABLE=0` 时输出黑色。关闭显示后 HSYNC/VSYNC 和帧计数继续运行，AXI 域停止发起新的 framebuffer 读取。像素行通过两个 BRAM bank 交替缓存，AXI 域填充空闲 bank，VGA 域读取另一 bank。

光标 BRAM 固定按每行 64 个 32 位像素寻址，像素数值使用 `A[31:24] R[23:16] G[15:8] B[7:0]`。光标分量必须已经乘过 alpha，中间透明度逐通道计算：

```text
out = source + background * (255 - alpha) / 255
```

alpha 为 0 时直接输出背景，alpha 为 255 时直接输出光标；相加结果饱和到 255，再截取高 4 位输出 RGB444。`CONTROL.ENABLE=0` 或主显示 `CONTROL.ENABLE=0` 时光标不会绕过黑屏门控。

## 7. 配置注意事项

- 时序、`FRAME_BASE`、`BURST_COUNT_MAX` 和 `CONTROL` 通过 CDC 握手，在帧边界更新，不会在一帧中途切换。
- 时序寄存器写入后，派生值计算需要 6 个 APB 时钟；APB 访问会继续等待 AXI/VGA 域捕获配置。
- vblank pending 为粘滞状态。中断处理程序应向 `IRQ_STATUS.bit0` 写 1，再由级联中断控制器完成 EOI。
- 重新使能显示后，AXI reader 从下一帧的 `FRAME_BASE` 开始重新填充行缓存。
- cursor 图像应先完整上传到 inactive bank，再通过 `CURSOR_CONTROL` 切换；连续移动只更新 position/source/size 并保持 bank 不变。
- 模块校验 `BURST_COUNT_MAX` 以及 cursor source、size、control 和 RAM 地址/active-bank 写入规则；显示时序零值、溢出值或超出行缓存能力仍会产生未定义显示行为。
