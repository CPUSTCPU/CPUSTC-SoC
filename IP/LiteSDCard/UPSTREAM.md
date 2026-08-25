# LiteSDCard vendored RTL

This directory contains the generated LiteSDCard core used by the
SDIO comparison build. The SD PHY/core and CPU control interface are
from LiteSDCard; the memory frontend is generated locally for verilog-axi DMA.

## Fixed upstream revisions

- LiteSDCard: `227d61bc2b92ca56cac78a539b98e378468b1ba1`
- LiteX: `37b75bd468e228b2c8637727c68a1320eb5531a4`
- Migen: `e19524c963a8342952840983047557707fbe0b6a`
- verilog-axi: `516bd5dadc3365b7f9e225d2af8fe0b8d804fe53`

LiteSDCard commit `227d61bc` includes upstream pull request 58, which starts
capturing card read data while the command response is still completing.

## Generation

Set `LITESD_DEPS_ROOT` to a directory containing `migen`, `litex`, and
`litesdcard` Git checkouts at the revisions above, then run:

```bash
LITESD_DEPS_ROOT=/path/to/fixed/checkouts \
IP/LiteSDCard/scripts/generate_litesdcard_axi.sh --update

# Regenerate and require byte-identical checked-in output.
LITESD_DEPS_ROOT=/path/to/fixed/checkouts \
IP/LiteSDCard/scripts/generate_litesdcard_axi.sh --check
```

Generated configuration:

- Xilinx `xc7a200tfbg676-2` target device
- 100 MHz system/reference clock
- 4-bit SD data bus
- 32-bit Wishbone control slave
- 32-bit descriptor and AXI Stream memory frontend
- 21-bit byte length (supports the Linux driver's 1 MiB maximum request)
- 512-byte FIFO in each direction
- little-endian AXI byte lanes (`Converter(..., reverse=False)`)
- Xilinx 7-series SDR I/O lowering: `IDDR` input registers, `FDCE` output
  registers, and CMD/DAT `IOBUF` instances

The Chisel `LiteSdioController` connects these streams to the fixed verilog-axi
`axi_dma_wr` and `axi_dma_rd` resources with 16-beat AXI4 INCR bursts. The two
DMA engines own disjoint AXI channels: card-to-memory uses AW/W/B and
memory-to-card uses AR/R.

`ENABLE=0` does not reset an active external DMA. Card-to-memory emits an early
request-level `tlast` (or a zero-`tkeep` terminator) and waits for write status.
Memory-to-card stops forwarding bytes to the card, drains AXI Stream through
the descriptor's `tlast`, and waits for read status. This is required because
the fixed `axi_dma_wr` has an unused `abort` input and `axi_dma_rd` has no abort
input. Once an active transfer terminates with `ENABLE=0`, the terminal status
remains visible while disabled and is cleared when software next writes
`ENABLE=1`; software can therefore wait for bus mastering to stop before
unmapping the DMA buffer.

The DMA `DONE` CSR remains at the original address and uses these bits:

```text
bit 0  success
bit 1  AXI SLVERR
bit 2  AXI DECERR
bit 3  aborted
bit 4  length/frame mismatch or unknown DMA error
bit 5  busy
bit 6  invalid descriptor
```

The generation script normalizes LiteX's wall-clock timestamp. The checked-in
artifacts have these SHA-256 values:

```text
5111e97811de4188f0bd1a076f2eb96baca1dc26d0eada68325bd35de8844bab  rtl/litesdcard_core.v
b821d0e4f957a22c4466bfb1adb4b49e24659a3590ee1aef118d81cdceff6241  csr.csv
```

`csr.csv` is the exact register map emitted alongside the RTL. CPU software
reaches these offsets through the existing `0x1fe10000` SDIO window. The first
SD PHY register is therefore at `0x1fe10800`.

## License

LiteSDCard is distributed under the BSD 2-Clause license. The upstream license
text is preserved as `LICENSE`. The verilog-axi license is preserved with its
vendored TensorCore resources.
