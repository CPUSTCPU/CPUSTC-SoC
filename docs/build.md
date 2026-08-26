# 构建、生成与验证

本文说明从 checkout 到生成 RTL、同步 Vivado 配置和验证 bitstream 的流程。命令默认在仓库根目录执行。

## 1. 环境

最低需要：

- Linux、Git、Python 3；
- JDK、SBT 1.12.10（版本记录在 `project/build.properties`）；
- 能访问 Maven Central 的网络或已准备好的 SBT 依赖缓存；
- 需要 bitstream 时使用 Vivado 2023.2，目标器件为 `xc7a200tfbg676-2`。

根 `build.sbt` 使用 Scala 2.13.16、Chisel 6.7.0、ChiselTest 6.0.0 和 ScalaTest 3.2.17。`IP/TensorCore` 是根工程的源码依赖；`IP/spinal/interrupt`、`IP/spinal/lcd` 和 `IP/spinal/usb` 是独立 SBT 工程。

## 2. 根 Chisel 工程

```bash
# 编译 Chisel SoC
sbt compile

# 生成当前 full profile 的 SystemVerilog
sbt run

# 运行根工程测试
sbt test
```

默认入口 `src/main/scala/main/main.scala` 使用 `SocFeatureConfig.full`，输出为：

```text
generated/xc7a200t/CPUSTCSoc.sv
```

生成器会移除 CIRCT 在输出末尾附加的资源清单并检查输出格式。修改 Chisel 后重新运行 `sbt compile` 和 `sbt run`。

### 2.1 生成 SpinalHDL IP

从根目录一次生成 SoC 和三个独立 IP：

```bash
./sbt_run_all.sh
```

输出为：

```text
generated/xc7a200t/spinal/interrupt/CascadedInterruptCtrl.v
generated/xc7a200t/spinal/lcd/LcdVideoDma.v
generated/xc7a200t/spinal/usb/UsbOhciAxi4Apb3Utmi.v
```

单独验证某个 IP：

```bash
(cd IP/spinal/interrupt && sbt compile && sbt test)
(cd IP/spinal/lcd && sbt compile && sbt test)
(cd IP/spinal/usb && sbt compile && sbt test)
```

### 2.2 CPUSTCore 适配器

CPUSTCore 保持为独立工程。将 CPUSTCore 生成的单文件 `CPU.sv` 放入 `IP/myCPU/`，再根据当前 Core 端口生成 `IP/myCPU/core_top.sv`：

```bash
python3 scripts/gen_cpustcore_adapter.py
python3 scripts/gen_cpustcore_adapter.py --check
```

生成器检查时钟、中断、AXI 必需端口和方向；默认 `io_retirePc` 连接到 `debug0_wb_pc`，不存在的可选调试输出绑零。`--check` 只检查一致性，不修改文件。

## 3. 生成 LiteSDCard

LiteSDCard 不是通过根 SBT 生成。按照 [`../IP/LiteSDCard/UPSTREAM.md`](../IP/LiteSDCard/UPSTREAM.md) 准备固定版本的 LiteSDCard、LiteX、Migen 和 verilog-axi checkout，然后设置依赖目录：

```bash
export LITESD_DEPS_ROOT=/path/to/fixed/checkouts
IP/LiteSDCard/scripts/generate_litesdcard_axi.sh --check
```

`--update` 更新受控生成物，`--check` 检查输出是否一致。LiteSDCard 上游 BSD-2-Clause、verilog-axi MIT 以及本地生成器的 MIT 映射不能混写。

## 4. Vivado 工程

### 4.1 维护项目本地 IP/Block Design

当前工程的 BD、standalone IP 配置和 BD 子 IP `.xci` 都以项目本地
`fpga/xc7a200t/CPUSTC-SoC/CPUSTC-SoC.srcs/` 为唯一维护源，并由 Git 跟踪。
不要再把这些文件复制到 `IP/xilinx_ip/`；后者只保留不属于本地
`sources_1` 文件集的其他 vendor IP 配置。

修改 Vivado 工程并保存后，直接从仓库根目录暂存项目文件：

```bash
git add fpga/xc7a200t/CPUSTC-SoC/CPUSTC-SoC.xpr \
  fpga/xc7a200t/CPUSTC-SoC/CPUSTC-SoC.srcs
```

`.gitignore` 只放行当前工程需要维护的主 `.bd`、standalone `.xci` 和 BD 子 IP
`.xci`；`.gen`、`.bda` 等生成物不会被加入 Git。不要把这些文件复制到
`IP/xilinx_ip/`。

需要重建生成物时，在 Vivado 中打开工程并对 BD/IP 执行 `Generate Output Products`：

```text
fpga/xc7a200t/CPUSTC-SoC/CPUSTC-SoC.xpr
```

生成的 HDL、约束和 OOC 运行输入位于 `.gen` 下，仍不纳入 Git。

### 4.2 打开、综合和实现

确认三个 SpinalHDL 输出和根 Chisel 输出都存在后打开工程：

```bash
vivado fpga/xc7a200t/CPUSTC-SoC/CPUSTC-SoC.xpr
```

Vivado top module 为 `soc_top`，目标器件为 `xc7a200tfbg676-2`。在 Vivado 中依次检查 IP status、synthesis、implementation、timing summary 和 bitstream。

板级约束位于：

```text
fpga/xc7a200t/soc_up.xdc
fpga/xc7a200t/sdio_timing.xdc
```

修改时钟、端口或 profile 后，要同时核对 `soc_top.v`、XPR file set、XDC 和 [`architecture.md`](architecture.md)。

## 5. 自动化检查

以下检查不需要 Vivado：

```bash
python3 scripts/test_gen_axi_interconnect_chisel.py
python3 scripts/test_gen_cpustcore_adapter.py
bash -n sbt_run_all.sh IP/LiteSDCard/scripts/generate_litesdcard_axi.sh
sbt test
```

若修改了特定 IP，还应在对应目录执行其 `sbt test`。生成器测试通过只证明脚本和接口断言成立，不证明 Vivado 综合、时序或板级行为。

## 6. 验证

最新保存的 Vivado 2023.2 routed 报告（2026-08-17）针对 `soc_top`、器件 `7a200t-fbg676` 显示：WNS/WHS 为 `0.360/0.052 ns`，TNS/THS 均为 `0 ns`，setup/hold failing endpoints 均为 0。当前源码和对应 bitstream 已完成目标板上板验证。
