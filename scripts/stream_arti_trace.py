#!/usr/bin/env python3
"""Capture a trace and strictly replay the same byte stream concurrently.

The original replayer is unchanged. Relay backpressure can affect producer wall
clock observations, so this is functional qualification, not a speed benchmark.
"""
from pathlib import Path
import argparse,hashlib,json,os,signal,subprocess,sys,time

def relay(source,sink,saved,report):
    digest=hashlib.sha256();total=0
    # The consumer starts first, then the producer. Never open an extra writer
    # on either FIFO: producer closure must propagate an unambiguous EOF.
    output=os.open(sink,os.O_WRONLY)
    input_fd=os.open(source,os.O_RDONLY)
    try:
        Path(report+'.connected').touch(exist_ok=False)
        with open(saved,'xb') as trace:
            while True:
                data=os.read(input_fd,65536)
                if not data:break
                trace.write(data);digest.update(data)
                view=memoryview(data)
                while view:
                    count=os.write(output,view)
                    if count<=0:raise RuntimeError('short FIFO write')
                    view=view[count:]
                total+=len(data)
        Path(report).write_text(json.dumps(dict(passed=True,bytes=total,sha256=digest.hexdigest()))+'\n')
    finally:
        os.close(input_fd);os.close(output)

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--trace',type=Path,required=True)
    p.add_argument('--work',type=Path,required=True)
    p.add_argument('--replayer',type=Path,required=True)
    p.add_argument('producer',nargs=argparse.REMAINDER)
    a=p.parse_args();command=a.producer[1:] if a.producer[:1]==['--'] else a.producer
    if not command: p.error('a producer command is required after --')
    work=a.work.resolve();trace=a.trace.resolve();replayer=a.replayer.resolve()
    if not replayer.is_file(): raise FileNotFoundError(replayer)
    if work.exists(): raise FileExistsError(work)
    if trace.exists(): raise FileExistsError(trace)
    work.mkdir(parents=True);trace.parent.mkdir(parents=True,exist_ok=True)
    record=work/'record.fifo';replay=work/'replay.fifo'
    os.mkfifo(record,0o600);os.mkfifo(replay,0o600)
    state=dict(pid=os.getpid(),started=time.time(),stage='start-stream',trace=str(trace),replayer=str(replayer),producer=command,child_pids={},scope='Concurrent actual recording and unchanged strict replay; relay saves exactly the bytes delivered to the replayer. Functional elapsed only; producer wall-clock observations may be delayed by backpressure.')
    def save():
        temp=work/'state.tmp';temp.write_text(json.dumps(state,indent=2)+'\n');os.replace(temp,work/'state.json')
    save();jobs={};logs={}
    def cancel(signum,frame): raise InterruptedError('stream qualification cancelled')
    signal.signal(signal.SIGTERM,cancel)
    try:
        env=dict(os.environ)
        replay_env={k:v for k,v in env.items() if k not in ['ARTI_MODEL_TRACE','ARTI_MODEL_REPLAY'] and not k.startswith('ARTI_REPLAY_')}
        producer_env=dict(replay_env,ARTI_MODEL_TRACE=str(record))
        replay_env['LLVM_PROFILE_FILE']=str(work/'replayer-%p.profraw')
        plans=[('replay',[str(replayer),str(replay)],replay_env),
               ('relay',[sys.executable,str(Path(__file__).resolve()),'--relay',str(record),str(replay),str(trace),str(work/'relay-result.json')],env),
               ('producer',command,producer_env)]
        for name,args,child_env in plans:
            logs[name]=(work/(name+'.log')).open('w')
            jobs[name]=subprocess.Popen(args,env=child_env,stdout=logs[name],stderr=subprocess.STDOUT,start_new_session=True)
            state['child_pids'][name]=jobs[name].pid;save()
        state['stage']='record-and-strict-replay';save()
        producer_finished=None
        while any(job.poll() is None for job in jobs.values()):
            for name,job in jobs.items():
                if job.poll() is not None and job.returncode!=0:
                    raise RuntimeError((name,job.returncode))
            if jobs['producer'].poll() == 0:
                if producer_finished is None: producer_finished=time.monotonic()
                if time.monotonic()-producer_finished>2 and not (work/'relay-result.json.connected').exists():
                    raise RuntimeError('producer exited without opening the recording FIFO')
            time.sleep(0.5)
        for name,job in jobs.items():
            assert job.returncode==0,(name,job.returncode);logs[name].close()
        rows=[json.loads(line) for line in (work/'replay.log').read_text().splitlines() if line.startswith('{')]
        if len(rows)!=1 or rows[0].get('truncated_tail',True) or rows[0].get('calls',0)<=0:
            raise RuntimeError('strict replay did not consume a complete trace')
        copied=json.loads((work/'relay-result.json').read_text())
        if not copied['passed'] or copied['bytes']!=trace.stat().st_size:
            raise RuntimeError('relay byte count differs from saved trace')
        with trace.open('rb') as f:
            if f.read(13)!=b'ARTI_TRACE_v1': raise RuntimeError('invalid ARTI trace header')
            f.seek(0);digest=hashlib.file_digest(f,'sha256').hexdigest()
        if digest!=copied['sha256']: raise RuntimeError('saved trace differs from replayed byte stream')
        state.update(stage='terminal',passed=True,finished=time.time(),strict_replay=rows[0],trace_sha256=digest,trace_bytes=trace.stat().st_size,relay=copied);save()
        print(json.dumps(dict(passed=True,strict_replay=rows[0],trace_sha256=digest,trace_bytes=trace.stat().st_size)),flush=True)
    except BaseException as e:
        state.update(stage='terminal',passed=False,error=repr(e),finished=time.time());save();raise
    finally:
        for name,job in jobs.items():
            if job.poll() is None:
                try:os.killpg(job.pid,signal.SIGTERM)
                except ProcessLookupError:pass
                try:job.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    try:os.killpg(job.pid,signal.SIGKILL)
                    except ProcessLookupError:pass
                    job.wait()
        for log in logs.values():
            if not log.closed:log.close()

if __name__=='__main__':
    if sys.argv[1:2]==['--relay']:
        assert len(sys.argv)==6
        relay(*sys.argv[2:])
    else:main()
