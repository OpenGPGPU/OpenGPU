#!/usr/bin/env python3
"""Exercise the independent TCS/TES program validator/RX snapshot with the real owner and MMU."""
import argparse
import os
import re
from pathlib import Path
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sanitize', action='store_true')
    parser.add_argument('--negative-controls', action='store_true')
    parser.add_argument('--negative-control-group', choices=('all', 'legacy', 'legacy-first', 'legacy-second', 'language'), default='all')
    parser.add_argument('--programs', type=Path, help='directory containing original LE native-tcs.words/native-tes.words')
    parser.add_argument('--tcs-layout', type=int, nargs=5, metavar=('IN_VERTS', 'IN_COMPS', 'OUT_VERTS', 'OUT_COMPS', 'PATCH_COMPS'))
    parser.add_argument('--tes-layout', type=int, nargs=2, metavar=('PATCH_WORDS', 'OUT_COMPS'))
    parser.add_argument('--tes-program-version', type=int, choices=(1, 2, 3), default=1)
    parser.add_argument('--tcs-program-version', type=int, choices=(1, 3), default=1)
    parser.add_argument('--owned-tessellation-info', action='store_true', help='explicit offline verified-test profile permission, never hardware discovery')
    parser.add_argument('--tes-patch-layout', type=int, nargs=5, metavar=('IN_VERTS', 'IN_COMPS', 'OUT_VERTS', 'OUT_COMPS', 'PATCH_COMPS'))
    args = parser.parse_args()
    if args.programs and (args.tcs_layout is None or args.tes_layout is None):
        parser.error('--programs requires exact --tcs-layout and --tes-layout')
    if not args.programs and (args.tcs_layout is not None or args.tes_layout is not None):
        parser.error('layout metadata requires --programs')
    if args.tcs_program_version == 3 or args.tes_program_version == 3:
        if not args.programs or not args.owned_tessellation_info:
            parser.error('version 3 requires --programs and explicit --owned-tessellation-info')
    elif args.owned_tessellation_info:
        parser.error('--owned-tessellation-info requires an explicit version 3 profile')
    if args.tes_program_version in (2, 3):
        if not args.programs or args.tes_patch_layout is None or args.tes_layout[0] != 0:
            parser.error('version 2/3 TES requires --programs, --tes-layout 0 OUT_COMPS and --tes-patch-layout')
    elif args.tes_patch_layout is not None:
        parser.error('--tes-patch-layout requires --tes-program-version 2 or 3')
    if any(value < 0 or value > 2**32-1 for value in (args.tcs_layout or []) + (args.tes_layout or []) + (args.tes_patch_layout or [])):
        parser.error('layout fields must be u32')
    temporary_root = ROOT / '.artifacts'
    temporary_root.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='j', dir=temporary_root) as temporary:
        out = Path(temporary)
        source = (ROOT / 'driver/opengpu_hw.c').read_text()
        first = source.index('int opengpu_hw_snapshot_submission_lock(')
        last = source.index('\n}\n', first) + 2
        (out / 'snapshot_submission_under_test.inc').write_text(source[first:last])
        flags = ['-std=c11', '-O2', '-Wall', '-Wextra', '-Werror']
        if args.sanitize or os.environ.get('OPENGPU_TEST_SANITIZERS') == '1':
            flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
        includes = [out, ROOT / 'driver/tests', ROOT / 'driver',
                    ROOT / 'driver/tests/private_snapshot_stubs',
                    ROOT / 'driver/tests/mmu_stubs', ROOT / 'driver/tests/compute_submit_stubs']
        environment = dict(os.environ, TMPDIR=temporary)
        subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + flags +
                       ['-I' + str(path) for path in includes] +
                       [str(ROOT / 'driver/tests/opengpu_tessellation_program_snapshot_test.c'), '-o', str(out / 'test')],
                       check=True, env=environment)
        command = [str(out / 'test')]
        if args.programs:
            command += [str(args.programs.resolve())] + list(map(str, args.tcs_layout + args.tes_layout))
            if args.owned_tessellation_info:
                command += [str(args.tes_program_version)] + list(map(str, args.tes_patch_layout or [0]*5))
                command += [str(args.tcs_program_version), '1']
            elif args.tes_program_version == 2:
                command += ['2'] + list(map(str, args.tes_patch_layout))
        subprocess.run(command, check=True, env=environment)
        if args.negative_controls:
            production = (ROOT / 'driver/opengpu_tessellation_shader_validator.c').read_text()
            mutations = {
                'undefined-launch-registers': ('s->v[1]=tess_unknown();', 's->v[1]=tess_unknown();for(u32 r=0;r<32;r++)s->x[r]=tess_unknown();'),
                'params-without-stage': ('s->params=unmasked?TESS_ALL_PARAMS:s->mask_version;', 's->params=TESS_ALL_PARAMS;'),
                'barrier-without-drain': ('!depth && !early_exit && !tess_pending(branches)', '((void)early_exit,true)'),
                'publication-bypass': ('!s->invalid && s->emitted && s->ended', 'true'),
                'ignore-uniform-ownership': ('p->owned_uniforms &&', 'true &&'),
                'raw-memory-admission': ('else goto done; /* No raw memory', 'else if(op==0x03 || op==0x23){}else goto done; /* No raw memory'),
                'ignore-combined-patch-capacity': ('p->output_vertices*p->output_components+p->patch_components<=2048 &&', ''),
                'restore-shared-scratch': ('s->x[10]=(struct tess_value){0};s->fp=0;', '(void)s;'),
                'restore-shared-mask-params': ('s->v[0]=(struct tess_value){0};s->mask_version=0;s->params=0;', '(void)s;'),
                'clobber-shared-scalar': ('(!rd || rd==10) && (op==0x37', 'true && (op==0x37'),
                'clobber-shared-fp': ('if(op==0x43 || op==0x47 || op==0x4b || op==0x4f || op==0x53 || op==0x73)goto done;', 'if(op==0x73)goto done;'),
                'short-output-as-vl': ('s->x[9].value==p->lanes*p->warps', 's->x[9].value==p->output_vertices'),
                'dynamic-v1-field-relabel': ('if(p->version==1 && (p->owned_patch_reads || !tess_layout_zero(&p->tes_patch_layout)))return false;', 'if(false && p->version==1 && (p->owned_patch_reads || !tess_layout_zero(&p->tes_patch_layout)))return false;'),
                'dynamic-without-profile-permission': ('return p->owned_patch_reads && !p->patch_words', 'return true && !p->patch_words'),
                'dynamic-without-coupled-capacity': ('l->output_vertices*l->output_components+l->patch_components<=2048', 'true'),
                'dynamic-input-bank': ('if(a>=1024 || !(a&1))return false;', 'if(a>=1024)return false;'),
                'dynamic-truncated-vertex': ('s->v[vertex].value>=l->output_vertices', '(s->v[vertex].value&63)>=l->output_vertices'),
                'dynamic-truncated-descriptor': ('u32 a=s->v[address].value,component=a>>3;\n        bool per_patch', 'u32 a=s->v[address].value&1023,component=a>>3;\n        bool per_patch'),
                'dynamic-confused-export-layout': ('component>=l->output_components', 'component>=p->output_components'),
                'dynamic-missing-old-destination': ('function==7 || function==21 || function==33', 'function==7 || function==21'),
                'dynamic-read-after-publication': ('p->owned_patch_reads && !depth && !s->invalid && !s->emitted && !s->ended', 'p->owned_patch_reads && !depth && !s->invalid'),
                'dynamic-undefined-source': ('if(!s->v[vertex].defined || !s->v[address].defined)return false;\n    if(s->v[vertex].known', 'if(s->v[vertex].known'),
                'dynamic-old-dialect': ('p->stage==5 && (p->version==2 || p->version==3) && p->owned_patch_reads && !depth', '!depth'),
                'dynamic-short-vl': ('!f && s->full_vl && tess_masked(s,insn,rd,function', '!f && (s->full_vl || function==33) && tess_masked(s,insn,rd,function'),

            }
            legacy_names = set(mutations)
            legacy_positions = {name: index for index, name in enumerate(mutations)}
            assert len(legacy_names) == 24
            mutations.update({
                'info-old-version-permission': ('if(p->version!=3 && p->owned_tessellation_info)return false;', ''),
                'info-missing-owner-permission': ('if(p->version==3 && !p->owned_tessellation_info)return false;', ''),
                'info-tcs-selector': ('p->owned_tessellation_info && s->x[a].value==6', 'p->owned_tessellation_info'),
                'info-tes-old-version-selector6': ('p->version==3 && p->owned_tessellation_info));', 'true));'),
                'info-arm-scalar-clobber': ('!depth || (a==10 && (!rd || rd==10))', '!depth || a==10'),
                'info-arm-selector-rematerialization': ('!depth || (a==10 && (!rd || rd==10))', '!depth || (!rd || rd==10)'),
                'info-result-is-not-authority': ('if(rd)s->x[rd]=tess_unknown();\n            }else{\n                TESS_REQUIRE(p->stage==5', 'if(rd)s->x[rd]=tess_constant(6);\n            }else{\n                TESS_REQUIRE(p->stage==5'),
                'info-arm-shared-boundary': ('s->x[10]=(struct tess_value){0};s->fp=0;', 's->fp=0;'),
                'info-tcs-tes-layout-confusion': ('if(p->version==3 && p->stage==4 &&\n        (p->owned_patch_reads || !tess_layout_zero(&p->tes_patch_layout)))return false;', ''),
            })
            for name, (before, after) in mutations.items():
                is_language = name not in legacy_names
                if args.negative_control_group.startswith('legacy') and is_language:
                    continue
                if args.negative_control_group == 'legacy-first' and legacy_positions.get(name, 24) >= 12:
                    continue
                if args.negative_control_group == 'legacy-second' and legacy_positions.get(name, -1) < 12:
                    continue
                if args.negative_control_group == 'language' and not is_language:
                    continue
                assert before in production
                mutant = out / (name + '.c')
                mutant.write_text(production.replace(before, after))
                binary = out / name
                subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + flags +
                               ['-I' + str(path) for path in includes] +
                               ['-DOPENGPU_TESS_VALIDATOR_SOURCE="' + str(mutant) + '"',
                                str(ROOT / 'driver/tests/opengpu_tessellation_program_snapshot_test.c'),
                                '-o', str(binary)], check=True, env=environment)
                result = subprocess.run([str(binary)] + (['--info-only'] if is_language else []), capture_output=True, text=True, env=environment)
                if result.returncode == 0 or not re.search(r'assertion.*failed', result.stderr, re.I | re.S):
                    raise SystemExit('negative control did not fail its semantic assertion: ' + name)
                print('tess program semantic negative control rejected: ' + name)
            if not args.negative_control_group.startswith('legacy'):
                # Use the real production snapshot body with only its profile
                # capture removed. The callback mutates valid source fields,
                # so rejection must be semantic, not a bool-representation UB.
                snapshot = (ROOT / 'driver/opengpu_tessellation_program_snapshot.c').read_text()
                before = 'captured=*view;policy=*profile;view=&captured;profile=&policy;'
                assert snapshot.count(before) == 1
                mutant = out / 'uncaptured-info-profile.c'
                mutant.write_text(snapshot.replace(before, 'captured=*view;policy=*profile;view=&captured;(void)policy;'))
                fixture = (ROOT / 'driver/tests/opengpu_tessellation_program_snapshot_test.c').read_text()
                literal = '#include "../opengpu_tessellation_program_snapshot.c"'
                assert fixture.count(literal) == 1
                source = out / 'uncaptured-info-profile-test.c'
                source.write_text(fixture.replace(literal, '#include "' + str(mutant) + '"'))
                binary = out / 'uncaptured-info-profile'
                subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + flags +
                               ['-I' + str(path) for path in includes] +
                               [str(source), '-o', str(binary)], check=True, env=environment)
                result = subprocess.run([str(binary), '--info-only'], capture_output=True, text=True, env=environment)
                if result.returncode == 0 or not re.search(r'assertion.*failed', result.stderr, re.I | re.S):
                    raise SystemExit('negative control did not fail its semantic assertion: info-profile-callback-capture')
                print('tess program semantic negative control rejected: info-profile-callback-capture')


if __name__ == "__main__":
    main()
