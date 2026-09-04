#!/usr/bin/env python3
"""Tests for gen_axi_interconnect_chisel.py."""

import importlib.util
import sys
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("gen_axi_interconnect_chisel.py")
SPEC = importlib.util.spec_from_file_location("gen_axi_interconnect_chisel", SCRIPT)
GENERATOR = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = GENERATOR
SPEC.loader.exec_module(GENERATOR)


def axi_interface(prefix: str, *, has_wid: bool, has_qos: bool, has_region: bool):
    return GENERATOR.AxiIf(
        prefix=prefix,
        role=prefix[0],
        id_width=8 if prefix.startswith("M") else 4,
        addr_width=32,
        len_width=4 if has_wid else 8,
        lock_width=2 if has_wid else 1,
        data_width=32,
        strb_width=4,
        has_wid=has_wid,
        has_id=True,
        has_qos=has_qos,
        has_region=has_region,
    )


class AxiInterconnectGeneratorTest(unittest.TestCase):
    def test_output_uses_path_derived_package(self):
        output = GENERATOR.emit_scala(
            "axi_interconnect_0_wrapper",
            [],
            [
                axi_interface("M00", has_wid=False, has_qos=True, has_region=True),
                axi_interface("S00", has_wid=True, has_qos=True, has_region=False),
            ],
        )

        self.assertEqual(
            GENERATOR.DEFAULT_OUTPUT,
            "src/main/scala/chisel/axiInterconnect/AxiInterconnect0.scala",
        )
        self.assertTrue(output.startswith("package chisel.axiInterconnect\n"))
        self.assertIn("import chisel.common.bus._", output)

    def test_axi4_master_uses_axi4_bundle_and_sidebands(self):
        interfaces = [
            axi_interface("M00", has_wid=False, has_qos=True, has_region=True),
            axi_interface("S00", has_wid=True, has_qos=True, has_region=False),
        ]

        output = GENERATOR.emit_scala("axi_interconnect_0_wrapper", [], interfaces)

        self.assertIn("val m0:       AXI4IO = new AXI4IO", output)
        self.assertIn("io.m0.awqos := raw.M00_AXI_0_awqos", output)
        self.assertIn("io.m0.awregion := raw.M00_AXI_0_awregion", output)
        self.assertIn("io.m0.arqos := raw.M00_AXI_0_arqos", output)
        self.assertIn("io.m0.arregion := raw.M00_AXI_0_arregion", output)
        self.assertNotIn("io.m0.wid", output)

    def test_axi3_slave_ties_present_qos_ports_low(self):
        interface = axi_interface("S00", has_wid=True, has_qos=True, has_region=False)

        output = "\n".join(GENERATOR.connect_slave(interface, "s0"))

        self.assertIn("raw.S00_AXI_0_awqos := 0.U", output)
        self.assertIn("raw.S00_AXI_0_arqos := 0.U", output)
        self.assertIn("raw.S00_AXI_0_wid := io.s0.wid", output)

    def test_incomplete_sideband_pair_is_rejected(self):
        ports = [
            GENERATOR.Port("input", 32, "S00_AXI_0_awaddr", False),
            GENERATOR.Port("input", 8, "S00_AXI_0_awlen", False),
            GENERATOR.Port("input", 1, "S00_AXI_0_awlock", False),
            GENERATOR.Port("input", 32, "S00_AXI_0_wdata", False),
            GENERATOR.Port("input", 4, "S00_AXI_0_wstrb", False),
            GENERATOR.Port("input", 4, "S00_AXI_0_awqos", False),
        ]

        with self.assertRaisesRegex(ValueError, "incomplete AXI QOS signal pair"):
            GENERATOR.infer_axi_interfaces(ports)


if __name__ == "__main__":
    unittest.main()
