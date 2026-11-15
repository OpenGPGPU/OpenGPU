#!/usr/bin/env python3
"""Original triangle/quad Mesa fixtures and strict domain receipts (CPU only).

--programs consumes actual test_native_joint.py --domains packets through an
independent, explicitly selected protected driver checkout. No GPU is executed.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
SCHEMA='opengpu-native-joint-domain-v1'
DOMAINS={'matrix-triangle':4,'matrix-quad':7}  # Mesa pipe_prim_type, NOT RTL enums.


def require(value,message):
    if not value:raise ValueError(message)


def unique(pairs):
    result={}
    for key,value in pairs:
        require(key not in result,'duplicate domain field')
        result[key]=value
    return result


def domain_metadata(path,expected):
    data=json.loads(path.read_text(),object_pairs_hook=unique)
    require(isinstance(data,dict),'domain object required')
    require(set(data)=={'schema','version','stage','domain','spacing','clockwise','pointMode'},'exact domain fields required')
    require(data['schema']==SCHEMA,'domain schema')
    require(all(type(data[k]) is int for k in data if k!='schema'),'domain fields must be integers')
    require(data==dict(schema=SCHEMA,version=1,stage=5,domain=expected,spacing=2,clockwise=0,pointMode=0),'domain recipe disagrees with compiler receipt')
    return data


def write_domain_sources(out,name):
    require(name in DOMAINS,'explicit supported recipe required')
    # Keep original VS, FS, array packing, dynamic indices/defaults and IDs.
    # These are genuinely different ESSL links, not re-tagged original words.
    outer,inner=(3,1) if name=='matrix-triangle' else (4,2)
    levels='if(gl_InvocationID==0){'+''.join(f'gl_TessLevelOuter[{i}]=1.;' for i in range(outer))+''.join(f'gl_TessLevelInner[{i}]=1.;' for i in range(inner))+'}'
    for suffix in ('vert','tesc','tese','frag'):
        text=(out/('matrix.'+suffix)).read_text()
        if suffix=='tesc':
            old='gl_TessLevelOuter[gl_InvocationID]=1.;'
            require(text.count(old)==1,'original control fixture changed')
            text=text.replace(old,levels)
        if suffix=='tese':
            require(text.count('layout(isolines,equal_spacing)')==1,'original evaluation domain changed')
            text=text.replace('layout(isolines,equal_spacing)',f'layout({"triangles" if outer==3 else "quads"},equal_spacing,ccw)')
            old='matrices[indices.x][indices.y]+vectors[indices.z]'
            require(text.count(old)==1,'original dynamic matrix fixture changed')
            text=text.replace(old,old+'+gl_TessCoord.xyz')
        (out/(name+'.'+suffix)).write_text(text)


def verify_packet(prefix):
    require(prefix.name in DOMAINS,'explicit domain recipe required')
    domain_metadata(prefix.with_suffix('.domain.json'),DOMAINS[prefix.name])
    layout=[int(v) for v in prefix.with_suffix('.layout').read_text().split()]
    require(len(layout)==9 and layout[:6]==[2,8,2,10,6,8],'exact original matrix layout required')
    for suffix,count in zip(('.vs.words','.tcs.words','.tes.words'),layout[6:]):
        data=prefix.with_suffix(suffix).read_bytes()
        require(0<count<=512 and len(data)==count*4,'exact bounded code words required')
    require(layout[6]==61,'original VS must remain unchanged')
    source=json.loads(prefix.with_suffix('.source.json').read_text(),object_pairs_hook=unique)
    require(source['schema']=='opengpu-native-joint-compiler-source-v1','original source schema')
    require([s['stage'] for s in source['stages']]==[0,4,5,1],'original stage order')
    require([s['code']['bytes'] for s in source['stages'][:3]]==[n*4 for n in layout[6:]],'actual source/code byte extents')
    expected_vs=[[0,i] for i in range(4)]+[[1,i] for i in range(60,64)]+[[0,0]]*56
    expected_fs=[[1,i] for i in range(4)]+[[0,0]]*60+[[1,i] for i in range(4,8)]
    require(source['vertexComponents']==expected_vs,'full original VS/TCS mapping')
    require(source['fragmentComponents']==expected_fs,'full original TES/FS mapping')
    for stage in source['stages']:
        views=stage['views']
        require([v['slot'] for v in views]==list(range(13)),'complete original uniform slots')
        require([v['bytes'] for v in views]==([16]+[0]*12 if stage['stage']==5 else [0]*13),'real TES-only indices defaults')
    return layout


def protected_consume(programs,protected_root,sanitize):
    protected_root=protected_root.resolve(strict=True)
    script=protected_root/'scripts/test_tessellation_program_snapshot.py'
    require(script.is_file(),'real protected consumer script required')
    require('--owned-tessellation-info' in script.read_text(),'version3 independent consumer required')
    # Fresh production fixture compilation/actual owner+Sv32 MMU, never a VM.
    with tempfile.TemporaryDirectory(prefix='domain-rx-',dir=programs) as temporary:
        target=Path(temporary)
        for name in DOMAINS:
            prefix=programs/name;layout=verify_packet(prefix)
            for stage in ('tcs','tes'):
                shutil.copyfile(prefix.with_suffix('.'+stage+'.words'),target/('native-'+stage+'.words'))
            command=[sys.executable,'-B',str(script),'--programs',str(target),
                '--tcs-layout',*map(str,layout[:5]),'--tes-layout','0',str(layout[5]),
                '--tes-program-version','3','--tcs-program-version','3','--owned-tessellation-info',
                '--tes-patch-layout',*map(str,layout[:5])]
            if sanitize:command.append('--sanitize')
            subprocess.run(command,check=True,env=dict(os.environ,PYTHONDONTWRITEBYTECODE='1'),timeout=180)
            print('NATIVE_JOINT_DOMAIN_RX PASS recipe='+name+' actualOwner=true MMU=true GPU=false')


class DomainTests(unittest.TestCase):
    def test_missing_original_fixture_rejected_before_work(self):
        with tempfile.TemporaryDirectory() as directory:
            out=Path(directory)/'must-not-create'
            command=[sys.executable,'-B',str(ROOT/'scripts/test_native_joint.py'),
                '--domains','--build',str(out),'--legacy-source',str(out/'missing-uniform.c'),
                '--tess-baseline-source',str(out/'missing-tess.c')]
            result=subprocess.run(command,capture_output=True,text=True,timeout=10)
            self.assertEqual(result.returncode,2)
            self.assertIn('--domains requires --joint-baseline-fixture',result.stderr)
            self.assertFalse(out.exists(),'missing prerequisite must fail before build/output creation')
    def test_receipt_and_every_field_mutation(self):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory)/'domain.json'
            original=dict(schema=SCHEMA,version=1,stage=5,domain=4,spacing=2,clockwise=0,pointMode=0)
            p.write_text(json.dumps(original));self.assertEqual(domain_metadata(p,4),original)
            for key in original:
                wrong=dict(original);wrong[key]='wrong' if key=='schema' else wrong[key]+1
                p.write_text(json.dumps(wrong))
                with self.subTest(key=key),self.assertRaises(ValueError):domain_metadata(p,4)
            for wrong in ({**original,'extra':0},{k:v for k,v in original.items() if k!='spacing'},
                          {**original,'clockwise':False},{**original,'domain':7}):
                p.write_text(json.dumps(wrong))
                with self.assertRaises(ValueError):domain_metadata(p,4)
            p.write_text(json.dumps(original)[:-1]+',"domain":4}')
            with self.assertRaises(ValueError):domain_metadata(p,4)
    def test_exact_original_source_and_complete_levels(self):
        from test_native_joint import write_matrix
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory);write_matrix(p)
            original={s:(p/('matrix.'+s)).read_bytes() for s in ('vert','tesc','tese','frag')}
            for name in DOMAINS:
                write_domain_sources(p,name)
                self.assertEqual((p/(name+'.vert')).read_bytes(),original['vert'])
                self.assertEqual((p/(name+'.frag')).read_bytes(),original['frag'])
                control=(p/(name+'.tesc')).read_text();evaluation=(p/(name+'.tese')).read_text()
                n,m=(3,1) if name=='matrix-triangle' else (4,2)
                for i in range(n):self.assertIn(f'gl_TessLevelOuter[{i}]=1.;',control)
                for i in range(m):self.assertIn(f'gl_TessLevelInner[{i}]=1.;',control)
                self.assertIn('if(gl_InvocationID==0){',control);self.assertIn('barrier();',control)
                self.assertIn('+gl_TessCoord.xyz',evaluation);self.assertIn('float(gl_PrimitiveID)',evaluation)
                self.assertIn('matrices[indices.x][indices.y]+vectors[indices.z]',evaluation)
            self.assertEqual(original,{s:(p/('matrix.'+s)).read_bytes() for s in original})
            with self.assertRaises(ValueError):write_domain_sources(p,'renamed-isoline')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--programs',type=Path);parser.add_argument('--protected-root',type=Path)
    parser.add_argument('--sanitize',action='store_true');args=parser.parse_args()
    if bool(args.programs)!=bool(args.protected_root):parser.error('--programs and --protected-root must be supplied together')
    result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(DomainTests))
    if not result.wasSuccessful():raise SystemExit(1)
    if args.programs:protected_consume(args.programs.resolve(strict=True),args.protected_root,args.sanitize)


if __name__=='__main__':main()
