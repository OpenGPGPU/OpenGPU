#!/usr/bin/env python3
"""Explicit one-CPU/one-GiB OpenDB oracle for the PPA SRAM count gates.

This links a tiny hierarchical netlist with two 32-bit and one 64-bit real
SRAM masters. It does not synthesize, route or qualify a product host. All
fixture databases, scripts and logs are temporary and removed on exit.
"""
import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

import qualify_ppa as q


M32, M64 = 'srambank_64x4x32_6t122', 'srambank_64x4x64_6t122'

NETLIST = '''module Pair(input clock, input [7:0] ADDRESS, input [63:0] WD,
    output [31:0] A, output [31:0] B);
  srambank_64x4x32_6t122 a (.clk(clock), .ADDRESS(ADDRESS), .wd(WD[31:0]),
    .banksel(1'b1), .read(1'b1), .write(1'b0), .dataout(A));
  srambank_64x4x32_6t122 b (.clk(clock), .ADDRESS(ADDRESS), .wd(WD[63:32]),
    .banksel(1'b1), .read(1'b1), .write(1'b0), .dataout(B));
endmodule
module Top(input clock, input [7:0] ADDRESS, input [63:0] WD,
    output [31:0] A, output [31:0] B, output [63:0] C);
  Pair pair (.clock(clock), .ADDRESS(ADDRESS), .WD(WD), .A(A), .B(B));
  srambank_64x4x64_6t122 c (.clk(clock), .ADDRESS(ADDRESS), .wd(WD),
    .banksel(1'b1), .read(1'b1), .write(1'b0), .dataout(C));
endmodule
'''


def verify(root, sram_root, image):
    output = root / 'proof'
    work = output / 'flow/orfs-work'
    results = work / 'results/base'
    results.mkdir(parents=True)
    cfg = work / 'designs/asap7/Top'
    macros = cfg / 'macros'
    macros.mkdir(parents=True)
    collateral = {}
    for name in (M32, M64):
        for folder, ext in (('LIB', 'lib'), ('LEF', 'lef')):
            source = sram_root / folder / (name + '.' + ext)
            target = macros / source.name
            shutil.copyfile(source, target)
            collateral[target.name] = q.sha(target)
    (root / 'tiny.v').write_text(NETLIST)
    flow = '/OpenROAD-flow-scripts/flow'
    macro_base = f'{flow}/designs/asap7/Top/macros'
    libs = ''.join(f'read_liberty {macro_base}/{name}.lib\n' for name in (M32, M64))
    odb_base = f'{flow}/results/asap7/Top/base'
    create = libs + f'''read_lef {flow}/platforms/asap7/lef/asap7_tech_1x_201209.lef
read_lef {macro_base}/{M32}.lef
read_lef {macro_base}/{M64}.lef
read_verilog /opengpu/tiny.v
link_design Top
create_clock -name core_clock -period 1000 [get_ports clock]
set_input_delay -clock core_clock 250 [get_ports {{ADDRESS* WD*}}]
set_output_delay -clock core_clock 250 [all_outputs]
write_sdc {odb_base}/1_synth.sdc
write_db {odb_base}/1_synth.odb
write_db {odb_base}/6_final.odb
write_sdc {odb_base}/6_final.sdc
''' + q.sram_macro_tcl()
    (root / 'create.tcl').write_text(create)
    volumes = ['-v', str(work / 'results') + f':{flow}/results/asap7/Top',
               '-v', str(cfg) + f':{flow}/designs/asap7/Top', '-v', str(root) + ':/opengpu']
    command = ['docker', 'run', '--rm', *volumes, image, 'bash', '-lc',
               'source /OpenROAD-flow-scripts/env.sh >/dev/null && '
               'timeout 60 openroad -exit -no_init -no_splash /opengpu/create.tcl']
    report, code, _, actual_command = q.run_odb_check(command, 60, 1, 'macro-oracle')
    expected = {M32: 2, M64: 1}
    if not q.sram_macro_gate(report, code, expected)['passed']:
        raise RuntimeError('Tiny OpenDB creation failed:\n' + report[-8000:])
    sta = output / 'flow/synthesis_sta.tcl'
    sta.write_text(libs + f'read_db {odb_base}/1_synth.odb\nread_sdc {odb_base}/1_synth.sdc\n'
                   'report_checks -path_delay max -digits 3\n')
    synthesis = dict(module_name='Top', artifacts={'synthesis_sta.tcl': str(sta)},
        reproducibility={'openroad_image': image},
        command=['docker', 'run', '--rm', *volumes, '-v', str(sta) + ':/tmp/synthesis_sta.tcl',
                 image, 'openroad', '/tmp/synthesis_sta.tcl'])
    setup = q.synthesis_constraint_proof(synthesis, output, 60, 1, expected, collateral)
    if not setup['passed']:
        raise RuntimeError('Tiny coverage/macro gate failed:\n' + (output / 'constraint_coverage.log').read_text()[-8000:])
    routed_fixture = dict(module_name='Top', artifacts={'final_odb': str(results / '6_final.odb'), 'final_sdc': str(results / '6_final.sdc')},
        reproducibility={'openroad_image': image, 'parameters': {'corner': 'TC', 'cell_vt': 'SLVT'}}, command=actual_command)
    routed = q.route_macro_proof(routed_fixture, output, expected, collateral, 60, 1)
    if not routed['passed']:
        raise RuntimeError('Tiny final-ODB macro gate failed:\n' + (output / 'route_macro_check.log').read_text()[-8000:])
    # Reject loss and type substitution using the report from the real ODB.
    actual_report = (output / 'route_macro_check.log').read_text()
    for wrong in ({M32: 1, M64: 1}, {M32: 1, M64: 2}, {M32: 2}, {}):
        assert not q.sram_macro_gate(actual_report, 0, wrong)['passed']
    final_sdc = results / '6_final.sdc'
    original_sdc = final_sdc.read_bytes()
    rejected = []
    for name, source in (('unclocked', '# deliberately missing clocks and delays\n'),
                         ('unconstrained', 'create_clock -name core_clock -period 1000 [get_ports clock]\n')):
        (output / 'route_macro_check.tcl').unlink()
        (output / 'route_macro_check.log').unlink()
        final_sdc.write_text(source)
        negative = q.route_macro_proof(routed_fixture, output, expected, collateral, 60, 1)
        if negative['passed'] or negative['constraint_coverage']['passed']:
            raise RuntimeError('Tiny final-ODB gate accepted ' + name + ' endpoints')
        rejected.append(name)
    final_sdc.write_bytes(original_sdc)
    return dict(synthesis_macro_counts=setup['sram_macros']['actual'],
                rejected_final_constraints=rejected,
                final_odb_macro_counts=routed['actual'],
                check_setup_passed=True, physical_timing_qualified=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sram-root', type=Path, required=True, help='Pinned generated SRAM collateral directory')
    parser.add_argument('--image', default='chipagent/openroad:arm64')
    args = parser.parse_args()
    image = subprocess.check_output(['docker', 'image', 'inspect', '--format', '{{.Id}}', args.image], text=True).strip()
    if not re.fullmatch(r'sha256:[0-9a-f]{64}', image):
        raise SystemExit('Missing pinned OpenROAD image')
    parent = Path(__file__).resolve().parents[1] / '.artifacts'
    parent.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='ppa-macro-oracle-', dir=parent) as directory:
        result = verify(Path(directory), args.sram_root.resolve(), image)
    print('PPA_SRAM_MACRO_ORACLE_PASS ' + json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    main()
