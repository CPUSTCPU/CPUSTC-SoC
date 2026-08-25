# TensorCore AXI/APB IP

这是 CPUSTCPU 自有的 TensorCore AXI/APB 集成 IP，包含 1 x 4 FP32 计算阵列、批量 GEMM 控制器、NHWC 窗口输入、PReLU/池化后处理以及 SoC 总线封装。本文的寄存器、内存布局、构建和编程说明适用于当前版本。

This directory contains a batched GEMM controller around the original
output-stationary 1 x 4 TensorCore plus a CPUSTC-Soc-compatible bus wrapper.
The FP32 `TensorCore` and `TensorPE` datapaths are unchanged.

## Interfaces

- APB3 slave: 20-bit address, 32-bit data, same-cycle response.
- AXI4 master: 32-bit address/data, 3-bit ID, incrementing bursts of at most
  16 beats.
- Interrupt: active-high level until `IRQ_STATUS` is cleared.
- Element format: FP32 (`expWidth=8`, `precision=24`).

The APB address is a local SoC peripheral offset.  The upstream APB mux must
select this slave only for the TensorCore window (`0x200..0x2ff`); the slave
uses `paddr[13:0]` consistently with the other display peripherals.

The controller keeps the 1 x 4 compute core fixed. It accepts a variable GEMM
with `M,N <= 65535` and `K <= 256`, and automatically traverses all rows and
columns. Register ABI v4 can also build each A row directly from an NHWC
convolution window and apply optional PReLU or PReLU plus 2 x 2 ceil
max-pooling before C is written. The Linux userspace ioctl ABI remains v3;
the driver translates each RUN into the v4 register programming sequence.

## Memory layout

All matrices are FP32. A and C are row-major, and their strides are expressed
in bytes.

```text
A[i,k] = A_BASE + i * A_STRIDE + k * 4
C[i,j] = C_BASE + i * C_STRIDE + j * 4
```

B is packed in groups of four output columns so that one 128-bit logical word
feeds the four TensorPE lanes:

```text
group = j / 4
lane = j % 4
B_PACKED[((group * K + k) * 4) + lane] = B[k,j]
```

Software pads lanes beyond N in the final group with zero. The controller
loads up to eight groups (32 output columns, 32 KiB at `K=256`) into four local
B BRAM banks, then retains that tile while processing all M rows. Each A row is
loaded once into a 1 KiB local BRAM, all local 1 x 4 groups are computed, and
the completed C row segment is burst-written to DDR. N values above 32 repeat
this sequence with the next B tile.

In `INPUT_MODE=NHWC_WINDOW`, A is not materialized in DDR. For output position
`(oy, ox)`, the controller generates the virtual row in this order:

```text
A_window[(ky * KERNEL_WIDTH + kx) * INPUT_CHANNELS + c] =
    input[(oy * STRIDE_Y + ky - PAD_TOP),
          (ox * STRIDE_X + kx - PAD_LEFT), c]
```

Out-of-range positions are zero. With `APPEND_ONE=1`, the final A element is
FP32 `1.0`; software stores the channel bias in the corresponding final B row.
Each valid kernel row is one contiguous NHWC DMA descriptor, so the unmodified
DMA emits AXI INCR bursts without creating an im2col buffer in DDR.

Software precomputes the byte quantities that formerly required wide
multipliers in the controller:

```text
SOURCE_PIXEL_BYTES   = INPUT_CHANNELS * 4
SOURCE_ROW_BYTES     = INPUT_WIDTH * SOURCE_PIXEL_BYTES
SOURCE_EXTENT_BYTES  = INPUT_HEIGHT * SOURCE_ROW_BYTES
SOURCE_STEP_X_BYTES  = STRIDE_X * SOURCE_PIXEL_BYTES
SOURCE_STEP_Y_BYTES  = STRIDE_Y * SOURCE_ROW_BYTES
SOURCE_PAD_LEFT_BYTES = PAD_LEFT * SOURCE_PIXEL_BYTES
SOURCE_PAD_TOP_BYTES  = PAD_TOP * SOURCE_ROW_BYTES
```

During one RUN, RTL advances row, pixel, B-tile, C-row, and PReLU addresses
with registered 32-bit additions. `TensorWindowLoader` scans one kernel column
per cycle and reports leading-zero, contiguous-valid, and trailing-zero spans;
it does not divide or multiply at runtime. The eight-group B-tile byte count
is accumulated over at most eight cycles. This trades a small fixed latency
for substantially smaller address-generation logic while leaving the 1 x 4
FP32 datapath and the upstream AXI DMA modules unchanged.

PReLU parameters are N contiguous FP32 values at `PRELU_BASE`. In
`PRELU_POOL2X2_CEIL` mode, the controller visits the valid members of one 2 x 2
output block before moving to the next block. Only the current 32-column tile
is accumulated locally. The C row count is therefore
`ceil(OUTPUT_HEIGHT/2) * ceil(OUTPUT_WIDTH/2)`.

This first batched implementation intentionally has no ping-pong buffers and
does not overlap DMA with compute. The separate read and write DMA engines are
unmodified `verilog-axi` RTL pinned in
`src/main/resources/verilog-axi/UPSTREAM.md`.

## SoC mapping

In `CPUSTC-Soc`, this IP shares APB port 3 with VGA and 2D_GPU. The APB
register offsets below map to CPU addresses `0x1fe04200` through
`0x1fe042bc` (`CPU address = 0x1fe04000 + APB offset`). Its AXI4 DMA master
uses dedicated DDR interconnect port `s7` (2D_GPU remains on `s6`), and its interrupt is cascaded
interrupt input 3.

## Register map

| Offset | Name | Description |
| --- | --- | --- |
| `0x200` | STATUS | `[0] busy`, `[1] done`, `[2] error`; done/error are W1C |
| `0x204` | CONTROL | write `[0]=1` to start |
| `0x208` | A_BASE | FP32 A matrix physical byte address |
| `0x20c` | B_BASE | packed FP32 B matrix physical byte address |
| `0x210` | C_BASE | FP32 C matrix physical byte address |
| `0x214` | M | number of GEMM input rows, `1..65535`; with pooling this is the pre-pool spatial element count, not the final C row count |
| `0x218` | N | number of B/C columns, `1..65535` |
| `0x21c` | K | dot-product length, `1..256` |
| `0x220` | A_STRIDE | A row stride in bytes |
| `0x224` | C_STRIDE | C row stride in bytes |
| `0x228` | ROUND_MODE | RISC-V/LoongArch IEEE-754 rounding mode `0..4` |
| `0x22c` | IRQ_ENABLE | `[0]` completion/error interrupt enable |
| `0x230` | IRQ_STATUS | `[0]` pending; W1C |
| `0x234` | ERROR_CODE | configuration, DMA, or protocol error code |
| `0x238` | TOTAL_CYCLES | cycles from accepted start through completion |
| `0x23c` | B_READ_CYCLES | cycles spent issuing/loading B |
| `0x240` | A_READ_CYCLES | cycles spent issuing/loading A rows |
| `0x244` | COMPUTE_CYCLES | core reset, BRAM prime, issue, and drain cycles |
| `0x248` | C_WRITE_CYCLES | cycles spent issuing/writing C rows |
| `0x250` | IDENTIFICATION | ASCII `TCG4` (`0x54434734`) |
| `0x254` | CAPABILITIES0 | `[15:0] max K`, `[23:16] core columns`, `[31:24] B tile columns` |
| `0x258` | CAPABILITIES1 | B BRAM capacity in bytes, currently 32768 |
| `0x25c` | VERSION | register ABI version, currently 4 |
| `0x260` | MODE | `[1:0] input`: 0 MATRIX, 1 NHWC_WINDOW; `[3:2] post`: 0 NONE, 1 PRELU, 2 PRELU_POOL2X2_CEIL; `[4] APPEND_ONE` |
| `0x264` | INPUT_HEIGHT | NHWC input height |
| `0x268` | INPUT_WIDTH | NHWC input width |
| `0x26c` | INPUT_CHANNELS | NHWC input channel count |
| `0x270` | OUTPUT_HEIGHT | convolution/GEMM spatial input height for post-processing |
| `0x274` | OUTPUT_WIDTH | convolution/GEMM spatial input width for post-processing |
| `0x278` | KERNEL_HEIGHT | NHWC convolution kernel height |
| `0x27c` | KERNEL_WIDTH | NHWC convolution kernel width |
| `0x280` | STRIDE_Y | NHWC vertical stride |
| `0x284` | STRIDE_X | NHWC horizontal stride |
| `0x288` | PAD_TOP | NHWC top zero-padding count |
| `0x28c` | PAD_LEFT | NHWC left zero-padding count |
| `0x290` | PRELU_BASE | physical address of N contiguous FP32 alpha values |
| `0x294` | WINDOW_CYCLES | cycles spent constructing NHWC A rows |
| `0x298` | POST_CYCLES | cycles spent loading alpha and applying PReLU/pool |
| `0x29c` | CAPABILITIES2 | bits 0..3: NHWC, PReLU, ceil-pool, append-one support |
| `0x2a0` | SOURCE_EXTENT_BYTES | total valid source allocation size used for NHWC DMA range checks |
| `0x2a4` | SOURCE_ROW_BYTES | byte distance between adjacent NHWC rows; matrix-pool row step |
| `0x2a8` | SOURCE_PIXEL_BYTES | byte distance between adjacent NHWC pixels; matrix-pool element step |
| `0x2ac` | SOURCE_STEP_Y_BYTES | byte distance for one logical Y stride |
| `0x2b0` | SOURCE_STEP_X_BYTES | byte distance for one logical X stride |
| `0x2b4` | SOURCE_PAD_TOP_BYTES | top padding converted to a source byte offset |
| `0x2b8` | SOURCE_PAD_LEFT_BYTES | left padding converted to a source byte offset |
| `0x2bc` | RESULT_ROWS | physical C row count after optional ceil-pooling; equals M otherwise |

`ERROR_CODE=1` means invalid configuration. `0x100 | dma_error` and
`0x200 | dma_error` report read/write DMA errors. `0x300` and `0x400` report
read/write stream protocol errors.

## Programming sequence

1. Place row-major A and packed B in DDR. Program physical byte addresses, not
   virtual addresses, and flush dirty CPU cache lines because the DMA master
   is not cache coherent.
2. Write `A_BASE`, `B_BASE`, `C_BASE`, `M`, `N`, `K`, both byte strides, and an
   IEEE-754 `ROUND_MODE` from 0 through 4. Base addresses and strides must be
   four-byte aligned. MATRIX requires `A_STRIDE >= K*4`; NHWC_WINDOW ignores
   A_STRIDE. Every mode requires `C_STRIDE >= N*4`. Configuration writes while
   `busy` receive `PSLVERR`.
3. Write `MODE`. For `NHWC_WINDOW`, also write all input/output, kernel, stride,
   and padding registers; `M` must equal `OUTPUT_HEIGHT*OUTPUT_WIDTH`, and K
   must equal `KERNEL_HEIGHT*KERNEL_WIDTH*INPUT_CHANNELS + APPEND_ONE`. For a
   post mode, write aligned `PRELU_BASE`; pool mode also requires the output
   dimensions even when A uses MATRIX mode.
4. Write `RESULT_ROWS` and the seven `SOURCE_*_BYTES` registers. Linux computes
   them with 64-bit CPU arithmetic and rejects values that do not fit the
   32-bit register interface. Plain MATRIX mode sets `RESULT_ROWS=M` and may
   set the source quantities to zero. MATRIX plus pooling uses `A_STRIDE` as
   the pixel step and `OUTPUT_WIDTH*A_STRIDE` as the row/Y step.
5. Clear stale `STATUS.done/error` and `IRQ_STATUS.pending` with W1C writes,
   then optionally set `IRQ_ENABLE[0]`.
6. Write `CONTROL.start` (`bit 0`) as 1. Poll `STATUS.busy/done/error`, or wait
   for cascaded interrupt bit 3 when interrupts are enabled.
7. After successful completion, use a memory barrier and invalidate the CPU
   cache lines covering C before reading the result. Clear `STATUS.done` and
   `IRQ_STATUS.pending` before reusing the accelerator.

One completion interrupt covers the entire M x N result, including all 32-column
B tiles.

## Build

```bash
cd IP/TensorCore
sbt compile
sbt run
```

On Linux hosts where Verilator 5.020 PCH compilation fails, run the complete
test suite without the parallel-build PCH path:

```bash
env MAKEFLAGS=-e VM_PARALLEL_BUILDS=0 sbt test
```

The standalone generator writes
`target/generated/tensor_core_gemm_axi_apb_top.sv` for IP-level testing and
out-of-context synthesis; this build output is ignored by Git. The generated
file contains the pinned read/write DMA modules exactly once. CPUSTC-Soc
depends on this sbt project, instantiates `TensorCoreGemmAxiApbTop` directly,
and emits it inside `generated/xc7a200t/CPUSTCSoc.sv`; Vivado consumes that
single combined SoC output.

## Fudian source

以下 Fudian 最小源码固定于 OpenXiangShan/fudian 提交
`e1bd4695ca7beb36a5ce7357e9527ad9e95b9ec1`。XiangShan 官方 `LICENSE` 采用
木兰宽松许可证第 2 版；本仓库在 `LICENSES/MulanPSL-2.0.txt` 保留完整中英文
文本。`FMUL.scala` 中为适配 TensorPE 延迟而新增的管线对齐修改按 MIT 映射，
详细文件边界见 `LICENSES/README.md` 和 `.reuse/dep5`。

The minimal Fudian sources are pinned to OpenXiangShan/fudian commit
`e1bd4695ca7beb36a5ce7357e9527ad9e95b9ec1`. The local FMUL top adds two
stage-boundary registers so its latency matches the unchanged TensorPE
`mulDelay = 2` contract.
