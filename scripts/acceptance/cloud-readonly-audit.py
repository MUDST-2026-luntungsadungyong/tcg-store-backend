"""Read-only DB/object audit using the running cloud container's private config.

Only aggregate counts are published. Credentials remain in process memory. Never
changes the database or uploads/deletes objects. Output directory must be ignored.
"""
import argparse
import datetime as dt
import json
import pathlib
import subprocess
import sys
import urllib.parse

import boto3
import certifi
import psycopg
from botocore.config import Config


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[2]
    output = pathlib.Path(args.output).resolve()
    if not output.is_relative_to(root / 'target'):
        raise ValueError('Audit output must stay in ignored target directory')
    raw = subprocess.run(['docker', 'inspect', 'tcg-store-service-backend-cloud'],
                         capture_output=True, check=True, text=True)
    container = json.loads(raw.stdout)[0]
    env = dict(item.split('=', 1) for item in container['Config']['Env'] if '=' in item)
    jdbc = env['NEON_JDBC_URL']
    if not jdbc.startswith('jdbc:postgresql://'):
        raise ValueError('Unexpected cloud database driver')
    parsed = urllib.parse.urlsplit(jdbc[5:])
    if not (parsed.hostname or '').endswith('.neon.tech'):
        raise ValueError('Configured database is not a Neon target')
    connection = psycopg.connect(host=parsed.hostname, port=parsed.port or 5432,
        dbname=parsed.path.lstrip('/'), user=env['NEON_DB_USERNAME'],
        password=env['NEON_DB_PASSWORD'], sslmode='verify-full', sslrootcert=certifi.where(), connect_timeout=15,
        options='-c default_transaction_read_only=on -c statement_timeout=20000')
    report = {'at': dt.datetime.now(dt.timezone.utc).isoformat(), 'mode': 'read-only',
              'backendImageId': container['Image'], 'database': {}, 'storage': {}}
    with connection:
        with connection.cursor() as cursor:
            cursor.execute('SHOW transaction_read_only')
            report['database']['transactionReadOnly'] = cursor.fetchone()[0]
            cursor.execute('SHOW server_version')
            report['database']['version'] = cursor.fetchone()[0]
            report['database']['clientTlsVerified'] = bool(connection.pgconn.ssl_in_use)
            cursor.execute('SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()')
            report['database']['serverInternalSessionSsl'] = cursor.fetchone()[0]
            cursor.execute('SELECT version, type, success FROM flyway_schema_history ORDER BY installed_rank')
            report['database']['migrationHistory'] = [
                {'version': v, 'type': t, 'success': s} for v, t, s in cursor.fetchall()]
            for table in ('users', 'global_cards', 'local_cards', 'orders', 'order_items'):
                cursor.execute('SELECT count(*) FROM ' + table)
                report['database'][table + 'Count'] = cursor.fetchone()[0]
            cursor.execute("SELECT custom_image_url FROM local_cards WHERE custom_image_url IS NOT NULL AND custom_image_url <> ''")
            urls = [row[0] for row in cursor.fetchall()]
    base = env['OBJECT_STORAGE_PUBLIC_BASE_URL'].rstrip('/') + '/'
    managed = [url for url in urls if url.startswith(base)]
    local = [url for url in urls if urllib.parse.urlsplit(url).hostname in ('localhost', '127.0.0.1', 's3mock')]
    report['storage'].update(bucket=env['OBJECT_STORAGE_BUCKET'], productImageReferenceCount=len(urls),
        managedReferenceCount=len(managed), localReferenceCount=len(local),
        otherReferenceCount=len(urls) - len(managed) - len(local), uniqueManagedKeys=len(set(managed)))
    try:
        endpoint = env.get('OBJECT_STORAGE_ENDPOINT')
        if endpoint and urllib.parse.urlsplit(endpoint).scheme != 'https':
            raise ValueError('Cloud object audit requires HTTPS')
        client = boto3.client('s3', endpoint_url=endpoint or None,
            region_name=env.get('OBJECT_STORAGE_REGION', 'us-east-2'),
            aws_access_key_id=env['OBJECT_STORAGE_ACCESS_KEY'],
            aws_secret_access_key=env['OBJECT_STORAGE_SECRET_KEY'],
            config=Config(s3={'addressing_style': 'path' if env.get('OBJECT_STORAGE_PATH_STYLE', 'true').lower() == 'true' else 'virtual'},
                          connect_timeout=10, read_timeout=20, retries={'max_attempts': 1}))
        objects = {}
        for page in client.get_paginator('list_objects_v2').paginate(Bucket=env['OBJECT_STORAGE_BUCKET']):
            objects.update({item['Key']: item['Size'] for item in page.get('Contents', [])})
        keys = {url[len(base):] for url in managed}
        missing = keys - objects.keys()
        report['storage'].update(listStatus='passed', objectCount=len(objects),
            totalObjectBytes=sum(objects.values()), missingManagedObjects=len(missing),
            unreferencedProductsObjects=len([key for key in objects if key.startswith('products/') and key not in keys]))
        # Signed GET validates actual bytes independently of public bucket access.
        read_count = 0
        for key in keys - missing:
            result = client.get_object(Bucket=env['OBJECT_STORAGE_BUCKET'], Key=key)
            with result['Body'] as body:
                data = body.read(8 * 1024 * 1024 + 1)
            if len(data) != objects[key] or len(data) > 8 * 1024 * 1024:
                raise ValueError('Managed object length verification failed')
            read_count += 1
        report['storage']['managedObjectReadCount'] = read_count
    except Exception as exc:
        report['storage'].update(listStatus='failed', errorType=type(exc).__name__)
        response = getattr(exc, 'response', {})
        if response:
            report['storage']['httpStatus'] = response.get('ResponseMetadata', {}).get('HTTPStatusCode')
            report['storage']['providerErrorCode'] = response.get('Error', {}).get('Code')
    output.parent.mkdir(parents=True, exist_ok=True)
    report['status'] = 'passed' if (
        report['storage'].get('listStatus') == 'passed'
        and report['storage'].get('missingManagedObjects') == 0
        and report['storage']['managedReferenceCount'] == report['storage']['productImageReferenceCount']
    ) else 'failed'
    output.write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report, indent=2))
    return 0 if report['status'] == 'passed' else 1


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as error:
        print(json.dumps({'auditStatus': 'failed', 'errorType': type(error).__name__}))
        sys.exit(1)
