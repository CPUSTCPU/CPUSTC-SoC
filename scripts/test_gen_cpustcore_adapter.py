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

    def test_wrapper_has_no_debug_interface(self):
        ports = GENERATOR.parse_cpu_ports(self.cpu_source())
        output = GENERATOR.render_adapter(ports)
        self.assertNotIn("debug", output)

    def test_present_debug_ports_are_not_connected(self):
        ports = GENERATOR.parse_cpu_ports(
            self.cpu_source(("io_cmt_0_valid", "io_cmt_0_pc"))
        )
        output = GENERATOR.render_adapter(ports)
        self.assertNotIn(".io_cmt_0_valid", output)
        self.assertNotIn(".io_cmt_0_pc", output)

    def test_missing_axi_port_is_rejected(self):
        ports = GENERATOR.parse_cpu_ports(self.cpu_source())
        del ports["io_axi_aw_id"]
        with self.assertRaisesRegex(ValueError, "missing required CPU port io_axi_aw_id"):
            GENERATOR.validate_required_ports(ports)


if __name__ == "__main__":
    unittest.main()
