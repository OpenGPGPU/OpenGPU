"""CPU protocol and original C source controls; never forced-DUT evidence."""
import argparse
from contextlib import redirect_stdout
import io
import json
import os
from pathlib import Path
import resource
import shlex
import struct
import subprocess
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock,patch
import compute_reservation_wait as contract

ROOT=Path(__file__).resolve().parents[1]


def blocked(index):
    p=contract.phase(index)
    return dict(schema=contract.BLOCKED_SCHEMA,sourceCase=p['case'],cachedKernarg=p['cached'],
        producerKind='external-cpu',executionAttemptStatus=-16,context=1,handle=2,objects=1,
        dependencies=1,pendingDependencies=1,reservationDependencies=1,producerDependencies=1,
        reservationUsage=1,queued=1,kernelSubmissions=0,blockedAttempts=1,originalIdentity=True,
        producerStatus=0,consumerStatus=0,producerSequence=1,consumerSequence=1,bytes=8192,
        producerContext=3,physical=p['base']+32768)


def released(index):
    return dict(blocked(index),schema=contract.RELEASED_SCHEMA,pendingDependencies=0,producerStatus=1)


def idle(index):
    b=blocked(index)
    return dict(schema=contract.IDLE_SCHEMA,phase=index,cachedKernarg=contract.phase(index)['cached'],
        producerKind='external-cpu',physical=b['physical'],producerContext=b['producerContext'],
        producerSequence=1,consumerSequence=1,context=1,bytes=8192,resetCount=1,ramUploadedBeforeRelease=False,
        readyBefore=True,readyAfter=True,irqAsserted=False,externalRequests=0,startCycle=7,endCycle=264,quietCycles=257)


def source_files(local,index):
    from compute_global_atomic_source import SCHEMA,CACHED_SCHEMA
    from compute_global_atomic_cases import bound_words
    p=contract.phase(index)
    (local/'reservation.blocked.json').write_text(json.dumps(blocked(index)))
    (local/'oracle.json').write_text(json.dumps(dict(schema=CACHED_SCHEMA if p['cached'] else SCHEMA,
        sourceCase=p['case'],context=1,kernarg=dict(handle=2,allocationPhysical=blocked(index)['physical'],
        allocationBytes=8192,offset=0,bytes=8192))))
    (local/'code.words').write_text(''.join('%08x\n'%w for w in bound_words(p['case'])))


class Peer:
    def __init__(self):self.code=None
    def poll(self):return self.code
    def terminate(self):self.code=-15
    def wait(self,timeout=None):return self.code


class ReservationWait(unittest.TestCase):
    def test_all_original_programs_both_policies_and_strict_source_commands(self):
        self.assertEqual(len(contract.PHASES),48)
        for index,p in enumerate(contract.PHASES):
            self.assertEqual(p['case'],contract.CASES[index//2]);self.assertIs(p['cached'],bool(index%2))
            command=contract.source_launch(index,Path('/build'),Path('/ipc'),Path('/profile'))
            contract.check_source_launch(command,index,Path('/build'),Path('/ipc'),Path('/profile'))
            self.assertIn('--reservation-wait',command)
            self.assertEqual('--cached-kernarg' in command,p['cached'])
            for n in range(len(command)):
                bad=list(command);bad[n]='substituted'
                with self.assertRaises(ValueError):contract.check_source_launch(bad,index,Path('/build'),Path('/ipc'),Path('/profile'))
            wire=contract.plan(blocked(index),index)
            self.assertEqual(struct.unpack('<8sIIIIQQQQQQ',wire),(b'OGCRWP01',index,index%2,257,0,
                blocked(index)['physical'],3,1,1,1,8192))
        for index in (-1,True,48):
            with self.assertRaises(ValueError):contract.phase(index)

    def test_every_source_identity_dependency_and_release_field_is_required(self):
        for index in (0,1,46,47):
            b=blocked(index);r=released(index)
            self.assertIs(contract.checked_source(b,index),b)
            self.assertIs(contract.checked_source(r,index,released=True,previous=b),r)
            for record,kwargs in ((b,{}),(r,dict(released=True,previous=b))):
                for key in record:
                    bad=dict(record);del bad[key]
                    with self.assertRaises(ValueError):contract.checked_source(bad,index,**kwargs)
                    with self.assertRaises(ValueError):contract.checked_source(dict(record,**{key:None}),index,**kwargs)
                    if type(record[key]) in (int,bool):
                        with self.assertRaises(ValueError):contract.checked_source(dict(record,**{key:str(record[key])}),index,**kwargs)
                with self.assertRaises(ValueError):contract.checked_source(dict(record,foreign=True),index,**kwargs)
            for change in ({'dependencies':2},{'pendingDependencies':0},{'producerDependencies':0},
                    {'reservationDependencies':0},{'reservationUsage':2},{'executionAttemptStatus':0},
                    {'kernelSubmissions':1},{'blockedAttempts':0},{'originalIdentity':False},
                    {'producerKind':'gpu'},{'physical':b['physical']+1}):
                with self.assertRaises(ValueError):contract.checked_source(dict(b,**change),index)
            for key in ('producerContext','physical'):
                with self.assertRaises(ValueError):contract.checked_source(dict(r,**{key:r[key]+4096}),index,released=True,previous=b)
            with self.assertRaises(ValueError):contract.checked_source(r,index,released=True)

    def test_native_idle_needs_exact_identity_quiet_window_and_no_early_publication(self):
        for index in (0,1,46,47):
            b=blocked(index);record=idle(index)
            self.assertIs(contract.checked_idle(record,b,index),record)
            for key in record:
                missing=dict(record);del missing[key]
                with self.assertRaises(ValueError):contract.checked_idle(missing,b,index)
                with self.assertRaises(ValueError):contract.checked_idle(dict(record,**{key:None}),b,index)
            for change in ({'producerContext':4},{'quietCycles':256,'endCycle':263},{'startCycle':264},
                    {'resetCount':2},{'ramUploadedBeforeRelease':True},{'externalRequests':1},
                    {'irqAsserted':True},{'readyBefore':False},{'readyAfter':False}):
                with self.assertRaises(ValueError):contract.checked_idle(dict(record,**change),b,index)

    def test_controller_releases_only_after_valid_native_idle_and_preserves_source(self):
        from compute_owner_controller import atomic
        (ROOT/'.artifacts').mkdir(exist_ok=True)
        for mutation in ('none','foreign-idle','early-ready','changed-source','bad-release'):
            with tempfile.TemporaryDirectory(prefix='reservation-peer-',dir=ROOT/'.artifacts') as temporary:
                local=Path(temporary);source_files(local,0);native=Peer();bridge=Peer();calls=[]
                def launch(*args,**kwargs):
                    calls.append(args[0]);proof=idle(0)
                    if mutation=='foreign-idle':proof['externalRequests']=1
                    (local/'reservation.idle.json').write_text(json.dumps(proof))
                    if mutation=='early-ready':(local/'READY').write_bytes(b'CPU peer')
                    if mutation=='changed-source':(local/'code.words').write_text('changed')
                    return native
                def publish(path,data):
                    atomic(path,data)
                    if path.name=='RELEASE_RESERVATION':
                        value=released(0)
                        if mutation=='bad-release':value['consumerStatus']=1
                        (local/'reservation.released.json').write_text(json.dumps(value))
                        (local/'READY').write_bytes(b'CPU peer')
                with (local/'native.log').open('w') as out,patch.object(contract.subprocess,'Popen',side_effect=launch),\
                        patch('compute_owner_controller.atomic',side_effect=publish):
                    args=(SimpleNamespace(),local,0,Path('/qualified/executor'),out,bridge,{},lambda:2)
                    if mutation=='none':
                        process,frozen=contract.begin_owner(*args);self.assertIs(process,native)
                        self.assertIn('reservation.released.json',frozen)
                    else:
                        with self.assertRaises(ValueError):contract.begin_owner(*args)
                        self.assertIsNotNone(native.poll())
                self.assertEqual(len(calls),1)
                self.assertEqual((local/'RELEASE_RESERVATION').exists(),mutation in ('none','bad-release'))
                self.assertFalse((local/'BEGIN_CLEANUP').exists())

    def test_modes_are_distinct_and_outer_command_carries_the_original_source(self):
        import qualify_compute_owner as q
        args=SimpleNamespace(timeout=14400,reservation_matrix=True,global_inputs=ROOT/'.artifacts/request.json')
        def command():return q.producer_command(args,gpu=ROOT,source=ROOT,ntt=None,image='sha256:'+'c'*64,
            name='original-reservation-caller',case=contract.CASES[0])
        self.assertEqual(command()[-1],'--reservation-matrix')
        self.assertNotIn('--global-atomic-matrix',command())
        for key in contract.MODE_FLAGS:
            setattr(args,key,True)
            with self.assertRaises(ValueError):command()
            setattr(args,key,False)

    def test_original_producer_routes_every_case_and_policy_to_the_distinct_live_c_flag(self):
        import compute_global_host_producer as producer
        (ROOT/'.artifacts').mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='reservation-source-',dir=ROOT/'.artifacts') as temporary:
            out=Path(temporary);wire=out/'profile.bin';wire.write_bytes(b'OGCP0003')
            def build(path,**kwargs):path.mkdir();return path/'compute_global_host'
            argv=['producer','--build',str(out/'build'),'--profile-wire',str(wire),'--session',str(out/'ipc'),
                '--bound-inputs',str(out/'request.json'),'--case',contract.CASES[0],'--sanitize','--reservation-matrix']
            with patch.object(sys,'argv',argv),patch.object(producer,'build',side_effect=build),\
                    patch('compute_global_host_inputs.Inputs',return_value=Mock()),\
                    patch.object(producer.subprocess,'run',return_value=SimpleNamespace(returncode=0)) as run,\
                    redirect_stdout(io.StringIO()):producer.main()
            self.assertEqual(len(run.call_args_list),48)
            for index,call in enumerate(run.call_args_list):
                contract.check_source_launch(call.args[0],index,out/'build',out/'ipc',out/'build/inputs/profile.bin')
            for extra in ('--capture-only','--global-atomic-matrix','--cached-kernarg',
                    '--cache-alias-source','--uc-cache-alias-matrix'):
                with patch.object(sys,'argv',[*argv,extra]),patch('sys.stderr',io.StringIO()),self.assertRaises(SystemExit):
                    producer.main()

    def test_execution_and_cleanup_authorizations_retain_phase_clock_and_zero_faults(self):
        for index in (0,1,46,47):
            self.assertEqual(struct.unpack('<8sI',contract.execution_authorization(index)),(b'OGCRGO01',index))
            observed=idle(index)
            done=dict(schema='opengpu-compute-reservation-done-v1',phase=index,resetCount=1,
                readyAfterCleanup=True,irqAsserted=False,externalDrained=True,vectorSatp=0,instructionSatp=0,
                physicalReadFaults=0,physicalWriteFaults=0,startCycle=observed['startCycle'],endCycle=300)
            self.assertIs(contract.checked_done(done,observed,index),done)
            for key in done:
                missing=dict(done);del missing[key]
                with self.assertRaises(ValueError):contract.checked_done(missing,observed,index)
                with self.assertRaises(ValueError):contract.checked_done(dict(done,**{key:None}),observed,index)
            for change in ({'resetCount':2},{'startCycle':0},{'endCycle':observed['endCycle']},
                    {'vectorSatp':1},{'instructionSatp':1},{'physicalReadFaults':1},{'physicalWriteFaults':1}):
                with self.assertRaises(ValueError):contract.checked_done(dict(done,**change),observed,index)

    def test_sanitized_native_idle_helper_checks_every_cycle_even_without_handshakes(self):
        code=r'''
#include <cassert>
#include <cstdint>
#include <stdexcept>
#include <utility>
constexpr uint32_t GPU_REG_UCMD_STATUS=1,GPU_UCMD_STATUS_READY=1;
namespace compute_owner {
void require(bool ok,const char *text){if(!ok)throw std::runtime_error(text);}
void traffic_unchanged(unsigned before,unsigned after){require(before==after,"traffic changed");}
}
#include "compute_reservation_idle.hpp"
struct Model {bool io_m_irq=false,io_m_axi_arvalid=false,io_m_axi_awvalid=false,io_m_axi_wvalid=false;};
struct Sim {Model state;Model *model=&state;};
struct Agent {unsigned traffic=0;bool pending=false;bool drained()const{return !pending;}};
struct Bus {
 Sim &sim;Agent &agent;uint64_t cycles=0;unsigned reads=0,at=0,field=0;bool before=true,after=true;
 uint32_t read(uint32_t address){assert(address==1);return (++reads==1?before:after)?1:0;}
 template<class F> void step(F observe){++cycles;if(cycles==at){
  if(field==0)sim.state.io_m_irq=true;if(field==1)sim.state.io_m_axi_arvalid=true;
  if(field==2)sim.state.io_m_axi_awvalid=true;if(field==3)sim.state.io_m_axi_wvalid=true;
  if(field==4)++agent.traffic;if(field==5)agent.pending=true;}
  observe(sim.state);}
};
int main(){
 {Sim sim;Agent agent;Bus bus{sim,agent};auto observed=compute_owner::reservation_idle(sim,agent,bus);
  assert(observed.first==0 && observed.second==257 && bus.cycles==257);}
 for(unsigned field=0;field<6;++field)for(unsigned at:{1u,128u,257u}){
  Sim sim;Agent agent;Bus bus{sim,agent};bus.at=at;bus.field=field;bool rejected=false;
  try{compute_owner::reservation_idle(sim,agent,bus);}catch(const std::runtime_error&){rejected=true;}assert(rejected);}
 for(unsigned n=0;n<2;++n){Sim sim;Agent agent;Bus bus{sim,agent};if(n)bus.after=false;else bus.before=false;
  bool rejected=false;try{compute_owner::reservation_idle(sim,agent,bus);}catch(const std::runtime_error&){rejected=true;}assert(rejected);}
}
'''
        (ROOT/'.artifacts').mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='reservation-idle-',dir=ROOT/'.artifacts') as temporary:
            out=Path(temporary);unit=out/'test.cpp';unit.write_text(code)
            command=shlex.split(os.environ.get('CXX','c++'))+['-std=c++17','-O1','-Wall','-Wextra','-Werror',
                '-fsanitize=address,undefined','-fno-omit-frame-pointer','-I'+str(ROOT/'scripts'),str(unit),'-o',str(out/'test')]
            done=subprocess.run(command,capture_output=True,text=True);self.assertEqual(done.returncode,0,done.stderr)
            done=subprocess.run([str(out/'test')],capture_output=True,text=True);self.assertEqual(done.returncode,0,done.stderr)


def native_source_controls(build,wire,profile,request):
    """Actual original C caller, deliberately no DUT or GPU success receipt."""
    from compute_global_host_inputs import Inputs
    from compute_owner_controller import ready_inputs
    from test_compute_global_atomic_source import check_capture
    guard=Inputs(request,ROOT);commands=json.loads((build/'commands.json').read_text())
    guard.prepare(build,[(c['label'],c['argv']) for c in commands[:-1]],commands[-1]['argv'])
    guard.before_link();guard.built(commands,build/'compute_global_host')
    resource.setrlimit(resource.RLIMIT_CORE,(0,0));session=build/'native-reservation-controls';session.mkdir()
    count=0
    for index in (0,1,46,47):
        local=session/('round-'+str(index));local.mkdir()
        with (local/'source.log').open('w') as log:
            child=subprocess.Popen(contract.source_launch(index,build,session,wire),stdout=log,stderr=subprocess.STDOUT,
                env=dict(os.environ,OPENGPU_COMPUTE_HOST_TIMEOUT='30'))
            try:
                deadline=time.monotonic()+20
                while not (local/'reservation.blocked.json').exists():
                    assert child.poll() is None,'original reservation C caller exited before blocking: '+str(local)
                    assert time.monotonic()<deadline;time.sleep(.01)
                blocked_record,frozen=contract.source_inputs(local,index)
                for name in ('READY','union.memory','record-0.capture','reservation.released.json'):
                    assert not (local/name).exists(),'original C consumer advanced before producer release: '+name
                # CPU-only source authorization exercises the C transition;
                # the actual controller requires a native idle certificate.
                (local/'RELEASE_RESERVATION').write_bytes(struct.pack('<8sI',b'OGCRRL01',1))
                while not (local/'READY').exists():
                    assert child.poll() is None,'original C source rejected its CPU control release'
                    assert time.monotonic()<deadline;time.sleep(.01)
                contract.checked_source(json.loads((local/'reservation.released.json').read_text()),index,
                    released=True,previous=blocked_record)
                count+=check_capture(local,profile,cached=contract.phase(index)['cached'])
                from owner_resource_contract import IMAGE_FLOAT_PROFILE
                ready_inputs(local,contract.phase(index)['case'],owner_profile=IMAGE_FLOAT_PROFILE,
                    profile=profile,global_cached=contract.phase(index)['cached'])
                from compute_owner_controller import unchanged_inputs
                unchanged_inputs(local,frozen)
                for name in ('record-0.held.response','record-0.retire','cleanup_wait','BEGIN_CLEANUP','FINAL_RESPONSE'):
                    assert not (local/name).exists(),'original C source advanced without actual DUT receipts'
                guard.unchanged(full=True)
            finally:
                if child.poll() is None:child.terminate()
                child.wait(timeout=10)
    assert count==74,count
    invalid=(('--reservation-wait','--capture-only'),('--reservation-wait','--reservation-wait'),
        ('--reservation-wait','--expect-global-fault','read'),
        ('--reservation-wait','--cached-kernarg','--cache-alias-source','--cache-alias-live'),
        ('--reservation-wait','--cached-kernarg','--uc-cache-alias-source','--uc-cache-alias-live'),
        ('--reservation-wait','--expect-cache-alias-write-error'),
        ('--reservation-wait','--expect-uc-cache-alias-write-error'),
        ('--reservation-wait','--cached-kernarg','--cached-kernarg'),('--reservation-wait','--unknown'))
    for index,options in enumerate(invalid):
        local=session/('rejected-'+str(index));local.mkdir()
        command=contract.source_launch(0,build,session,wire)[:6];command[1]=str(local)
        result=subprocess.run([*command,*options],stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,timeout=10)
        assert result.returncode and b'Assertion' in result.stderr,(options,result.stderr)
        assert not any((local/name).exists() for name in ('READY','reservation.blocked.json'))
    guard.unchanged(full=True)
    print('original C reservation source: 4 blocked/released READY controls, 74 source negatives and 9 mixed-option rejections PASS; CPU only, no native idle or shader execution',flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--native-source-build',type=Path);parser.add_argument('--profile-wire',type=Path)
    parser.add_argument('--profile-json',type=Path);parser.add_argument('--bound-inputs',type=Path)
    args=parser.parse_args()
    if args.native_source_build is None:unittest.main(argv=[sys.argv[0]])
    else:
        assert all((args.profile_wire,args.profile_json,args.bound_inputs))
        native_source_controls(args.native_source_build.resolve(strict=True),args.profile_wire.resolve(strict=True),
            json.loads(args.profile_json.read_text()),args.bound_inputs.resolve(strict=True))
