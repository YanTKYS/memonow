#!/usr/bin/env python3
"""Read-only release planning: owner PR merge is the publication approval."""
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from release import version, parse_version, validate_ref
from distribute import api, endpoint


def require_merge(commit):
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    if event.get('after') != commit or event.get('forced') or event.get('deleted') or event.get('created'):
        raise ValueError('Expected an ordinary main PR merge push')
    page = 1
    while True:
        prs = api(endpoint(f'commits/{commit}/pulls?per_page=100&page={page}'))
        for pr in prs:
            # Fetch complete merge metadata; associated-PR responses need not include merged_by.
            detail = api(endpoint(f'pulls/{pr["number"]}'))
            if (detail.get('merged_at') and detail.get('merge_commit_sha') == commit
                    and detail.get('base', {}).get('ref') == 'main'
                    and detail.get('base', {}).get('repo', {}).get('full_name') == 'YanTKYS/memonow'
                    and detail.get('merged_by', {}).get('login') == 'YanTKYS'):
                return
        if len(prs) < 100:
            break
        page += 1
    raise ValueError('Publication requires a PR merged into main by repository owner YanTKYS')


def all_releases():
    result, page = [], 1
    while True:
        batch = api(endpoint(f'releases?per_page=100&page={page}'))
        result.extend(batch)
        if len(batch) < 100:
            return result
        page += 1


def published_version(tag):
    content = api(endpoint(f'contents/version.properties?ref={tag}'))
    return parse_version(base64.b64decode(content['content'], validate=False).decode('utf-8'))


def decide(name, code):
    tag = 'v' + name
    ref = api(endpoint('git/ref/tags/' + tag), optional=True)
    releases = all_releases()
    matching = [r for r in releases if r['tag_name'] == tag]
    if len(matching) > 1:
        raise ValueError('Duplicate official release records')
    if matching:
        current = matching[0]
        if ref is not None and not current['draft'] and not current['prerelease']:
            if published_version(tag) != (name, code):
                raise ValueError('Same published version has different versionCode')
            for other in releases:
                other_tag = other['tag_name']
                if other['draft'] or other['prerelease'] or not re.fullmatch(r'v\d+\.\d+\.\d+', other_tag):
                    continue
                other_name, other_code = published_version(other_tag)
                if other_tag != 'v' + other_name or other_code > code or tuple(map(int, other_name.split('.'))) > tuple(map(int, name.split('.'))):
                    raise ValueError('Main version is older than an official published release')
            return 'skip', current['html_url']
        raise ValueError('Official tag/Draft/Release collision; inspect prior run, never overwrite')
    if ref is not None:
        raise ValueError('Official tag exists without a published Release; inspect prior run')
    for previous in releases:
        old_tag = previous['tag_name']
        if previous['draft'] or previous['prerelease'] or not re.fullmatch(r'v\d+\.\d+\.\d+', old_tag):
            continue  # Test Drafts are unrelated to official publication.
        old_name, old_code = published_version(old_tag)
        if old_tag != 'v' + old_name or code <= old_code:
            raise ValueError('versionCode must increase beyond every official published version')
        if tuple(map(int, name.split('.'))) <= tuple(map(int, old_name.split('.'))):
            raise ValueError('versionName must increase beyond official published versions')
    return 'release', ''


def plan():
    name, code = version()
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    mode = 'publish' if os.environ.get('GITHUB_EVENT_NAME') == 'push' else 'preview'
    validate_ref(os.environ.get('GITHUB_EVENT_NAME'), os.environ.get('GITHUB_REF'), name, mode)
    if commit != os.environ.get('GITHUB_SHA'):
        raise ValueError('Checkout differs from triggering commit')
    if not Path(f'docs/releases/v{name}.md').read_text().strip():
        raise ValueError('Versioned release notes must not be empty')
    if mode == 'publish':
        require_merge(commit)
        action, url = decide(name, code)
    else:
        action, url = 'preview', ''
    with open(os.environ['GITHUB_OUTPUT'], 'a') as out:
        out.write(f'action={action}\nmode={mode}\nversion={name}\ncode={code}\ncommit={commit}\n')
    with open(os.environ.get('GITHUB_STEP_SUMMARY', os.devnull), 'a') as out:
        out.write(f'## {action}\nVersion: {name} / {code}\n\nTag: v{name}\n\nCommit: `{commit}`\n\nURL: {url or "成果物の作成後に表示"}\n')


if __name__ == '__main__':
    try:
        plan()
    except (ValueError, RuntimeError, KeyError, OSError, subprocess.CalledProcessError) as error:
        with open(os.environ.get('GITHUB_STEP_SUMMARY', os.devnull), 'a') as out:
            try:
                name, code = version()
                target = f'https://github.com/YanTKYS/memonow/releases/tag/v{name}'
            except (ValueError, KeyError, OSError):
                name, code, target = 'unknown', 'unknown', 'unknown'
            out.write(f'## failed\nVersion: {name} / {code}\n\nTag: v{name}\n\nURL (target): {target}\n\nRelease planning failed. No signing or publication was started.\n')
        print('Release planning failed: ' + (str(error) if isinstance(error, (ValueError, RuntimeError)) else 'check trigger, notes and API access'), file=sys.stderr)
        sys.exit(1)
