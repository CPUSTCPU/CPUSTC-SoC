# 许可证映射

本仓库采用多许可证结构。每个文件首先适用其自身保留的许可证头；没有单独许可
证头时，再按下表中的组件映射判断。本目录收录许可证文本和映射说明，但不代表
整个仓库统一适用某一种许可证。

| 组件 | 许可证 | 依据 |
| --- | --- | --- |
| `IP/AMBA`、`IP/APB_DEV`、`IP/CONFREG`、`IP/DMA`、`IP/MAC`、`IP/SPI` 以及 `chip/xc7a200t/config.h` 中的龙芯旧版 RTL | BSD-3-Clause | 各源文件保留的许可证头；规范文本见 `BSD-3-Clause.txt` |
| `IP/IIC` 中的 OpenCores I2C RTL | LicenseRef-OpenCores-I2C | 各源文件保留的版权和授权声明；整理后的文本见 `LicenseRef-OpenCores-I2C.txt` |
| `.reuse/dep5` 明确列出的 LiteSDCard 上游生成物 | BSD-2-Clause | `IP/LiteSDCard/LICENSE` 和 `UPSTREAM.md`；该目录的 CPUSTCPU 生成器、脚本、测试和补丁按 MIT 映射 |
| `IP/CAMERA/LICENSE-verilog-axis` 指明的 `axis_async_fifo` 文件 | MIT | 上游许可证和源文件头 |
| `IP/TensorCore/src/main/resources/verilog-axi` 中的 verilog-axi DMA 文件 | MIT | 该目录中的 `COPYING` 和 `UPSTREAM.md` |
| `IP/TensorCore/src/main/scala/fudian/RoundingUnit.scala`、`package.scala` 和 `utils/*` 中的 Fudian 上游文件 | MulanPSL-2.0 | XiangShan 官方 `LICENSE` 明确采用木兰宽松许可证第 2 版；本仓库保留固定 Fudian 提交来源，规范文本见 `MulanPSL-2.0.txt` |
| `IP/TensorCore/src/main/scala/fudian/FMUL.scala` 中的 Fudian 上游内容及 CPUSTCPU 管线对齐修改 | MulanPSL-2.0 AND MIT | 上游内容沿用木兰许可证；新增的管线对齐修改按 MIT 映射 |
| `IP/xilinx_ip` 中的 AMD/Xilinx IP 配置和 Vivado 工程元数据 | 适用 AMD/Xilinx 工具及相应 IP 条款；本项目不另行授权 | 生成文件中的版权声明和 `LicenseRef-AMD-Xilinx-Tools.txt` |

项目自有的 Chisel、Scala、Verilog、脚本、测试、文档和集成文件统一采用 MIT，
映射见 `.reuse/dep5`，规范文本见 `MIT.txt`。

`IP/TensorCore/src/main/scala/fudian` 的最小源码来自 OpenXiangShan/fudian 的
固定提交 `e1bd4695ca7beb36a5ce7357e9527ad9e95b9ec1`。XiangShan
官方仓库的 `LICENSE` 明确采用木兰宽松许可证第 2 版，本仓库在
`LICENSES/MulanPSL-2.0.txt` 保留完整中英文许可证文本。`FMUL.scala` 的本地
管线对齐修改另按 MIT 标识；其余自有 SoC 代码仍统一按 MIT 映射。

通过 SBT 或 Vivado 下载的依赖不会因本仓库而被重新许可，应以依赖各自发布包
中的许可证和声明为准。
