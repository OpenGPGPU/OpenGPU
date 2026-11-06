"""H8 original-run binding with explicit compact16 INDEX support.

The shared H7/common verifier is unchanged. This never rewrites captured files.
"""
import json
from pathlib import Path
from check_guest_common import digest, need

def check_index_app(index, config, process, app, need):
    if 'app' in index:
        need(index['app'] == app, 'launch INDEX selects another app')
        return
    need(set(index) == {'state', 'files'} and
         index['state'] == 'UNARMED_16_ITEM_H8_NO_GUEST_EXECUTED' and
         app == 'h8' and config.get('app') == 'h8' and
         type(config.get('qualification_items')) is int and
         config['qualification_items'] == 16 and
         config.get('qualification_local') == [8, 2, 1] and
         all(type(n) is int for n in config['qualification_local']) and
         config.get('original_128_actual_guest_passed') is False,
         'unsupported compact launch INDEX schema/profile')
    argv = process['argv']
    need(argv == config['argv'] and argv.count('-append') == 1 and
         argv[argv.index('-append') + 1] ==
         'console=ttyAMA0 cma=1536M@0x80000000 rdinit=/init opengpu.qual=h8',
         'compact H8 INDEX does not bind the actual selected app')


def check_original_run(launch_result_path,serial,stderr,proof,dtb,original_terminal,app):
    """Bind immutable evidence to the one original supervised QEMU attempt.

    Exit0 is necessary process evidence. Actual boot/app/PTE checks remain
    separate and no semantic PASS is inferred from this record.
    """
    result=json.loads(Path(launch_result_path).read_text())
    need(result['status']=='EXITED_ZERO_UNVERIFIED' and result['app']==app and not result['changed_inputs'],'original attempt failed/changed/mixed')
    need(result['semantic_pass'] is False and result['automatic_retry'] is False and result['shared_model_lock_released'],'wrong original result contract/lock')
    logs={}
    for row in result['immutable_logs']:
        p=Path(row['path']).resolve();need(p not in logs,'duplicate immutable log')
        need(p.stat().st_size==row['bytes'] and digest(p)==row['sha256'],'original log bytes changed');logs[p]=row
    need(all(Path(p).resolve()in logs for p in(serial,stderr,original_terminal)),'CLI evidence does not belong to original attempt')
    terminal=json.loads(Path(original_terminal).read_text())
    need(terminal==result['original_terminal'] and terminal['exit_code']==0 and terminal['stop']is None and not terminal['remaining'] and terminal.get('coordinator_error')is None and terminal['automatic_retry']is False and terminal['semantic_pass']is False,'original process/owned drain failure')
    processes=[p for p in logs if p.name=='original-process.json'];need(len(processes)==1,'original process record absent')
    process=json.loads(processes[0].read_text());need(process['PID']>0 and process['PGID']==process['PID'] and process['birth'],'original process identity incomplete')
    config_ref=result['config'];need(digest(config_ref['path'])==config_ref['sha256'],'original configuration changed')
    config_path=Path(config_ref['path']);config=json.loads(config_path.read_text());need(config['app']==app and process['argv']==config['argv'] and process['environment']==config['environment'],'actual argv/environment differs from reviewed original config')
    index_path=config_path.parent/'INDEX.json';need(digest(index_path)==result['launch_index_sha256'],'original reviewed launch INDEX changed')
    index=json.loads(index_path.read_text())
    check_index_app(index,config,process,app,need)
    need(any(row['path']=='config.json'and row['sha256']==config_ref['sha256']for row in index['files']),'original configuration is not in approved launch INDEX')
    argv=process['argv'];env=process['environment']
    def arg(name):need(argv.count(name)==1,'duplicate/missing actual '+name);return argv[argv.index(name)+1]
    need(arg('-m')=='3072M' and Path(arg('-dtb')).resolve()==Path(dtb).resolve(),'wrong original RAM/DTB argv')
    need(arg('-serial')=='file:'+str(Path(serial).resolve()),'wrong original serial argv')
    need(Path(argv[0]).resolve()==Path(config['paths']['qemu']).resolve() and all(Path(arg(flag)).resolve()==Path(config['paths'][key]).resolve()for flag,key in[('-kernel','kernel'),('-initrd','initramfs')]),'original executable/kernel/initramfs argv differs from approved config')
    need(arg('-append')=='console=ttyAMA0 cma=1536M@0x80000000 rdinit=/init opengpu.qual='+app,'wrong original selected app/boot argv')
    need(env.get('ARTI_NATIVE_MEMORY_PROOF')==str(Path(proof).resolve()) and env.get('ARTI_NATIVE_RAM_BASE')=='0x40000000' and env.get('ARTI_NATIVE_RAM_END')=='0x100000000','observer env does not bind actual proof/RAM')
    need(env.get('ARTI_NATIVE_REQUIRE_TWO_CUS')==('1'if app=='h7'else None),'wrong workload-specific two-CU observer gate')
    authority=result['actual_dtb'];need(Path(authority['path']).resolve()==Path(dtb).resolve() and digest(dtb)==authority['sha256'],'actual DTB changed/mismatched')
    need(Path(result['proof_directory']).resolve()==Path(proof).resolve(),'wrong actual proof directory')
    manifest_ref=result['proof_manifest'];need(digest(manifest_ref['path'])==manifest_ref['sha256'],'proof manifest changed')
    manifest=json.loads(Path(manifest_ref['path']).read_text());need(Path(manifest['directory']).resolve()==Path(proof).resolve(),'proof manifest labels another run')
    paths=set()
    for row in manifest['files']:
        p=Path(row['path']).resolve();need(p.is_relative_to(Path(proof).resolve()) and p not in paths,'proof path escape/duplicate')
        need(p.stat().st_size==row['bytes'] and digest(p)==row['sha256'],'actual proof bytes changed');paths.add(p)
    need(paths=={p.resolve()for p in Path(proof).rglob('*')if p.is_file()},'actual proof membership changed')
    need(len(paths)==manifest_ref['files'],'proof file count differs')
    approval=result['root_approval'];need(digest(approval['path'])==approval['sha256'],'original root action approval changed')
    authority=json.loads(Path(approval['path']).read_text());need(authority['action']=='ONE_RESOURCE_GUEST_BOOT_NO_BUILD' and authority['app']==app and authority['index_sha256']==result['launch_index_sha256'] and all(authority[k]==config[k]for k in('outer_max_seconds','owned_RSS_cap_bytes')),'root approval does not authorize this original app/INDEX/budget')
    return dict(originalPid=process['PID'],originalBirth=process['birth'],launchIndexSha256=result['launch_index_sha256'],immutableLogs=len(logs),proofFiles=len(paths),actualArgvAndObserverBoundsBound=True)
