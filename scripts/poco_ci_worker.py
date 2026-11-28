#!/usr/bin/env python3
"""Execute one trusted, completed hosted build on Poco, using fixed local code.

No self-hosted Actions runner, downloaded script execution or arbitrary refs.
GitHub writes require the explicit --publish flag and existing gh authorization.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile

import device_ci

REPO = 'Samuel-Ku/slukhayka'
WORKFLOW = 'poco-device.yml'
CONTEXT = 'device/poco-f3'


def gh(*args):
    try:
        result = subprocess.run(['gh', *args], capture_output=True, text=True, timeout=120, check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise device_ci.DeviceError(f'GitHub command unavailable: {error}') from error
    if result.returncode:
        raise device_ci.DeviceError(f'GitHub command failed: {result.stderr.strip()}')
    return result.stdout


def trusted_run(run, workflow_id, comparison):
    sha = run.get('head_sha', '')
    if (run.get('workflow_id') != workflow_id or run.get('event') != 'workflow_dispatch'
            or run.get('head_branch') != 'main' or run.get('status') != 'completed'
            or run.get('conclusion') != 'success'
            or (run.get('head_repository') or {}).get('full_name') != REPO
            or not re.fullmatch(r'[0-9a-f]{40}', sha)
            or comparison.get('status') not in ('identical', 'ahead')):
        raise device_ci.DeviceError('run is not a successful trusted main build of the Poco workflow')
    return sha


def execute(args):
    # Identity and real input permission fail fast, before network/downloads.
    device = device_ci.Device(args.serial)
    facts = device.preflight()
    workflow = json.loads(gh('api', f'repos/{REPO}/actions/workflows/{WORKFLOW}'))
    run = json.loads(gh('api', f'repos/{REPO}/actions/runs/{args.run_id}'))
    candidate_sha = run.get('head_sha', '')
    if not re.fullmatch(r'[0-9a-f]{40}', candidate_sha):
        raise device_ci.DeviceError('invalid source SHA in GitHub run')
    comparison = json.loads(gh('api', f'repos/{REPO}/compare/{candidate_sha}...main'))
    sha = trusted_run(run, workflow['id'], comparison)
    reports = args.reports_dir.resolve() / str(args.run_id)
    if reports.exists():
        raise device_ci.DeviceError(f'receipt directory already exists; inspect before retry: {reports}')
    reports.mkdir(parents=True)
    state, detail = 'success', 'All five instrumented suites passed'
    with tempfile.TemporaryDirectory(prefix='slukhayka-poco-apks-') as temporary:
        artifacts = Path(temporary)
        gh('run', 'download', str(args.run_id), '--repo', REPO,
           '--name', f'poco-ci-apks-{args.run_id}', '--dir', str(artifacts))
        manifest = device_ci.verify_artifacts(artifacts)
        if manifest['sourceSha'] != sha:
            raise device_ci.DeviceError('artifact source SHA differs from the trusted hosted run')
        try:
            device_ci.run_suites(device, artifacts, reports)
        except device_ci.DeviceError as error:
            state, detail = 'failure', str(error)
    receipt = {'runId': args.run_id, 'sourceSha': sha, 'device': facts,
               'state': state, 'detail': detail, 'suites': list(device_ci.SUITES)}
    (reports / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    if args.publish:
        logs = []
        for log in sorted(reports.glob('*.log')):
            logs.append(f'**{log.name}**\n```text\n{log.read_text()[-1800:]}\n```')
        body = (f'<!-- poco-ci:{args.run_id} -->\nPoco F3 CI: **{state}**\n\n'
                f'Source: `{sha}`\nHosted run: {run["html_url"]}\n\n{detail}\n\n' + '\n\n'.join(logs))
        comment = json.loads(gh('api', '--method', 'POST', f'repos/{REPO}/commits/{sha}/comments',
                                '-f', f'body={body}'))
        gh('api', '--method', 'POST', f'repos/{REPO}/statuses/{sha}',
           '-f', f'state={state}', '-f', f'context={CONTEXT}',
           '-f', f'description=Poco F3: {detail[:110]}', '-f', f'target_url={comment["html_url"]}')
    print(json.dumps(receipt))
    return 0 if state == 'success' else 1

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-id', required=True, type=int)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--reports-dir', required=True, type=Path)
    parser.add_argument('--publish', action='store_true', help='publish commit receipt and device/poco-f3 status')
    args = parser.parse_args()
    if args.run_id <= 0:
        parser.error('--run-id must be positive')
    try:
        with device_ci.device_lock():
            return execute(args)
    except (device_ci.DeviceError, OSError, ValueError, KeyError) as error:
        print(f'BLOCKED: {error}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
