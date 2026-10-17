#!/usr/bin/env python3
"""Exercise stream integrity and failure handling without a GPU build."""
from pathlib import Path
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

SCRIPT=Path(__file__).with_name('stream_arti_trace.py')

class StreamTraceTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.root=Path(self.temp.name)
        self.payload=b'ARTI_TRACE_v1'+bytes(3)+bytes(range(256))*257
        (self.root/'input.bin').write_bytes(self.payload)
        digest=hashlib.sha256(self.payload).hexdigest()
        self.producer=self.program('producer.py',
            "import os\nfrom pathlib import Path\n"
            f"data=Path({str(self.root/'input.bin')!r}).read_bytes()\n"
            "with open(os.environ['ARTI_MODEL_TRACE'],'wb',buffering=0) as f:\n"
            " for start in range(0,len(data),37): f.write(data[start:start+37])\n")
        self.consumer=self.program('consumer.py',
            "import hashlib,json,sys\n"
            "data=open(sys.argv[1],'rb').read()\n"
            f"assert hashlib.sha256(data).hexdigest()=={digest!r}\n"
            "print(json.dumps(dict(calls=7,ticks=31,truncated_tail=False)))\n")

    def tearDown(self):
        self.temp.cleanup()

    def program(self,name,source):
        path=self.root/name
        path.write_text('#!'+sys.executable+'\n'+source)
        path.chmod(0o755)
        return path

    def run_stream(self,producer=None,consumer=None):
        return subprocess.run([sys.executable,str(SCRIPT),'--trace',str(self.root/'saved.trace'),
            '--work',str(self.root/'work'),'--replayer',str(consumer or self.consumer),
            '--',str(producer or self.producer)],capture_output=True,text=True,timeout=20)

    def test_fragmented_stream_is_saved_and_verified_byte_for_byte(self):
        result=self.run_stream()
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual((self.root/'saved.trace').read_bytes(),self.payload)
        state=json.loads((self.root/'work/state.json').read_text())
        self.assertTrue(state['passed'])
        self.assertEqual(state['trace_bytes'],len(self.payload))
        self.assertEqual(state['trace_sha256'],hashlib.sha256(self.payload).hexdigest())
        self.assertEqual(state['strict_replay']['calls'],7)

    def test_truncated_tail_is_rejected_even_if_replayer_exits_zero(self):
        consumer=self.program('truncated.py',
            "import json,sys\nopen(sys.argv[1],'rb').read()\n"
            "print(json.dumps(dict(calls=7,truncated_tail=True)))\n")
        result=self.run_stream(consumer=consumer)
        self.assertNotEqual(result.returncode,0)
        state=json.loads((self.root/'work/state.json').read_text())
        self.assertFalse(state['passed'])
        self.assertIn('complete trace',state['error'])

    def test_failed_producer_stops_blocked_relay_and_replayer(self):
        producer=self.program('failed.py','raise SystemExit(9)\n')
        result=self.run_stream(producer=producer)
        self.assertNotEqual(result.returncode,0)
        state=json.loads((self.root/'work/state.json').read_text())
        self.assertFalse(state['passed'])
        self.assertIn('producer',state['error'])
        for pid in state['child_pids'].values():
            with self.assertRaises(ProcessLookupError): os.kill(pid,0)

    def test_successful_producer_without_recording_fails_instead_of_hanging(self):
        producer=self.program('no-recording.py','raise SystemExit(0)\n')
        result=self.run_stream(producer=producer)
        self.assertNotEqual(result.returncode,0)
        state=json.loads((self.root/'work/state.json').read_text())
        self.assertIn('without opening',state['error'])
        for pid in state['child_pids'].values():
            with self.assertRaises(ProcessLookupError):os.kill(pid,0)

    def test_inherited_replay_settings_do_not_disable_recording(self):
        producer=self.program('env-producer.py',
            "import os,runpy\n"
            "assert 'ARTI_MODEL_REPLAY' not in os.environ\n"
            "assert 'ARTI_REPLAY_LIMIT' not in os.environ\n"
            "assert os.environ['LLVM_PROFILE_FILE']=='keep-producer-profile-%p'\n"
            f"runpy.run_path({str(self.producer)!r},run_name='__main__')\n")
        with patch.dict(os.environ,ARTI_MODEL_REPLAY='stale.trace',
                        ARTI_REPLAY_LIMIT='1',LLVM_PROFILE_FILE='keep-producer-profile-%p'):
            result=self.run_stream(producer=producer)
        self.assertEqual(result.returncode,0,result.stderr)

    def test_existing_trace_is_preserved(self):
        path=self.root/'saved.trace'
        path.write_bytes(b'keep this result')
        result=self.run_stream()
        self.assertNotEqual(result.returncode,0)
        self.assertEqual(path.read_bytes(),b'keep this result')
        self.assertFalse((self.root/'work').exists())

    def test_cancellation_stops_owned_process_groups(self):
        producer=self.program('waiting.py','import time\ntime.sleep(60)\n')
        process=subprocess.Popen([sys.executable,str(SCRIPT),'--trace',str(self.root/'saved.trace'),
            '--work',str(self.root/'work'),'--replayer',str(self.consumer),
            '--',str(producer)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        try:
            deadline=time.monotonic()+10
            while time.monotonic()<deadline:
                path=self.root/'work/state.json'
                if path.exists():
                    state=json.loads(path.read_text())
                    if state['stage']=='record-and-strict-replay':break
                time.sleep(0.02)
            else:self.fail('stream controller did not start')
            process.terminate()
            process.communicate(timeout=10)
            self.assertNotEqual(process.returncode,0)
            state=json.loads((self.root/'work/state.json').read_text())
            self.assertFalse(state['passed'])
            self.assertIn('cancelled',state['error'])
            for pid in state['child_pids'].values():
                with self.assertRaises(ProcessLookupError):os.kill(pid,0)
        finally:
            if process.poll() is None:process.kill();process.communicate()

if __name__=='__main__':
    unittest.main()
