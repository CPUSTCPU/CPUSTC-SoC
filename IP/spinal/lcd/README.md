# SpinalHDL LCD DMA

该目录是独立的 LCD DMA SpinalHDL 工程。模块复用 SpinalHDL 1.14.1 的
`spinal.lib.graphic.VideoDma`，负责 32 位 AXI4 framebuffer 二维读取、100 MHz 到
33 MHz 跨时钟缓存及 32 位到 16 位流转换。输入参数为矩形左上角字节地址、每行
16 位元素数、高度和源 stride。每行独立按 16 字节对齐读取并在模块内丢弃首尾
padding。默认配置每次读取 4 个 32 位 beat，内部 `StreamFifoCC` 深度为 16 个
32 位 word，输出顺序为低 16 位后高 16 位。

生成 RTL：

```bash
sbt "runMain cpustc.lcd.generator.LcdVideoDmaGenerator"
```

运行仿真：

```bash
sbt test
```

输出文件：

```text
../../../generated/xc7a200t/spinal/lcd/LcdVideoDma.v
```

APB 寄存器和 8080 写时序位于 Chisel `LcdCtrl`，不在本模块中实现。
