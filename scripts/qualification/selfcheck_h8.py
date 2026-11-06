"""Synthetic H8 serial-format negatives, never actual guest qualification."""
from pathlib import Path
import hashlib,json
import check_h8 as h
A=Path(__file__).parent;g=h.golden();lines=['BUFFER_GLSL_QUALIFICATION items=16 local=8,2,1 fullBOs=true'];cases=0
for n in h.NAMES:
 d=g[n];lines.append('BUFFER_GLSL_PROGRAM name=%s words=%u storage=%x/%x textures=%08x images=%x/%x/%x local=8,2,1 GPU=UNRUN'%(n,d['words'],d['reads'],d['writes'],d['textures'],d['imageReads'],d['imageWrites'],d['imageQueries']))
objects={};pa=0x80000000
for bo in range(40):objects[('buffer',bo)]=(bo+1,pa,h.bo_bytes(bo));pa+=h.bo_bytes(bo)
for b in range(24,36):objects[('ubo-source',b)]=(100+b,pa,16384);pa+=16384
replacement=(41,pa,h.VIEW)
for epoch in(1,2):
 for n in h.NAMES if epoch==1 else h.NAMES[:-1]:
  count=41 if epoch==2 and n!='producer'else 40
  if n=='textures-0':
   for (kind,b),(handle,physical,size)in sorted(objects.items()):lines.append('OPENGPU_QUAL_PFN kind=%s binding=%u handle=%u page=0 pages=%u physical=0x%x'%(kind,b,handle,size//4096,physical))
   if epoch==2:lines.append('OPENGPU_QUAL_PFN kind=buffer binding=40 handle=41 page=0 pages=32768 physical=0x%x'%replacement[1])
   lines.append('BUFFER_GLSL_PAGEMAP publicBOs=complete UBOsourcePFNs=notShaderSnapshotPFNs shaderPTEwalk=REQUIRED')
  lines.append('BUFFER_GLSL_CHECK stage=%s BOs=%u words=%u exact=true'%(n,count,count*2064))
  lines.append('BUFFER_GLSL_LAUNCH name=%s epoch=%u expectedError=%u PASS'%(n,epoch,5 if n=='fault'else 0))
lines.append('BUFFER_GLSL_RUNTIME parents=11 shaderPrograms=6 full32textures=true full4SSBO=true full4independentImages=true full13CB=true faultFreshReuse=PASS CAP_COMPUTE=0 PASS')
text='\n'.join(lines)+'\n'
assert len(h.serial_checks(text)['objects'])==2;cases+=1
def reject(s):
 global cases
 try:h.serial_checks(s)
 except ValueError:cases+=1;return
 raise AssertionError('bad H8 format accepted')
reject(text.replace('BUFFER_GLSL_LAUNCH name=fault epoch=1 expectedError=5 PASS\n',''))
reject(text.replace('BOs=41 words=84624','BOs=41 words=82560'))
old_program='BUFFER_GLSL_PROGRAM name=textures-2 words=%u'%g['textures-2']['words']
assert text.count(old_program)==1
bad_program=text.replace(old_program,'BUFFER_GLSL_PROGRAM name=textures-2 words=513',1)
assert bad_program!=text
reject(bad_program)
reject(text.replace('kind=buffer binding=40 handle=41 page=0 pages=32768','kind=buffer binding=40 handle=41 page=0 pages=1'))
reject(text.replace('kind=buffer binding=40 handle=41','kind=buffer binding=40 handle=1'))
reject(text+'BUFFER_GLSL_CHECK partial\n')
reject(text+'GLSL_LINUX_RUNTIME another-app\n')

import copy
index={'state':'UNARMED_16_ITEM_H8_NO_GUEST_EXECUTED','files':[]}
argv=['qemu','-append','console=ttyAMA0 cma=1536M@0x80000000 rdinit=/init opengpu.qual=h8']
config={'app':'h8','qualification_items':16,'qualification_local':[8,2,1],
        'original_128_actual_guest_passed':False,'argv':argv}
process={'argv':list(argv)}
schema_cases = []

def need(ok, why):
    if not ok:
        raise ValueError(why)

def case(name, want, edit=lambda i,c,p: None, app='h8'):
    i,c,p = copy.deepcopy((index, config, process))
    edit(i,c,p)
    try:
        h.h8_run.check_index_app(i,c,p,app,need)
        actual = True
    except (ValueError, KeyError, IndexError, TypeError):
        actual = False
    assert actual is want, (name, actual, want)
    schema_cases.append(dict(name=name,accepted=actual))

case('actual compact16', True)
case('legacy explicit app', True, lambda i,c,p: i.update(app='h8'))
case('legacy different app', False, lambda i,c,p: i.update(app='h7'))
case('compact cannot select h7', False, app='h7')
case('unknown state', False, lambda i,c,p: i.update(state='UNARMED'))
case('extra compact key', False, lambda i,c,p: i.update(extra=True))
case('missing files', False, lambda i,c,p: i.pop('files'))
case('config selects h7', False, lambda i,c,p: c.update(app='h7'))
case('config missing items', False, lambda i,c,p: c.pop('qualification_items'))
case('config128', False, lambda i,c,p: c.update(qualification_items=128))
case('noninteger items', False, lambda i,c,p: c.update(qualification_items=16.0))
case('local128', False, lambda i,c,p: c.update(qualification_local=[8,4,4]))
case('boolean local', False, lambda i,c,p: c.update(qualification_local=[8,2,True]))
case('old128 cannot become pass', False, lambda i,c,p: c.update(original_128_actual_guest_passed=True))
case('missing old128 status', False, lambda i,c,p: c.pop('original_128_actual_guest_passed'))
case('actual argv differs', False, lambda i,c,p: p['argv'].append('-bad'))
def wrong_selected(i,c,p):
    n=p['argv'].index('-append')+1
    p['argv'][n]=c['argv'][n]='console=ttyAMA0 cma=1536M@0x80000000 rdinit=/init opengpu.qual=h7'
case('both argv select h7', False, wrong_selected)
def duplicate_append(i,c,p):
    for v in (c['argv'],p['argv']):
        v.extend(['-append',v[v.index('-append')+1]])
case('duplicate app selector', False, duplicate_append)
cases += len(schema_cases)
x={'status':'PASS_SYNTHETIC_H8_SERIAL_CHECKS_ONLY','cases':cases,'compactIndexSchemaCases':len(schema_cases),'H8Sha256':hashlib.sha256((A/'check_h8.py').read_bytes()).hexdigest(),'actualGuestExecuted':False,'actualNativeBuilt':False,'scope':'six compiled program metadata identities,11checks/launches/fault/fresh sequence,full40/41+12sourcePFNs,independent replacement owner,explicit compact16 INDEX schema; real PTE/AXI waits for actual guest'}
print(json.dumps(x,indent=2))
