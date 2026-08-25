# CPUSTC-Soc 文档

本目录保存 CPUSTC-Soc 的中文工程文档。
## 文档索引

| 文档 | 内容 | 适用场景 |
| --- | --- | --- |
| [`architecture.md`](architecture.md) | SoC 数据通路、AXI/APB 地址、中断、DDR 端口、时钟复位、功能 profile 和外设边界 | 修改 RTL、连接 Linux 驱动、核对寄存器或 CDC |
| [`build.md`](build.md) | 环境依赖、SBT/SpinalHDL 生成、适配器、Vivado 同步、综合实现和上板验证 | 从源码生成 RTL 或 bitstream |
| [`lcd_ctrl/README.md`](lcd_ctrl/README.md) | LCD 8080 控制器、DMA、寄存器和时序 | LCD 驱动或 DMA 调试 |
| [`vga_ctrl/README.md`](vga_ctrl/README.md) | VGA framebuffer、光标和 2D GPU 寄存器 | VGA/Xorg 或显示 RTL 开发 |
| [`../IP/CAMERA/README.md`](../IP/CAMERA/README.md) | OV7670 capture、SCCB、FIFO 和 AXI writer | 摄像头 RTL 或后续 V4L2 接入 |
| [`../IP/TensorCore/README.md`](../IP/TensorCore/README.md) | TensorCore AXI/APB 接口、寄存器和编程序列 | 加速器软件或 RTL 开发 |
| [`../IP/spinal/README.md`](../IP/spinal/README.md) | Interrupt、LCD DMA、USB 三个 SpinalHDL 子工程 | 生成独立 IP |
| [`../IP/LiteSDCard/UPSTREAM.md`](../IP/LiteSDCard/UPSTREAM.md) | LiteSDCard 固定上游版本、生成参数和许可证 | 重新生成或审查 SDIO |
