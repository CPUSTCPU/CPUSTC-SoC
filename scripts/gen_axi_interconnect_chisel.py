#!/usr/bin/env python3
"""Generate the Chisel wrapper for the Vivado AXI interconnect BD wrapper."""

from __future__ import annotations

import argparse
import math
import re
from dataclasses import dataclass
from pathlib import Path


DEFAULT_INPUT = (
    "fpga/xc7a200t/CPUSTC-SoC/CPUSTC-SoC.gen/sources_1/bd/"
    "axi_interconnect_0/hdl/axi_interconnect_0_wrapper.v"
)
DEFAULT_OUTPUT = "src/main/scala/chisel/AxiInterconnect0.scala"


@dataclass(frozen=True)
class Port:
    direction: str
    width: int
    name: str
    is_clock: bool


@dataclass(frozen=True)
class AxiIf:
    prefix: str
    role: str
    id_width: int
    addr_width: int
    len_width: int
    lock_width: int
    data_width: int
    strb_width: int
    has_wid: bool
    has_id: bool
    has_qos: bool
    has_region: bool


def parse_width(width: str | None) -> int:
    if width is None:
        return 1
    match = re.fullmatch(r"\s*(\d+)\s*:\s*(\d+)\s*", width)
    if not match:
        raise ValueError(f"Unsupported Verilog range: {width!r}")
    high, low = (int(match.group(1)), int(match.group(2)))
    return abs(high - low) + 1


def chisel_type(port: Port) -> str:
    if port.is_clock:
        return "Clock"
    if port.width == 1:
        return "Bool"
    return "UInt"


def chisel_ctor(port: Port) -> str:
    if port.is_clock:
        return "Clock()"
    if port.width == 1:
        return "Bool()"
    return f"UInt({port.width}.W)"


def parse_verilog(path: Path) -> tuple[str, list[Port]]:
    text = path.read_text()
    module_match = re.search(r"\bmodule\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(", text)
    if not module_match:
        raise ValueError(f"Cannot find module declaration in {path}")
    module_name = module_match.group(1)

    ports: list[Port] = []
    for line in text.splitlines():
        stripped = line.strip().rstrip(";")
        match = re.fullmatch(
            r"(input|output)\s+(?:\[(.*?)\]\s*)?([A-Za-z_][A-Za-z0-9_]*)",
            stripped,
        )
        if not match:
            continue
        direction, width, name = match.groups()
        ports.append(
            Port(
                direction=direction,
                width=parse_width(width),
                name=name,
                is_clock=(name == "ACLK_0" or name.endswith("_ACLK_0")),
            )
        )

    if not ports:
        raise ValueError(f"Cannot find input/output declarations in {path}")
    return module_name, ports


def infer_axi_interfaces(ports: list[Port]) -> list[AxiIf]:
    by_prefix: dict[str, dict[str, Port]] = {}
    for port in ports:
        match = re.fullmatch(r"([SM]\d\d)_AXI_0_(.+)", port.name)
        if not match:
            continue
        prefix, signal = match.groups()
        by_prefix.setdefault(prefix, {})[signal] = port

    interfaces: list[AxiIf] = []
    for prefix in sorted(by_prefix):
        signals = by_prefix[prefix]
        required = ["awaddr", "awlen", "awlock", "wdata", "wstrb"]
        missing = [name for name in required if name not in signals]
        if missing:
            raise ValueError(f"{prefix} is missing AXI signals: {', '.join(missing)}")
        for sideband in ("qos", "region"):
            if (f"aw{sideband}" in signals) != (f"ar{sideband}" in signals):
                raise ValueError(f"{prefix} has an incomplete AXI {sideband.upper()} signal pair")
        interfaces.append(
            AxiIf(
                prefix=prefix,
                role=prefix[0],
                id_width=signals["awid"].width if "awid" in signals else 0,
                addr_width=signals["awaddr"].width,
                len_width=signals["awlen"].width,
                lock_width=signals["awlock"].width,
                data_width=signals["wdata"].width,
                strb_width=signals["wstrb"].width,
                has_wid=("wid" in signals),
                has_id=("awid" in signals),
                has_qos=("awqos" in signals),
                has_region=("awregion" in signals),
            )
        )
    return interfaces


def raw_decl(port: Port) -> str:
    direction = "Input" if port.direction == "input" else "Output"
    return f"  val {port.name}: {chisel_type(port)} = IO({direction}({chisel_ctor(port)}))"


def bundle_params(interface: AxiIf) -> str:
    params = [
        f"idWidth = {interface.id_width}",
        f"addrWidth = {interface.addr_width}",
        f"lenWidth = {interface.len_width}",
        f"lockWidth = {interface.lock_width}",
        f"dataWidth = {interface.data_width}",
        f"strbWidth = {interface.strb_width}",
    ]
    return ", ".join(params)


def connect_slave(interface: AxiIf, io_name: str) -> list[str]:
    p = f"{interface.prefix}_AXI_0"
    is_axi4 = not interface.has_wid
    lines: list[str] = []
    for field in ["awid", "awaddr", "awlen", "awsize", "awburst", "awlock", "awcache", "awprot"]:
        lines.append(f"  raw.{p}_{field} := io.{io_name}.{field}")
    if interface.has_qos:
        source = f"io.{io_name}.awqos" if is_axi4 else "0.U"
        lines.append(f"  raw.{p}_awqos := {source}")
    if interface.has_region:
        source = f"io.{io_name}.awregion" if is_axi4 else "0.U"
        lines.append(f"  raw.{p}_awregion := {source}")
    lines += [
        f"  raw.{p}_awvalid := io.{io_name}.awvalid",
        f"  io.{io_name}.awready := raw.{p}_awready",
        "",
    ]
    if interface.has_wid:
        lines.append(f"  raw.{p}_wid := io.{io_name}.wid")
    lines += [
        f"  raw.{p}_wdata := io.{io_name}.wdata",
        f"  raw.{p}_wstrb := io.{io_name}.wstrb",
        f"  raw.{p}_wlast := io.{io_name}.wlast",
        f"  raw.{p}_wvalid := io.{io_name}.wvalid",
        f"  io.{io_name}.wready := raw.{p}_wready",
        "",
        f"  io.{io_name}.bid := raw.{p}_bid",
        f"  io.{io_name}.bresp := raw.{p}_bresp",
        f"  io.{io_name}.bvalid := raw.{p}_bvalid",
        f"  raw.{p}_bready := io.{io_name}.bready",
        "",
    ]
    for field in ["arid", "araddr", "arlen", "arsize", "arburst", "arlock", "arcache", "arprot"]:
        lines.append(f"  raw.{p}_{field} := io.{io_name}.{field}")
    if interface.has_qos:
        source = f"io.{io_name}.arqos" if is_axi4 else "0.U"
        lines.append(f"  raw.{p}_arqos := {source}")
    if interface.has_region:
        source = f"io.{io_name}.arregion" if is_axi4 else "0.U"
        lines.append(f"  raw.{p}_arregion := {source}")
    lines += [
        f"  raw.{p}_arvalid := io.{io_name}.arvalid",
        f"  io.{io_name}.arready := raw.{p}_arready",
        "",
        f"  io.{io_name}.rid := raw.{p}_rid",
        f"  io.{io_name}.rdata := raw.{p}_rdata",
        f"  io.{io_name}.rresp := raw.{p}_rresp",
        f"  io.{io_name}.rlast := raw.{p}_rlast",
        f"  io.{io_name}.rvalid := raw.{p}_rvalid",
        f"  raw.{p}_rready := io.{io_name}.rready",
    ]
    return lines


def connect_master(interface: AxiIf, io_name: str) -> list[str]:
    p = f"{interface.prefix}_AXI_0"
    is_axi4 = not interface.has_wid
    lines: list[str] = []
    if interface.has_id:
        lines.append(f"  io.{io_name}.awid := raw.{p}_awid")
    else:
        lines.append(f"  io.{io_name}.awid := 0.U")
    for field in ["awaddr", "awlen", "awsize", "awburst", "awlock", "awcache", "awprot"]:
        lines.append(f"  io.{io_name}.{field} := raw.{p}_{field}")
    if is_axi4:
        qos = f"raw.{p}_awqos" if interface.has_qos else "0.U"
        region = f"raw.{p}_awregion" if interface.has_region else "0.U"
        lines.append(f"  io.{io_name}.awqos := {qos}")
        lines.append(f"  io.{io_name}.awregion := {region}")
    lines += [
        f"  raw.{p}_awready := io.{io_name}.awready",
        f"  io.{io_name}.awvalid := raw.{p}_awvalid",
        "",
    ]
    if interface.has_wid:
        lines.append(f"  io.{io_name}.wid := raw.{p}_wid")
    lines += [
        f"  io.{io_name}.wdata := raw.{p}_wdata",
        f"  io.{io_name}.wstrb := raw.{p}_wstrb",
        f"  io.{io_name}.wlast := raw.{p}_wlast",
        f"  io.{io_name}.wvalid := raw.{p}_wvalid",
        f"  raw.{p}_wready := io.{io_name}.wready",
        "",
    ]
    if interface.has_id:
        lines.append(f"  raw.{p}_bid := io.{io_name}.bid")
    lines += [
        f"  raw.{p}_bresp := io.{io_name}.bresp",
        f"  raw.{p}_bvalid := io.{io_name}.bvalid",
        f"  io.{io_name}.bready := raw.{p}_bready",
        "",
    ]
    if interface.has_id:
        lines.append(f"  io.{io_name}.arid := raw.{p}_arid")
    else:
        lines.append(f"  io.{io_name}.arid := 0.U")
    for field in ["araddr", "arlen", "arsize", "arburst", "arlock", "arcache", "arprot"]:
        lines.append(f"  io.{io_name}.{field} := raw.{p}_{field}")
    if is_axi4:
        qos = f"raw.{p}_arqos" if interface.has_qos else "0.U"
        region = f"raw.{p}_arregion" if interface.has_region else "0.U"
        lines.append(f"  io.{io_name}.arqos := {qos}")
        lines.append(f"  io.{io_name}.arregion := {region}")
    lines += [
        f"  raw.{p}_arready := io.{io_name}.arready",
        f"  io.{io_name}.arvalid := raw.{p}_arvalid",
        "",
    ]
    if interface.has_id:
        lines.append(f"  raw.{p}_rid := io.{io_name}.rid")
    lines += [
        f"  raw.{p}_rdata := io.{io_name}.rdata",
        f"  raw.{p}_rresp := io.{io_name}.rresp",
        f"  raw.{p}_rlast := io.{io_name}.rlast",
        f"  raw.{p}_rvalid := io.{io_name}.rvalid",
        f"  io.{io_name}.rready := raw.{p}_rready",
    ]
    return lines


def emit_scala(module_name: str, ports: list[Port], interfaces: list[AxiIf]) -> str:
    slave_interfaces = [interface for interface in interfaces if interface.role == "S"]
    routed_id_width = max(interface.id_width for interface in slave_interfaces) + math.ceil(
        math.log2(len(slave_interfaces))
    )
    lines: list[str] = [
        "package chisel",
        "",
        "import chisel3._",
        "import chisel3.experimental.ExtModule",
        "",
        "// Generated by scripts/gen_axi_interconnect_chisel.py. Do not edit by hand.",
        "",
    ]

    lines += [
        "class RawAxiInterconnect0 extends ExtModule {",
        f'  override def desiredName: String = "{module_name}"',
        "",
    ]
    lines.extend(raw_decl(port) for port in ports)
    lines += ["}", ""]

    lines += [
        "class AxiInterconnect0IO extends Bundle {",
        "  val aclk:    Clock = Input(Clock())",
        "  val aresetn: Bool  = Input(Bool())",
    ]
    for interface in interfaces:
        index = int(interface.prefix[1:])
        name = f"{interface.role.lower()}{index}"
        lines.append(f"  val {name}Clock:  Clock = Input(Clock())")
        lines.append(f"  val {name}Resetn: Bool  = Input(Bool())")
        if interface.role == "S":
            if interface.has_wid:
                lines.append(f"  val {name}:       AXI3IO = Flipped(new AXI3IO({bundle_params(interface)}))")
            else:
                lines.append(f"  val {name}:       AXI4IO = Flipped(new AXI4IO({bundle_params(interface)}))")
        else:
            logical_interface = interface
            if not interface.has_id:
                logical_interface = AxiIf(**{**interface.__dict__, "id_width": routed_id_width})
            bundle_type = "AXI3IO" if interface.has_wid else "AXI4IO"
            lines.append(
                f"  val {name}:       {bundle_type} = new {bundle_type}({bundle_params(logical_interface)})"
            )
    lines += ["}", ""]

    lines += [
        "class AxiInterconnect0 extends RawModule {",
        "  val io: AxiInterconnect0IO = IO(new AxiInterconnect0IO)",
        "",
        "  val raw: RawAxiInterconnect0 = Module(new RawAxiInterconnect0)",
        "",
        "  raw.ACLK_0 := io.aclk",
        "  raw.ARESETN_0 := io.aresetn",
    ]
    for interface in interfaces:
        index = int(interface.prefix[1:])
        name = f"{interface.role.lower()}{index}"
        lines.append(f"  raw.{interface.prefix}_ACLK_0 := io.{name}Clock")
        lines.append(f"  raw.{interface.prefix}_ARESETN_0 := io.{name}Resetn")
    lines.append("")

    for interface in interfaces:
        index = int(interface.prefix[1:])
        name = f"{interface.role.lower()}{index}"
        if interface.role == "S":
            lines.extend(connect_slave(interface, name))
        else:
            lines.extend(connect_master(interface, name))
        lines.append("")
    lines += ["}"]
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", default=DEFAULT_INPUT, type=Path)
    parser.add_argument("--output", default=DEFAULT_OUTPUT, type=Path)
    args = parser.parse_args()

    module_name, ports = parse_verilog(args.input)
    interfaces = infer_axi_interfaces(ports)
    scala = emit_scala(module_name, ports, interfaces)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(scala)


if __name__ == "__main__":
    main()
