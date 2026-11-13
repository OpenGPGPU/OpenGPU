#!/usr/bin/env python3
"""Prepare/generate the full 96-word owner DUT; execution qualification is separate."""
import argparse,json,os,re,subprocess,sys,tempfile
from pathlib import Path
sys.dont_write_bytecode=True
import qualify_native_product_compiled as common
import qualify_native_product_flashsim as flash
ROOT=Path(__file__).resolve().parents[1]
PROFILE='two-cu-vga60-owner-v1'
EXPECTED_BLOCKS=(
    (0x360,(0x47435201,16,0x3ffff,2,16,8,128,128,0xffffffff,16384,16,8,0x10000000,32,131072,4)),
    (0x160,(0x474e4303,8,0x474e4a01,0x08000200,0xc2100800,0x08000800,0xe4908000,0x8813d902)),
    (0x340,(0x47434901,8,0x7f,14,12,4,0xffffffff,128)),
    (0x240,(0x47515601,8,0x0b0a0908,0x47514f01,64,8,0x01001001,31)),
    (0x260,(0x47464301,8,0x1003010c,0x47544601,512,0x04400408,69,255)),
    (0x280,(0x47515001,8,0x0010040d,0x47504f01,64,0x00204008,0x8c88,63)),
    (0x3a0,(0x47554301,16,1,0x7f,3,12,24,16384,1024,1024,256,64,0x0e00000b,0x1c00002b,0x1e00002b,31)),
    (0x4c0,(0x47414201,8,8,0x7fff,0xf00001ff,15,7,0)),
    (0x4e0,(0x474e5001,8,512,2048,2048,512,15,0)),
    (0x300,(0x47534c01,8,31,32,1048576,0x04051918,0x320,0x138)))
EXPECTED_WORDS=[{'address':base+4*n,'value':value} for base,words in EXPECTED_BLOCKS for n,value in enumerate(words)]

def source_inputs():
    return common.rtl_inputs() | {str(p.relative_to(ROOT)):common.sha(p) for p in (
        ROOT/'scripts/prepare_native_owner_flashsim.py',ROOT/'scripts/qualify_native_product_compiled.py',
        ROOT/'scripts/qualify_native_product_flashsim.py')}

def check_manifest(manifest):
    if type(manifest) is not dict:raise RuntimeError('Owner manifest must be an object')
    required={'version':1,'profile':PROFILE,'computeUnits':2,'frameWidth':640,'frameHeight':480,
        'lanes':16,'warps':8,'transactionsPerCu':16,'axiDataBytes':32,'engines':4,
        'nativeMaxWidth':2048,'nativeMaxHeight':2048,'pagedCapacity':65536,'payloadBytes':524288,
        'resourceDiscoveryWords':96,'computeFeatureFlags':0x3ffff,'targetFps':60,
        'rtlEmitted':True,'sourceQualificationRequired':True,'gpuExecution':False,
        'clockQualified':False,'fpsQualified':False}
    if any(type(manifest.get(k)) is not type(v) or manifest[k]!=v for k,v in required.items()):
        raise RuntimeError('Incomplete or mismatched protected owner factory')
    words=manifest.get('requiredDiscovery')
    addresses=[w['address'] for w in EXPECTED_WORDS]
    if (not isinstance(words,list) or len(words)!=96 or
        any(not isinstance(w,dict) or set(w)!={'address','value'} or
            type(w['address']) is not int or w['address']!=a or
            type(w['value']) is not int or not 0<=w['value']<2**32 for w,a in zip(words,addresses))):
        raise RuntimeError('Incomplete native owner discovery words')
    if words!=EXPECTED_WORDS:
        raise RuntimeError('Protected owner discovery differs from its factory')

def prepared(out):
    record=json.loads((out/'preparation.json').read_text())
    manifest_path=out/'owner-manifest.json';check_manifest(json.loads(manifest_path.read_text()))
    if (type(record.get('version')) is not int or record['version']!=1 or
        type(record.get('exit_code')) is not int or record['exit_code']!=0 or
        record.get('source_sha256')!=source_inputs() or
        record.get('manifest_sha256')!=common.sha(manifest_path) or
        record.get('rtl_sha256')!=common.tree_hashes(out/'rtl') or
        not common.assertion_layer_hashes(record['rtl_sha256'])):
        raise RuntimeError('Owner RTL, assertions, factory or sources changed after preparation')
    return record

def prepare(out,heap):
    if out.exists():raise RuntimeError('Owner preparation requires a new output directory')
    if not re.fullmatch(r'[1-9][0-9]*[kKmMgG]',heap):raise ValueError('Invalid Java heap size')
    sources=source_inputs()
    main=Path(subprocess.check_output(['git','rev-parse','--path-format=absolute','--git-common-dir'],cwd=ROOT,text=True).strip()).parent
    temporary=main/'.artifacts';temporary.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='o',dir=temporary) as directory:
        if len(directory.encode())>52:raise RuntimeError('JVM temporary path exceeds Unix-socket limit')
        command=['sbt','-J-Xmx'+heap,'-J-XX:ActiveProcessorCount=2','-J-Djava.io.tmpdir='+directory,
            '-Dopengpu.fullHostQualification=1',
            'Test / runMain opengpu.system.PrepareNativeOwnerHost --output '+json.dumps(str(out))]
        subprocess.run(command,cwd=ROOT,env=dict(os.environ,TMPDIR=directory),check=True)
    if sources!=source_inputs():raise RuntimeError('Source changed during owner RTL preparation')
    manifest=out/'owner-manifest.json';check_manifest(json.loads(manifest.read_text()))
    rtl=common.tree_hashes(out/'rtl')
    if not common.assertion_layer_hashes(rtl):raise RuntimeError('Owner DUT has no assertion bind layers')
    common.simulation_inputs(out/'rtl')
    common.write_json(out/'preparation.json',dict(version=1,exit_code=0,source_sha256=sources,
        manifest_sha256=common.sha(manifest),rtl_sha256=rtl))
    prepared(out)

class OwnerRunner(flash.Runner):
    def __init__(self,out,flashsim,shards):
        super().__init__(out,True,flashsim,shards=shards,two_cu_vga=True)
    def prepared(self):return prepared(self.out)
    def generate(self):
        self.prepared() # Validate this owner output before retiring old build records.
        return super().generate()
    def state(self,stage,**fields):
        common.write_json(self.out/'state.json',dict(stage=stage,pid=os.getpid(),profile=PROFILE,**fields))

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',required=True,type=Path)
    parser.add_argument('--stage',required=True,choices=('prepare','generate','check'))
    parser.add_argument('--java-heap',default='24G')
    parser.add_argument('--flashsim-dir',type=Path)
    parser.add_argument('--shards',type=int,default=32)
    args=parser.parse_args();out=args.output.resolve()
    os.environ['PYTHONDONTWRITEBYTECODE']='1'
    if not out.is_relative_to(ROOT/'.artifacts'):parser.error('--output must be inside this checkout .artifacts')
    if args.stage=='prepare':prepare(out,args.java_heap)
    elif args.stage=='generate':
        if args.flashsim_dir is None:parser.error('--stage generate requires --flashsim-dir')
        OwnerRunner(out,args.flashsim_dir,args.shards).generate()
    else:prepared(out)
    print('NATIVE_OWNER_HOST_INPUTS_PASS profile='+PROFILE+' gpuExecution=false clockQualified=false fpsQualified=false')
if __name__=='__main__':main()
