# SpinalHDL IP 工程

本目录集中保存当前 SoC 使用的独立 SpinalHDL SBT 工程。它们不加入仓库根目录的 Chisel 编译；各生成器输出 Verilog 到仓库根目录下 Git 忽略的 `generated/xc7a200t/spinal/`，Vivado 工程直接引用这些生成文件。

| 工程 | 生成入口 | Vivado 使用的输出 |
| --- | --- | --- |
| `interrupt/` | `cpustc.interrupt.generator.CascadedInterruptCtrlGenerator` | `generated/xc7a200t/spinal/interrupt/CascadedInterruptCtrl.v` |
| `lcd/` | `cpustc.lcd.generator.LcdVideoDmaGenerator` | `generated/xc7a200t/spinal/lcd/LcdVideoDma.v` |
| `usb/` | `cpustc.usb.generator.UsbOhciAxi4Apb3UtmiGenerator` | `generated/xc7a200t/spinal/usb/UsbOhciAxi4Apb3Utmi.v` |

从仓库根目录统一生成 Chisel SoC 和三个 SpinalHDL IP：

```bash
./sbt_run_all.sh
```

单独编译或测试时进入对应工程目录运行 `sbt compile` 或 `sbt test`。

接口、寄存器和仿真说明见各子目录的 `README.md`。生成 RTL 默认不提交，打开 `../../fpga/xc7a200t/CPUSTC-SoC/CPUSTC-SoC.xpr` 前应确认上述三个文件均存在。根工程的 `SocFeatureConfig` 只控制 Chisel SoC 是否例化 LCD DMA 和 USB wrapper；Vivado file set 保持固定，因此任何 profile 都继续准备全部 SpinalHDL 生成文件。
