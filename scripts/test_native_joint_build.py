#!/usr/bin/env python3
"""Opt-in actual linked Mesa originals -> protected joint BUILD/MMU CPU proof.

Requires the original read-only Mesa/SDK setup and explicit private output and
baseline arguments of test_native_joint.py. Never executes ISA or submits work.
"""
import test_native_joint as original

KERNEL='userspace/tests/native_joint_build_kernel.c'

def domain_cases(sources,generated):
    cases=[]
    for name,domain,sample in (('triangle8','triangles',False),('quad9','quads',True)):
        for suffix in ('vert','tesc','tese','frag'):
            text=(sources/('full9.'+suffix if sample else 'full8.'+suffix)).read_text()
            if suffix=='tese':text=text.replace('layout(isolines,equal_spacing)',f'layout({domain},equal_spacing,ccw)')
            generated(sources/(name+'.'+suffix),text)
        cases.append((name,[sources/(name+'.'+s) for s in ('vert','tesc','tese','frag')],1,1 if sample else 15,15 if sample else 0))
    return cases

def build_negative_controls(mutant,sources,out):
    mutant('mutant-build-wrong-vs-uniform-stage','userspace/gallium/opengpu_native_joint_build_source.c',
        'p->vertex_uniforms=s->program.uniforms[0];','p->vertex_uniforms=s->program.uniforms[3];',
        [*[sources/('full8.'+s) for s in ('vert','tesc','tese','frag')],out/'mutant-vs-gur',1,15,0],
        'graph_read(vm,data+n,3)==expected')
    mutant('mutant-build-no-source-retain','userspace/gallium/opengpu_native_joint_program.c',
        'if(!s || s->refs==~0u){errno=EINVAL;return NULL;}s->refs++;return s;',
        'if(!s || s->refs==~0u){errno=EINVAL;return NULL;}return s;',
        [*[sources/('full8.'+s) for s in ('vert','tesc','tese','frag')],out/'mutant-source-retain',1,15,0],
        'heap-use-after-free',expected=1)
    mutant('mutant-build-retag-domain','userspace/gallium/opengpu_native_joint_build_source.c',
        'case PIPE_PRIM_TRIANGLES:domain=1;', 'case PIPE_PRIM_TRIANGLES:domain=0;',
        [*[sources/('triangle8.'+s) for s in ('vert','tesc','tese','frag')],out/'mutant-domain',1,15,0],
        'd.domain==expected_domain')

def main():
    original.GPU_UNITS=tuple('userspace/tests/native_joint_build_flow.c' if u=='userspace/tests/native_joint_resources.c' else u
                            for u in original.GPU_UNITS)+(
        'userspace/gallium/opengpu_native_joint_build_source.c','userspace/tests/native_joint_build_link.cpp',KERNEL)
    original.GPU_UNIT_FLAGS={KERNEL:['-I'+str(original.ROOT/'driver/tests'/name) for name in ('private_snapshot_stubs','mmu_stubs','compute_submit_stubs')]}
    source=(original.ROOT/'driver/opengpu_hw.c').read_text()
    first=source.index('int opengpu_hw_snapshot_submission_lock(')
    last=source.index('\n}\n',first)+2
    original.GENERATED_INPUTS={'snapshot_submission_under_test.inc':source[first:last]}
    original.__doc__=__doc__
    original.EXTRA_CASES=domain_cases
    original.EXTRA_NEGATIVE_CONTROLS=build_negative_controls
    original.main()
    print('NATIVE_JOINT_BUILD_CPU PASS linkedCases8 domains3 original52uniformViews/4code/input/index/scratch/frame; normal+zeroWorkInvocation+noInvocation graphs; independentSourceControls3; ISA=false submit=false')

if __name__=='__main__':main()
