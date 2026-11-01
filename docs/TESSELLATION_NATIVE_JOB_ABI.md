# Draft tessellation job ABI

GNQ1 is a 512-byte little-endian job record with the same field locations and
capacity header as GNJ1, but distinct magic `0x474e5101`. Its frame reference
uses GNF1 and its draw array contains GNT1 records. The generated header and
schema describe the exact 14 fields, including the 33-bit exclusive limits;
unassigned words and unused high bits must be zero. The consuming job reader
also requires the GNT1 array bounds and stride to be 64-byte aligned.

The generated files are draft descriptions of an internal interface. They do
not advertise a host command or hardware capability, or establish a driver,
compiler, alias-provenance or product qualification contract. The generated
header and schema match the hardware layout; the C11 compile and CI selection
checks pass.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 \
  'runMain opengpu.elaboration.EmitTessellationNativeJobAbi --check' \
  'testOnly opengpu.system.TessellationNativeJobAbiSpec'
cc -std=c11 -Wall -Wextra -Werror -fsyntax-only driver/tests/opengpu_tessellation_job_abi_test.c
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```
