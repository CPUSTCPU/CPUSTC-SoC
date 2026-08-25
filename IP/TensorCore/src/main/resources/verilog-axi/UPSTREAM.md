# verilog-axi DMA sources

The files in this directory are copied without modification from:

- Repository: https://github.com/alexforencich/verilog-axi
- Commit: `516bd5dadc3365b7f9e225d2af8fe0b8d804fe53`
- Source files: `rtl/axi_dma_rd.v`, `rtl/axi_dma_wr.v`
- License: MIT, reproduced in `COPYING`

The Chisel integration treats these modules as external RTL. Keep local SoC
adaptation in Scala wrappers instead of editing the vendored Verilog.
