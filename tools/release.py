#!/usr/bin/env python3
"""Release input/ref and APK verification. Never prints signing secret values."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys


def version(root=Path('.')):
    return parse_version((root / 'version.properties').read_text())


def parse_version(text):
    props = dict(line.strip().split('=', 1) for line in text.splitlines()
                 if line.strip() and not line.lstrip().startswith('#'))
    name, code = props['versionName'], props['versionCode']
    if not re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', name):
        raise ValueError('Invalid versionName')
    if not code.isdecimal() or not 1 <= int(code) <= 2_100_000_000:
        raise ValueError('Invalid versionCode')
    return name, int(code)


def validate_ref(event, ref, name, mode='preview'):
    if ref != 'refs/heads/main' or (event, mode) not in (('push', 'publish'), ('workflow_dispatch', 'preview')):
        raise ValueError('Release requires main PR merge push; manual execution is preview only')


def fingerprint(value):
    normalized = value.replace(':', '').strip().lower()
    if not re.fullmatch('[0-9a-f]{64}', normalized):
        raise ValueError('Expected certificate SHA-256 must contain 64 hexadecimal digits')
    return normalized


def verify_apk(apk, expected_name, expected_code, expected_certificate, sdk):
    toolchain = Path(sdk) / 'build-tools' / '36.0.0'
    # Capture output: a failed command should not echo arguments or signing inputs.
    certs = subprocess.run([str(toolchain / 'apksigner'), 'verify', '--print-certs', str(apk)],
                           check=True, capture_output=True, text=True).stdout
    found = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$', certs, re.M)
    if len(found) != 1 or fingerprint(found[0]) != fingerprint(expected_certificate):
        raise ValueError('APK signing certificate does not match the pinned release certificate')
    if 'Android Debug' in certs:
        raise ValueError('Android debug certificate is not a release identity')
    badging = subprocess.run([str(toolchain / 'aapt'), 'dump', 'badging', str(apk)],
                             check=True, capture_output=True, text=True).stdout
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    if not package or package.groups() != ('com.yantkys.memonow', str(expected_code), expected_name):
        raise ValueError('APK applicationId or version does not match the release inputs')
    if re.search(r'^application-debuggable', badging, re.M):
        raise ValueError('Release APK is debuggable')
    print('Verified release APK identity, version and certificate SHA-256: ' + fingerprint(found[0]))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['check-ref', 'verify-apk'])
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--expected-code', type=int)
    args = parser.parse_args()
    name, code = version()
    if args.command == 'check-ref':
        mode = os.environ.get('RELEASE_MODE', 'preview')
        validate_ref(os.environ.get('GITHUB_EVENT_NAME'), os.environ.get('GITHUB_REF'), name, mode)
        subprocess.run(['git', 'merge-base', '--is-ancestor', 'HEAD', 'refs/remotes/origin/main'], check=True)
        commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
        notes = Path('docs/releases') / ('v' + name + '.md')
        if not notes.is_file():
            raise ValueError('Versioned release notes are missing')
        if mode == 'publish':
            from distribute import ensure_absent
            ensure_absent('v' + name)
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            output.write(f'version={name}\ncode={code}\ncommit={commit}\n')
        with open(os.environ.get('GITHUB_STEP_SUMMARY', os.devnull), 'a') as summary:
            summary.write(f'## {mode}: {name} (code {code})\nCommit: `{commit}`\n\n'
                          + ('正式公開を要求。検証・署名成功後に公開します。' if mode == 'publish' else 'テスト候補のみ。正式公開は行いません。') + '\n')
    else:
        if args.apk is None:
            raise ValueError('--apk is required')
        verify_apk(args.apk, name, code if args.expected_code is None else args.expected_code,
                   os.environ.get('MEMONOW_RELEASE_CERT_SHA256', ''), os.environ['ANDROID_HOME'])


if __name__ == '__main__':
    try:
        main()
    except (ValueError, RuntimeError, KeyError, OSError, subprocess.CalledProcessError) as error:
        # Never dump a child process stderr or environment containing signing inputs.
        print('Release validation failed; check the ref, version, certificate and SDK configuration.', file=sys.stderr)
        if isinstance(error, (ValueError, RuntimeError)):
            print(str(error), file=sys.stderr)
        sys.exit(1)
