# SpinalHDL USB

该目录是独立的 SpinalHDL USB IP 工程，与仓库根目录的 Chisel 工程分开构建。

## 目录

- `spinalhdl-lib`：OHCI、内部接口和软 PHY 由 SpinalHDL 1.14.1 依赖提供，本仓库不复制上游源码。
- `src/main/scala/cpustc/usb/utmi/`：存放本项目的 USB3500 UTMI+适配器和 OHCI UTMI 顶层。
- `src/main/scala/cpustc/usb/generator/`：存放本项目的板级 Verilog 生成入口。
- `../../../generated/xc7a200t/spinal/usb/`：约定的 Verilog 输出目录，默认不提交。

## 版本

- SpinalHDL：1.14.1
- Scala：2.13.16

在本目录运行 `sbt compile` 可独立编译该工程。添加生成入口后，应将输出路径显式设置为 `../../../generated/xc7a200t/spinal/usb/`。

运行 `sbt test` 可执行 USB 仿真。Root Hub 中断测试直接驱动 `Usb3500UtmiIo`；
UTMI 适配器测试只例化一个 `UsbHubLsFsToUtmi` DUT，并在 test scope 中连接 USB3500 行为模型和虚拟 D+/D- 总线。

## 上游 USB IP 结构

以下模块由 `spinalhdl-lib 1.14.1` 提供，通过 `spinal.lib.com.usb` 包直接引用。

### 公共逻辑

- `usb/Usb.scala`：提供按 USB 全速/低速时序换算的 `UsbTimer` 计时辅助逻辑。
- `usb/Misc.scala`：提供 USB 数据包和 Token 包的收发状态机，处理 PID、CRC、超时、bit stuffing 和 EOP。

### OHCI 主机控制器

- `usb/ohci/UsbOhci.scala`：OHCI 主机控制器核心。定义控制器及端口参数、OHCI 寄存器和 PID 常量，实现寄存器访问、DMA、端点调度、帧处理、Root Hub 与 PHY 控制。
- `usb/ohci/UsbOhciAxi4.scala`：将 OHCI 控制寄存器和 DMA 都封装成 AXI4 接口，并包含可配置的 Verilog 生成入口。
- `usb/ohci/UsbOhciAxi4Apb3.scala`：使用 APB3 访问控制寄存器、使用 AXI4 执行 DMA；支持独立控制、PHY、DMA 时钟域，并包含 Verilog 生成入口。
- `usb/ohci/UsbOhciWishbone.scala`：将 OHCI 控制寄存器和 DMA 封装成 Wishbone 接口，并包含 Verilog 生成入口。
- `usb/ohci/UsbOhciTilelink.scala`：通过 BMB bridge 将 OHCI 核心封装成 TileLink 控制和 DMA 接口。
- `usb/ohci/UsbOhciTilelinkFiber.scala`：面向 SpinalHDL Fiber/TileLink fabric 的 OHCI 集成层，声明总线节点、中断节点并提供默认 PHY 创建方法。
- `usb/ohci/UsbOhciGenerator.scala`：面向 `BmbInterconnectGenerator` 的 OHCI 集成组件，注册控制 slave、DMA master、地址映射和可选默认 PHY。

### PHY 与 Hub

- `usb/phy/UsbHubLsFs.scala`：定义 OHCI 与低速/全速 PHY 之间的控制 Bundle、端口状态信号及跨时钟域桥接 `CtrlCc`。
- `usb/phy/UsbHubPhy.scala`：实现主机侧低速/全速 PHY、D+/D- 三态接口、输入采样过滤、线路状态检测、收发和端口管理。
- `usb/phy/UsbDevicePhyNative.scala`：实现设备侧原生 D+/D- PHY，包括收发、复位、挂起、恢复、断开和上拉控制。
- `usb/phy/UsbPhyFsNative.scala`：旧版 PHY 实现草稿，当前文件内容全部被注释，不参与编译和生成。

### UDC 设备控制器

- `usb/udc/UsbDeviceCtrl.scala`：USB Device Controller 核心，定义端点参数、寄存器、BMB 控制接口、描述符处理、收发状态机、中断和 PHY 跨时钟域接口；文件末尾带有测试性生成入口。
- `usb/udc/UsbDeviceWithPhyWishbone.scala`：组合 UDC、Wishbone-to-BMB bridge 和设备 PHY，形成 Wishbone 控制接口的设备侧顶层，并包含 Verilog 生成入口。
- `usb/udc/UsbDeviceBmbGenerator.scala`：面向 `BmbInterconnectGenerator` 的 UDC 集成组件，负责控制寄存器地址映射、中断和默认设备 PHY 创建。

### 仿真支持

- `usb/sim/Agent.scala`：USB 低速/全速 PHY 仿真代理，模拟连接、线路电平、包收发、NRZI、bit stuffing、CRC 和错误注入。
- `usb/sim/DeviceAgent.scala`：建立在 PHY 仿真代理上的 USB 设备行为模型，解析 SOF、SETUP、IN、OUT 和 ACK，并通过 listener 与测试逻辑交互。

## 当前 SoC 集成状态

当前 SoC 只集成 OHCI Host，UDC 设备模式源码仅作参考，不纳入顶层。当前组成如下：

- `spinal.lib.com.usb.ohci.UsbOhci`：OHCI Host 核心。
- `spinal.lib.com.usb.phy.UsbHubLsFs`：OHCI 核心面向 PHY 的内部控制接口和跨时钟域连接。
- `cpustc.usb.utmi.UsbHubLsFsToUtmi`：本项目新增的 USB3500 UTMI+适配器，替代上游软 PHY。
- `cpustc.usb.utmi.UsbOhciAxi4Apb3Utmi`：本项目新增顶层，组合 APB3、AXI4 DMA、OHCI、CDC 和 UTMI+适配器。

固定板级参数的 OHCI Host 生成入口位于 `src/main/scala/cpustc/usb/generator/`，输出到 `../../../generated/xc7a200t/spinal/usb/`。生成后的 Verilog 顶层由 Chisel `ExtModule` 声明端口，SpinalHDL Scala 源码不加入 Chisel SBT 工程。

OHCI 控制寄存器经 32 位 APB3 接入 CPU 地址 `0x1fe02000`，AXI4 DMA master 接入 DDR interconnect `s4`，中断接入 HWI5。Chisel 顶层已连接 OHCI 的 APB3、AXI4 DMA、UTMI+、时钟和 active-high 复位；板级 `soc_top.v` 已连接 USB3500 DATA IOBUF、UTMI+控制信号和硬件复位，USB3500 PHY 通过 CLKOUT 向 FPGA 提供 60 MHz UTMI 时钟。USB 功能逻辑已经通过 `sbt compile`、`sbt run`、Vivado 实现和历史 ILA 上板捕获；`SocFeatureConfig.usbIla` 控制当前 27 路 UTMI ILA，且仅允许在 USB 开启时使用。

根工程 `SocFeatureConfig.usb` 控制 OHCI wrapper 是否进入 SoC 生成 RTL。关闭时 APB 页立即返回错误、DDR interconnect `s4` 保持 idle、HWI5 固定为 `0`，UTMI 输出进入安全状态；板级端口和 Vivado file set 保持不变，SpinalHDL 生成文件仍需存在。

HWI7 现接独立的 8 路 APB3 级联中断控制器，USB 仍独占 HWI5，USB 的地址和中断路径均未改变。级联控制器工程和寄存器语义见 `../interrupt/README.md`。

SoC 顶层的 `switch[7]` 已用于 CPU 退休 PC 调试，不再统计或显示 OHCI `interrupt`。USB 中断状态继续通过 ILA、Linux 中断计数和 OHCI 寄存器观测。


## USB3500 UTMI+实现

- `src/main/scala/cpustc/usb/utmi/UsbHubLsFsToUtmi.scala`：单端口 `UsbHubLsFs.Ctrl` 到 USB3500 UTMI+的适配器，处理字节流收发、LS/FS连接与断连过滤、端口复位、挂起和带低速 EOP 的恢复。
- `src/main/scala/cpustc/usb/utmi/UsbOhciAxi4Apb3Utmi.scala`：组合 APB3控制、OHCI核心、独立DMA时钟域、AXI4 DMA和UTMI适配器，并声明可选的 `ila_usb_utmi_eop` 黑盒。
- `src/main/scala/cpustc/usb/generator/UsbOhciAxi4Apb3UtmiGenerator.scala`：固定生成单端口、32-bit DMA、2048-byte FIFO的 `UsbOhciAxi4Apb3Utmi`，并导出 UTMI 调试信号；是否例化 ILA 由 SoC debug 参数决定。

当前实现固定 `DPPD/DMPD=1`、`overcurrent=False`，只支持OHCI LS/FS。USB3500硬件RESET和DATA IOBUF由板级顶层处理；`utmi_reset`是同步到60 MHz域的 active-high 内部逻辑复位。第一阶段保持 `SUSPENDN=1`，维持USB3500 CLKOUT运行，不进入PHY低功耗停钟状态。

USB-A VBUS 经 PTC 常供电，板级没有 VBUS 开关和过流检测。生产生成参数固定 `NPS=1`、`PSM=0`、`NOCP=1`、`OCPM=0`、`POTPGT=0`、`PPCM=0`；本地 OHCI APB wrapper 对 Descriptor A/B 的软件写入保持这些能力位，防止 Linux 初始化后重新宣告不存在的电源开关或过流报告。该描述修正只影响 Root Hub 能力和失败恢复语义，不改变首次枚举的数据事务。

发送最后字节握手后，适配器等待 `LINESTATE=SE0` 及其返回 J，再向 OHCI 产生 `txEop`；下一包还需等待 FS 10 拍或 LS 80 拍连续 J。`WAIT_SE0/WAIT_J` 各有 4096 拍看门狗，超时后发送路径粘滞锁定到 `utmi_reset`，故障只通过显式 ILA 观测。

PHY 输入保持直通；`DATA[7:0]`、`TXVALID`、`XCVRSEL`、`TERMSEL` 和 `OPMODE` 由 60 MHz 域 IOB 寄存器输出，每个 DATA 位另以独立、复位为高阻的 T 寄存器直连对应 `IOBUF.T`。TX 两包缓存吸收上游空拍，物理输出级在 `TXREADY=0` 时保持 DATA/TXVALID/T，连续握手时同拍装入下一字节，末字节握手后再进入 EOP 检测；PHY 输入时序和模块外部接口形态保持不变。

SETUP/OUT 使用两包事务前瞻：适配器将 token 和后续 DATA0/DATA1 分别完整保存在两个 bank，token 收齐后先向 OHCI 返回一次逻辑 `txEop`，并在 DATA 收齐前保持 UTMI 物理静默。两包均 Ready 后才依次物理发送 token、等待既有 FS/LS IPD、再发送 DATA；token 的物理 EOP不重复上报，DATA 的物理 EOP完成当前 OHCI 发送阶段。IN、SOF、握手包和独立 DATA 保持原物理 EOP语义；非法配对及配对阶段物理 EOP超时进入带原因的粘滞故障锁定，端口失效路径只产生一次终止 `txEop`。

全速根端口访问 Hub 下挂低速设备时，适配器在包首将 `ctrl.lowSpeed` 锁存到 TX bank，并要求配对 token 与 DATA 速度一致；物理发送和接收阶段选择 USB3500 `XCVRSEL=11`，由 PHY 发送 PRE、保持全速段 J/K 极性并以低速位周期传输目标包。模式只在 `TXVALID=0`、`RXACTIVE=0` 且物理 EOP/IPD 已完成时切换，IPD 按已发送事务选择 LS 80 拍或 FS 10 拍，随后可恢复 `XCVRSEL=01` 的全速流量。

`UsbClockSofDiagnostic` 仅观测 USB 数据通路：60 MHz 域产生 `/256` 心跳和 Gray 累计计数，33 MHz 域独立测量心跳间隔、Reset 时长、Reset 到首个 SOF、SOF 间隔和 SETUP 时间戳；独立 USB 调试候选启用时可由 `ila_usb_clock_sof_diag` 保存。`portResetActive` 先在源域寄存再进入 `BufferCC`，诊断逻辑不向 USB 控制或数据路径反馈；定向仿真的 3 个场景覆盖正常计数、60 MHz 心跳停止和前端复位基线校正。`usbIlaDebug` 会在 SoC RTL 中例化 `ila_usb_utmi_eop`，当前 Vivado 工程提供端口匹配的 ILA Core Container。

`Disabled/Enabled/Suspended` 共享持续 SE0 断连过滤；resume K 结束后依次保持 80 拍 SE0 和 40 拍 J，再恢复物理包发送。端口在包中途失效时停止 UTMI 驱动，同时完成 OHCI 共享 TX 的逻辑 EOP，避免 SOF 与断连重叠后帧调度停滞。`RXERROR` 即使未与 `RXVALID` 同拍也会形成错误 beat，使 OHCI 保留 bit-stuffing condition code。

已知依赖问题：SpinalHDL 1.14.1 `UsbOhci` 的 `PESC.set` 由 `port.overcurrent` 驱动，已使能端口断连后可能保留 `PES=1` 且缺少 `PESC`。定向仿真实际值为 `HcRhPortStatus=0x10302`；当前保留官方依赖行为，不在本地 wrapper 覆盖。

RX CDC 已改用本地 `UsbHubLsFsCtrlCc`：控制、发送和端口路径保持上游 `CtrlCc` 连接，接收侧以深度 8 的 `StreamFifoCC` 将 `START`、`DATA/ERROR` 和 `END` 放入同一有序事件流。`START` 与首个 `DATA/ERROR` 同拍时写入保留 payload 的 `StartData`；控制域先保持 FIFO 头部并拉高 `rx.active`，下一控制周期再输出该 payload 并 pop，保证接收 FSM 先观察到 active。控制域消费全部 DATA 后才撤销 `rx.active`，对应 `core_usb_host` 在 UTMI 域对齐并排空包尾的顺序语义；内部 sticky overflow/collision 状态用于仿真门禁，不增加顶层端口。完整 OHCI 回归中，规则 5 拍、START+DATA 同拍、2 拍短尾和 Trial 4 实测节奏各扫描 20 相位，80/80 均为 `CC=0/18字节/CBP=0/3次响应`、DMA 完整且 sticky 状态为 0。

`ctrl.lowSpeed` 在 33 MHz 源域先寄存一拍再进入 `BufferCC`，避免 OHCI 端点状态组合译码直接驱动同步器；项目 `CtrlCc` 的 LS 到 FS 切换测试扫描 20 个 33/60 MHz 相位，目的域首 beat 和物理 `LS+PRE`、FS 模式均与源事务一致。

## 第一阶段仿真

- `src/test/scala/cpustc/usb/sim/Usb3500UtmiAgent.scala`：保留直接 UTMI 驱动代理，并提供测试专用 USB3500 行为模型；模型处理 `TXVALID/TXREADY`、LS/FS 位周期、SYNC、NRZI、bit stuffing、EOP 和 D+/D- 到 UTMI 接收字节的转换，并将 `XCVRSEL=11` 建模为 PRE、4 个全速 J 位时间及使用全速段 J/K 极性的低速目标包。直接 RX 注入会独占并清理物理解码状态，避免 reset 前后的残留 `physicalRxError` 污染注入 beat。
- `src/test/scala/cpustc/usb/UsbHubLsFsToUtmiSpec.scala`：只例化 `UsbHubLsFsToUtmi`，复用 SpinalHDL 官方 `UsbLsFsPhyAbstractIoAgent` 与 `UsbDeviceAgent`，验证 LS/FS SETUP/OUT 两包前瞻、全速 Hub 下挂低速设备的 OUT/IN/主机 ACK 与全速恢复、普通包 EOP、设备 ACK、源 Stream 空洞、物理 EOP/IPD、速度冲突和配对错误/中止/超时、1026 字节边界，以及固定历史 seed 的直接 UTMI 接收注入。
- `src/test/scala/cpustc/usb/UsbHubLsFsToUtmiPortFsmSpec.scala`：验证 Suspended 断连、resume trailing EOP、Suspended reset/disable 和 Disabled 短 SE0 过滤。
- `src/test/scala/cpustc/usb/UsbOhciHccaFrameSpec.scala`、`UsbOhciRxCompletionSpec.scala`：验证断连与 SOF 重叠后的 HCCA 帧推进，以及三种 UTMI RX/RXERROR 完成路径。
- `src/test/scala/cpustc/usb/UsbOhciRhDisconnectSpec.scala`：记录官方 OHCI 1.14.1 的 PES/PESC 已知失败；断连检测和 CSC 正常，PES/PESC 断言保持失败，暂不修改或移除。
- `src/test/scala/cpustc/usb/UsbOhciRhscSpec.scala`：以独立控制、DMA、UTMI 时钟运行完整 OHCI 顶层，检查连接/断开的 CSC、RHSC、中断屏蔽、W1C 清除及事件不重复置位。
- `src/test/scala/cpustc/usb/UsbOhciFullSpeedRxCdcSpec.scala`：以完整 OHCI、APB 配置和 AXI DMA 内存模型执行全速 EP0 18 字节的 8/8/2 分包，扫描 33/60 MHz 的 20 个起始相位；覆盖规则 5 拍包尾、RXACTIVE 与首 PID RXVALID 同拍、2 拍短尾和 Trial 4 实测 `45/85/125/205/209/211` 节奏。
- `UsbHubLsFsToUtmiTiming` 的默认周期保持 60 MHz 板级时序；测试实例仅缩短状态机计数，生产生成入口继续使用默认值。

当前单模块框架已模拟 USB 包；完整 OHCI 测试已覆盖单个 EP0 IN TD 的 APB 配置、三包接收、AXI DMA 读写和 TD 退休。完整 Linux 枚举、通用 OHCI 调度与多端点流量仍未纳入该框架。
