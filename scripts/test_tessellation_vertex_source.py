#!/usr/bin/env python3
"""Exercise GVS1 original VS inputs/code/uniforms with the actual owner and MMU."""
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
    args = parser.parse_args()
    temporary_root = ROOT / '.artifacts'
    temporary_root.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='j', dir=temporary_root) as temporary:
        out = Path(temporary)
        source = (ROOT / 'driver/opengpu_hw.c').read_text()
        first = source.index('int opengpu_hw_snapshot_submission_lock(')
        last = source.index('\n}\n', first) + 2
        (out / 'snapshot_submission_under_test.inc').write_text(source[first:last])
        flags = ['-std=c11', '-O1', '-Wall', '-Wextra', '-Werror']
        if args.sanitize or os.environ.get('OPENGPU_TEST_SANITIZERS') == '1':
            flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
        includes = [out, ROOT / 'driver/tests', ROOT / 'driver',
                    ROOT / 'driver/tests/private_snapshot_stubs',
                    ROOT / 'driver/tests/mmu_stubs', ROOT / 'driver/tests/compute_submit_stubs']
        environment = dict(os.environ, TMPDIR=temporary)
        subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + flags +
                       ['-I' + str(path) for path in includes] +
                       [str(ROOT / 'driver/tests/opengpu_tessellation_vertex_source_test.c'),
                        str(ROOT / 'driver/opengpu_native_frame.c'), '-o', str(out / 'test')],
                       check=True, env=environment)
        subprocess.run([str(out / 'test')], check=True, env=environment)
        if args.negative_controls:
            production = (ROOT / 'driver/opengpu_tessellation_vertex_source.c').read_text()
            mutations = {
                'ignore-physical-alias': ('if(first<limit && scratch<end)return -EINVAL;',
                                          'if(false && first<limit && scratch<end)return -EINVAL;'),
                'ignore-legacy-sampler-source': ('for(u32 word=328;word<=335;word++)if(gvs_word(c->draw,word))return true;', ''),
                'accept-old-input-mode': ('for(u32 i=17;i<=23;i++)if(gvs_word(raw,i))return false;', ''),
                'accept-old-static-tes-mode': ('for(u32 i=domain==0?237u:238u;i<=(domain==0?482u:483u);i++)if(gvs_word(raw,i))return false;', ''),
                'fail-to-canonicalize-patches-restart': ('gvs_put(c->draw,326,0);', '(void)c;'),
                'truncate-source-limit33': ('draw_end=gvs_wide(raw,25);code_end=gvs_wide(raw,28);',
                                            'draw_end=gvs_word(raw,25);code_end=gvs_word(raw,28);'),
                'ignore-sequence-high-word': ('end=gvs_wide(raw,510);', 'end=gvs_word(raw,510);'),
                'ignore-unused-maps': ('if(i>=s->layout.input_components) {if(m->kind || m->component)return false;continue;}',
                                       'if(i>=s->layout.input_components) {continue;}'),
                'live-source-after-callback': ('if(ret)goto done;\n    /* This ES3.2',
                                               'if(ret)goto done;\n    c->source=*source;\n    /* This ES3.2'),
                'executable-metadata': ('opengpu_private_snapshot_append(owner,c->root,512,&root)',
                                       'opengpu_private_snapshot_append_code(owner,c->root,512,&root)'),
            }
            for name, (before, after) in mutations.items():
                assert before in production
                mutant = out / (name + '.c')
                mutant.write_text(production.replace(before, after))
                binary = out / name
                subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + flags +
                               ['-I' + str(path) for path in includes] +
                               ['-DOPENGPU_GVS_SOURCE="' + str(mutant) + '"',
                                str(ROOT / 'driver/tests/opengpu_tessellation_vertex_source_test.c'),
                                str(ROOT / 'driver/opengpu_native_frame.c'), '-o', str(binary)],
                               check=True, env=environment)
                result = subprocess.run([str(binary)], capture_output=True, text=True, env=environment)
                if not result.returncode or not re.search(r'assertion.*failed', result.stderr, re.I | re.S):
                    raise SystemExit('negative control did not fail a semantic assertion: ' + name)
                print('GVS1 semantic negative control rejected: ' + name)


if __name__ == '__main__':
    main()
