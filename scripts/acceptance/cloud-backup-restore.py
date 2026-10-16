"""Snapshot the live Neon app schema, restore into an isolated local PostgreSQL.

Reads production-like data but never writes to Neon. Captures all subprocess
output privately and publishes only aggregate checksums/counts and safe status.
"""
import argparse
import datetime as dt
import hashlib
import json
import os
import re
from pathlib import Path
import secrets
import subprocess
import sys
import time
from urllib.parse import urlsplit

import psycopg
import certifi

PREFIX = 'tcg-accept-20261016-cloudrestore'
APP_TABLES = ('users', 'global_cards', 'local_cards', 'orders', 'order_items',
              'cart_items', 'collection_items', 'flyway_schema_history')


class CommandFailure(RuntimeError):
    def __init__(self, args, result):
        super().__init__('Subprocess failed')
        text = result.stderr.decode('utf-8', errors='replace').lower()
        self.details = {'exitCode': result.returncode,
            'operation': next((name for name in ('pg_dump', 'pg_restore', 'psql', 'java') if name in args), 'docker'),
            'categories': [label for label, pattern in (
                ('tls-root', 'root certificate'), ('certificate', 'certificate verify'),
                ('snapshot', 'snapshot'), ('permission', 'permission denied'),
                ('version', 'version mismatch'), ('timeout', 'timeout'),
                ('connection', 'connection'), ('collation', 'collation'),
                ('extension', 'extension'), ('unrecognized-setting', 'unrecognized configuration parameter'))
                if pattern in text]}
        self.details['categories'].extend(label for label, pattern in (
            ('already-exists', 'already exists'), ('missing-role', 'role '),
            ('missing-schema', 'schema '), ('missing-function', 'function '),
            ('missing-type', 'type '), ('not-exist', 'does not exist'),
            ('unsupported', 'not supported'), ('syntax', 'syntax error')) if pattern in text)


def command(args, **kwargs):
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            timeout=240, **kwargs)
    if result.returncode:
        raise CommandFailure(args, result)
    return result.stdout


def fingerprints(cursor):
    results = {}
    for table in APP_TABLES:
        # Sorted row hashes avoid relying on every table having the same PK.
        cursor.execute('SELECT count(*), md5(coalesce(string_agg(h, \'\' ORDER BY h), \'\')) '
                       'FROM (SELECT md5(to_jsonb(t)::text) AS h FROM public.' + table + ' t) rows')
        count, digest = cursor.fetchone()
        results[table] = {'rows': count, 'digest': digest}
    return results


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True)
    parser.add_argument('--postgres-image', required=True)
    parser.add_argument('--backend-image', default='tcg-accept-20261016-backend:current')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    output = Path(args.output).resolve()
    if not output.is_relative_to(root / 'target'):
        raise ValueError('Output must be in ignored target')
    container = json.loads(command(['docker', 'inspect', 'tcg-store-service-backend-cloud']))[0]
    values = dict(x.split('=', 1) for x in container['Config']['Env'] if '=' in x)
    url = urlsplit(values['NEON_JDBC_URL'][5:])
    if not (url.hostname or '').endswith('.neon.tech'):
        raise ValueError('Unexpected source provider')
    report = {'at': dt.datetime.now(dt.timezone.utc).isoformat(),
              'sourceWrites': False, 'destination': 'isolated-local-postgresql',
              'status': 'running', 'checks': {}}
    local_password = secrets.token_urlsafe(32)
    subprocess_env = dict(os.environ, PGHOST=url.hostname, PGPORT=str(url.port or 5432),
        PGDATABASE=url.path.lstrip('/'), PGUSER=values['NEON_DB_USERNAME'],
        PGPASSWORD=values['NEON_DB_PASSWORD'], PGSSLMODE='verify-full',
        PGOPTIONS='-c default_transaction_read_only=on', PGSSLROOTCERT='/tmp/tcg-ca.pem',
        POSTGRES_PASSWORD=local_password)
    network_created = False
    container_created = False
    try:
        # Refuse collisions: this script never replaces an existing resource.
        if subprocess.run(['docker', 'container', 'inspect', PREFIX], capture_output=True).returncode == 0:
            raise ValueError('Isolated restore container already exists')
        command(['docker', 'network', 'create', PREFIX])
        network_created = True
        command(['docker', 'run', '-d', '--name', PREFIX, '--network', PREFIX,
            '--network-alias', 'restore-db', '-e', 'POSTGRES_PASSWORD', '-e', 'POSTGRES_DB=restore',
            args.postgres_image], env=subprocess_env)
        container_created = True
        for _ in range(60):
            ready = subprocess.run(['docker', 'exec', PREFIX, 'pg_isready', '-U', 'postgres', '-d', 'restore'], capture_output=True)
            if ready.returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError('Restore database readiness timed out')
        with psycopg.connect(host=url.hostname, port=url.port or 5432,
            dbname=url.path.lstrip('/'), user=values['NEON_DB_USERNAME'],
            password=values['NEON_DB_PASSWORD'], sslmode='verify-full', sslrootcert=certifi.where(), connect_timeout=15,
            options='-c default_transaction_read_only=on -c statement_timeout=60000') as conn:
            with conn.cursor() as cursor:
                cursor.execute('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY')
                cursor.execute('SELECT pg_export_snapshot()')
                snapshot = cursor.fetchone()[0]
                expected = fingerprints(cursor)
                dump = command(['docker', 'run', '--rm',
                    '--mount', 'type=bind,source=' + certifi.where() + ',target=/tmp/tcg-ca.pem,readonly',
                    '-e', 'PGHOST', '-e', 'PGPORT',
                    '-e', 'PGDATABASE', '-e', 'PGUSER', '-e', 'PGPASSWORD', '-e', 'PGSSLMODE',
                    '-e', 'PGOPTIONS', '-e', 'PGSSLROOTCERT', args.postgres_image,
                    'pg_dump', '--format=custom', '--no-owner', '--no-acl', '--schema=public',
                    '--snapshot=' + snapshot], env=subprocess_env)
        report['checks']['snapshotBackup'] = {'status': 'passed', 'bytes': len(dump),
                                                'sha256': hashlib.sha256(dump).hexdigest()}
        # Dump remains in memory, never a tracked file; restore is on a new target.
        command(['docker', 'exec', '-i', PREFIX, 'pg_restore', '--exit-on-error', '--clean', '--if-exists', '--no-owner',
                 '--no-acl', '--username=postgres', '--dbname=restore'], input=dump)
        del dump
        # Use container-local SQL for exact aggregate equality without exposing rows.
        actual = {}
        for table in APP_TABLES:
            sql = "SELECT count(*), md5(coalesce(string_agg(h, '' ORDER BY h), '')) FROM (SELECT md5(to_jsonb(t)::text) h FROM public." + table + ' t) rows'
            row = command(['docker', 'exec', PREFIX, 'psql', '-X', '-A', '-t', '-U', 'postgres',
                           '-d', 'restore', '-v', 'ON_ERROR_STOP=1', '-c', sql]).decode().strip()
            count, digest = row.split('|')
            actual[table] = {'rows': int(count), 'digest': digest}
        if actual != expected:
            raise ValueError('Restored application data checksum mismatch')
        report['checks']['restoredData'] = {'status': 'passed', 'tables': actual}
        migration_env = dict(os.environ, DB_PASSWORD=local_password)
        migration_output = command(['docker', 'run', '--rm', '--network', PREFIX,
            '-e', 'DB_URL=jdbc:postgresql://restore-db:5432/restore', '-e', 'DB_USERNAME=postgres',
            '-e', 'DB_PASSWORD', '-e', 'SPRING_PROFILES_ACTIVE=dev,migration', '-e', 'FLYWAY_ENABLED=true',
            '--entrypoint', 'java', args.backend_image,
            '-Dloader.main=com.mythix.tcg.DatabaseMigrationApplication', '-cp', '/app/app.jar',
            'org.springframework.boot.loader.launch.PropertiesLauncher'], env=migration_env)
        report['checks']['runnerHibernateValidation'] = {'status': 'passed'}
        if not re.search(rb'Successfully validated \d+ migrations?', migration_output):
            raise ValueError('Runner exited without Flyway validation evidence')
        report['checks']['flywayExecution'] = {'status': 'passed', 'validationMessageObserved': True}
        report['checks']['restoreIntoNeonBranch'] = {'status': 'pending', 'reason': 'No disposable Neon branch selected'}
        report['status'] = 'passed-local-restore'
    except Exception as exc:
        report.update(status='failed', errorType=type(exc).__name__)
        if isinstance(exc, CommandFailure):
            report['failure'] = exc.details
    finally:
        cleanup = []
        if container_created:
            result = subprocess.run(['docker', 'rm', '-f', '-v', PREFIX], capture_output=True)
            cleanup.append(result.returncode == 0)
        if network_created:
            result = subprocess.run(['docker', 'network', 'rm', PREFIX], capture_output=True)
            cleanup.append(result.returncode == 0)
        report['isolatedResourcesRemoved'] = bool(cleanup) and all(cleanup)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, indent=2), encoding='utf-8')
        print(json.dumps(report, indent=2))
    return 0 if report['status'] == 'passed-local-restore' else 1


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as error:
        print(json.dumps({'status': 'failed', 'errorType': type(error).__name__}))
        sys.exit(1)
