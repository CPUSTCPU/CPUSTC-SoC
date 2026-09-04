# CPUSTC-Soc

[中文](README.md) | [English](README.en.md)

CPUSTC-Soc 是面向 `xc7a200t` FPGA 板卡的 Chisel/Verilog/Vivado SoC 工程，集成 CPUSTCore、DDR3、AXI/APB 总线、存储、网络、显示、USB、摄像头和可选加速器。当前 Vivado 工程入口为：

```text
fpga/xc7a200t/CPUSTC-SoC/CPUSTC-SoC.xpr
```

## 项目概览

Chisel 负责 SoC 顶层集成和总线 glue，现有 Verilog/Vivado IP 负责板级接口与外设实现，CPUSTCore 通过 `IP/myCPU/core_top.sv` 接入 CPU AXI 边界。生成的 SystemVerilog 和独立 SpinalHDL IP 由 Vivado 工程使用。

CPU 核保持为独立的 CPUSTCore 工程。将 CPUSTCore 生成的 `CPU.sv` 放入 `IP/myCPU/`，再运行 `python3 scripts/gen_cpustcore_adapter.py` 生成同目录的 `core_top.sv`；Vivado 工程从该目录读取这两个文件。

```text
CPUSTCore
   |
   v
CPU AXI -> local AXI slave mux -> MMIO / CPU DDR path
                                      |
DMA masters (MAC, NAND, VGA, LCD, USB, SDIO, camera, TensorCore, 2D GPU)
                                      v
                              DDR AXI interconnect -> MIG -> DDR3
```

完整地址映射、时钟复位和外设连接见 [`docs/architecture.md`](docs/architecture.md)。

## 当前状态

- 当前目标板卡为 `xc7a200t`，顶层模块为 `soc_top`。
- 根 Chisel 工程和三个 SpinalHDL 子工程均可通过 SBT 构建。
- 生成 RTL 默认写入 Git 忽略的 `generated/xc7a200t/`。
- 已有 Vivado 2023.2 实现基线，当前源码和对应 bitstream 已完成目标板上板验证。
- 可选外设由 `SocFeatureConfig` 控制，顶层端口和 Vivado AXI interconnect 保持固定。

## 环境要求

- Linux、Git、Python 3。
- 与 `project/build.properties` 匹配的 SBT（当前为 1.12.10）和可用 JDK。
- Vivado 2023.2（生成 bitstream 时），目标器件 `xc7a200tfbg676-2`。
- 重新生成 LiteSDCard 时，还需要固定版本的 LiteSDCard、LiteX、Migen 和 verilog-axi checkout；版本与命令见 [`IP/LiteSDCard/UPSTREAM.md`](IP/LiteSDCard/UPSTREAM.md)。

## 快速开始

在仓库根目录执行：

```bash
# 编译 Chisel SoC
sbt compile

# 生成 generated/xc7a200t/CPUSTCSoc.sv
sbt run

# 生成 SoC 和三个 SpinalHDL IP
./sbt_run_all.sh

# 运行根工程测试
sbt test
```

更新 CPUSTCore RTL 后，先运行 `python3 scripts/gen_cpustcore_adapter.py`，再运行 `--check` 检查适配器；Vivado 本地 IP 或 Block Design 变化后，直接保存项目本地 `.srcs` 配置并用 Git 暂存。完整构建和 Vivado 流程见 [`docs/build.md`](docs/build.md)。

## 仓库结构

```text
src/main/scala/chisel/       Chisel SoC、总线和外设 wrapper
src/main/scala/verilog/      板级 Verilog wrapper
src/main/scala/main/         Chisel elaboration 入口
IP/                          复用 RTL、Chisel/SpinalHDL IP 和 Vivado 配置
generated/xc7a200t/          生成的 SystemVerilog（默认不提交）
fpga/xc7a200t/               唯一当前 Vivado 工程和约束
chip/xc7a200t/               legacy RTL 公共配置
scripts/                     生成、同步和校验脚本
docs/                        中文架构、构建和专题文档
LICENSES/                    许可证文本与组件映射
```

## 文档

- [`docs/README.md`](docs/README.md)：中文文档总目录。
- [`docs/architecture.md`](docs/architecture.md)：AXI/APB/DDR 架构、地址映射、中断、时钟复位和外设说明。
- [`docs/build.md`](docs/build.md)：依赖、生成器、测试、Vivado、bitstream 和上板验证流程。
- [`IP/spinal/README.md`](IP/spinal/README.md)：三个 SpinalHDL 子工程和生成输出。
- [`IP/LiteSDCard/UPSTREAM.md`](IP/LiteSDCard/UPSTREAM.md)：LiteSDCard 固定上游版本、生成参数和校验值。
- [`IP/TensorCore/README.md`](IP/TensorCore/README.md)：TensorCore 接口、寄存器和 DMA 编程顺序。
- [`LICENSES/README.md`](LICENSES/README.md)：多许可证边界和第三方组件映射。

## 许可证

本仓库采用多许可证结构：项目自有 Chisel、Scala、Verilog、脚本、测试、文档和集成文件按 MIT 映射；第三方代码、Fudian/XiangShan 内容、LiteSDCard、verilog-axi、OpenCores 和 AMD/Xilinx IP 继续适用各自上游许可证或工具条款。Linux、U-Boot、Buildroot 等外部仓库的许可证不因本仓库重新选择。依据为文件头、`.reuse/dep5`、[`LICENSES/README.md`](LICENSES/README.md) 和各组件随附声明。

## 致谢

- 感谢中国科学技术大学计算机学院的老师与助教对龙芯杯比赛的指导与支持。
- 感谢 [ChipLab 实验平台](https://gitee.com/loongson-edu/chiplab) 提供的参考设计与开发基础以及 [iFuSoC](https://github.com/iFuProcessor/iFuSoC) 等历年龙芯开源仓库项目，为本项目早期架构设计提供了参考。
- 感谢 [马子睿（@MAdrid1011）](https://github.com/MAdrid1011) 提供的 FP32 脉动阵列核心；中国科学技术大学历年参赛项目：[USTC-NSCSCC](https://github.com/MAdrid1011/USTC-NSCSCC)。
- 感谢本项目引用的开源硬件 IP：[OpenCores I2C](https://github.com/freecores/i2c)、[LiteSDCard](https://github.com/enjoy-digital/litesdcard)、[verilog-axi](https://github.com/alexforencich/verilog-axi)、[verilog-axis](https://github.com/alexforencich/verilog-axis) 和 [OpenXiangShan Fudian](https://github.com/OpenXiangShan/fudian)。
- 感谢 [Chisel](https://github.com/chipsalliance/chisel)、[SpinalHDL](https://github.com/SpinalHDL/SpinalHDL)、[LiteX](https://github.com/enjoy-digital/litex) 开源硬件开发生态；本项目使用了 SpinalHDL 提供的 OHCI、VideoDma 和 APB3 中断控制器等组件。
- 感谢 [Thibault Sottiaux（@tibo-openai）](https://github.com/tibo-openai)对本项目的间接支持。