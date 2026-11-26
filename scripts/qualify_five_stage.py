import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import signal
import subprocess
import sys
import tempfile

sys.dont_write_bytecode=True

from run_owner_bounded import run_bounded
from owner_process_lifetime import CleanupIncomplete

ROOT=Path(__file__).resolve().parents[1]
ARTIFACTS=ROOT/'.artifacts'
PURPOSE='original-linked-five-isoline-no-tf'
SOURCE_SCHEMA='opengpu-original-five-isoline-no-tf-v1'
PLAN_SCHEMA='opengpu-original-five-runtime-v1'
CASES=(('full5',4),('pointcoord',4))
JAVA_HOME=Path(os.environ.get('JAVA_HOME','/opt/homebrew/opt/openjdk@21'))
JAVA_OPTS='-Xms1G -Xmx6G -Xss6M -XX:+UseG1GC -Dfile.encoding=UTF-8'
SCALA_SOURCES=(
 'src/test/scala/opengpu/system/FiveStageIsolineMesaTop.scala',
 'src/test/scala/opengpu/system/RetainFiveStageMesaModel.scala',
 'src/test/scala/opengpu/system/FiveStageHostShapeCPU.scala',
 'src/test/scala/svsim/FiveStageMesaReplay.scala',
)
SIM_SOURCES=(
 'src/test/scala/opengpu/testutil/GpuSim.scala',
 'src/test/scala/opengpu/testutil/GpuSimRetainedReplay.scala',
 'src/test/scala/opengpu/testutil/GpuSimModelPortABI.scala',
 'src/test/scala/opengpu/testutil/GraphicsTestResolution.scala',
)


def require(value,message):
 if not value:raise ValueError(message)


def sha(path):
 digest=hashlib.sha256()
 with Path(path).open('rb') as stream:
  for block in iter(lambda:stream.read(1024*1024),b''):digest.update(block)
 return digest.hexdigest()


def regular(path):
 path=Path(path)
 require(path.is_file() and not path.is_symlink() and path.resolve(strict=True)==path,'missing or aliased file: '+str(path))


def strict_json(path):
 def unique(pairs):
  result={}
  for key,value in pairs:
   require(key not in result,'duplicate JSON key: '+key)
   result[key]=value
  return result
 return json.loads(Path(path).read_text(),object_pairs_hook=unique)


def write_json(path,value):
 Path(path).write_text(json.dumps(value,indent=2)+'\n')


def input_directory(path):
 path=Path(path).absolute()
 require(path.is_dir() and not path.is_symlink() and path.resolve(strict=True)==path,'missing or aliased original-input directory')
 return path


def resolve_input(root,name):
 candidate=Path(name)
 if candidate.is_absolute():
  regular(candidate)
  require(candidate.parent in (root,root/candidate.name.split('.',1)[0]),'original input escaped its case directory')
  return candidate
 require(candidate.name==name,'original input must be a basename')
 candidates=(root/name,root/name.split('.',1)[0]/name)
 matches=[path for path in candidates if path.is_file()]
 require(matches,'missing original input: '+name)
 path=matches[0];regular(path)
 require(path.parent in (root,root/name.split('.',1)[0]),'original input escaped its case directory')
 return path


def validate_plan(path,input_root):
 regular(path)
 plan=strict_json(path);root=input_directory(input_root)
 def forbidden(value):
  if isinstance(value,dict):
   for key,item in value.items():
    normalized=key.lower()
    require('feedback' not in normalized and 'expectedtf' not in normalized and 'abi10' not in normalized,'transform-feedback or ABI10 plan field: '+key)
    forbidden(item)
  elif isinstance(value,list):
   for item in value:forbidden(item)
 forbidden(plan)
 require(plan.get('schema')==PLAN_SCHEMA and plan.get('cpuOnly') is True,'wrong runtime plan schema/mode')
 require(plan.get('recordBytes')==1472,'runtime record capacity must be 1472')
 require(plan.get('properties',{}).get('opengpu.productQualification')=='0' and plan.get('supported')==[640,480],'wrong runtime graphics capacity')
 require(plan.get('abiSha')==sha(ROOT/'docs/abi/typed-fragment-state-v1.json'),'typed fragment ABI changed')
 if 'inputRoot' in plan:require(Path(plan['inputRoot']).resolve(strict=True)==root,'runtime input root changed')
 actual=tuple((case['name'],case['width']) for case in plan['cases'])
 require(actual==CASES,'runtime cases must be exactly full5/4 then pointcoord/4')
 for index,case in enumerate(plan['cases']):
  require(case['expectedGsPrimitives']==index and case['expectedOriginalTesPrimitives']==1 and case['taskCount']==1 and len(case['tasks'])==1,'wrong original GS/task counts')
  require(len(case['images'])==4 and len(case['fsScratchBases'])==4 and len(case['codePcs'])==5,'incomplete original frame/code plan')
  for image in case['images']:
   require(image['bytes']==768 and image['guardedBytes']==896 and image['storedBytes']==1024,'wrong 4x4 image extents')
   require(len(image['expectedWithGuardsHex'])==1792 and len(image['expectedStoredHex'])==2048,'wrong independent image oracle extent')
   require(image['expectedStoredHex'].startswith(image['expectedWithGuardsHex']),'guarded image oracle differs from stored oracle')
 for name,digest in plan['originalInputs'].items():
  candidate=resolve_input(root,name)
  require(sha(candidate)==digest,'original input changed: '+str(candidate))
 input_names={Path(name).name for name in plan['originalInputs']}
 for name,_ in CASES:
  for suffix in ('five.json','geometry.json','ognsf','uniform-private.bin','vs.words','tcs.words','tes.words','gs.words','fs.words'):
   require(name+'.'+suffix in input_names,'missing original input '+name+'.'+suffix)
 return plan


def normalize_plan(plan,root):
 normalized=json.loads(json.dumps(plan));normalized['inputRoot']=str(root)
 normalized['originalInputs']={str(resolve_input(root,name)):digest for name,digest in plan['originalInputs'].items()}
 return normalized


def validate_sources():
 gpu_sim=(ROOT/'src/test/scala/opengpu/testutil/GpuSim.scala').read_text()
 require('--comp-limit-syms 0' in gpu_sim,'GpuSim lost full DPI symbol support')
 require('case "flashsim"' in gpu_sim and 'new FlashSimRunner' in gpu_sim,'GpuSim lost FlashSim support')
 retain=(ROOT/'src/test/scala/opengpu/system/RetainFiveStageMesaModel.scala').read_text()
 require('simulateWithInlineVerificationRetainingModel' in retain,'retained model does not use current GpuSim')
 replay=(ROOT/'src/test/scala/svsim/FiveStageMesaReplay.scala').read_text()
 require('GpuSimRetainedReplay.run' in replay,'replay does not use the retained GpuSim adapter')


def environment(temporary):
 require(JAVA_HOME.is_dir(),'JDK21 is required at '+str(JAVA_HOME))
 temporary=Path(temporary)
 require(temporary.is_dir(),'temporary directory is missing')
 env=dict(os.environ)
 env['JAVA_HOME']=str(JAVA_HOME)
 env['PATH']=str(JAVA_HOME/'bin')+os.pathsep+env.get('PATH','')
 env['JAVA_OPTS']=JAVA_OPTS
 env['PYTHONDONTWRITEBYTECODE']='1'
 env['TMPDIR']=str(temporary)
 return env


def run_logged(command,env,log,memory_mib,timeout_seconds):
 log=Path(log)
 with log.open('w') as stream:
  sys.stdout.flush();sys.stderr.flush()
  saved=os.dup(1),os.dup(2)
  saved_env=dict(os.environ)
  try:
   os.dup2(stream.fileno(),1);os.dup2(stream.fileno(),2)
   os.environ.clear();os.environ.update(env)
   code=run_bounded(command,memory_mib,timeout_seconds)
  finally:
   sys.stdout.flush();sys.stderr.flush()
   os.environ.clear();os.environ.update(saved_env)
   os.dup2(saved[0],1);os.dup2(saved[1],2)
   for fd in saved:os.close(fd)
 return code,log.read_text(errors='replace')


def sbt_command(arguments,heap):
 return ['sbt','-batch','-J-Xmx'+heap,'-J-XX:+ExitOnOutOfMemoryError',*arguments]


def shape_command(plan,output):
 return sbt_command(['Test/runMain opengpu.system.FiveStageHostShapeCPU '+shlex.quote(str(plan))+' '+shlex.quote(str(output))],'6G')


def validate_shape(path):
 shape=strict_json(path)
 require(shape.get('clock')=={'width':1,'settable':True} and shape.get('reset')=={'width':1,'settable':True},'wrong actual clock/reset Bundle leaves')
 require(shape.get('io_completion_bits_img_image_faultAddress',{}).get('settable') is False,'current image faultAddress output leaf is missing')
 require(not any('feedback' in name.lower() for name in shape),'transform-feedback Bundle leaf remains')
 return shape


def compare_model_shape(binding,shape):
 ports=binding['port_bindings']
 require([port['id'] for port in ports]==list(range(len(ports))),'noncanonical retained port order')
 require(len({port['name'] for port in ports})==len(ports),'duplicate retained port')
 require({port['name'] for port in ports}==set(shape),'retained ABI differs from actual Bundle CPU shape')
 for port in ports:
  leaf=shape[port['name']]
  require(port['width']==leaf['width'] and port['settable']==leaf['settable'] and port['gettable'] is True,'retained ABI leaf mismatch: '+port['name'])


def source_binding(plan_path,plan):
 files={}
 for path in (ROOT/'src/main/scala').rglob('*.scala'):files[str(path)]=sha(path)
 for name in (*SCALA_SOURCES,*SIM_SOURCES,'build.sbt','docs/abi/typed-fragment-state-v1.json','scripts/qualify_five_stage.py','scripts/run_owner_bounded.py','scripts/owner_process_memory.py','scripts/owner_process_lifetime.py'):
  path=ROOT/name;regular(path);files[str(path)]=sha(path)
 for path in (ROOT/'project').glob('*.sbt'):
  regular(path);files[str(path)]=sha(path)
 regular(ROOT/'project/build.properties');files[str(ROOT/'project/build.properties')]=sha(ROOT/'project/build.properties')
 files[str(plan_path)]=sha(plan_path);files.update(plan['originalInputs'])
 head=subprocess.check_output(['git','-C',str(ROOT),'rev-parse','HEAD'],text=True).strip()
 return dict(schema=SOURCE_SCHEMA,purpose=PURPOSE,head=head,hostRoute='direct-typed-isoline',
  transformFeedback=False,abi10=False,physicalGraphicsCUs=2,recordBytes=1472,inputRoot=plan['inputRoot'],
  properties={'opengpu.productQualification':'0'},supported_graphics=[640,480],
  cases=[dict(name=name,width=width) for name,width in CASES],inputs=dict(sorted(files.items())),tools={})


def build_properties(work):
 return ['-Dopengpu.productQualification=0','-Dopengpu.simCache=off','-Dopengpu.simCcache=off',
  '-Dopengpu.simHierarchy=GpuComputeUnit','-Dopengpu.simJobs=1','-Dopengpu.simLowMem=1','-Dopengpu.simLowMemOpt=0',
  '-Dopengpu.simSeparateBuild=1','-Dopengpu.simReleaseHeap=1','-Dopengpu.simWorkspace='+str(work/'sim-workspace')]


def java_options(properties):
 return JAVA_OPTS+' '+' '.join(properties)


def validate_model(model):
 model=Path(model)
 require(model.is_dir() and not model.is_symlink() and model.resolve(strict=True)==model,'aliased model directory')
 require({path.name for path in model.iterdir()}=={'simulation','binding.json','source-binding.json'},'incomplete retained model')
 for name in ('simulation','binding.json','source-binding.json'):regular(model/name)
 binding=strict_json(model/'binding.json')
 require(binding.get('schema')=='opengpu-svsim-active-consumer-v1' and binding.get('qualification') is False,'wrong retained model publication')
 require(binding.get('simulation_body_returned') is True and binding.get('module')=='FiveStageIsolineMesaTop' and binding.get('purpose')==PURPOSE,'incomplete retained model body')
 require(binding.get('inline_verification') is True and binding.get('firtool_options')==['--default-layer-specialization=enable'],'inline verification options changed')
 expected=dict(hierarchy=['GpuComputeUnit'],build_jobs=1,low_memory=True,optimization='0',separate_build=True,cache_enabled=False,compiler_cache=None,release_heap=True)
 require(binding.get('options')==expected,'wrong retained Verilator build options')
 require(all(type(binding['options'][key]) is type(value) for key,value in expected.items()),'wrong retained build option types')
 require(binding['binary_sha256']==sha(model/'simulation') and binding['source_binding_sha256']==sha(model/'source-binding.json'),'retained binary/source binding hash mismatch')
 source=strict_json(model/'source-binding.json')
 require(source.get('schema')==SOURCE_SCHEMA and source.get('purpose')==PURPOSE,'wrong source binding')
 require(source.get('transformFeedback') is False and source.get('abi10') is False and source.get('physicalGraphicsCUs')==2,'source binding is not no-TF two-CU direct geometry')
 require(source.get('hostRoute')=='direct-typed-isoline' and source.get('recordBytes')==1472,'wrong direct host route or record capacity')
 require(source.get('properties',{}).get('opengpu.productQualification')=='0' and source.get('supported_graphics')==[640,480],'wrong source graphics capacity')
 require(tuple((case['name'],case['width']) for case in source.get('cases',[]))==CASES,'wrong source case scope')
 require(not any('feedback' in port['name'].lower() for port in binding['port_bindings']),'retained model exposes transform feedback')
 return binding,source


def check_bound_inputs(source,plan_path):
 for name,digest in source['inputs'].items():
  regular(name);require(sha(name)==digest,'bound source/input changed: '+name)
 require(source['inputs'][str(plan_path)]==sha(plan_path),'runtime plan changed')


def output_directory(path):
 require(ARTIFACTS.is_dir(),'missing checkout artifacts directory')
 path=Path(path).absolute()
 require(path.parent.resolve()==ARTIFACTS.resolve() and path!=ARTIFACTS,'work output must be a direct child of checkout .artifacts')
 require(not path.exists(),'work output already exists: '+str(path))
 return path


def cpu_only(args,plan,memory_mib,timeout_seconds):
 require(args.shape_output is not None,'--cpu-only requires --shape-output')
 output=Path(args.shape_output).absolute()
 require(output.parent.is_dir() and not output.exists(),'shape output parent must exist and output must be new')
 temporary=Path(tempfile.mkdtemp(prefix='five-stage-cpu-',dir=ARTIFACTS))
 log=output.with_suffix(output.suffix+'.log')
 try:
  code,text=run_logged(shape_command(args.runtime_plan,output),environment(temporary),log,memory_mib,timeout_seconds)
  require(code==0,'Bundle CPU check failed: '+text[-4000:])
  shape=validate_shape(output)
  require(text.count('FIVE_STAGE_ACTUAL_BUNDLE_CPU_PASS')==1,'Bundle CPU pass marker missing')
  print('FIVE_STAGE_BUNDLE_CPU_PASS ports='+str(len(shape))+' noTf=true cases=2')
 finally:
  shutil.rmtree(temporary,ignore_errors=True)
  if log.exists():log.unlink()


def build(args,plan,root,memory_mib,timeout_seconds):
 require(args.work is not None,'--build requires --work')
 work=output_directory(args.work);work.mkdir();(work/'tmp').mkdir()
 plan_path=work/'runtime-plan.json';write_json(plan_path,normalize_plan(plan,root));validate_plan(plan_path,root)
 shape_path=work/'host-shape.json';shape_log=work/'shape.log'
 code,text=run_logged(shape_command(plan_path,shape_path),environment(work/'tmp'),shape_log,memory_mib,timeout_seconds)
 require(code==0,'Bundle CPU check failed: '+text[-4000:])
 shape=validate_shape(shape_path);shape_log.unlink()
 source=source_binding(plan_path,strict_json(plan_path));source_path=work/'source-binding.json';write_json(source_path,source)
 model=work/'model';build_log=work/'build.log'
 if args.resume_workspace is None:
  command=sbt_command(['Test/runMain opengpu.system.RetainFiveStageMesaModel '+shlex.quote(str(model))+' '+shlex.quote(str(source_path))],'3G')
  env=environment(work/'tmp');env['JAVA_OPTS']=java_options(build_properties(work))
 else:
  resume=Path(args.resume_workspace).resolve()
  require(resume.is_relative_to((work/'sim-workspace').resolve()) and resume.name=='slot-0','resume workspace must be the active five-stage slot')
  binary=resume/'workdir-verilator/simulation';compile_log=resume/'workdir-verilator/compilation-log.txt'
  regular(binary);regular(compile_log)
  if model.exists():shutil.rmtree(model)
  command=sbt_command(['Test/runMain svsim.FiveStageMesaReplay --retain-reset-idle '+shlex.quote(str(resume))+' '+shlex.quote(str(model))+' '+shlex.quote(str(source_path))+' '+shlex.quote(str(shape_path))],'1G')
  env=environment(work/'tmp')
 code,text=run_logged(command,env,build_log,memory_mib,timeout_seconds)
 require(code==0,'retained model build failed: '+text[-4000:])
 require(text.count('FIVE_STAGE_MODEL_RESET_IDLE_ONLY originalShaderExecuted=false noTf=true physicalGraphicsCUs=2')==1,'retained reset/idle marker missing')
 binding,source=validate_model(model);compare_model_shape(binding,shape);check_bound_inputs(source,plan_path)
 build_log.unlink();shape_path.unlink();shutil.rmtree(work/'sim-workspace',ignore_errors=True)
 print('FIVE_STAGE_MODEL_RETAINED_PASS '+str(model)+' noTf=true physicalGraphicsCUs=2')


def replay(args,plan,root,memory_mib,timeout_seconds):
 model=Path(args.replay_model).absolute();binding,source=validate_model(model)
 work=model.parent.resolve()
 require(work.parent==ARTIFACTS.resolve() and work.name.startswith('five-stage-'),'replay model must be inside a dedicated five-stage artifacts directory')
 plan_path=work/'runtime-plan.json';validate_plan(plan_path,root)
 require(sha(args.runtime_plan)==sha(plan_path),'replay runtime plan differs from retained model plan')
 check_bound_inputs(source,plan_path)
 replay=work/'replay';require(not replay.exists(),'replay output already exists');replay.mkdir();(work/'tmp').mkdir(exist_ok=True)
 for name in ('simulation','binding.json','source-binding.json'):os.link(model/name,replay/name)
 guard=dict(schema='private-five-host-bound-inputs-v1',runtimePlanSha256=sha(plan_path),modelBindingSha256=sha(model/'binding.json'),
  inputs={str(plan_path):sha(plan_path),str(model/'simulation'):sha(model/'simulation'),str(model/'binding.json'):sha(model/'binding.json'),str(model/'source-binding.json'):sha(model/'source-binding.json')})
 guard_path=replay/'host-binding.json';write_json(guard_path,guard);log=replay/'replay.log'
 command=sbt_command(['Test/runMain svsim.FiveStageMesaReplay '+shlex.quote(str(model))+' '+shlex.quote(str(replay))+' '+shlex.quote(str(plan_path))+' '+shlex.quote(str(guard_path))],'1G')
 env=environment(work/'tmp');env['JAVA_OPTS']=java_options(['-Dopengpu.productQualification=0'])
 code,text=run_logged(command,env,log,memory_mib,timeout_seconds)
 require(code==0,'retained replay failed: '+text[-4000:])
 marker='ACTUAL_ORIGINAL_FIVE_STAGE_ISOLINE_PASS cases=2 noReset=true noTf=true physicalGraphicsCUs=2'
 require(text.count(marker)==1 and text.count('ACTUAL_ORIGINAL_FIVE_STAGE_FRAME')==2,'whole original replay marker missing')
 validate_model(model);shutil.rmtree(work)
 print('FIVE_STAGE_RETAINED_REPLAY_PASS cases=2 noReset=true noTf=true physicalGraphicsCUs=2')


def main():
 parser=argparse.ArgumentParser()
 parser.add_argument('--runtime-plan',type=Path,required=True)
 parser.add_argument('--input-root',type=Path,required=True)
 modes=parser.add_mutually_exclusive_group(required=True)
 modes.add_argument('--cpu-only',action='store_true')
 modes.add_argument('--build',action='store_true')
 modes.add_argument('--replay-model',type=Path)
 parser.add_argument('--work',type=Path)
 parser.add_argument('--shape-output',type=Path)
 parser.add_argument('--resume-workspace',type=Path)
 parser.add_argument('--memory-mib',type=int,default=10240)
 parser.add_argument('--timeout-seconds',type=float,default=13472)
 args=parser.parse_args()
 require(args.memory_mib>0 and args.timeout_seconds>0,'positive process budgets are required')
 args.runtime_plan=args.runtime_plan.absolute();args.input_root=input_directory(args.input_root)
 def interrupted(signum,_frame):raise InterruptedError('five-stage qualifier interrupted by signal '+str(signum))
 previous={sig:signal.signal(sig,interrupted) for sig in (signal.SIGINT,signal.SIGTERM)}
 try:
  validate_sources();plan=validate_plan(args.runtime_plan,args.input_root)
  if args.cpu_only:cpu_only(args,plan,args.memory_mib,args.timeout_seconds)
  elif args.build:build(args,plan,args.input_root,args.memory_mib,args.timeout_seconds)
  else:replay(args,plan,args.input_root,args.memory_mib,args.timeout_seconds)
  return 0
 except CleanupIncomplete as error:
  print(str(error)+'; preserved active inputs',file=sys.stderr);return 1
 except (OSError,RuntimeError,ValueError,subprocess.SubprocessError) as error:
  print(str(error),file=sys.stderr);return 1
 finally:
  for sig,handler in previous.items():signal.signal(sig,handler)


if __name__=='__main__':raise SystemExit(main())
