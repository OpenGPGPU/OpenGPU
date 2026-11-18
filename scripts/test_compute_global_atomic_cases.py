"""Original RV32 source identities and actual production admission controls.

No CPU instruction execution; emitted kernels still require complete-host DUT
execution, old-value/whole-RAM verification and original cleanup receipts.
"""
import os
from pathlib import Path
import shlex
import struct
import subprocess
import tempfile
import unittest

import compute_global_atomic_cases as source
from compute_atomic_order import OPERATIONS

ROOT = Path(__file__).resolve().parents[1]


class GlobalSources(unittest.TestCase):
    def test_full_operation_set_and_exact_bounded_scalar_callers(self):
        self.assertEqual(len(source.CASES), 24)
        self.assertEqual(len(set(source.CASES)), 24)
        seen = set()
        for case in source.CASES:
            p, calls = source.parameters(case), source.events(case)
            self.assertEqual((p['groups'], p['local'], p['scalar_callers']), (4, 16, 4))
            self.assertEqual(len(calls), 8)
            self.assertEqual(source.predecessors(case), (0, 1, 0, 4, 0, 16, 0, 64))
            self.assertEqual(p['target_offset'] % 4, 0)
            self.assertLessEqual(p['target_offset'] + 4, p['kernarg_bytes'])
            self.assertFalse(source.OUTPUT_OFFSET <= p['target_offset'] < source.OUTPUT_OFFSET + 32)
            seen.update(call['operation'] for call in calls)
        self.assertEqual(seen, OPERATIONS)
        for invalid in ('global-atomic-add', '', None, True):
            with self.assertRaisesRegex(ValueError, 'unknown original'):
                source.parameters(invalid)

    def test_original_source_words_have_distinct_old_slots_and_no_prefilled_results(self):
        for case in source.CASES:
            code = source.words(case)
            stores = [word for word in code if word & 127 == 0x23]
            offsets = [((word >> 25) << 5) | ((word >> 7) & 31) for word in stores]
            self.assertEqual(offsets, list(range(128, 160, 4)))
            self.assertTrue(all((word >> 15) & 31 == 1 and (word >> 20) & 31 == 12
                                for word in stores))
            atomics = [word for word in code if word & 127 == 0x2f]
            self.assertEqual([word >> 27 for word in atomics],
                             [source.FUNCT5[call['operation']] for call in source.events(case)])
            self.assertEqual(code.count(source.FENCE), 4)
            self.assertEqual(code.count(source.CEASE), 4)
            branches = [(pc, word) for pc, word in enumerate(code) if word & 127 == 0x63]
            exits = [pc for pc, word in enumerate(code) if word == source.CEASE]
            self.assertEqual(len(branches), 3)
            for group, (pc, word) in enumerate(branches):
                self.assertEqual((word >> 12) & 7, 1)  # BNE group.x, selected group
                self.assertEqual(((word >> 15) & 31, (word >> 20) & 31), (2, 10))
                self.assertEqual(code[pc - 1], group << 20 | 10 << 7 | 0x13)
                displacement = ((word >> 31) << 12 | ((word >> 7) & 1) << 11 |
                                ((word >> 25) & 63) << 5 | ((word >> 8) & 15) << 1)
                self.assertEqual(pc + displacement // 4, exits[group] + 1)
            data = source.initial_kernarg(case)
            expected = bytearray(b'\x6b' * 8192)
            struct.pack_into('<8I', expected, 128, *([0xdeadbeef] * 8))
            p = source.parameters(case)
            struct.pack_into('<I', expected, p['target_offset'], p['seed'])
            self.assertEqual(data, expected)
            with self.assertRaises(ValueError):
                source.check_results(case, (0xdeadbeef,) * 8, p['seed'])
        # Literal RV32 words use the same raw word-AMO dialect as the original
        # production ioctl smoke; no checked-SSBO/image opcode substitutes it.
        self.assertEqual([word for word in source.words('global-atomic-swap-add-base')
                          if word & 127 == 0x2f][:2], [0x08d5a62f, 0x00d5a62f])

    def test_cas_winner_and_both_returns_have_independent_literal_histories(self):
        # Source order here serializes each group; other legal interleavings
        # remain allowed. The first winning CAS determines both its old word
        # and the final target. These literal words are not produced by a VM.
        final = 0xa1000000
        old = (0, 1, final, final, final, final, final, final)
        case = 'global-atomic-cas-pair-endpoint'
        self.assertEqual(source.check_results(case, old, final), tuple(range(8)))
        for index in range(8):
            bad = list(old)
            bad[index] ^= 0x80000000
            with self.assertRaises(ValueError):
                source.check_results(case, bad, final)
        # Group 3 wins while other workgroups observe its committed value.
        final = 0xa1000003
        old = (final, final, final, final, final, final, 0, 4)
        self.assertEqual(source.check_results(case, old, final)[:2], (6, 7))
        with self.assertRaises(ValueError):
            source.check_results(case, old, final ^ 1)

    def test_all_original_words_pass_the_production_gate_and_reject_bad_amos(self):
        artifacts = ROOT / '.artifacts'
        artifacts.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='global-atomic-source-', dir=artifacts) as directory:
            out = Path(directory)
            rows = []
            for index, case in enumerate(source.CASES):
                rows.append('static const uint32_t code_%u[]={%s};' %
                            (index, ','.join('0x%08xu' % word for word in source.words(case))))
            rows.append('static const struct program programs[]={%s};' % ','.join(
                '{code_%u,sizeof(code_%u)/sizeof(uint32_t)}' % (index, index)
                for index in range(len(source.CASES))))
            c = out / 'admission.c'
            c.write_text('''#include <assert.h>
#include <stdint.h>
#include <string.h>
#include "opengpu_shader_validator.h"
struct program {const uint32_t *words; unsigned count;};
''' + '\n'.join(rows) + '''
static int valid(const uint32_t *words,unsigned count,unsigned feature) {
 return opengpu_compute_shader_validate_words_features(words,count,8192,16,0,feature);
}
int main(void) {
 unsigned checked=0;
 for(unsigned p=0;p<sizeof(programs)/sizeof(programs[0]);p++) {
  const struct program *original=&programs[p]; uint32_t changed[512];
  assert(original->count<=512 && valid(original->words,original->count,1));
  assert(!valid(original->words,original->count,0));
  for(unsigned i=0;i<original->count;i++) if((original->words[i]&127)==0x2f) {
   uint32_t mutations[]={original->words[i]|(1u<<25),original->words[i]|(2u<<25),
    (original->words[i]&~(7u<<12))|(3u<<12),
    (original->words[i]&~(31u<<27))|(2u<<27),
    (original->words[i]&~(31u<<15))|(31u<<15),
    (original->words[i]&~(31u<<7))|(1u<<7)};
   for(unsigned m=0;m<sizeof(mutations)/sizeof(mutations[0]);m++) {
    memcpy(changed,original->words,original->count*sizeof(uint32_t));
    changed[i]=mutations[m]; assert(!valid(changed,original->count,1)); checked++;
   }
  }
 }
 assert(checked==24*8*6); return 0;
}
''')
            binary = out / 'admission'
            subprocess.run(shlex.split(os.environ.get('CC', 'cc')) +
                           ['-std=c11', '-Wall', '-Wextra', '-Werror', '-I', str(ROOT / 'driver'),
                            str(c), '-o', str(binary)], check=True, capture_output=True, text=True)
            subprocess.run([str(binary)], check=True, capture_output=True, text=True)
            source.write_sources(out / 'inputs')
            for case in source.CASES:
                data = (out / 'inputs' / (case + '.bin')).read_bytes()
                self.assertEqual(struct.unpack('<%uI' % (len(data) // 4), data), source.words(case))


if __name__ == '__main__':
    unittest.main()
