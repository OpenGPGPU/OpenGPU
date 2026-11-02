# Opt-in system image atomics

`GpuSystem(imageAvailable = true, imageAtomicAvailable = true)` passes the
internal image-atomic option to its CUs. It requires image execution and at
least eight transaction IDs per CU. The existing image task, VM and completion
leases remain in force. The default is disabled, and no native host or public
capability enables or advertises this option.

Two small local system cases pass with two four-lane, two-warp CUs and no
rendered frame, using bare and Sv32 addresses. A direct compute command launches
two four-invocation workgroups that add three to the same R32UI word through the
real shared L2. Both CUs are
observed busy together; all eight acknowledged writes complete, changing the
word from 10 to 34 while preserving surrounding bytes. The command completion
and image lease are held and checked before release. Host compute dispatch
intentionally clears graphics helper policy, so all eight compute lanes execute;
helper suppression is checked separately in the CU tests.
The Sv32 case then switches page-table roots at a quiescent boundary, checks
that a read-only atomic mapping traps without a write, and verifies a later
command succeeds under a writable mapping. These system cases run locally.

```sh
sbt -J-Xmx5G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 -Dopengpu.simHierarchy=GpuComputeUnit \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.GpuSystemImageAtomicSpec'
```

Instruction, descriptor and lower memory are modeled, including the Sv32 page
tables. The tests prove these bare and translated results and overlapping CU
activity; they do not observe whether both atomic requests were offered in the
same cycle. They do not establish a complete system-level fault matrix, graphics
admission, concurrent counter instructions,
cross-domain ordering, compiler/API support, full capacity or physical
qualification. H7 and the broader mobile GPU objective remain open.
