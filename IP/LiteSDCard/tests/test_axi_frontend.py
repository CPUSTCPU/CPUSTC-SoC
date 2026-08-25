import pathlib
import sys
import unittest

from migen import Memory, Signal
from migen.sim import passive, run_simulation


GENERATOR_DIR = pathlib.Path(__file__).resolve().parents[1] / "generator"
sys.path.insert(0, str(GENERATOR_DIR))

from axi_dma_frontend import (  # noqa: E402
    AXIStreamDMAReaderFrontend,
    AXIStreamDMAWriterFrontend,
    DMA_DONE_ABORTED,
    DMA_DONE_BUSY,
    DMA_DONE_DECERR,
    DMA_DONE_SLVERR,
    DMA_DONE_SUCCESS,
    DMA_ERROR_RD_DECERR,
    DMA_ERROR_RD_SLVERR,
    DMA_ERROR_WR_DECERR,
    DMA_ERROR_WR_SLVERR,
    SDBlock2MemAXIDMA,
    SDMem2BlockAXIDMA,
)


class AXIDMAFrontendTest(unittest.TestCase):
    timeout_cycles = 100

    def configure(self, dma, *, base=0x1000, length=4):
        yield dma._base.storage.eq(base)
        yield dma._length.storage.eq(length)
        yield dma._loop.storage.eq(0)
        yield dma._enable.storage.eq(1)

    def wait_high(self, signal, description, limit=None):
        limit = limit or self.timeout_cycles
        for _ in range(limit):
            if (yield signal):
                return
            yield
        self.fail(f"timeout waiting for {description}")

    def wait_low(self, signal, description, limit=None):
        limit = limit or self.timeout_cycles
        for _ in range(limit):
            if not (yield signal):
                return
            yield
        self.fail(f"timeout waiting for {description}")

    def assert_done(self, dma, bit):
        status = yield dma._done.status
        self.assertEqual(status, 1 << bit)

    def assert_terminal_holds_until_reenabled(self, dma, bit):
        for _ in range(8):
            yield from self.assert_done(dma, bit)
            self.assertEqual((yield dma.flush), 1)
            yield

        yield dma._enable.storage.eq(1)
        for _ in range(4):
            yield
            if not ((yield dma._done.status) & (1 << bit)):
                return
        self.fail("terminal status did not clear after DMA was re-enabled")

    def make_fifo_simulatable(self, factory):
        """Work around fixed Migen's MemoryToArray handling of write-only ports."""
        original_get_port = Memory.get_port

        def get_port_with_dummy_read(memory, *args, **kwargs):
            port = original_get_port(memory, *args, **kwargs)
            if port.dat_r is None:
                port.dat_r = Signal(memory.width)
            return port

        Memory.get_port = get_port_with_dummy_read
        try:
            return factory()
        finally:
            Memory.get_port = original_get_port

    def start_writer(self, dut, *, length, first_data=0):
        yield from self.configure(dut, length=length)
        yield dut.desc_ready.eq(0)
        yield dut.data_tready.eq(0)
        yield dut.status_valid.eq(0)
        yield dut.sink.data.eq(first_data)
        yield dut.sink.valid_token_count.eq(4)
        yield dut.sink.valid.eq(1)
        yield from self.wait_high(dut.desc_valid, "writer descriptor valid")
        self.assertEqual((yield dut.desc_valid), 1)
        self.assertEqual((yield dut.desc_addr), 0x1000)
        self.assertEqual((yield dut.desc_len), length)
        yield dut.desc_ready.eq(1)
        yield
        yield dut.desc_ready.eq(0)
        yield from self.wait_high(dut.data_tvalid, "writer data ready")

    def start_reader(self, dut, *, length):
        yield from self.configure(dut, length=length)
        yield dut.desc_ready.eq(0)
        yield dut.source.ready.eq(1)
        yield dut.status_valid.eq(0)
        yield dut.data_tvalid.eq(0)
        yield from self.wait_high(dut.desc_valid, "reader descriptor valid")
        self.assertEqual((yield dut.desc_valid), 1)
        self.assertEqual((yield dut.desc_addr), 0x1000)
        self.assertEqual((yield dut.desc_len), length)
        yield dut.desc_ready.eq(1)
        yield
        yield dut.desc_ready.eq(0)
        yield from self.wait_high(dut.data_tready, "reader data ready")

    def test_writer_normal_512_byte_request(self):
        dut = AXIStreamDMAWriterFrontend()
        words = [0x40000000 | index for index in range(128)]
        last_indices = []

        def driver():
            yield from self.start_writer(dut, length=512, first_data=words[0])

            for index, word in enumerate(words):
                yield dut.sink.data.eq(word)
                yield dut.sink.valid.eq(1)
                yield dut.data_tready.eq(1)
                yield
                self.assertEqual((yield dut.data_tvalid), 1)
                self.assertEqual((yield dut.data_tdata), word)
                self.assertEqual((yield dut.data_tkeep), 0xF)
                if (yield dut.data_tlast):
                    last_indices.append(index)

            yield dut.sink.valid.eq(0)
            yield dut.data_tready.eq(0)
            yield
            self.assertEqual(last_indices, [127])
            self.assertEqual((yield dut._offset.status), 128)
            self.assertEqual((yield dut._done.status), 1 << DMA_DONE_BUSY)

            yield dut.status_len.eq(512)
            yield dut.status_error.eq(0)
            yield dut.status_valid.eq(1)
            yield
            yield dut.status_valid.eq(0)
            yield

            yield from self.assert_done(dut, DMA_DONE_SUCCESS)
            self.assertEqual((yield dut._offset.status), 128)

        run_simulation(dut, driver())

    def test_maximum_request_length_preserves_bit20(self):
        length = 1 << 20

        writer = AXIStreamDMAWriterFrontend()

        def writer_driver():
            yield from self.configure(writer, length=length)
            yield writer.desc_ready.eq(0)
            yield writer.sink.data.eq(0x44332211)
            yield writer.sink.valid_token_count.eq(4)
            yield writer.sink.valid.eq(1)
            yield from self.wait_high(writer.desc_valid, "1 MiB writer descriptor")
            self.assertEqual((yield writer.desc_len), length)

        run_simulation(writer, writer_driver())

        reader = AXIStreamDMAReaderFrontend()

        def reader_driver():
            yield from self.configure(reader, length=length)
            yield reader.desc_ready.eq(0)
            yield from self.wait_high(reader.desc_valid, "1 MiB reader descriptor")
            self.assertEqual((yield reader.desc_len), length)

        run_simulation(reader, reader_driver())

    def test_writer_abort_without_data_waits_for_status(self):
        dut = AXIStreamDMAWriterFrontend()

        def driver():
            yield from self.configure(dut, length=512)
            yield dut.desc_ready.eq(0)
            yield dut.data_tready.eq(0)
            yield dut.status_valid.eq(0)

            # A valid word starts the request, but is withdrawn before RUN.
            yield dut.sink.data.eq(0xDEADBEEF)
            yield dut.sink.valid_token_count.eq(4)
            yield dut.sink.valid.eq(1)
            yield from self.wait_high(dut.desc_valid, "writer abort descriptor valid")
            self.assertEqual((yield dut.desc_valid), 1)
            yield dut.desc_ready.eq(1)
            yield dut.sink.valid.eq(0)
            yield
            yield dut.desc_ready.eq(0)
            yield from self.wait_low(dut.desc_valid, "writer descriptor handshake")

            yield dut._enable.storage.eq(0)
            yield from self.wait_high(dut.data_tvalid, "writer abort terminator")
            self.assertEqual((yield dut.data_tdata), 0)
            self.assertEqual((yield dut.data_tkeep), 0)
            self.assertEqual((yield dut.data_tlast), 1)
            self.assertEqual((yield dut._done.status), 1 << DMA_DONE_BUSY)
            yield

            # No AXI-stream handshake means no terminal state yet.
            self.assertEqual((yield dut.data_tvalid), 1)
            self.assertEqual((yield dut._done.status), 1 << DMA_DONE_BUSY)
            yield dut.data_tready.eq(1)
            yield

            self.assertEqual((yield dut._offset.status), 0)
            self.assertEqual((yield dut._done.status), 1 << DMA_DONE_BUSY)
            yield
            self.assertEqual((yield dut._done.status), 1 << DMA_DONE_BUSY)

            yield dut.status_len.eq(0)
            yield dut.status_error.eq(0)
            yield dut.status_valid.eq(1)
            yield
            yield dut.status_valid.eq(0)
            yield
            yield from self.assert_done(dut, DMA_DONE_ABORTED)
            yield from self.assert_terminal_holds_until_reenabled(dut, DMA_DONE_ABORTED)

        run_simulation(dut, driver())

    def run_writer_error(self, error_code, expected_bit):
        dut = AXIStreamDMAWriterFrontend()

        def driver():
            yield from self.start_writer(dut, length=4, first_data=0x44332211)
            yield dut.data_tready.eq(1)
            yield
            self.assertEqual((yield dut.data_tlast), 1)
            yield dut.sink.valid.eq(0)
            yield dut.data_tready.eq(0)
            yield
            self.assertEqual((yield dut._done.status), 1 << DMA_DONE_BUSY)

            yield dut.status_len.eq(4)
            yield dut.status_error.eq(error_code)
            yield dut.status_valid.eq(1)
            yield
            yield dut.status_valid.eq(0)
            yield
            yield from self.assert_done(dut, expected_bit)

        run_simulation(dut, driver())

    def test_writer_maps_axi_errors(self):
        self.run_writer_error(DMA_ERROR_WR_SLVERR, DMA_DONE_SLVERR)
        self.run_writer_error(DMA_ERROR_WR_DECERR, DMA_DONE_DECERR)

    def test_writer_loop_keeps_a_queued_second_frame(self):
        dut = AXIStreamDMAWriterFrontend()
        first_word = 0x44332211
        second_word = 0x88776655

        def driver():
            yield from self.start_writer(dut, length=4, first_data=first_word)
            yield dut._loop.storage.eq(1)
            yield dut.data_tready.eq(1)
            yield
            self.assertEqual((yield dut.data_tdata), first_word)
            self.assertEqual((yield dut.data_tlast), 1)
            yield dut.data_tready.eq(0)
            yield dut.sink.valid.eq(0)
            yield

            self.assertEqual((yield dut._done.status), 1 << DMA_DONE_BUSY)
            self.assertEqual((yield dut.capture), 1)
            self.assertEqual((yield dut.flush), 0)

            # The next frame is already available while the first waits for B/status.
            yield dut.sink.data.eq(second_word)
            yield dut.sink.valid_token_count.eq(4)
            yield dut.sink.valid.eq(1)
            yield
            self.assertEqual((yield dut.capture), 1)
            self.assertEqual((yield dut.sink.ready), 0)

            yield dut.status_len.eq(4)
            yield dut.status_error.eq(0)
            yield dut.status_valid.eq(1)
            yield
            yield dut.status_valid.eq(0)
            yield

            yield from self.wait_high(dut.desc_valid, "second loop descriptor valid")
            self.assertEqual((yield dut.desc_len), 4)
            yield dut.desc_ready.eq(1)
            yield
            yield dut.desc_ready.eq(0)
            yield from self.wait_high(dut.data_tvalid, "second loop frame data")
            self.assertEqual((yield dut.data_tdata), second_word)
            self.assertEqual((yield dut.data_tlast), 1)

            yield dut.data_tready.eq(1)
            yield
            yield dut.data_tready.eq(0)
            yield dut.sink.valid.eq(0)
            yield dut._loop.storage.eq(0)
            yield

            yield dut.status_len.eq(4)
            yield dut.status_valid.eq(1)
            yield
            yield dut.status_valid.eq(0)
            yield
            yield from self.assert_done(dut, DMA_DONE_SUCCESS)

        run_simulation(dut, driver())

    def test_reader_status_before_last_data(self):
        dut = AXIStreamDMAReaderFrontend()
        words = [0x44332211, 0x88776655, 0xCCBBAA99, 0x00FFEEDD]
        observed = []

        def driver():
            yield from self.start_reader(dut, length=16)

            yield dut.status_error.eq(0)
            yield dut.status_valid.eq(1)
            yield
            yield dut.status_valid.eq(0)
            yield
            self.assertEqual((yield dut._done.status), 1 << DMA_DONE_BUSY)

            for index, word in enumerate(words):
                yield dut.data_tdata.eq(word)
                yield dut.data_tkeep.eq(0xF)
                yield dut.data_tlast.eq(index == len(words) - 1)
                yield dut.data_tvalid.eq(1)
                yield
                self.assertEqual((yield dut.source.valid), 1)
                self.assertEqual((yield dut.source.data), word)
                self.assertEqual((yield dut.source.first), index == 0)
                self.assertEqual((yield dut.source.last), index == len(words) - 1)
                observed.append((yield dut.source.data))

            yield dut.data_tvalid.eq(0)
            yield
            self.assertEqual(observed, words)
            yield from self.assert_done(dut, DMA_DONE_SUCCESS)
            self.assertEqual((yield dut._offset.status), 4)
            self.assertEqual((yield dut.flush), 0)

        run_simulation(dut, driver())

    def test_reader_abort_drains_without_sending_to_sd(self):
        dut = AXIStreamDMAReaderFrontend()

        def driver():
            yield from self.start_reader(dut, length=16)
            yield dut.source.ready.eq(0)
            yield dut._enable.storage.eq(0)

            for index in range(4):
                yield dut.data_tdata.eq(0xA0 + index)
                yield dut.data_tkeep.eq(0xF)
                yield dut.data_tlast.eq(index == 3)
                yield dut.data_tvalid.eq(1)
                yield
                self.assertEqual((yield dut.source.valid), 0)
                self.assertEqual((yield dut.data_tready), 1)
                self.assertEqual((yield dut._done.status), 1 << DMA_DONE_BUSY)

            yield dut.data_tvalid.eq(0)
            yield
            self.assertEqual((yield dut.data_tready), 1)
            self.assertEqual((yield dut._done.status), 1 << DMA_DONE_BUSY)
            self.assertEqual((yield dut._offset.status), 4)
            yield
            self.assertEqual((yield dut.data_tready), 1)
            self.assertEqual((yield dut._done.status), 1 << DMA_DONE_BUSY)

            yield dut.status_error.eq(0)
            yield dut.status_valid.eq(1)
            yield
            yield dut.status_valid.eq(0)
            yield
            yield from self.assert_done(dut, DMA_DONE_ABORTED)
            yield from self.assert_terminal_holds_until_reenabled(dut, DMA_DONE_ABORTED)

        run_simulation(dut, driver())

    def run_reader_error(self, error_code, expected_bit):
        dut = AXIStreamDMAReaderFrontend()

        def driver():
            yield from self.start_reader(dut, length=4)
            yield dut.data_tdata.eq(0x44332211)
            yield dut.data_tkeep.eq(0xF)
            yield dut.data_tlast.eq(1)
            yield dut.data_tvalid.eq(1)
            yield dut.status_error.eq(error_code)
            yield dut.status_valid.eq(1)
            yield
            yield dut.data_tvalid.eq(0)
            yield dut.status_valid.eq(0)
            yield
            yield from self.assert_done(dut, expected_bit)

        run_simulation(dut, driver())

    def test_reader_maps_axi_errors(self):
        self.run_reader_error(DMA_ERROR_RD_SLVERR, DMA_DONE_SLVERR)
        self.run_reader_error(DMA_ERROR_RD_DECERR, DMA_DONE_DECERR)

    def test_block2mem_preserves_byte_order(self):
        dut = self.make_fifo_simulatable(lambda: SDBlock2MemAXIDMA(fifo_depth=8))
        payload = [0x11, 0x22, 0x33, 0x44]

        def driver():
            yield from self.configure(dut.dma, length=4)
            yield dut.dma.desc_ready.eq(0)
            yield dut.dma.data_tready.eq(0)
            yield dut.dma.status_valid.eq(0)

            for byte in payload:
                yield dut.sink.data.eq(byte)
                yield dut.sink.valid.eq(1)
                yield from self.wait_high(dut.sink.ready, "block2mem byte ready")
                yield
            yield dut.sink.valid.eq(0)

            yield from self.wait_high(dut.dma.desc_valid, "block2mem descriptor valid")
            yield dut.dma.desc_ready.eq(1)
            yield
            yield dut.dma.desc_ready.eq(0)
            yield from self.wait_high(dut.dma.data_tvalid, "block2mem AXI-stream word")
            self.assertEqual((yield dut.dma.data_tdata), 0x44332211)
            self.assertEqual((yield dut.dma.data_tkeep), 0xF)
            self.assertEqual((yield dut.dma.data_tlast), 1)
            yield dut.dma.data_tready.eq(1)
            yield
            yield dut.dma.data_tready.eq(0)
            yield

            yield dut.dma.status_len.eq(4)
            yield dut.dma.status_error.eq(0)
            yield dut.dma.status_valid.eq(1)
            yield
            yield dut.dma.status_valid.eq(0)
            yield
            yield from self.assert_done(dut.dma, DMA_DONE_SUCCESS)

        run_simulation(dut, driver())

    def test_mem2block_preserves_tail_after_success(self):
        dut = self.make_fifo_simulatable(lambda: SDMem2BlockAXIDMA(fifo_depth=8))
        observed = []

        @passive
        def monitor():
            while True:
                if (yield dut.source.valid) and (yield dut.source.ready):
                    observed.append((yield dut.source.data))
                yield

        def driver():
            yield from self.configure(dut.dma, length=4)
            yield dut.dma.desc_ready.eq(0)
            yield dut.source.ready.eq(1)
            yield dut.dma.status_valid.eq(0)
            yield dut.dma.data_tvalid.eq(0)
            yield from self.wait_high(dut.dma.desc_valid, "mem2block descriptor valid")
            self.assertEqual((yield dut.dma.desc_valid), 1)
            yield dut.dma.desc_ready.eq(1)
            yield
            yield dut.dma.desc_ready.eq(0)

            yield dut.dma.data_tdata.eq(0x44332211)
            yield dut.dma.data_tkeep.eq(0xF)
            yield dut.dma.data_tlast.eq(1)
            yield dut.dma.data_tvalid.eq(1)
            yield dut.dma.status_error.eq(0)
            yield dut.dma.status_valid.eq(1)
            yield from self.wait_high(dut.dma.data_tready, "mem2block DMA word ready")
            yield
            yield dut.dma.data_tvalid.eq(0)
            yield dut.dma.status_valid.eq(0)
            yield

            yield from self.assert_done(dut.dma, DMA_DONE_SUCCESS)
            self.assertEqual((yield dut.dma.flush), 0)

            for _ in range(20):
                if len(observed) == 4:
                    break
                yield

            self.assertEqual(observed, [0x11, 0x22, 0x33, 0x44])

        run_simulation(dut, [driver(), monitor()])


if __name__ == "__main__":
    unittest.main()
