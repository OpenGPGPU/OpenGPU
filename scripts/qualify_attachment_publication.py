#!/usr/bin/env python3
"""Continuous color-attachment publication on a float framebuffer-blit-sample DUT."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time

from attachment_publication_cases import CASES, BASE, parameters
from attachment_publication_packets import build_packets
from framebuffer_blit_fixture import digest
from native_sample_owner_protocol import strict_json
import prepare_owner_executor as owner
from owner_resource_contract import IMAGE_FLOAT_PROFILE

ROOT = Path(__file__).resolve().parents[1]
KIND = 'framebuffer-blit-sample'


def require(ok, text):
    if not ok:
        raise RuntimeError('attachment publication: ' + text)


def qualified(args):
    require(args.executor.is_file() and os.access(args.executor, os.X_OK),
            'original qualified blit executable required')
    record = owner.verify_qualification(args.executor_qualification, executor=args.executor, kind=KIND)
    require(record.get('profile') == IMAGE_FLOAT_PROFILE and record.get('kind') == KIND and
            record.get('source_root') == str(ROOT),
            'exact float framebuffer-blit-sample factory required')
    return digest(args.executor_qualification), digest(args.executor)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--executor', type=Path, required=True)
    parser.add_argument('--executor-qualification', type=Path, required=True)
    parser.add_argument('--producer-template', type=Path, required=True)
    parser.add_argument('--consumer-template', type=Path, required=True)
    parser.add_argument('--timeout', type=int, default=3600)
    args = parser.parse_args()
    require(0 < args.timeout <= 86400, 'timeout must be 1..86400 seconds')
    for name in ('executor', 'executor_qualification', 'producer_template', 'consumer_template'):
        setattr(args, name, getattr(args, name).absolute())
    identity = qualified(args)
    temporary = Path(tempfile.mkdtemp(prefix='attachment-publication-', dir=ROOT / '.artifacts'))
    try:
        packets = build_packets(args.producer_template, args.consumer_template, temporary / 'packets')
        session = temporary / 'session'
        session.mkdir()
        for name in ('attachment-publication.plan', 'publication.cache-target', 'manifest.json'):
            shutil.copy2(packets / name, session / name)
        for index, case in enumerate(CASES):
            shutil.copy2(packets / (case + '.blit'), session / ('round-%d.blit' % index))
        output = temporary / 'results'
        require(qualified(args) == identity, 'qualified blit source/model/binary changed')
        deadline = time.monotonic() + args.timeout
        process = subprocess.Popen(
            [str(args.executor), '--active-attachment-publication', str(output), str(session)],
            cwd=ROOT, start_new_session=True,
            env=dict(os.environ, TMPDIR=str(temporary), PYTHONDONTWRITEBYTECODE='1'))
        try:
            require(process.wait(timeout=max(1, deadline - time.monotonic())) == 0,
                    'attachment publication executor failed')
        finally:
            if process.poll() is None:
                process.kill()
        for index, case in enumerate(CASES):
            done = strict_json(session / ('executor.round-%d.json' % index))
            require(done.get('schema') == 'opengpu-attachment-publication-round-v1' and
                    done.get('phase') == index and done.get('role') == parameters(case)['role'] and
                    done.get('resetCount') == 1 and done.get('ramReplaced') is False,
                    'attachment publication round receipt differs')
            print('original attachment publication phase=%d role=%s full bytes/cleanup PASS' % (
                index, parameters(case)['role']), flush=True)
        observation = strict_json(session / 'publication.cache-observation.json')
        require(observation.get('schema') == 'opengpu-attachment-publication-fabric-observation-v1' and
                observation.get('uncachedAttachmentPath') is True and
                observation.get('warmReadRequestCycle') < observation.get('warmReadResponseCycle') <
                observation.get('firstTargetWriteCycle') < observation.get('reloadReadRequestCycle') <
                observation.get('reloadReadResponseCycle'),
                'attachment publication fabric observation order differs')
        require(qualified(args) == identity, 'blit source/tool/model changed during execution')
        print('all three original attachment publication phases PASS; one DUT/RAM/reset, '
              'warm/producer/reload, uncached fabric observation, original source/model postchecks',
              flush=True)
        print('original Mesa framebuffer attachment-publication: actual full-DUT returned bytes '
              'and context cleanup PASS', flush=True)
        print('ATTACHMENT_PUBLICATION_BASE', hex(BASE), flush=True)
    finally:
        shutil.rmtree(temporary, ignore_errors=True)


if __name__ == '__main__':
    main()
