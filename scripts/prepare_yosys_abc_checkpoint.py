#!/usr/bin/env python3
"""Derive pre-ABC checkpoint scripts from the supported pinned Yosys flow.

These scripts preserve a mapping checkpoint; they do not verify that a resumed
run uses the same design, constraints, libraries, environment or tool image.
Those inputs must be verified independently before resuming.
"""
import argparse
import hashlib
import json
from pathlib import Path


# Exact scripts/synth.tcl in the OpenROAD image used by the qualified control.
SUPPORTED_SHA256 = '4725557a1a74a1ea6495140db1142a5cfe84f4b46ba7b6cfb20835935e611319'
SETUNDEF = 'setundef -zero\n'
ABC = ('if {\n  ![env_var_exists_and_non_empty SYNTH_WRAPPED_OPERATORS] &&\n'
       '  ![env_var_exists_and_non_empty SWAP_ARITH_OPERATORS]\n} {\n  log_cmd abc {*}$abc_args\n')
GUARD = '''
if { [env_var_exists_and_non_empty SYNTH_WRAPPED_OPERATORS] ||
     [env_var_exists_and_non_empty SWAP_ARITH_OPERATORS] } {
  error "pre-ABC checkpoints require the standard ABC flow"
}
'''
SAVE = GUARD + '''
set checkpoint_file "$::env(RESULTS_DIR)/pre_abc.rtlil"
if { [file exists $checkpoint_file] || [file exists "$checkpoint_file.sha256"] ||
     [file exists "$checkpoint_file.sha256.tmp"] } {
  error "pre-ABC checkpoint already exists or is incomplete"
}
write_rtlil $checkpoint_file
set checksum [lindex [exec sha256sum $checkpoint_file] 0]
set checksum_file [open "$checkpoint_file.sha256.tmp" {WRONLY CREAT EXCL}]
puts $checksum_file $checksum
close $checksum_file
file rename "$checkpoint_file.sha256.tmp" "$checkpoint_file.sha256"
puts "OPENGPU_PRE_ABC_COMPLETE $checksum"
'''
RESUME = '''source $::env(SCRIPTS_DIR)/synth_preamble.tcl
''' + GUARD + '''
set checkpoint_file "$::env(RESULTS_DIR)/pre_abc.rtlil"
if { ![file isfile $checkpoint_file] || ![file isfile "$checkpoint_file.sha256"] ||
     [file exists "$checkpoint_file.sha256.tmp"] } {
  error "missing or incomplete pre-ABC checkpoint"
}
set checksum_file [open "$checkpoint_file.sha256" r]
set expected [string trim [read $checksum_file]]
close $checksum_file
if { ![regexp {^[0-9a-f]{64}$} $expected] } { error "invalid pre-ABC checksum" }
set actual [lindex [exec sha256sum $checkpoint_file] 0]
if { $expected ne $actual } { error "pre-ABC checkpoint checksum mismatch" }
read_checkpoint $checkpoint_file
'''


def sha(data):
    return hashlib.sha256(data).hexdigest()


def derive(source):
    if sha(source) != SUPPORTED_SHA256:
        raise ValueError('Unsupported synthesis script SHA-256')
    text = source.decode('utf-8')
    if text.count(SETUNDEF) != 1 or text.count(ABC) != 1:
        raise ValueError('Unexpected synthesis checkpoint boundary')
    start = text.index(SETUNDEF) + len(SETUNDEF)
    end = text.index(ABC)
    if start > end or text[start:end].strip():
        raise ValueError('Unexpected commands before ABC')
    if 'check -assert -mapped\n' not in text[end:] or 'write_verilog ' not in text[end:]:
        raise ValueError('Missing mapped-design validation or netlist output')
    checkpoint = (text[:start] + SAVE + text[start:]).encode('utf-8')
    resume = (RESUME + text[end:]).encode('utf-8')
    return checkpoint, resume


def prepare(source, output):
    original = Path(source).read_bytes()
    checkpoint, resume = derive(original)
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    (output / 'checkpoint-synth.tcl').write_bytes(checkpoint)
    (output / 'resume-synth.tcl').write_bytes(resume)
    manifest = dict(original_sha256=sha(original), checkpoint_script_sha256=sha(checkpoint),
                    resume_script_sha256=sha(resume), physical_qualified=False,
                    scope='Script derivation only; independently verify all run inputs before resume')
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--synth-script', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(prepare(args.synth_script, args.output), indent=2))


if __name__ == '__main__':
    main()
