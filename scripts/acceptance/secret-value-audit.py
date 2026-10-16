"""Check known running-cloud secrets without printing values or raw logs."""
import datetime as dt
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def capture(args):
    return subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          check=True, timeout=60).stdout


def main():
    container = json.loads(capture(['docker', 'inspect', 'tcg-store-service-backend-cloud']))[0]
    env = dict(x.split('=', 1) for x in container['Config']['Env'] if '=' in x)
    sensitive = ('APP_JWT_SECRET', 'NEON_DB_PASSWORD', 'OBJECT_STORAGE_ACCESS_KEY', 'OBJECT_STORAGE_SECRET_KEY')
    needles = [env[name].encode() for name in sensitive if len(env.get(name, '')) >= 8]
    if len(needles) != len(sensitive):
        raise ValueError('Expected private values unavailable for exact-value audit')
    tracked = capture(['git', '-C', str(ROOT), 'ls-files', '-z']).decode().split('\0')
    matched_paths = []
    for relative in tracked:
        file = ROOT / relative
        if relative and file.is_file() and file.stat().st_size < 10 * 1024 * 1024:
            data = file.read_bytes()
            if any(secret in data for secret in needles):
                matched_paths.append(relative)
    logs = subprocess.run(['docker', 'logs', 'tcg-store-service-backend-cloud'],
                          capture_output=True, check=True, timeout=60)
    log_found = any(secret in logs.stdout or secret in logs.stderr for secret in needles)
    image_found = {}
    for tag in ('tcg-store-service-backend:dev', 'tcg-accept-20261016-backend:current'):
        metadata = capture(['docker', 'image', 'inspect', tag])
        history = capture(['docker', 'history', '--no-trunc', tag])
        image_found[tag] = any(secret in metadata or secret in history for secret in needles)
    report = {'at': dt.datetime.now(dt.timezone.utc).isoformat(),
              'scope': 'exact known private values in tracked worktree, running-cloud logs, image metadata/history',
              'secretCountChecked': len(needles), 'matchedTrackedPaths': matched_paths,
              'runningCloudLogsContainSecret': log_found,
              'imageMetadataHistoryContainSecret': image_found,
              'limitations': ['Does not inspect arbitrary unknown secrets or unpacked image filesystem layers'],
              'status': 'passed' if not matched_paths and not log_found and not any(image_found.values()) else 'failed'}
    output = ROOT / 'target/acceptance-20261016/secret-value-audit.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report, indent=2))
    return 0 if report['status'] == 'passed' else 1


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except Exception as error:
        print(json.dumps({'status': 'failed', 'errorType': type(error).__name__}))
        raise SystemExit(1)
