#!/usr/bin/env python3
"""Check the actual compiled Verilator runtime's zero and 64-bit context clocks."""
import argparse
import copy
from pathlib import Path
import shutil
import signal
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
import prepare_owner_verilator_executor as owner
from owner_process_lifetime import CleanupIncomplete

DRIVER = '''#include "verilated.h"
#include <stdexcept>
#include <string>
[[gnu::noinline]] static void throw_for_check() { throw std::runtime_error("clock unwind"); }
int main() {
  VerilatedContext first, second;
  Verilated::threadContextp(&first);
  if (first.time()!=0 || VL_TIME_Q()!=0) return 1;
  first.timeInc(5);
  if (first.time()!=5 || VL_TIME_Q()!=5) return 2;
  first.timeInc(1ULL<<60);
  if (first.time()!=((1ULL<<60)+5) || VL_TIME_Q()!=((1ULL<<60)+5)) return 3;
  Verilated::threadContextp(&second);
  if (second.time()!=0 || VL_TIME_Q()!=0) return 4;
  second.timeInc(7);
  if (second.time()!=7 || VL_TIME_Q()!=7) return 5;
  Verilated::threadContextp(&first);
  if (VL_TIME_Q()!=((1ULL<<60)+5)) return 6;
  first.time(0);
  if (first.time()!=0 || VL_TIME_Q()!=0) return 7;
  try { throw_for_check(); return 8; }
  catch (const std::runtime_error& error) {
    if (std::string(error.what())!="clock unwind") return 9;
  }
  return 0;
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--owner-output', required=True, type=Path)
    args = parser.parse_args()
    directory = args.owner_output.resolve(strict=True)
    bound = owner.context(directory, 'native-sample')
    temporary = Path(tempfile.mkdtemp(prefix='owner-clock-check-', dir=ROOT / '.artifacts'))
    cleanup_known = True
    def interrupted(signum, _frame):
        raise InterruptedError('Clock check interrupted by signal ' + str(signum))
    previous = {sig: signal.signal(sig, interrupted) for sig in (signal.SIGINT, signal.SIGTERM)}
    try:
        source = temporary / 'clock.cpp'
        source.write_text(DRIVER)
        for mode in ('healthy', 'wrong-initial-time'):
            target = temporary / mode
            target.mkdir()
            selected = copy.deepcopy(bound)
            selected['driver'] = str(source)
            if mode != 'healthy':
                bad = temporary / 'wrong-time.hpp'
                bad.write_text('double sc_time_stamp() { return 1.0; }\n')
                index = selected['flags'].index('-include') + 1
                selected['flags'][index] = str(bad)
            compile_command, link_command = owner.commands(selected, target)
            owner.execute(target / 'compile.log', compile_command, 2048, 120)
            owner.execute(target / 'link.log', link_command, 2048, 120)
            owner.execute(target / 'execute.log', [str(target / 'executor')], 2048, 120,
                          expected_exit=0 if mode == 'healthy' else 1)
        owner.require(owner.context(directory, 'native-sample') == bound,
                      'Real model/runtime or clock-check inputs changed')
        print('PASS: actual runtime zero, 64-bit advance, two independent contexts, exception unwind; '
              'wrong initial clock rejected', flush=True)
    except CleanupIncomplete:
        cleanup_known = False
        print('Consumer exit unproven; preserve clock check: ' + str(temporary), file=sys.stderr)
        raise
    finally:
        if cleanup_known:
            shutil.rmtree(temporary)
        for sig, handler in previous.items():
            signal.signal(sig, handler)


if __name__ == '__main__':
    main()
