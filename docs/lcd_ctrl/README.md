# LCD Ctrl 用户文档

本文描述 `xc7a200t` 目标工程中的自定义 LCD 控制器，面向固件、裸机程序和 Linux 驱动开发。内容以当前 Chisel、SpinalHDL 和板级连接为准。

## 1. 功能概览

LCD Ctrl 提供两条写入路径：

```text
CPU
  -> AXI-to-APB
  -> APB4 @ 0x1fe08000
  -> CMD/DATA 寄存器
  -> 16-bit 8080 写接口

DDR
  -> AXI interconnect S5
  -> LcdVideoDma @ 100 MHz
  -> 16 x 32-bit StreamFifoCC
  -> 32-bit 到 16-bit 转换
  -> 8080 写引擎 @ 33 MHz
```

主要能力：

- CPU 通过 `CMD` 和 `DATA` 寄存器执行单次 16 位 8080 写周期。
- DMA 从 DDR 读取连续区域或带源 stride 的二维矩形，并将有效 16 位元素作为 LCD DATA 写出。
- DMA 起始地址支持 2 字节对齐，无需 16 字节对齐。
- AXI 侧固定使用 32 位数据、4 beat、16 字节 INCR burst。
- 内部异步 FIFO 完成 100 MHz AXI 域到 33 MHz 8080 域的跨时钟缓存。
- DMA 完成状态可轮询，也可通过级联中断控制器送到 CPU HWI7。
- 8080 setup、WR low 和 hold 时间均可按 33 MHz 时钟周期配置。

当前范围限制：

- 只支持写操作，无 LCD 数据读回。
- `LCD_rd` 固定为高，16 位数据总线固定为 FPGA 输出。
- 背光 `LCD_lighton` 固定为高，无软件控制位。
- DMA 支持单个二维矩形，无链式描述符或请求队列。
- 无硬件 abort、超时、自动重试或 DMA 队列。
- DMA 不自动发送 LCD 命令，窗口设置和 memory-write 命令必须由软件先完成。
- 控制器不转换像素格式或字节序，RGB565 只是当前约定的数据格式。
- DMA_ERROR 不单独产生中断，DMA_DONE 可以产生中断。

## 2. 地址与访问要求

LCD Ctrl 的 CPU 物理地址窗口为：

| 项目 | 数值 |
| --- | --- |
| APB 从设备 | `apb4` |
| CPU 物理基址 | `0x1fe08000` |
| APB 窗口 | `0x1fe08000` - `0x1fe09fff` |
| 有效寄存器偏移 | `0x00` - `0x28`，按下表精确匹配 |
| 推荐访问宽度 | 32 位 |
| 地址对齐 | 4 字节 |

软件应使用 32 位、4 字节对齐的 MMIO 读写。未定义偏移不会镜像到低地址寄存器，例如 `0x100` 不会映射到 `CMD`。

LCD Ctrl 会在以下情况产生 APB `PSLVERR`：

- 地址未按 4 字节对齐。
- 访问未定义寄存器偏移。
- 读取只写的 `CMD` 或 `DATA`。
- DMA 运行期间访问 `CMD` 或 `DATA`。
- DMA 运行期间再次请求 START。
- START 时 `DMA_BASE` 为奇地址。
- START 时 `DMA_LENGTH` 为 0。
- START 时 `DMA_BASE + DMA_LENGTH * 2` 超出 32 位地址空间。
- 二维 START 时宽高超出 `1..480`、`1..800`，stride 为奇数，`DMA_SRC_STRIDE < DMA_WIDTH * 2`，或最后一行有效范围超出 32 位地址空间。

> 当前共享 `apb_mux2` 不向上游传播从设备 `PSLVERR`，`axi2apb_bridge` 的 AXI `BRESP/RRESP` 固定为 `OKAY`。CPU 当前无法依靠总线异常判断上述错误。软件必须主动检查参数、访问顺序和 `STATUS`。非法写入不会生效。

## 3. 寄存器总表

| 偏移 | 名称 | 访问 | 复位值 | 说明 |
| --- | --- | --- | --- | --- |
| `0x00` | `CMD` | WO | - | 执行一次 16 位 LCD 命令写 |
| `0x04` | `DATA` | WO | - | 执行一次 16 位 LCD 数据写 |
| `0x08` | `CONTROL` | RW/触发 | `0x00000000` | 面板复位电平和 DMA START |
| `0x0c` | `STATUS` | RO/W1C | `0x00000000` | 忙、完成和错误状态 |
| `0x10` | `DMA_BASE` | RW | `0x00000000` | DDR 字节地址 |
| `0x14` | `DMA_LENGTH` | RW | `0x00000000` | 16 位元素数量 |
| `0x18` | `WRITE_TIMING` | RW | `0x00050503` | 8080 写时序周期数 |
| `0x1c` | `IRQ_ENABLE` | RW | `0x00000000` | DMA_DONE 本地中断使能 |
| `0x20` | `DMA_WIDTH` | RW | `0x00000000` | 二维模式每行 16 位元素数 |
| `0x24` | `DMA_HEIGHT` | RW | `0x00000000` | 二维模式行数 |
| `0x28` | `DMA_SRC_STRIDE` | RW | `0x00000000` | 二维模式相邻行起点字节间隔 |

保留位应写 0。

## 4. 寄存器详细说明

### 4.1 CMD, `0x00`

| 位 | 名称 | 说明 |
| --- | --- | --- |
| `[15:0]` | `COMMAND` | 输出到 LCD 数据总线 |
| `[31:16]` | 保留 | 写入值被忽略 |

写入 `CMD` 时：

- `LCD_rs=0`，表示命令周期。
- `LCD_csel` 在 setup、WR low 和 hold 阶段保持低。
- `LCD_wr` 只在 WR low 阶段为低。
- APB `PREADY` 等待整个物理写周期完成后才拉高。

`CMD` 只允许写。DMA_BUSY 为 1 时该访问被拒绝。

### 4.2 DATA, `0x04`

| 位 | 名称 | 说明 |
| --- | --- | --- |
| `[15:0]` | `DATA` | 输出到 LCD 数据总线 |
| `[31:16]` | 保留 | 写入值被忽略 |

写入 `DATA` 时的时序与 `CMD` 相同，`LCD_rs=1`。DMA_BUSY 为 1 时该访问被拒绝。

DMA 输出的每个 16 位元素也使用 DATA 周期，软件无需逐项写 `DATA`。

### 4.3 CONTROL, `0x08`

| 位 | 名称 | 访问 | 说明 |
| --- | --- | --- | --- |
| `[0]` | `LCD_RESET_N` | RW | 直接驱动面板低有效复位引脚，复位值为 0 |
| `[1]` | `DMA_START` | W1T | 写 1 请求启动 DMA，读回恒为 0 |
| `[2]` | `DMA_2D` | W1T | 与 START 同时写 1 时使用二维参数，读回恒为 0 |
| `[31:3]` | 保留 | - | 写入忽略，读回 0 |

`LCD_RESET_N` 没有自动脉冲或延时。软件负责按面板数据手册控制低电平持续时间和复位释放后的等待时间。

每次写 `CONTROL` 都会更新 bit0。面板处于工作状态时，启动 DMA 必须写入：

```text
CONTROL = LCD_RESET_N | DMA_START = 0x00000003
```

写 `0x00000002` 会在请求 START 的同时把面板重新拉入复位。

START 请求被接受时，硬件执行以下操作：

1. 锁存当前一维或二维 DMA 参数作为本次传输配置。
2. 置位 `DMA_BUSY`。
3. 清除上一笔 `DMA_DONE` 和 `DMA_ERROR`。
4. 将启动事件跨时钟域发送到 100 MHz AXI reader。

START 参数无效或 DMA 已经忙时，请求不会排队，也不会修改状态。由于当前 CPU 看不到 `PSLVERR`，软件应在写 START 前自行验证参数并确认 `DMA_BUSY=0`。

### 4.4 STATUS, `0x0c`

| 位 | 名称 | 访问 | 说明 |
| --- | --- | --- | --- |
| `[0]` | `LCD_BUSY` | RO | 8080 写状态机当前处于 setup、low、hold 或直接响应阶段 |
| `[1]` | `DMA_BUSY` | RO | 一笔 DMA 已接受且尚未完整结束 |
| `[2]` | `DMA_DONE` | RO/W1C | DMA 完成粘滞状态，写 1 清除 |
| `[3]` | `DMA_ERROR` | RO/W1C | DMA 错误粘滞状态，写 1 清除 |
| `[31:4]` | 保留 | RO | 读回 0 |

状态说明：

- `LCD_BUSY` 只表示当前物理 8080 周期。DMA 字之间的调度空拍中，该位可能短暂为 0。
- `DMA_BUSY` 覆盖整个 DMA 生命周期，应使用该位判断能否启动下一笔传输。
- `DMA_DONE` 在最后一个请求数据完成 hold 阶段，并且物理 AXI burst 的尾部多读数据全部排空后置 1。
- `DMA_DONE` 保持到 W1C 或下一次有效 START。
- `DMA_ERROR` 保持到 W1C 或下一次有效 START。
- DONE 和 ERROR 可以同时为 1。AXI 出错不会阻止传输完成。

W1C 示例：

```text
写 0x00000004：只清 DMA_DONE
写 0x00000008：只清 DMA_ERROR
写 0x0000000c：同时清 DMA_DONE 和 DMA_ERROR
```

### 4.5 DMA_BASE, `0x10`

`DMA_BASE` 是连续数据起点或二维矩形左上角元素的 DDR 字节地址。

约束：

- 地址必须为偶数。
- 地址可以位于 16 字节 burst 内的任意偶数偏移。
- 必须使用 AXI/MIG 可见的 DDR 总线地址。CPU 虚拟地址不能直接写入该寄存器。
- 软件必须保证硬件实际读取的对齐扩展区域可访问，详见“DMA 对齐与多读”。

DMA 启动后，修改 `DMA_BASE` 只影响下一笔传输。

### 4.6 DMA_LENGTH, `0x14`

`DMA_LENGTH` 的单位为 16 位元素，不能写 0。

RGB565 连续区域通常使用：

```text
DMA_LENGTH = width * height
请求字节数 = DMA_LENGTH * 2
```

地址范围必须满足：

```text
DMA_BASE + DMA_LENGTH * 2 <= 0x1_0000_0000
```

DMA 启动后，修改 `DMA_LENGTH` 只影响下一笔传输。

### 4.7 WRITE_TIMING, `0x18`

| 位 | 名称 | 说明 |
| --- | --- | --- |
| `[7:0]` | `SETUP` | `LCD_csel=0`、`LCD_wr=1`，数据和 RS 建立时间 |
| `[15:8]` | `WR_LOW` | `LCD_csel=0`、`LCD_wr=0` 的持续时间 |
| `[23:16]` | `HOLD` | `LCD_csel=0`、`LCD_wr=1`，数据和 RS 保持时间 |
| `[31:24]` | 保留 | 不参与时序，建议写 0 |

每个有效字段的单位为一个 33 MHz `aClk` 周期：

```text
Tcycle = 1 / 33 MHz = 约 30.303 ns
```

字段写 0 时，硬件按 1 个周期执行。有效行为范围为 1 到 255 个周期。

复位值 `0x00050503` 表示：

| 阶段 | 周期数 | 标称时间 |
| --- | --- | --- |
| SETUP | 3 | 约 90.9 ns |
| WR_LOW | 5 | 约 151.5 ns |
| HOLD | 5 | 约 151.5 ns |

软件应根据实际 LCD 控制器数据手册设置时序。DMA 期间修改该寄存器可能影响正在进行的后续写周期，推荐只在 `DMA_BUSY=0` 时修改。

### 4.8 IRQ_ENABLE, `0x1c`

| 位 | 名称 | 说明 |
| --- | --- | --- |
| `[0]` | `DMA_DONE_EN` | 允许 DMA_DONE 驱动 LCD 本地中断输出 |
| `[31:1]` | 保留 | 写入忽略，读回 0 |

LCD 本地中断为电平信号：

```text
lcd_interrupt = STATUS.DMA_DONE && IRQ_ENABLE.DMA_DONE_EN
```

`DMA_ERROR` 不参与中断表达式。DONE 已经置位后再开启 `DMA_DONE_EN`，本地中断会立即拉高。

### 4.9 二维 DMA 参数, `0x20` - `0x28`

二维模式参数为：

```text
DMA_WIDTH      = 每行有效 16 位元素数
DMA_HEIGHT     = 有效行数
DMA_SRC_STRIDE = 相邻两行起点的字节间隔
```

`DMA_WIDTH` 范围为 1 到 480，`DMA_HEIGHT` 范围为 1 到 800，
`DMA_SRC_STRIDE` 必须为偶数且不小于 `DMA_WIDTH * 2`。硬件拒绝最后一行有效范围
超出 32 位地址空间的配置；软件还必须保证每行 16 字节对齐扩展读取位于已映射的
DMA 缓冲区内。写入
`CONTROL=LCD_RESET_N | DMA_START | DMA_2D` 启动二维传输。旧软件继续使用
`DMA_LENGTH` 和 `CONTROL=LCD_RESET_N | DMA_START`，其行为保持不变。

## 5. 8080 写接口行为

板级信号均为 3.3 V LVCMOS：

| 信号 | 极性/方向 | 行为 |
| --- | --- | --- |
| `LCD_data_tri_io[15:0]` | FPGA 输出 | 命令、数据或 RGB565 半字 |
| `LCD_nrst` | 低有效输出 | 由 `CONTROL.LCD_RESET_N` 直接控制 |
| `LCD_csel` | 低有效输出 | 每个写周期的 setup、low、hold 阶段为低 |
| `LCD_rs` | 输出 | CMD 为 0，DATA 和 DMA 为 1 |
| `LCD_wr` | 低有效输出 | 仅 WR_LOW 阶段为低 |
| `LCD_rd` | 低有效输出 | 固定为高，不执行读周期 |
| `LCD_lighton` | 输出 | 固定为高 |

单次写周期顺序：

```text
IDLE
  -> SETUP: CS=0, WR=1, DATA/RS稳定
  -> LOW:   CS=0, WR=0, DATA/RS稳定
  -> HOLD:  CS=0, WR=1, DATA/RS稳定
  -> IDLE:  CS=1, WR=1
```

CPU 写 `CMD` 或 `DATA` 时，APB 访问会等待以上三个阶段完成。DMA 连续发送时，相邻 16 位写之间还有一个内部 IDLE 调度周期。

在 AXI 数据持续可用且无其他停顿时，DMA 每个 16 位元素约占：

```text
1 + SETUP + WR_LOW + HOLD 个 aClk 周期
```

复位时序配置下约为 14 个周期，即约 424 ns/元素。480 x 800 RGB565 全帧的纯写时间约 163 ms，对应约 6.1 帧/秒的理论上限。实际性能还受 AXI stall、CDC 启动延迟和软件操作影响。

## 6. DMA 数据语义

### 6.1 AXI 访问参数

DMA reader 固定使用：

| 参数 | 数值 |
| --- | --- |
| AXI 数据宽度 | 32 位 |
| 每个 burst 的 beat 数 | 4 |
| 每个 beat 字节数 | 4 |
| 每个 burst 字节数 | 16 |
| `ARLEN` | 3 |
| `ARSIZE` | 2，即 4 字节 |
| `ARBURST` | INCR |
| 同时 pending 请求上限 | 1 |
| 跨时钟 FIFO | 16 x 32 位，共 64 字节 |

FIFO 满时 reader 停止提交后续 burst。8080 侧回压不会改变已经有效的流数据。

### 6.2 32 位到 16 位顺序

每个 32 位 AXI beat 按以下顺序输出：

```text
先输出 RDATA[15:0]
再输出 RDATA[31:16]
```

软件按连续 `uint16_t` 数组在 DDR 中布置数据即可。控制器不交换 RGB565 的 R/B 分量，也不进行字节交换。

### 6.3 DMA 对齐与多读

硬件将每行起始地址分别向下对齐到 16 字节边界，并丢弃目标起点前的半字。一维
模式等价于高度为 1 的二维请求。每行计算方式为：

```text
row_base          = DMA_BASE + row_index * DMA_SRC_STRIDE
aligned_base      = row_base & ~0xf
leading_halfwords = (row_base & 0xf) / 2
burst_count       = ceil((leading_halfwords + DMA_WIDTH) / 8)
physical_bytes    = burst_count * 16
```

因此硬件可能读取：

- `DMA_BASE` 之前最多 14 字节。
- 请求结束地址之后最多 14 字节。

这些额外数据只进入内部裁剪逻辑，不会写到 LCD。软件仍需保证完整对齐读取范围位于可访问的 DDR 中，避免跨越未映射区域或访问保护边界。

示例：

```text
DMA_BASE   = 0x00001002
DMA_LENGTH = 3

aligned_base      = 0x00001000
leading_halfwords = 1
burst_count       = 1
```

DMA 读取 `0x1000` 到 `0x100f`，丢弃 `0x1000` 对应的第一个半字，只把地址 `0x1002`、`0x1004`、`0x1006` 的三个半字写到 LCD，随后排空 burst 尾部。

### 6.4 DMA 完成条件

`DMA_DONE` 的置位条件同时包含：

1. 请求的最后一个有效 16 位元素已经完成 8080 HOLD 阶段。
2. 最后一个 AXI burst 中被裁剪的尾部半字已经排空。
3. 8080 写状态机已经回到 IDLE。

最后一个可见像素写完后，`DMA_BUSY` 仍可能保持数个周期。软件应等待 `DMA_DONE=1` 或 `DMA_BUSY=0`，不要使用 `LCD_BUSY=0` 判断整笔传输结束。

### 6.5 DMA 错误

以下事件会置位 `DMA_ERROR`：

- AXI read response 为 `SLVERR` 或 `DECERR`。
- 内部数据流在预期长度之前出现物理 last，表示长度或流结束不一致。

错误行为：

- ERROR 为粘滞位。
- AXI 错误 beat 的数据仍会进入输出流。
- DMA 不执行硬件 abort，后续已请求数据继续处理。
- 正常排空后 DONE 和 ERROR 可以同时置位。
- AXI 永久不返回响应时没有超时机制，DMA_BUSY 可能一直保持。
- `CONTROL.LCD_RESET_N=0` 只复位面板，不会终止 DMA 状态机。

出现永久 AXI stall 时，当前寄存器接口无法恢复该笔 DMA，需要由更高层复位控制器复位相关逻辑。

## 7. 完成中断

LCD 中断通过 8 路级联控制器进入 CPU：

```text
DMA_DONE && LCD_IRQ_ENABLE[0]
  -> cascaded inputs[1]
  -> cascaded pending[1] && cascaded mask[1]
  -> CPU HWI7
  -> LoongArch CSR.ESTAT.IS[9]
```

### 7.1 级联控制器寄存器

级联控制器基址为 `0x1fe0a000`：

| 地址 | 名称 | 说明 |
| --- | --- | --- |
| `0x1fe0a000` | `PENDING` | 读已使能 pending，写 1 清除对应位 |
| `0x1fe0a004` | `MASK` | 8 位使能寄存器，复位值 0 |

LCD DMA_DONE 使用 bit1，即掩码 `0x00000002`。

级联 pending 的更新规则为：

```text
pending_next = (pending & ~clear) | inputs
```

输入与 W1C 同拍时输入优先。只清级联 `PENDING[1]` 时，如果 LCD 本地中断源仍为高，pending 会立即重新置位。

### 7.2 推荐使能顺序

1. 写 LCD `IRQ_ENABLE=0`，关闭本地中断源。
2. 向 LCD `STATUS` 写 `DMA_DONE | DMA_ERROR`，清除旧状态。
3. 向级联 `PENDING` 写 bit1，清除旧 pending。
4. 对级联 `MASK` 执行读改写，置位 bit1，保留触摸等其他中断使能。
5. 写 LCD `IRQ_ENABLE=1`。

级联控制器会在 MASK 为 0 时继续锁存输入事件。晚于 DONE 才打开 MASK 或 LCD IRQ_ENABLE，仍可能产生中断。

### 7.3 推荐中断处理顺序

1. 读取级联 `PENDING`，确认 bit1。
2. 读取 LCD `STATUS`，记录 DONE 和 ERROR。
3. 向 LCD `STATUS` 写 bit2，先撤销 LCD 电平中断源；如需同时清错误，可写 bit3。
4. 向级联 `PENDING` 写 bit1，清除锁存 pending。
5. 再次检查需要诊断的状态，然后退出处理程序。

当前 Linux 软件还需要提供 HWI7 分发和匹配该 APB 寄存器语义的级联 irqchip。现有 IOCSR/256-vector `extioiic` 驱动不能直接管理该控制器。

## 8. 软件操作流程

### 8.1 面板复位与初始化

1. 写 `CONTROL=0`，保持 `LCD_nrst=0`。
2. 按面板数据手册等待复位低电平时间。
3. 配置 `WRITE_TIMING`。
4. 写 `CONTROL=LCD_RESET_N`，释放面板复位。
5. 按面板数据手册等待复位恢复时间。
6. 使用 `CMD` 和 `DATA` 完成面板初始化。

控制器不内置 NT35510、ILI9488 或其他面板的初始化表。命令值、参数数量和延时均由软件负责。

### 8.2 启动一次轮询 DMA

1. 确认 `STATUS.DMA_BUSY=0`。
2. 通过 CMD/DATA 设置 LCD 窗口并发送 memory-write 命令。
3. 准备连续 16 位 framebuffer。
4. 对非一致性缓存执行写回，并执行 DMA/MMIO 写屏障。
5. 向 `STATUS` 写 `DMA_DONE | DMA_ERROR` 清除旧状态。
6. 写 `DMA_BASE`。
7. 写 `DMA_LENGTH`。
8. 写 `CONTROL=LCD_RESET_N | DMA_START`。
9. 轮询 `DMA_BUSY` 或 `DMA_DONE`。
10. 读取 `DMA_ERROR` 并处理结果。
11. W1C 清除 DONE 和 ERROR。

### 8.3 启动一次中断 DMA

1. 按“推荐使能顺序”配置 LCD 和级联中断。
2. 完成窗口命令和 framebuffer 准备。
3. 写 `DMA_BASE`、`DMA_LENGTH`。
4. 写 `CONTROL=LCD_RESET_N | DMA_START`。
5. 在 HWI7 级联处理程序中按“推荐中断处理顺序”完成清除。

### 8.4 连续刷新和二维区域

连续数据继续使用 `DMA_LENGTH`。源 framebuffer 中的矩形区域使用 `DMA_WIDTH`、
`DMA_HEIGHT` 和 `DMA_SRC_STRIDE`，LCD 窗口配置一次后启动一笔二维 DMA。DMA 会在
每行末尾跳过 stride 中的其余字节，CPU 无需逐行重新配置寄存器。

## 9. C 语言 MMIO 示例

以下代码展示寄存器语义。缓存维护、物理地址转换、锁和延时函数需要由实际运行环境提供。

```c
#include <stdbool.h>
#include <stdint.h>

#define LCD_BASE              0x1fe08000u
#define LCD_CMD               0x00u
#define LCD_DATA              0x04u
#define LCD_CONTROL           0x08u
#define LCD_STATUS            0x0cu
#define LCD_DMA_BASE          0x10u
#define LCD_DMA_LENGTH        0x14u
#define LCD_WRITE_TIMING      0x18u
#define LCD_IRQ_ENABLE        0x1cu
#define LCD_DMA_WIDTH         0x20u
#define LCD_DMA_HEIGHT        0x24u
#define LCD_DMA_SRC_STRIDE    0x28u

#define LCD_CTRL_RESET_N      (1u << 0)
#define LCD_CTRL_DMA_START    (1u << 1)
#define LCD_CTRL_DMA_2D       (1u << 2)

#define LCD_ST_LCD_BUSY       (1u << 0)
#define LCD_ST_DMA_BUSY       (1u << 1)
#define LCD_ST_DMA_DONE       (1u << 2)
#define LCD_ST_DMA_ERROR      (1u << 3)

#define LCD_IRQ_DMA_DONE      (1u << 0)

#define CASCADE_BASE          0x1fe0a000u
#define CASCADE_PENDING       0x00u
#define CASCADE_MASK          0x04u
#define CASCADE_LCD_DMA       (1u << 1)

static inline void mmio_write32(uint32_t address, uint32_t value)
{
    *(volatile uint32_t *)(uintptr_t)address = value;
}

static inline uint32_t mmio_read32(uint32_t address)
{
    return *(volatile uint32_t *)(uintptr_t)address;
}

static inline void lcd_write(uint32_t offset, uint32_t value)
{
    mmio_write32(LCD_BASE + offset, value);
}

static inline uint32_t lcd_read(uint32_t offset)
{
    return mmio_read32(LCD_BASE + offset);
}

static bool lcd_write_cmd(uint16_t command)
{
    if (lcd_read(LCD_STATUS) & LCD_ST_DMA_BUSY)
        return false;
    lcd_write(LCD_CMD, command);
    return true;
}

static bool lcd_write_data(uint16_t data)
{
    if (lcd_read(LCD_STATUS) & LCD_ST_DMA_BUSY)
        return false;
    lcd_write(LCD_DATA, data);
    return true;
}

static void lcd_set_write_timing(uint8_t setup,
                                 uint8_t wr_low,
                                 uint8_t hold)
{
    uint32_t value = (uint32_t)setup |
                     ((uint32_t)wr_low << 8) |
                     ((uint32_t)hold << 16);
    lcd_write(LCD_WRITE_TIMING, value);
}

static bool lcd_dma_parameters_valid(uint32_t dma_address,
                                     uint32_t halfword_count)
{
    uint64_t end = (uint64_t)dma_address +
                   (uint64_t)halfword_count * 2u;

    return (dma_address & 1u) == 0u &&
           halfword_count != 0u &&
           end <= (1ull << 32);
}

static int lcd_dma_start(uint32_t dma_address, uint32_t halfword_count)
{
    if (!lcd_dma_parameters_valid(dma_address, halfword_count))
        return -1;
    if (lcd_read(LCD_STATUS) & LCD_ST_DMA_BUSY)
        return -2;

    lcd_write(LCD_STATUS, LCD_ST_DMA_DONE | LCD_ST_DMA_ERROR);
    lcd_write(LCD_DMA_BASE, dma_address);
    lcd_write(LCD_DMA_LENGTH, halfword_count);

    /* 在此处完成 framebuffer cache writeback 和 DMA/MMIO 写屏障。 */

    lcd_write(LCD_CONTROL, LCD_CTRL_RESET_N | LCD_CTRL_DMA_START);
    return 0;
}
```

中断配置示例：

```c
static void lcd_irq_enable(void)
{
    uint32_t mask;

    lcd_write(LCD_IRQ_ENABLE, 0);
    lcd_write(LCD_STATUS, LCD_ST_DMA_DONE | LCD_ST_DMA_ERROR);
    mmio_write32(CASCADE_BASE + CASCADE_PENDING, CASCADE_LCD_DMA);

    mask = mmio_read32(CASCADE_BASE + CASCADE_MASK);
    mmio_write32(CASCADE_BASE + CASCADE_MASK, mask | CASCADE_LCD_DMA);
    lcd_write(LCD_IRQ_ENABLE, LCD_IRQ_DMA_DONE);
}

static bool lcd_irq_service(uint32_t *completed_status)
{
    uint32_t pending = mmio_read32(CASCADE_BASE + CASCADE_PENDING);
    uint32_t status;

    if ((pending & CASCADE_LCD_DMA) == 0)
        return false;

    status = lcd_read(LCD_STATUS);
    if (completed_status != 0)
        *completed_status = status;

    /* 先撤销 LCD 电平源，再清级联 pending。 */
    lcd_write(LCD_STATUS, status &
              (LCD_ST_DMA_DONE | LCD_ST_DMA_ERROR));
    mmio_write32(CASCADE_BASE + CASCADE_PENDING, CASCADE_LCD_DMA);

    return true;
}
```

## 10. Linux 驱动注意事项

- 使用 `ioremap()` 映射 `0x1fe08000` 寄存器窗口，通过 `readl()`/`writel()` 访问。
- DMA_BASE 需要写 DMA 总线地址。使用 DMA API 返回的 `dma_addr_t`，不要写用户虚拟地址或内核虚拟地址。
- 当前硬件 DMA 地址宽度为 32 位，驱动应设置 32 位 DMA mask。
- 非一致性平台应使用 `dma_alloc_coherent()`，或在流式映射上执行正确的 `dma_map_*()` / `dma_sync_*_for_device()`。
- 在 START 前保证 framebuffer 内容已经对 DMA 可见，并保证描述寄存器写入顺序。
- 访问 CMD、DATA、时序和 DMA 状态需要串行化，避免多个调用者同时操作控制器。
- HWI7 的 Linux 分发和 `0x1fe0a000` 级联 irqchip 需要先接入，才能使用中断模式。
- 当前没有正式的 Device Tree binding。驱动和 DTS 应在定义稳定 binding 后共同提交。

## 11. 并发、恢复与诊断

### 11.1 并发规则

- 同一时间只允许一笔 DMA。
- START 不排队。
- DMA 期间禁止 CMD/DATA。
- DMA 期间可以读取 STATUS。
- DMA 期间写 DMA_BASE、DMA_LENGTH 或二维参数只更新下一笔配置。
- DMA 期间写 WRITE_TIMING 或 CONTROL.RESET_N 会影响当前硬件行为，驱动应禁止这类操作。
- IRQ_ENABLE 可以随时修改；关闭它只撤销本地中断电平，不清 DONE 或级联 pending。

### 11.2 常见现象

| 现象 | 检查项 |
| --- | --- |
| 写 START 后 DMA_BUSY 仍为 0 | 检查地址偶数、长度非零、32 位地址不溢出、上一笔不忙；CPU看不到非法 START 的 PSLVERR |
| LCD 一直处于复位 | 检查 START 是否写成 `0x2`；正常运行应保持 CONTROL bit0 为 1 |
| DMA_DONE 有效但无 HWI7 | 检查 LCD IRQ_ENABLE bit0、级联 MASK bit1、CPU HWI7/ESTAT.IS[9] 分发 |
| 清级联 pending 后立即重现 | 先清 LCD STATUS.DONE，使本地电平源降低，再清级联 pending |
| DMA_DONE 和 DMA_ERROR 同时为 1 | 检查 AXI RRESP、DDR地址和内存映射；错误数据可能已经写到LCD |
| DMA_BUSY 长时间不清 | 检查 DDR/MIG状态和AXI响应；当前无超时和硬件abort |
| 颜色通道颠倒 | 控制器不转换RGB565格式，检查软件像素编码和面板配置 |
| 图像行错位 | 检查 `DMA_WIDTH`、`DMA_HEIGHT`、源 stride 和 LCD 窗口是否一致 |
| 首尾出现总线错误 | 检查16字节向下对齐和尾部多读范围是否均位于可访问DDR |

## 12. 板级引脚

目标约束文件为 `../../fpga/xc7a200t/soc_up.xdc`，IOSTANDARD 为 LVCMOS33。

| LCD 信号 | FPGA 管脚 |
| --- | --- |
| DATA0 | H9 |
| DATA1 | K17 |
| DATA2 | J20 |
| DATA3 | M17 |
| DATA4 | L17 |
| DATA5 | L18 |
| DATA6 | L15 |
| DATA7 | M15 |
| DATA8 | M16 |
| DATA9 | L14 |
| DATA10 | M14 |
| DATA11 | F22 |
| DATA12 | G22 |
| DATA13 | G21 |
| DATA14 | H24 |
| DATA15 | J16 |
| `LCD_nrst` | J25 |
| `LCD_csel` | H18 |
| `LCD_rd` | K8 |
| `LCD_rs` | K16 |
| `LCD_wr` | L8 |
| `LCD_lighton` | J15 |

LCD Touch 的 I2C 和中断属于独立外设，不属于本文描述的 LCD Ctrl 数据通路。

## 13. 实现与验证入口

主要源码：

```text
src/main/scala/chisel/LcdCtrl.scala
src/main/scala/chisel/LcdVideoDma.scala
IP/spinal/lcd/src/main/scala/cpustc/lcd/LcdVideoDma.scala
src/main/scala/chisel/CPUSTCSoc.scala
src/main/scala/verilog/soc_top.v
```

相关测试：

```text
src/test/scala/chisel/LcdCtrlSpec.scala
IP/spinal/lcd/src/test/scala/cpustc/lcd/LcdVideoDmaSpec.scala
```

常用验证命令：

```bash
# 生成 SpinalHDL DMA RTL
cd IP/spinal/lcd
sbt "runMain cpustc.lcd.generator.LcdVideoDmaGenerator"

# 运行 SpinalHDL DMA 仿真
sbt test

# 回到仓库根目录，编译、生成 SoC RTL并运行 Chisel测试
cd ../../..
sbt compile
sbt run
sbt "testOnly chisel.LcdCtrlSpec"
```

Vivado 工程引用的 DMA RTL 为：

```text
generated/xc7a200t/spinal/lcd/LcdVideoDma.v
```

该文件由生成命令产生，默认位于 Git 忽略目录中。打开 Vivado 工程前应确认文件已经生成。
