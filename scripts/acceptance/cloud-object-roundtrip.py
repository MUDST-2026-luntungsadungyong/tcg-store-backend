"""Exercise one task-owned UUID object; never updates existing product records."""
import base64
import datetime as dt
import hashlib
import json
import pathlib
import subprocess
import sys
import urllib.error
import urllib.request
import uuid

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError


def main():
    root = pathlib.Path(__file__).resolve().parents[2]
    target = root / 'target/acceptance-20261016/cloud-object-roundtrip.json'
    config = json.loads(subprocess.check_output(['docker', 'inspect', 'tcg-store-service-backend-cloud']))[0]
    env = dict(item.split('=', 1) for item in config['Config']['Env'] if '=' in item)
    endpoint = env.get('OBJECT_STORAGE_ENDPOINT', '')
    base = env['OBJECT_STORAGE_PUBLIC_BASE_URL'].rstrip('/')
    if not endpoint.startswith('https://') or not base.startswith('https://'):
        raise ValueError('Cloud roundtrip requires HTTPS')
    client = boto3.client('s3', endpoint_url=endpoint,
        region_name=env.get('OBJECT_STORAGE_REGION', 'us-east-2'),
        aws_access_key_id=env['OBJECT_STORAGE_ACCESS_KEY'], aws_secret_access_key=env['OBJECT_STORAGE_SECRET_KEY'],
        config=Config(s3={'addressing_style': 'path' if env.get('OBJECT_STORAGE_PATH_STYLE', 'true').lower() == 'true' else 'virtual'},
                      connect_timeout=10, read_timeout=15, retries={'max_attempts': 1}))
    bucket = env['OBJECT_STORAGE_BUCKET']
    key = 'products/' + str(uuid.uuid4()) + '.png'
    body = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lXkAAAAASUVORK5CYII=')
    report = {'at': dt.datetime.now(dt.timezone.utc).isoformat(), 'bucket': bucket,
        'scope': 'one disposable UUID object; no database changes', 'checks': {}, 'status': 'running'}
    attempt_owned = False
    try:
        try:
            client.head_object(Bucket=bucket, Key=key)
        except ClientError as error:
            if error.response['ResponseMetadata']['HTTPStatusCode'] != 404:
                raise
        else:
            raise ValueError('UUID already exists; refusing overwrite')
        attempt_owned = True
        client.put_object(Bucket=bucket, Key=key, Body=body, ContentType='image/png')
        report['checks']['put'] = 'passed'
        result = client.get_object(Bucket=bucket, Key=key)
        with result['Body'] as stream:
            received = stream.read(1024)
        if hashlib.sha256(received).digest() != hashlib.sha256(body).digest():
            raise ValueError('Signed read checksum mismatch')
        report['checks']['signedGetChecksum'] = 'passed'
        # Default URL opener sends no authorization; credentials never go to public URL.
        try:
            with urllib.request.urlopen(base + '/' + key, timeout=15) as response:
                received = response.read(1024)
            report['checks']['publicGetChecksum'] = 'passed' if received == body else 'failed'
        except urllib.error.HTTPError as error:
            report['checks']['publicGetChecksum'] = 'failed'
            report['publicHttpStatus'] = error.code
        report['status'] = 'passed' if report['checks'].get('publicGetChecksum') == 'passed' else 'failed'
    except Exception as error:
        report.update(status='failed', errorType=type(error).__name__)
        if isinstance(error, ClientError):
            report['providerErrorCode'] = error.response.get('Error', {}).get('Code')
    finally:
        if attempt_owned:
            try:
                client.delete_object(Bucket=bucket, Key=key)
                try:
                    client.head_object(Bucket=bucket, Key=key)
                except ClientError as error:
                    if error.response['ResponseMetadata']['HTTPStatusCode'] != 404:
                        raise
                else:
                    raise ValueError('Disposable object remains')
                report['checks']['deleteAndAbsent'] = 'passed'
            except Exception as error:
                report.update(status='failed', cleanupErrorType=type(error).__name__)
                report['retainedTaskObjectKey'] = key
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(report, indent=2), encoding='utf-8')
        print(json.dumps(report, indent=2))
    return 0 if report['status'] == 'passed' else 1


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as error:
        print(json.dumps({'status': 'failed', 'errorType': type(error).__name__}))
        sys.exit(1)
