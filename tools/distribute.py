#!/usr/bin/env python3
"""Create a new release only; never overwrite/delete or resume an existing one."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import sys
from release import version, validate_ref, validate_notes, verify_apk, fingerprint


def gh(*args):
    return subprocess.check_output(['gh', *args], text=True).strip()


def api(path, method='GET', data=None, optional=False):
    result = subprocess.run(['gh', 'api', path, '--method', method] + (['--input', '-'] if data is not None else []),
                            input=json.dumps(data) if data is not None else None,
                            capture_output=True, text=True)
    if result.returncode:
        if optional and re.search(r'HTTP 404\b', result.stderr):
            return None
        raise RuntimeError('GitHub API failed; check permissions/network in this job. No existing objects were removed.')
    return json.loads(result.stdout)


def endpoint(suffix):
    repo = os.environ['GH_REPO']
    if repo != 'YanTKYS/memonow':
        raise ValueError('Unexpected repository')
    return f'repos/{repo}/{suffix}'


def ensure_absent(tag):
    # Fail closed on permission/network errors, and on ANY existing draft/tag/release.
    ref = api(endpoint('git/ref/tags/' + tag), optional=True)
    existing = None
    page = 1
    while True:
        releases = api(endpoint(f'releases?per_page=100&page={page}'))
        existing = next((item for item in releases if item['tag_name'] == tag), None)
        if existing is not None or len(releases) < 100:
            break
        page += 1
    if ref is not None or existing is not None:
        raise ValueError(f'{tag} already exists. Do not rerun/overwrite. Inspect its commit, Draft/assets and prior run; '
                         'finish recovery by maintainer review or prepare a new version. Never delete published releases to retry.')


def check_files(folder, name):
    apk = folder / f'memonow-v{name}.apk'
    checksum = folder / (apk.name + '.sha256')
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    if checksum.read_text().strip() != f'{digest}  {apk.name}':
        raise ValueError('APK checksum/file name mismatch')
    return apk, checksum, digest


def distribute(mode, commit, certificate):
    name, code = version()
    validate_ref(os.environ.get('GITHUB_EVENT_NAME'), os.environ.get('GITHUB_REF'), name, mode)
    if not re.fullmatch('[0-9a-f]{40}', commit) or gh('api', endpoint('commits/' + commit), '--jq', '.sha') != commit:
        raise ValueError('Invalid validated commit')
    if subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip() != commit:
        raise ValueError('Checkout differs from validated commit')
    notes = Path(f'docs/releases/v{name}.md').read_text()
    validate_notes(mode, notes)
    apk, checksum, digest = check_files(Path('dist'), name)
    certificate = fingerprint(certificate)
    verify_apk(apk, name, code, certificate, os.environ['ANDROID_HOME'])
    run, attempt = os.environ['GITHUB_RUN_ID'], os.environ['GITHUB_RUN_ATTEMPT']
    if not run.isdecimal() or not attempt.isdecimal():
        raise ValueError('Invalid run identity')
    tag = 'v' + name if mode == 'publish' else f'test-v{name}-run{run}-attempt{attempt}'
    ensure_absent(tag)  # Repeat the pre-sign guard after signing, immediately before mutation.
    if mode == 'publish':
        api(endpoint('git/refs'), 'POST', {'ref': 'refs/tags/' + tag, 'sha': commit})
        if api(endpoint('git/ref/tags/' + tag))['object']['sha'] != commit:
            raise ValueError('New tag does not point to validated commit')
    body = notes if mode == 'publish' else f'Unpublished device test. Run {run}, attempt {attempt}, commit {commit}. Not an official release.'
    created = api(endpoint('releases'), 'POST', {'tag_name': tag, 'target_commitish': commit, 'draft': True,
                                               'prerelease': mode == 'preview', 'name': f'memonow {tag}', 'body': body})
    # Leave a useful recovery link even when upload/final verification fails.
    def summary(state):
        with open(os.environ.get('GITHUB_STEP_SUMMARY', os.devnull), 'a') as output:
            output.write(f'\n## {mode}: {state}\nVersion: {name} / {code}\n\nCommit: `{commit}`\n\n'
                         f'Release: {created["html_url"]}\n\nAPK SHA-256: `{digest}`\n')
    summary('未公開Draft作成済み（検証中・まだ公開していません）')
    assets = []
    for file in (apk, checksum):
        uploaded = json.loads(gh('api',
                                 'https://uploads.github.com/' + endpoint(f'releases/{created["id"]}/assets?name={file.name}'),
                                 '--method', 'POST', '--header', 'Content-Type: application/octet-stream',
                                 '--input', str(file)))
        assets.append((file.name, uploaded['id']))
    with tempfile.TemporaryDirectory() as temp:
        for filename, asset_id in assets:
            with (Path(temp) / filename).open('wb') as output:
                subprocess.run(['gh', 'api', endpoint(f'releases/assets/{asset_id}'),
                                '--header', 'Accept: application/octet-stream'], stdout=output, check=True)
        remote_apk, _, remote_digest = check_files(Path(temp), name)
        if remote_digest != digest:
            raise ValueError('Uploaded APK differs from this run')
        verify_apk(remote_apk, name, code, certificate, os.environ['ANDROID_HOME'])
    current = api(endpoint('releases/' + str(created['id'])))
    if not current['draft'] or current['tag_name'] != tag:
        raise ValueError('Release changed during verification')
    if mode == 'publish':
        if api(endpoint('git/ref/tags/' + tag))['object']['sha'] != commit:
            raise ValueError('Tag changed before publication')
        api(endpoint('releases/' + str(created['id'])), 'PATCH', {'draft': False, 'make_latest': 'true'})
        summary('正式公開済み')
    else:
        summary('テスト候補・未公開Draft（publishは選択されていません）')


if __name__ == '__main__':
    try:
        distribute(os.environ['RELEASE_MODE'], os.environ['COMMIT'], os.environ['PUBLIC_CERT_SHA256'])
    except (ValueError, RuntimeError, KeyError, OSError, subprocess.CalledProcessError) as error:
        # No secret input is used in this job. Do not dump child stderr/environment.
        print('Distribution stopped. Inspect the job summary and existing tag/Draft/assets before retrying. '
              'Nothing is deleted automatically.', file=sys.stderr)
        if isinstance(error, (ValueError, RuntimeError)):
            print(str(error), file=sys.stderr)
        sys.exit(1)
