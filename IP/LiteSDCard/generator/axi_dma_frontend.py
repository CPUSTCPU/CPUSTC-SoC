#
# This file is part of the CPUSTCPU project.
#
# Copyright (c) 2026 CPUSTCPU contributors
# SPDX-License-Identifier: MIT

from migen import *

from litex.gen import LiteXModule
from litex.soc.interconnect import stream
from litex.soc.interconnect.csr import CSRStatus, CSRStorage


DMA_DONE_SUCCESS = 0
DMA_DONE_SLVERR = 1
DMA_DONE_DECERR = 2
DMA_DONE_ABORTED = 3
DMA_DONE_MISMATCH = 4
DMA_DONE_BUSY = 5
DMA_DONE_INVALID = 6
DMA_DONE_WIDTH = 7

DMA_ERROR_RD_SLVERR = 4
DMA_ERROR_RD_DECERR = 5
DMA_ERROR_WR_SLVERR = 6
DMA_ERROR_WR_DECERR = 7


def _add_dma_csrs(dma):
    # Keep this declaration order compatible with LiteSDCard's Wishbone DMA CSRs.
    dma._base = CSRStorage(64, description="DMA base address.")
    dma._length = CSRStorage(32, description="DMA transfer length in bytes.")
    dma._enable = CSRStorage(1, description="DMA enable.")
    dma._done = CSRStatus(DMA_DONE_WIDTH, description="DMA completion and error status.")
    dma._loop = CSRStorage(1, description="DMA loop enable.")
    dma._offset = CSRStatus(32, description="DMA current transfer offset in words.")


def _keep_mask(valid_bytes):
    masks = Array(Constant(v, 4) for v in (0x0, 0x1, 0x3, 0x7, 0xf, 0xf, 0xf, 0xf))
    return masks[valid_bytes]


class AXIStreamDMAWriterFrontend(LiteXModule):
    """Control a stream-to-AXI DMA used for SD-card reads."""

    def __init__(self, len_width=21):
        self.sink = stream.Endpoint([("data", 32), ("valid_token_count", 3)])

        self.desc_addr = Signal(32)
        self.desc_len = Signal(len_width)
        self.desc_valid = Signal()
        self.desc_ready = Signal()

        self.status_len = Signal(len_width)
        self.status_error = Signal(4)
        self.status_valid = Signal()

        self.data_tdata = Signal(32)
        self.data_tkeep = Signal(4)
        self.data_tvalid = Signal()
        self.data_tready = Signal()
        self.data_tlast = Signal()

        self.capture = Signal()
        self.flush = Signal()
        self.irq = Signal()

        _add_dma_csrs(self)

        base = self._base.storage
        length = self._length.storage
        enable = self._enable.storage
        loop = self._loop.storage

        offset_bytes = Signal(32)
        self.debug_offset_bytes = offset_bytes
        self.debug_state = Signal(3)
        done_bits = Signal(DMA_DONE_WIDTH)
        abort_latched = Signal()
        mismatch_latched = Signal()
        terminal_pulse = Signal()

        invalid = Signal()
        token_count = self.sink.valid_token_count
        next_offset = Signal(33)
        aborting = Signal()
        normal_last = Signal()
        stream_fire = Signal()

        self.comb += [
            invalid.eq(
                (length == 0)
                | (base[0:2] != 0)
                | (base[32:64] != 0)
                | (length[0:2] != 0)
                | (length[len_width:32] != 0)
            ),
            next_offset.eq(offset_bytes + token_count),
            aborting.eq(abort_latched | ~enable),
            normal_last.eq(next_offset >= length),
            stream_fire.eq(self.data_tvalid & self.data_tready),

            self.desc_addr.eq(base[0:32]),
            self.desc_len.eq(length[0:len_width]),
            self.desc_valid.eq(0),

            self.data_tdata.eq(Mux(self.sink.valid, self.sink.data, 0)),
            self.data_tkeep.eq(Mux(self.sink.valid, _keep_mask(token_count), 0)),
            self.data_tvalid.eq(0),
            self.data_tlast.eq(0),
            self.sink.ready.eq(0),

            self.capture.eq(0),
            self.flush.eq(0),
            terminal_pulse.eq(0),
            self.irq.eq(terminal_pulse),
            self._offset.status.eq(offset_bytes >> 2),
        ]

        fsm = FSM(reset_state="IDLE")
        self.fsm = fsm
        busy = Signal()
        busy_bits = Cat(
            Constant(0, DMA_DONE_BUSY),
            busy,
            Constant(0, DMA_DONE_WIDTH - DMA_DONE_BUSY - 1),
        )
        self.comb += [
            busy.eq(
                fsm.ongoing("ISSUE")
                | fsm.ongoing("RUN")
                | fsm.ongoing("WAIT_STATUS")
            ),
            self._done.status.eq(done_bits | busy_bits),
        ]

        fsm.act(
            "IDLE",
            self.debug_state.eq(0),
            self.capture.eq(enable & ~invalid),
            If(
                ~enable,
                self.flush.eq(1),
                NextValue(offset_bytes, 0),
                NextValue(done_bits, 0),
                NextValue(abort_latched, 0),
                NextValue(mismatch_latched, 0),
            ).Elif(
                invalid,
                self.flush.eq(1),
                NextValue(done_bits, 1 << DMA_DONE_INVALID),
                terminal_pulse.eq(1),
                NextState("DONE"),
            ).Elif(
                self.sink.valid,
                NextValue(offset_bytes, 0),
                NextValue(done_bits, 0),
                NextValue(abort_latched, 0),
                NextValue(mismatch_latched, 0),
                NextState("ISSUE"),
            ),
        )

        fsm.act(
            "ISSUE",
            self.debug_state.eq(1),
            self.capture.eq(1),
            self.desc_valid.eq(enable),
            If(
                ~enable,
                self.flush.eq(1),
                NextValue(done_bits, 1 << DMA_DONE_ABORTED),
                terminal_pulse.eq(1),
                NextState("DONE"),
            ).Elif(self.desc_ready, NextState("RUN")),
        )

        fsm.act(
            "RUN",
            self.debug_state.eq(2),
            self.capture.eq(~aborting),
            self.data_tvalid.eq(self.sink.valid | aborting),
            self.data_tlast.eq(normal_last | aborting),
            self.sink.ready.eq(self.data_tready & self.sink.valid),
            If(~enable, NextValue(abort_latched, 1)),
            If(
                stream_fire,
                If(
                    self.sink.valid,
                    NextValue(offset_bytes, next_offset),
                    If(
                        (token_count != 4) | (next_offset > length),
                        NextValue(mismatch_latched, 1),
                    ),
                ),
                If(normal_last | aborting, NextState("WAIT_STATUS")),
            ),
        )

        writer_slverr = self.status_error == DMA_ERROR_WR_SLVERR
        writer_decerr = self.status_error == DMA_ERROR_WR_DECERR
        writer_other_error = (self.status_error != 0) & ~writer_slverr & ~writer_decerr
        writer_mismatch = mismatch_latched | writer_other_error | (self.status_len != offset_bytes)
        writer_fault = writer_slverr | writer_decerr | aborting | writer_mismatch
        writer_result = Cat(
            ~writer_fault,
            writer_slverr,
            writer_decerr,
            aborting,
            writer_mismatch,
            Constant(0, 2),
        )

        fsm.act(
            "WAIT_STATUS",
            self.debug_state.eq(3),
            self.capture.eq(loop & enable & ~aborting),
            If(~enable, NextValue(abort_latched, 1)),
            If(
                self.status_valid,
                If(
                    loop & enable & ~writer_fault,
                    NextValue(offset_bytes, 0),
                    NextValue(abort_latched, 0),
                    NextValue(mismatch_latched, 0),
                    NextState("IDLE"),
                ).Else(
                    NextValue(done_bits, writer_result),
                    terminal_pulse.eq(1),
                    NextState("DONE"),
                ),
            ),
        )

        fsm.act(
            "DONE",
            self.debug_state.eq(4),
            self.flush.eq(1),
            If(~enable, NextState("RESET_WAIT")),
        )

        fsm.act(
            "RESET_WAIT",
            self.debug_state.eq(5),
            self.flush.eq(1),
            If(
                enable,
                NextValue(done_bits, 0),
                NextValue(offset_bytes, 0),
                NextValue(abort_latched, 0),
                NextValue(mismatch_latched, 0),
                NextState("IDLE"),
            ),
        )


class AXIStreamDMAReaderFrontend(LiteXModule):
    """Control an AXI-to-stream DMA used for SD-card writes."""

    def __init__(self, len_width=21):
        self.source = stream.Endpoint([("data", 32)])

        self.desc_addr = Signal(32)
        self.desc_len = Signal(len_width)
        self.desc_valid = Signal()
        self.desc_ready = Signal()

        self.status_error = Signal(4)
        self.status_valid = Signal()

        self.data_tdata = Signal(32)
        self.data_tkeep = Signal(4)
        self.data_tvalid = Signal()
        self.data_tready = Signal()
        self.data_tlast = Signal()

        self.flush = Signal()
        self.irq = Signal()

        _add_dma_csrs(self)

        base = self._base.storage
        length = self._length.storage
        enable = self._enable.storage
        loop = self._loop.storage

        offset_bytes = Signal(32)
        done_bits = Signal(DMA_DONE_WIDTH)
        abort_latched = Signal()
        mismatch_latched = Signal()
        status_seen = Signal()
        status_error_latched = Signal(4)
        last_seen = Signal()
        terminal_failed = Signal()
        terminal_pulse = Signal()

        invalid = Signal()
        aborting = Signal()
        data_fire = Signal()
        last_fire = Signal()
        next_offset = Signal(33)
        status_available = Signal()
        last_available = Signal()
        completion_error = Signal(4)
        completion_mismatch = Signal()

        self.comb += [
            invalid.eq(
                (length == 0)
                | (base[0:2] != 0)
                | (base[32:64] != 0)
                | (length[0:2] != 0)
                | (length[len_width:32] != 0)
            ),
            aborting.eq(abort_latched | ~enable),
            data_fire.eq(self.data_tvalid & self.data_tready),
            last_fire.eq(data_fire & self.data_tlast),
            next_offset.eq(offset_bytes + 4),
            status_available.eq(status_seen | self.status_valid),
            last_available.eq(last_seen | last_fire),
            completion_error.eq(Mux(self.status_valid, self.status_error, status_error_latched)),
            completion_mismatch.eq(
                mismatch_latched
                | (data_fire & (self.data_tkeep != 0xf))
                | (last_fire & (next_offset != length))
                | (data_fire & ~self.data_tlast & (next_offset >= length))
            ),

            self.desc_addr.eq(base[0:32]),
            self.desc_len.eq(length[0:len_width]),
            self.desc_valid.eq(0),

            self.source.data.eq(self.data_tdata),
            self.source.first.eq(offset_bytes == 0),
            self.source.last.eq(self.data_tlast),
            self.source.valid.eq(0),
            self.data_tready.eq(0),

            self.flush.eq(0),
            terminal_pulse.eq(0),
            self.irq.eq(terminal_pulse),
            self._offset.status.eq(offset_bytes >> 2),
        ]

        fsm = FSM(reset_state="IDLE")
        self.fsm = fsm
        busy = Signal()
        busy_bits = Cat(
            Constant(0, DMA_DONE_BUSY),
            busy,
            Constant(0, DMA_DONE_WIDTH - DMA_DONE_BUSY - 1),
        )
        self.comb += [
            busy.eq(fsm.ongoing("ISSUE") | fsm.ongoing("RUN")),
            self._done.status.eq(done_bits | busy_bits),
        ]

        reader_slverr = completion_error == DMA_ERROR_RD_SLVERR
        reader_decerr = completion_error == DMA_ERROR_RD_DECERR
        reader_other_error = (completion_error != 0) & ~reader_slverr & ~reader_decerr
        reader_mismatch = completion_mismatch | reader_other_error
        reader_fault = reader_slverr | reader_decerr | aborting | reader_mismatch
        reader_result = Cat(
            ~reader_fault,
            reader_slverr,
            reader_decerr,
            aborting,
            reader_mismatch,
            Constant(0, 2),
        )

        fsm.act(
            "IDLE",
            If(
                ~enable,
                self.flush.eq(1),
                NextValue(offset_bytes, 0),
                NextValue(done_bits, 0),
                NextValue(abort_latched, 0),
                NextValue(mismatch_latched, 0),
                NextValue(status_seen, 0),
                NextValue(status_error_latched, 0),
                NextValue(last_seen, 0),
                NextValue(terminal_failed, 0),
            ).Elif(
                invalid,
                self.flush.eq(1),
                NextValue(done_bits, 1 << DMA_DONE_INVALID),
                NextValue(terminal_failed, 1),
                terminal_pulse.eq(1),
                NextState("DONE"),
            ).Else(
                NextValue(offset_bytes, 0),
                NextValue(done_bits, 0),
                NextValue(abort_latched, 0),
                NextValue(mismatch_latched, 0),
                NextValue(status_seen, 0),
                NextValue(status_error_latched, 0),
                NextValue(last_seen, 0),
                NextValue(terminal_failed, 0),
                NextState("ISSUE"),
            ),
        )

        fsm.act(
            "ISSUE",
            self.desc_valid.eq(enable),
            If(
                ~enable,
                self.flush.eq(1),
                NextValue(done_bits, 1 << DMA_DONE_ABORTED),
                NextValue(terminal_failed, 1),
                terminal_pulse.eq(1),
                NextState("DONE"),
            ).Elif(self.desc_ready, NextState("RUN")),
        )

        fsm.act(
            "RUN",
            self.flush.eq(aborting),
            self.source.valid.eq(self.data_tvalid & ~aborting),
            self.data_tready.eq(Mux(aborting, 1, self.source.ready)),
            If(~enable, NextValue(abort_latched, 1)),
            If(
                self.status_valid,
                NextValue(status_seen, 1),
                NextValue(status_error_latched, self.status_error),
            ),
            If(
                data_fire,
                NextValue(offset_bytes, next_offset),
                If(self.data_tkeep != 0xf, NextValue(mismatch_latched, 1)),
                If(
                    self.data_tlast,
                    NextValue(last_seen, 1),
                    If(next_offset != length, NextValue(mismatch_latched, 1)),
                ).Elif(next_offset >= length, NextValue(mismatch_latched, 1)),
            ),
            If(
                status_available & last_available,
                If(
                    loop & enable & ~reader_fault,
                    NextValue(offset_bytes, 0),
                    NextValue(abort_latched, 0),
                    NextValue(mismatch_latched, 0),
                    NextValue(status_seen, 0),
                    NextValue(status_error_latched, 0),
                    NextValue(last_seen, 0),
                    NextValue(terminal_failed, 0),
                    NextState("RESTART"),
                ).Else(
                    NextValue(done_bits, reader_result),
                    NextValue(terminal_failed, reader_fault),
                    terminal_pulse.eq(1),
                    NextState("DONE"),
                ),
            ),
        )

        fsm.act("RESTART", NextState("IDLE"))

        fsm.act(
            "DONE",
            self.flush.eq(terminal_failed | ~enable),
            If(~enable, NextState("RESET_WAIT")),
        )

        fsm.act(
            "RESET_WAIT",
            self.flush.eq(1),
            If(
                enable,
                NextValue(done_bits, 0),
                NextValue(offset_bytes, 0),
                NextValue(abort_latched, 0),
                NextValue(mismatch_latched, 0),
                NextValue(status_seen, 0),
                NextValue(status_error_latched, 0),
                NextValue(last_seen, 0),
                NextValue(terminal_failed, 0),
                NextState("IDLE"),
            ),
        )


class SDBlock2MemAXIDMA(LiteXModule):
    """Buffer SD bytes and present request-framed AXI stream write data."""

    def __init__(self, fifo_depth=512, len_width=21):
        self.sink = stream.Endpoint([("data", 8)])
        self.irq = Signal()

        fifo = ResetInserter()(stream.SyncFIFO([("data", 8)], fifo_depth, buffered=True))
        converter = ResetInserter()(
            stream.Converter(8, 32, reverse=False, report_valid_token_count=True)
        )
        self.submodules.fifo = fifo
        self.submodules.converter = converter
        self.dma = AXIStreamDMAWriterFrontend(len_width=len_width)

        self.comb += [
            If(
                self.dma.capture,
                self.sink.connect(fifo.sink),
            ).Else(self.sink.ready.eq(1)),
            fifo.source.connect(converter.sink),
            converter.source.connect(self.dma.sink),
            fifo.reset.eq(self.dma.flush),
            converter.reset.eq(self.dma.flush),
            self.irq.eq(self.dma.irq),
        ]


class SDMem2BlockAXIDMA(LiteXModule):
    """Buffer AXI stream read data and serialize it into SD bytes."""

    def __init__(self, fifo_depth=512, len_width=21):
        self.source = stream.Endpoint([("data", 8)])
        self.irq = Signal()

        converter = ResetInserter()(stream.Converter(32, 8, reverse=False))
        fifo = ResetInserter()(stream.SyncFIFO([("data", 8)], fifo_depth, buffered=True))
        self.submodules.converter = converter
        self.submodules.fifo = fifo
        self.dma = AXIStreamDMAReaderFrontend(len_width=len_width)

        self.comb += [
            self.dma.source.connect(converter.sink),
            converter.source.connect(fifo.sink),
            fifo.source.connect(self.source),
            converter.reset.eq(self.dma.flush),
            fifo.reset.eq(self.dma.flush),
            self.irq.eq(self.dma.irq),
        ]
