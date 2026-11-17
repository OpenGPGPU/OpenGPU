#!/usr/bin/env python3
"""Explicit compiler check: retain private-state assertions across hierarchy."""
import argparse
import os
from pathlib import Path
import resource
import shutil
import signal
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from owner_process_lifetime import CleanupIncomplete
from run_owner_bounded import run_bounded

FIRRTL = '''FIRRTL version 4.0.0
circuit InlineAssertionTop:
  layer Verification, bind:
    layer Assert, bind:
  module InlineAssertionChild:
    input clock: Clock
    input active: UInt<1>
    input okay: UInt<1>
    output value: UInt<1>
    reg previous: UInt<1>, clock
    connect previous, okay
    connect value, previous
    layerblock Verification:
      layerblock Assert:
        assert(clock, okay, active, "inline current input")
        assert(clock, eq(previous, okay), active, "inline private state")
  public module InlineAssertionTop:
    input clock: Clock
    input active: UInt<1>
    input okay: UInt<1>
    output value: UInt<1>
    inst child of InlineAssertionChild
    connect child.clock, clock
    connect child.active, active
    connect child.okay, okay
    connect value, child.value
'''

DRIVER = '''#include "VInlineAssertionTop.h"
#include "verilated.h"
#include <string>
int main(int argc, char **argv) {
  VerilatedContext context; context.commandArgs(argc, argv);
  VInlineAssertionTop dut(&context);
  auto tick = [&]() { dut.clock=0;dut.eval();dut.clock=1;dut.eval(); };
  const std::string mode=argc>1 ? argv[1] : "healthy";
  dut.active=0;dut.okay=mode=="private" ? 0 : 1;tick();tick();
  dut.active=1;dut.okay=1;tick();
  if (mode=="private") return 7; // The private-state assertion must stop first.
  if (dut.value!=1) return 2;
  if (mode=="input") { dut.okay=0;tick();return 7; }
  dut.final();return 0;
}
'''


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--firtool', required=True)
    parser.add_argument('--verilator', default='verilator')
    parser.add_argument('--cxx', default='c++')
    args = parser.parse_args()
    for name in ('firtool', 'verilator', 'cxx'):
        path = shutil.which(getattr(args, name))
        require(path is not None, 'Missing compiler: ' + getattr(args, name))
        setattr(args, name, str(Path(path).resolve()))
    os.environ['CXX'] = args.cxx
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    parent = ROOT / '.artifacts'
    parent.mkdir(exist_ok=True)
    out = Path(tempfile.mkdtemp(prefix='owner-inline-assertions-', dir=parent))
    cleanup_known = True
    def interrupted(signum, _frame):
        raise InterruptedError('assertion qualification interrupted by signal ' + str(signum))
    previous = {sig: signal.signal(sig, interrupted) for sig in (signal.SIGINT, signal.SIGTERM)}

    def run(name, command):
        path = out / (name + '.log')
        with path.open('w') as log:
            sys.stdout.flush()
            sys.stderr.flush()
            saved = os.dup(1), os.dup(2)
            try:
                os.dup2(log.fileno(), 1)
                os.dup2(log.fileno(), 2)
                code = run_bounded(command, 2048, 120)
            finally:
                sys.stdout.flush()
                sys.stderr.flush()
                os.dup2(saved[0], 1)
                os.dup2(saved[1], 2)
                for fd in saved:
                    os.close(fd)
        return code, path.read_text(errors='replace')

    try:
        (out / 'input.fir').write_text(FIRRTL)
        (out / 'driver.cpp').write_text(DRIVER)
        control = out / 'hierarchy.vlt'
        control.write_text(chr(96) + 'verilator_config\nhier_block -module "InlineAssertionChild"\n')
        for mode, flags in (('bound', []),
                ('inline', ['--enable-layers=Verification,Verification.Assert'])):
            target = out / mode
            target.mkdir()
            code, text = run(mode + '-firtool', [args.firtool, str(out / 'input.fir'),
                '--split-verilog', '-o', str(target), *flags])
            require(code == 0, text[-6000:])
            code, text = run(mode + '-build', [args.verilator, '--cc', '--exe', '--build',
                '--assert', '--hierarchical', str(control), '--build-jobs', '1',
                '--output-groups', '0', '--top-module', 'InlineAssertionTop',
                '--Mdir', str(target / 'model'), '-Wno-fatal',
                '-CFLAGS', '-std=c++17 -O1 -g0', '-I' + str(target),
                str(out / 'driver.cpp'), *map(str, sorted(target.glob('*.sv')))])
            if mode == 'bound':
                require(code != 0 and 'Cannot access non-port symbols' in text,
                    'Bound private-state checks unexpectedly accepted hierarchy:\n' + text[-6000:])
                continue
            require(code == 0, text[-6000:])
            executable = str(target / 'model/VInlineAssertionTop')
            code, text = run('healthy', [executable])
            require(code == 0 and 'Assertion failed' not in text, text[-6000:])
            for case, message in (('input', 'inline current input'),
                                  ('private', 'inline private state')):
                code, text = run(case, [executable, case])
                require(code not in (0, 7) and 'Assertion failed' in text and message in text,
                    'Actual assertion did not reject ' + case + ':\n' + text[-6000:])
        print('PASS: bound hierarchy rejection; inline build; healthy execution; '
              'two independent actual assertion failures')
    except CleanupIncomplete:
        cleanup_known = False
        print('Consumer exit is unproven; preserve inputs for diagnosis: ' + str(out), file=sys.stderr)
        raise
    finally:
        if cleanup_known:
            shutil.rmtree(out)
        for sig, handler in previous.items():
            signal.signal(sig, handler)


if __name__ == '__main__':
    main()
