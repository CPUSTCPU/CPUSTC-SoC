#!/usr/bin/env python3
"""Tests for gen_cpustcore_adapter.py."""

import importlib.util
import sys
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("gen_cpustcore_adapter.py")
SPEC = importlib.util.spec_from_file_location("gen_cpustcore_adapter", SCRIPT)
GENERATOR = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = GENERATOR
SPEC.loader.exec_module(GENERATOR)


class AdapterGeneratorTest(unittest.TestCase):
    @staticmethod
    def cpu_source(extra_ports=()):
        ports = []
        for name, direction, _ in GENERATOR.REQUIRED_CONNECTIONS:
            ports.append(f"  {direction} {name}")
        ports.extend(f"  output {name}" for name in extra_ports)
        return "module CPU(\n" + ",\n".join(ports) + "\n);\nendmodule\n"

    def test_parses_grouped_ansi_ports(self):
        ports = GENERATOR.parse_cpu_ports(
            "module CPU(input clock, reset, output [3:0] value); endmodule"
        )
        self.assertEqual(ports["clock"].direction, "input")
        self.assertEqual(ports["reset"].direction, "input")
        self.assertEqual(ports["value"].direction, "output")

    def test_wrapper_keeps_compat_debug_interface_without_cpu_debug(self):
        ports = GENERATOR.parse_cpu_ports(self.cpu_source())
        output = GENERATOR.render_adapter(ports)
        self.assertIn("debug0_wb_pc", output)
        self.assertIn("assign debug0_wb_pc       = 32'b0;", output)
        self.assertIn("assign retire_valid       = {1'b0, 1'b0, 1'b0};", output)
        self.assertIn("assign retire_pc2         = 32'b0;", output)

    def test_present_debug_ports_are_connected(self):
        ports = GENERATOR.parse_cpu_ports(
            self.cpu_source(("io_cmt_0_valid", "io_cmt_0_pc", "io_cmt_0_data",
                             "io_cmt_0_inst", "io_cmt_0_rd_valid", "io_cmt_0_rd"))
        )
        output = GENERATOR.render_adapter(ports)
        self.assertIn(".io_cmt_0_valid", output)
        self.assertIn(".io_cmt_0_pc", output)
        self.assertIn("assign debug0_wb_pc       = cmt0_pc;", output)
        self.assertIn("assign debug0_wb_rf_wen   = {4{cmt0_valid && cmt0_rd_valid}};", output)

    def test_three_retirement_lanes_are_exported(self):
        ports = GENERATOR.parse_cpu_ports(
            self.cpu_source((
                "io_cmt_0_valid", "io_cmt_0_pc",
                "io_cmt_1_valid", "io_cmt_1_pc",
                "io_cmt_2_valid", "io_cmt_2_pc",
            ))
        )
        output = GENERATOR.render_adapter(ports)
        self.assertIn("assign retire_valid       = {cmt2_valid, cmt1_valid, cmt0_valid};", output)
        self.assertIn("assign retire_pc0         = cmt0_pc;", output)
        self.assertIn("assign retire_pc1         = cmt1_pc;", output)
        self.assertIn("assign retire_pc2         = cmt2_pc;", output)

    def test_incomplete_retirement_lane_is_rejected(self):
        ports = GENERATOR.parse_cpu_ports(self.cpu_source(("io_cmt_1_valid",)))
        with self.assertRaisesRegex(ValueError, "lane 1 must provide both valid and pc"):
            GENERATOR.render_adapter(ports)

    def test_missing_axi_port_is_rejected(self):
        ports = GENERATOR.parse_cpu_ports(self.cpu_source())
        del ports["io_axi_aw_id"]
        with self.assertRaisesRegex(ValueError, "missing required CPU port io_axi_aw_id"):
            GENERATOR.validate_required_ports(ports)

    def test_optional_cpu_input_is_rejected(self):
        ports = GENERATOR.parse_cpu_ports(self.cpu_source())
        ports["break_point"] = GENERATOR.Port("input", "break_point")
        with self.assertRaisesRegex(ValueError, "unsupported optional CPU input"):
            GENERATOR.render_adapter(ports)


if __name__ == "__main__":
    unittest.main()
