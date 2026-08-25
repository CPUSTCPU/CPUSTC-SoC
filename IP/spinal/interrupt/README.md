# SpinalHDL 级联中断控制器

该目录是独立的 SpinalHDL 中断 IP 工程，与仓库根目录的 Chisel 工程分开构建。实现直接复用 SpinalHDL 1.14.1 的 `Apb3InterruptCtrl(8)`，只增加 masked pending 输出和汇总中断输出，不在模块内部处理异步输入。

## 接口与寄存器

- APB3：4 位地址、32 位数据，连接 CPU 地址 `0x1fe0a000`。
- `inputs[7:0]`：8 路 active-high 中断输入。
- `pendings[7:0]`：mask 后的 pending 状态。
- `interrupt`：`pendings.orR`，连接 CPU HWI7。
- `0x00 PENDING`：读已使能 pending，写一清除。
- `0x04 MASK`：中断使能读写寄存器，复位值为 `0`。

pending 更新规则为 `(pending & ~clear) | inputs`，所以输入与清除同拍时输入优先。持续为高的电平输入无法通过 W1C 保持清除状态，源设备必须先解除中断条件。

当前 SoC 输入分配：`inputs[0]` 为同步到 `aClk` 的 LCD Touch interrupt，`inputs[1]` 为 LCD DMA_DONE，`inputs[2]` 为 VGA framebuffer interrupt，`inputs[3]` 为 TensorCore completion/error，`inputs[4]` 为 SDIO controller interrupt；`inputs[7:5]` 预留并固定为 `0`。

## 构建与生成

- `sbt compile`：编译独立工程。
- `sbt test`：使用 Verilator 执行 APB3 寄存器与中断语义仿真。
- `sbt "runMain cpustc.interrupt.generator.CascadedInterruptCtrlGenerator"`：生成板级 Verilog。

生成文件位于 `../../../generated/xc7a200t/spinal/interrupt/CascadedInterruptCtrl.v`，默认不提交。Vivado 工程引用该生成文件，Chisel `ExtModule` 仅声明并整理它的物理端口。
