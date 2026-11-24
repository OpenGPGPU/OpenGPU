#!/usr/bin/env python3
"""Prepare an exact owner factory; execution qualification is separate."""
import argparse,json,os,re,shutil,subprocess,sys,tempfile
from contextlib import ExitStack
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
        ROOT/'scripts/qualify_native_product_flashsim.py',ROOT/'scripts/owner_resource_contract.py')}

def check_manifest(manifest,*,rtl_emitted=True):
    if type(manifest) is not dict:raise RuntimeError('Owner manifest must be an object')
    from owner_resource_contract import (COMPUTE_STAGE, COMPUTE_WORDS, PROFILE as FULL_PROFILE,
        FULL_STAGE, FULL_WORDS, SAMPLE_PROFILE, SAMPLE_STAGE, SAMPLE_WORDS,
        IMAGE_FLOAT_PROFILE, IMAGE_FLOAT_STAGE, IMAGE_FLOAT_WORDS,
        TESSELLATION_UNIFORM_PROFILE, TESSELLATION_UNIFORM_STAGE)
    compute_stage = manifest.get('profile') == COMPUTE_STAGE
    full_resources = manifest.get('profile') == FULL_PROFILE
    tessellation_uniforms = manifest.get('profile') == TESSELLATION_UNIFORM_PROFILE
    float_resources = manifest.get('profile') == IMAGE_FLOAT_PROFILE or tessellation_uniforms
    sample_resources = manifest.get('profile') == SAMPLE_PROFILE or float_resources
    if not tessellation_uniforms and 'tessellationUniforms' in manifest:
        raise RuntimeError('Checked tessellation uniforms require their distinct owner factory')
    if not float_resources and 'imageFloatExchange' in manifest:
        raise RuntimeError('Float exchange metadata requires its distinct owner factory')
    if 'assertionMode' in manifest and (not sample_resources or
            manifest['assertionMode'] != 'inline' or not rtl_emitted):
        raise RuntimeError('Inline assertions require the emitted complete sample factory')
    if not sample_resources and any(key in manifest for key in
            ('nativeSampleShading','nativeSampleTransport','nativeFragmentVersions',
             'tessellationFragmentImages','tessellationSampleShading')):
        raise RuntimeError('Sample transport metadata requires its distinct owner factory')
    expected_words = IMAGE_FLOAT_WORDS if float_resources else SAMPLE_WORDS if sample_resources else FULL_WORDS if full_resources else COMPUTE_WORDS if compute_stage else EXPECTED_WORDS
    required={'version':1,'profile':PROFILE,'computeUnits':2,'frameWidth':640,'frameHeight':480,
        'lanes':16,'warps':8,'transactionsPerCu':16,'axiDataBytes':32,'engines':4,
        'nativeMaxWidth':2048,'nativeMaxHeight':2048,'pagedCapacity':65536,'payloadBytes':524288,
        'resourceDiscoveryWords':96,'computeFeatureFlags':0x3ffff,'targetFps':60,
        'rtlEmitted':rtl_emitted,'sourceQualificationRequired':True,'gpuExecution':False,
        'clockQualified':False,'fpsQualified':False}
    if compute_stage:
        required.update(version=2, profile=COMPUTE_STAGE, computeFeatureFlags=0x7ffff,
                        ownerResourceVersion=2, ownerResourceStage='compute', fullResourceQualification=False)
    if full_resources or sample_resources:
        required.update(version=2, profile=FULL_PROFILE, computeFeatureFlags=0xfffff,
            resourceDiscoveryWords=104, ownerResourceVersion=2, ownerResourceStage=FULL_STAGE,
            nativeGeometryDraw=True, nativeGeometryTransport=True, transformFeedback=True,
            fullResourceQualification=False)
    if sample_resources:
        required.update(profile=SAMPLE_PROFILE, computeFeatureFlags=0x1fffff,
            resourceDiscoveryWords=112, ownerResourceStage=SAMPLE_STAGE,
            nativeSampleShading=True, nativeSampleTransport=True, nativeFragmentVersions=[6,7],
            tessellationFragmentImages=True, tessellationSampleShading=True)
    if float_resources:
        required.update(profile=IMAGE_FLOAT_PROFILE, ownerResourceStage=IMAGE_FLOAT_STAGE,
            computeFeatureFlags=0x3fffff, imageFloatExchange=True)
    if tessellation_uniforms:
        required.update(profile=TESSELLATION_UNIFORM_PROFILE, ownerResourceStage=TESSELLATION_UNIFORM_STAGE,
                        tessellationUniforms=True)
    if any(type(manifest.get(k)) is not type(v) or manifest[k]!=v for k,v in required.items()):
        raise RuntimeError('Incomplete or mismatched protected owner factory')
    words=manifest.get('requiredDiscovery')
    addresses=[w['address'] for w in expected_words]
    if (not isinstance(words,list) or len(words)!=len(expected_words) or
        any(not isinstance(w,dict) or set(w)!={'address','value'} or
            type(w['address']) is not int or w['address']!=a or
            type(w['value']) is not int or not 0<=w['value']<2**32 for w,a in zip(words,addresses))):
        raise RuntimeError('Incomplete native owner discovery words')
    if words!=expected_words:
        raise RuntimeError('Protected owner discovery differs from its factory')

def check_assertion_format(out, manifest, rtl):
    layers = common.assertion_layer_hashes(rtl)
    if manifest.get('assertionMode') != 'inline':
        if not layers:raise RuntimeError('Owner DUT has no assertion bind layers')
        return
    if layers:raise RuntimeError('Inline owner DUT still contains assertion bind layers')
    # This is a format guard, not execution qualification. Source/RTL bindings
    # below and the assertion-enabled model/probes remain mandatory.
    for name in rtl:
        if not name.endswith('.sv'):continue
        text = re.sub(r'/\*.*?\*/', '', (out/'rtl'/name).read_text(), flags=re.S)
        if re.search(r'(?m)^\s*(?:assert\s*\(|\$(?:fatal|error)\b)', text):
            return
    raise RuntimeError('Inline owner DUT contains no executable assertion statements')

def prepared(out):
    record=json.loads((out/'preparation.json').read_text())
    manifest_path=out/'owner-manifest.json';manifest=json.loads(manifest_path.read_text())
    check_manifest(manifest)
    if (type(record.get('version')) is not int or record['version']!=1 or
        type(record.get('exit_code')) is not int or record['exit_code']!=0 or
        record.get('source_sha256')!=source_inputs() or
        record.get('manifest_sha256')!=common.sha(manifest_path) or
        record.get('rtl_sha256')!=common.tree_hashes(out/'rtl')):
        raise RuntimeError('Owner RTL, assertions, factory or sources changed after preparation')
    check_assertion_format(out,manifest,record['rtl_sha256'])
    return record

def prepare(out, heap, compute_stage=False, full_resources=False, sample_resources=False,
            inline_assertions=False, float_resources=False, tessellation_uniforms=False):
    from owner_resource_contract import (COMPUTE_STAGE, PROFILE as FULL_PROFILE, SAMPLE_PROFILE,
        IMAGE_FLOAT_PROFILE, TESSELLATION_UNIFORM_PROFILE)
    selected = [(entry, profile) for enabled, entry, profile in (
        (compute_stage, 'PrepareOwnerResourceHost', COMPUTE_STAGE),
        (full_resources, 'PrepareOwnerResourceFullHost', FULL_PROFILE),
        (sample_resources, 'PrepareOwnerResourceSampleHost', SAMPLE_PROFILE),
        (float_resources, 'PrepareOwnerResourceImageFloatHost', IMAGE_FLOAT_PROFILE),
        (tessellation_uniforms, 'PrepareOwnerResourceTessellationUniformHost', TESSELLATION_UNIFORM_PROFILE),
    ) if enabled]
    if len(selected) > 1: raise RuntimeError('Select one exact owner factory')
    if inline_assertions and not (sample_resources or float_resources or tessellation_uniforms):
        raise RuntimeError('Inline assertions require the complete sample factory')
    entry, expected_profile = selected[0] if selected else ('PrepareNativeOwnerHost', PROFILE)
    if out.exists():raise RuntimeError('Owner preparation requires a new output directory')
    if not re.fullmatch(r'[1-9][0-9]*[kKmMgG]',heap):raise ValueError('Invalid Java heap size')
    source_snapshot=source_inputs()
    staged_lowering = float_resources or tessellation_uniforms
    main=Path(subprocess.check_output(['git','rev-parse','--path-format=absolute','--git-common-dir'],cwd=ROOT,text=True).strip()).parent
    temporary=main/'.artifacts';temporary.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='o',dir=temporary) as directory, ExitStack() as stack:
        jvm_directory = directory
        if len(os.fsencode(directory)) > 52:
            # sbt's Unix-domain socket adds its own path suffix. Keep the
            # staged RTL on the workspace filesystem for the final rename.
            jvm_directory = stack.enter_context(tempfile.TemporaryDirectory(prefix='opengpu-jvm-', dir='/tmp'))
        environment = dict(os.environ, TMPDIR=jvm_directory)
        staged = Path(directory) / 'staged'
        argument = ('--firrtl-output ' + json.dumps(str(staged)) if staged_lowering else
                    '--output ' + json.dumps(str(out)))
        command=['sbt','-J-Xmx'+heap,'-J-XX:ActiveProcessorCount=2','-J-Djava.io.tmpdir='+jvm_directory,
            '-Dopengpu.fullHostQualification=1',
            'Test / runMain opengpu.system.'+entry+' '+argument+
            (' --inline-assertions' if inline_assertions else '')]
        subprocess.run(command,cwd=ROOT,env=environment,check=True)
        if staged_lowering:
            # The JVM has exited. Lower the serialized input with the same
            # firtool and options chosen by the Scala factory.
            lower = (staged / 'firtool-command').read_text().splitlines()
            subprocess.run(lower, cwd=ROOT, env=environment, check=True)
            rtl = staged / 'rtl'
            macro = staged / 'NativeFourEngineSramModels.sv'
            shutil.copyfile(macro, rtl / macro.name)
            out.mkdir(parents=True, exist_ok=False)
            rtl.rename(out / 'rtl')
            (staged / 'owner-manifest.json').rename(out / 'owner-manifest.json')
    if source_snapshot!=source_inputs():raise RuntimeError('Source changed during owner RTL preparation')
    manifest=out/'owner-manifest.json';description=json.loads(manifest.read_text());check_manifest(description)
    if description['profile'] != expected_profile:
        raise RuntimeError('Emitted owner profile differs from the requested factory')
    if description.get('assertionMode','bound') != ('inline' if inline_assertions else 'bound'):
        raise RuntimeError('Emitted assertion format differs from the requested format')
    rtl=common.tree_hashes(out/'rtl')
    check_assertion_format(out,description,rtl)
    common.simulation_inputs(out/'rtl')
    common.write_json(out/'preparation.json',dict(version=1,exit_code=0,source_sha256=source_snapshot,
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
        common.write_json(self.out/'state.json',dict(stage=stage,pid=os.getpid(),profile=json.loads((self.out/'owner-manifest.json').read_text())['profile'],**fields))

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',required=True,type=Path)
    parser.add_argument('--stage',required=True,choices=('prepare','generate','check'))
    factories=parser.add_mutually_exclusive_group()
    factories.add_argument('--compute-stage',action='store_true',
                        help='prepare the distinct shared-atomic-enabled intermediate owner factory')
    factories.add_argument('--full-resources',action='store_true',
                        help='prepare the distinct full geometry/compute/feedback owner factory')
    factories.add_argument('--sample-resources',action='store_true',
                        help='prepare the distinct complete sample-plane geometry/compute owner factory')
    factories.add_argument('--image-float-resources',action='store_true',
                        help='prepare the distinct complete sample owner with R32F exchange')
    factories.add_argument('--tessellation-uniforms', action='store_true',
                        help='prepare the complete sample/float owner with checked TCS/TES uniforms')
    parser.add_argument('--inline-assertions',action='store_true',
                        help='inline the complete sample factory checks for hierarchical compilation')
    parser.add_argument('--java-heap', help='JVM heap for preparation (5G for staged image-float or checked uniforms, otherwise 24G)')
    parser.add_argument('--flashsim-dir',type=Path)
    parser.add_argument('--shards',type=int,default=32)
    args=parser.parse_args();out=args.output.resolve()
    os.environ['PYTHONDONTWRITEBYTECODE']='1'
    if not out.is_relative_to(ROOT/'.artifacts'):parser.error('--output must be inside this checkout .artifacts')
    if (args.compute_stage or args.full_resources or args.sample_resources or args.image_float_resources
            or args.tessellation_uniforms) and args.stage!='prepare':
        parser.error('factory options select only a fresh preparation')
    if args.inline_assertions and (args.stage!='prepare' or not (
            args.sample_resources or args.image_float_resources or args.tessellation_uniforms)):
        parser.error('--inline-assertions requires --stage prepare with a complete sample factory')
    if args.stage=='prepare':
        heap = args.java_heap or ('5G' if args.image_float_resources or args.tessellation_uniforms else '24G')
        prepare(out, heap, args.compute_stage, args.full_resources, args.sample_resources,
                args.inline_assertions, args.image_float_resources, args.tessellation_uniforms)
    elif args.stage=='generate':
        if args.flashsim_dir is None:parser.error('--stage generate requires --flashsim-dir')
        OwnerRunner(out,args.flashsim_dir,args.shards).generate()
    else:prepared(out)
    profile=json.loads((out/'owner-manifest.json').read_text())['profile']
    print('NATIVE_OWNER_HOST_INPUTS_PASS profile='+profile+' gpuExecution=false clockQualified=false fpsQualified=false')
if __name__=='__main__':main()
