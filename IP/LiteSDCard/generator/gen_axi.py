#!/usr/bin/env python3

#
# This file is part of the CPUSTCPU project.
#
# Copyright (c) 2026 CPUSTCPU contributors
# SPDX-License-Identifier: MIT

import argparse

from migen import *

from litex.build.generic_platform import Pins, Subsignal
from litex.build.io import CRG
from litex.build.xilinx.platform import XilinxPlatform
from litex.gen import LiteXModule
from litex.soc.integration.builder import Builder
from litex.soc.integration.soc import SoCMini
from litex.soc.interconnect import wishbone
from litex.soc.interconnect.csr_eventmanager import (
    EventManager,
    EventSourceLevel,
    EventSourcePulse,
)

from litesdcard.core import SDCore
from litesdcard.phy import SDPHY

from axi_dma_frontend import SDBlock2MemAXIDMA, SDMem2BlockAXIDMA


DMA_LEN_WIDTH = 21
XILINX_DEVICE = "xc7a200tfbg676-2"


_io = [
    ("clk", 0, Pins(1)),
    ("rst", 0, Pins(1)),
    ("irq", 0, Pins(1)),
    ("dma_block2mem_enabled", 0, Pins(1)),
    ("dma_mem2block_enabled", 0, Pins(1)),
    ("mem2block_payload_requested", 0, Pins(1)),
    (
        "sdcard",
        0,
        Subsignal("data", Pins(4)),
        Subsignal("cmd", Pins(1)),
        Subsignal("clk", Pins(1)),
        Subsignal("cd", Pins(1)),
        Subsignal("cmd_dir", Pins(1)),
        Subsignal("dat0_dir", Pins(1)),
        Subsignal("dat13_dir", Pins(1)),
    ),
    (
        "dma_wr",
        0,
        Subsignal("desc_addr", Pins(32)),
        Subsignal("desc_len", Pins(DMA_LEN_WIDTH)),
        Subsignal("desc_valid", Pins(1)),
        Subsignal("desc_ready", Pins(1)),
        Subsignal("status_len", Pins(DMA_LEN_WIDTH)),
        Subsignal("status_error", Pins(4)),
        Subsignal("status_valid", Pins(1)),
        Subsignal("data_tdata", Pins(32)),
        Subsignal("data_tkeep", Pins(4)),
        Subsignal("data_tvalid", Pins(1)),
        Subsignal("data_tready", Pins(1)),
        Subsignal("data_tlast", Pins(1)),
    ),
    (
        "dma_rd",
        0,
        Subsignal("desc_addr", Pins(32)),
        Subsignal("desc_len", Pins(DMA_LEN_WIDTH)),
        Subsignal("desc_valid", Pins(1)),
        Subsignal("desc_ready", Pins(1)),
        Subsignal("status_error", Pins(4)),
        Subsignal("status_valid", Pins(1)),
        Subsignal("data_tdata", Pins(32)),
        Subsignal("data_tkeep", Pins(4)),
        Subsignal("data_tvalid", Pins(1)),
        Subsignal("data_tready", Pins(1)),
        Subsignal("data_tlast", Pins(1)),
    ),
    (
        "debug",
        0,
        Subsignal("crc_error_next", Pins(1)),
        Subsignal("crc_error_latched", Pins(1)),
        Subsignal("crc_sample_enable", Pins(1)),
        Subsignal("sd_dat", Pins(4)),
        Subsignal("crc_count", Pins(5)),
        Subsignal("datar_state", Pins(3)),
        Subsignal("datar_count", Pins(10)),
        Subsignal("block_index", Pins(8)),
        Subsignal("local_crc_dat0", Pins(16)),
        Subsignal("local_crc_dat1", Pins(16)),
        Subsignal("local_crc_dat2", Pins(16)),
        Subsignal("local_crc_dat3", Pins(16)),
        Subsignal("frontend_offset_bytes", Pins(32)),
        Subsignal("frontend_state", Pins(3)),
        Subsignal("crc_expected", Pins(4)),
        Subsignal("crc_mismatch", Pins(4)),
        Subsignal("crc_correct", Pins(1)),
        Subsignal("data_done", Pins(1)),
        Subsignal("datar_valid", Pins(1)),
        Subsignal("sample_ce", Pins(1)),
        Subsignal("clocker_ce", Pins(1)),
        Subsignal("clocker_clk", Pins(1)),
        Subsignal("clocker_clk_en", Pins(1)),
        Subsignal("clocker_stop", Pins(1)),
        Subsignal("datar_reset", Pins(1)),
        Subsignal("sd_data_oe", Pins(1)),
        Subsignal("crc16_enable", Pins(1)),
        Subsignal("crc16_reset", Pins(1)),
        Subsignal("clock_divider", Pins(9)),
        Subsignal("data_count", Pins(16)),
        Subsignal("datar_source_valid", Pins(1)),
        Subsignal("datar_source_ready", Pins(1)),
        Subsignal("datar_source_data", Pins(8)),
        Subsignal("core_state", Pins(3)),
        Subsignal("dataw_state", Pins(4)),
        Subsignal("cmdw_state", Pins(2)),
        Subsignal("cmdr_state", Pins(3)),
        Subsignal("mem2block_fifo_level", Pins(10)),
        Subsignal("cmd_index", Pins(6)),
        Subsignal("data_type", Pins(2)),
        Subsignal("cmd_send", Pins(1)),
        Subsignal("cmd_event", Pins(4)),
        Subsignal("data_event", Pins(4)),
        Subsignal("sd_cmd_i", Pins(1)),
        Subsignal("sd_cmd_o", Pins(1)),
        Subsignal("sd_cmd_oe", Pins(1)),
        Subsignal("dataw_sink_valid", Pins(1)),
        Subsignal("dataw_sink_ready", Pins(1)),
    ),
]


class LiteSDCardAXI(LiteXModule):
    def __init__(self, soc, name="sdcard"):
        pads = soc.platform.request(name)

        self.phy = phy = SDPHY(
            pads,
            soc.platform.device,
            soc.sys_clk_freq,
            cmd_timeout=10e-1,
            data_timeout=10e-1,
        )
        self.core = core = SDCore(phy)

        self.block2mem = block2mem = SDBlock2MemAXIDMA(len_width=DMA_LEN_WIDTH)
        self.mem2block = mem2block = SDMem2BlockAXIDMA(len_width=DMA_LEN_WIDTH)
        self.comb += [
            core.source.connect(block2mem.sink),
            mem2block.source.connect(core.sink),
        ]

        self.ev = ev = EventManager()
        ev.card_detect = EventSourcePulse(description="SDCard has been ejected/inserted.")
        ev.block2mem_dma = EventSourcePulse(description="Block2Mem DMA terminated.")
        ev.mem2block_dma = EventSourcePulse(description="Mem2Block DMA terminated.")
        ev.data_done = EventSourceLevel(description="Transfer completed (cmd and data).")
        ev.cmd_done = EventSourceLevel(description="Command completed.")
        ev.finalize()
        self.comb += [
            ev.card_detect.trigger.eq(phy.card_detect_irq),
            ev.block2mem_dma.trigger.eq(block2mem.irq),
            ev.mem2block_dma.trigger.eq(mem2block.irq),
            ev.data_done.trigger.eq(core.data_event.fields.done),
            ev.cmd_done.trigger.eq(core.cmd_event.fields.done),
        ]


class LiteSDCardCore(SoCMini):
    def __init__(self, platform, clk_freq=int(100e6)):
        self.crg = CRG(platform.request("clk"), platform.request("rst"))
        SoCMini.__init__(self, platform, clk_freq=clk_freq, bus_data_width=32)
        self.add_constant("CPU_HAS_DMA_BUS")

        wb_ctrl = wishbone.Interface()
        self.bus.add_master(name="wb_ctrl", master=wb_ctrl)
        platform.add_extension(wb_ctrl.get_ios("wb_ctrl"))
        self.comb += wb_ctrl.connect_to_pads(platform.request("wb_ctrl"), mode="slave")

        sdcard = LiteSDCardAXI(self)
        self.add_module(name="sdcard", module=sdcard)

        dma_wr = platform.request("dma_wr")
        writer = sdcard.block2mem.dma
        self.comb += [
            dma_wr.desc_addr.eq(writer.desc_addr),
            dma_wr.desc_len.eq(writer.desc_len),
            dma_wr.desc_valid.eq(writer.desc_valid),
            writer.desc_ready.eq(dma_wr.desc_ready),
            writer.status_len.eq(dma_wr.status_len),
            writer.status_error.eq(dma_wr.status_error),
            writer.status_valid.eq(dma_wr.status_valid),
            dma_wr.data_tdata.eq(writer.data_tdata),
            dma_wr.data_tkeep.eq(writer.data_tkeep),
            dma_wr.data_tvalid.eq(writer.data_tvalid),
            writer.data_tready.eq(dma_wr.data_tready),
            dma_wr.data_tlast.eq(writer.data_tlast),
        ]

        dma_rd = platform.request("dma_rd")
        reader = sdcard.mem2block.dma
        self.comb += [
            dma_rd.desc_addr.eq(reader.desc_addr),
            dma_rd.desc_len.eq(reader.desc_len),
            dma_rd.desc_valid.eq(reader.desc_valid),
            reader.desc_ready.eq(dma_rd.desc_ready),
            reader.status_error.eq(dma_rd.status_error),
            reader.status_valid.eq(dma_rd.status_valid),
            reader.data_tdata.eq(dma_rd.data_tdata),
            reader.data_tkeep.eq(dma_rd.data_tkeep),
            reader.data_tvalid.eq(dma_rd.data_tvalid),
            dma_rd.data_tready.eq(reader.data_tready),
            reader.data_tlast.eq(dma_rd.data_tlast),
        ]

        self.comb += platform.request("irq").eq(sdcard.ev.irq)
        self.comb += [
            platform.request("dma_block2mem_enabled").eq(
                sdcard.block2mem.dma._enable.storage
            ),
            platform.request("dma_mem2block_enabled").eq(
                sdcard.mem2block.dma._enable.storage
            ),
            platform.request("mem2block_payload_requested").eq(
                sdcard.core.write_data_active
            ),
        ]

        debug = platform.request("debug")
        datar = sdcard.phy.datar
        writer = sdcard.block2mem.dma
        self.comb += [
            debug.crc_error_next.eq(datar.debug_crc_error_next),
            debug.crc_error_latched.eq(datar.debug_crc_error),
            debug.crc_sample_enable.eq(datar.debug_crc_sample_enable),
            debug.sd_dat.eq(datar.pads_in.data.i),
            debug.crc_count.eq(datar.debug_crc_count),
            debug.datar_state.eq(datar.debug_state),
            debug.datar_count.eq(datar.debug_count),
            debug.block_index.eq(sdcard.core.debug_data_read_count[:8]),
            debug.local_crc_dat0.eq(datar.crc16.crc[0]),
            debug.local_crc_dat1.eq(datar.crc16.crc[1]),
            debug.local_crc_dat2.eq(datar.crc16.crc[2]),
            debug.local_crc_dat3.eq(datar.crc16.crc[3]),
            debug.frontend_offset_bytes.eq(writer.debug_offset_bytes),
            debug.frontend_state.eq(writer.debug_state),
            debug.crc_expected.eq(datar.crc16.data_pads_out),
            debug.crc_mismatch.eq(datar.crc16.data_pads_out ^ datar.pads_in.data.i),
            debug.crc_correct.eq(datar.debug_crc_correct),
            debug.data_done.eq(datar.debug_data_done),
            debug.datar_valid.eq(datar.debug_datar_valid),
            debug.sample_ce.eq(sdcard.phy.sdpads.data_i_ce),
            debug.clocker_ce.eq(sdcard.phy.clocker.ce),
            debug.clocker_clk.eq(sdcard.phy.clocker.clk),
            debug.clocker_clk_en.eq(sdcard.phy.clocker.clk_en),
            debug.clocker_stop.eq(sdcard.phy.clocker.stop),
            debug.datar_reset.eq(datar.debug_datar_reset),
            debug.sd_data_oe.eq(sdcard.phy.sdpads.data.oe),
            debug.crc16_enable.eq(datar.crc16.enable),
            debug.crc16_reset.eq(datar.crc16.reset),
            debug.clock_divider.eq(sdcard.phy.clocker.divider.storage),
            debug.data_count.eq(datar.debug_data_count),
            debug.datar_source_valid.eq(datar.source.valid),
            debug.datar_source_ready.eq(datar.source.ready),
            debug.datar_source_data.eq(datar.source.data),
            debug.core_state.eq(sdcard.core.debug_state),
            debug.dataw_state.eq(sdcard.phy.dataw.debug_state),
            debug.cmdw_state.eq(sdcard.phy.cmdw.debug_state),
            debug.cmdr_state.eq(sdcard.phy.cmdr.debug_state),
            debug.mem2block_fifo_level.eq(sdcard.mem2block.fifo.level),
            debug.cmd_index.eq(sdcard.core.debug_cmd),
            debug.data_type.eq(sdcard.core.debug_data_type),
            debug.cmd_send.eq(sdcard.core.debug_cmd_send),
            debug.cmd_event.eq(sdcard.core.cmd_event.status),
            debug.data_event.eq(sdcard.core.data_event.status),
            debug.sd_cmd_i.eq(sdcard.phy.sdpads.cmd.i),
            debug.sd_cmd_o.eq(sdcard.phy.sdpads.cmd.o),
            debug.sd_cmd_oe.eq(sdcard.phy.sdpads.cmd.oe),
            debug.dataw_sink_valid.eq(sdcard.phy.dataw.sink.valid),
            debug.dataw_sink_ready.eq(sdcard.phy.dataw.sink.ready),
        ]


def main():
    parser = argparse.ArgumentParser(description="Generate the CPUSTC LiteSDCard AXI-stream core.")
    parser.add_argument("--clk-freq", default="100e6", help="Input clock frequency.")
    parser.add_argument("--output-dir", default="build", help="Generator output directory.")
    parser.add_argument("--build-name", default="litesdcard_core", help="Generated top-level name.")
    args = parser.parse_args()

    platform = XilinxPlatform(device=XILINX_DEVICE, io=_io)
    core = LiteSDCardCore(platform, clk_freq=int(float(args.clk_freq)))
    builder = Builder(core, output_dir=args.output_dir)
    builder.build(build_name=args.build_name, run=False)


if __name__ == "__main__":
    main()
