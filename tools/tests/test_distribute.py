import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
import distribute as d


class Distribution(unittest.TestCase):
    def test_invalid_modes_and_refs(self):
        for mode in ('', 'release', 'PUBLISH'):
            with self.assertRaises(ValueError):
                d.validate_ref('workflow_dispatch', 'refs/heads/main', '0.1.0', mode)
        for event, ref in [('push', 'refs/tags/v0.1.0'), ('workflow_dispatch', 'refs/heads/feature'), ('workflow_dispatch', 'refs/heads/main')]:
            with self.assertRaises(ValueError):
                d.validate_ref(event, ref, '0.1.0', 'publish')

    def test_existing_tag_or_release_always_blocks_including_same_commit_draft(self):
        with patch.dict(os.environ, GH_REPO='YanTKYS/memonow'):
            for responses in ([{'object': {'sha': 'a'*40}}, []], [None, [{'tag_name':'v0.1.0','draft': True}]], [None, [{'tag_name':'v0.1.0','draft': False}]]):
                with patch.object(d, 'api', side_effect=responses), self.assertRaisesRegex(ValueError, 'already exists'):
                    d.ensure_absent('v0.1.0')

    def test_api_only_404_counts_as_absent(self):
        for status in ('403', '429', '500'):
            with patch.object(d.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, '', 'HTTP '+status)):
                with self.assertRaises(RuntimeError):
                    d.api('path', optional=True)
        with patch.object(d.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, '', 'HTTP 404')):
            self.assertIsNone(d.api('path', optional=True))

    def simulate(self, mode, fail=None):
        commit = 'a'*40
        calls = []
        original = Path.cwd()
        with tempfile.TemporaryDirectory() as temp:
            os.chdir(temp)
            try:
                Path('docs/releases').mkdir(parents=True)
                Path('docs/releases/v0.1.0.md').write_text('# v0.1.0\nnotes')
                Path('version.properties').write_text('versionName=0.1.0\nversionCode=1\n')
                Path('dist').mkdir()
                apk=Path('dist/memonow-v0.1.0.apk'); apk.write_bytes(b'fixture apk, not signed')
                digest=d.hashlib.sha256(apk.read_bytes()).hexdigest()
                Path(str(apk)+'.sha256').write_text(digest+'  '+apk.name+'\n')
                def api(path, method='GET', data=None, optional=False):
                    calls.append((path, method, data))
                    if optional:
                        return None
                    if 'releases?per_page' in path:
                        return []
                    if method == 'POST' and path.endswith('/releases'):
                        return {'id': 1, 'html_url': 'https://example.invalid/draft'}
                    if 'git/ref/tags/' in path:
                        return {'object': {'sha': 'b'*40 if fail == 'tag_changed' and any(c[0].endswith('/releases/1') for c in calls) else commit}}
                    return {'draft': True, 'tag_name': 'v0.1.0' if mode == 'publish' else 'test-v0.1.0-run123-attempt1'}
                def gh(*args):
                    if '--input' not in args: return commit
                    if fail == 'upload': raise subprocess.CalledProcessError(1, 'upload')
                    return json.dumps({'id': 2 if str(args[-1]).endswith('.sha256') else 1})
                def download(args, stdout, check):
                    is_checksum = args[2].endswith('/2')
                    stdout.write(Path(str(apk)+'.sha256').read_bytes() if is_checksum else
                                 b'tampered' if fail == 'download' else apk.read_bytes())
                def verify(*args):
                    if fail == 'signature': raise ValueError('signature mismatch')
                env={'GH_REPO':'YanTKYS/memonow','GITHUB_EVENT_NAME':'push' if mode == 'publish' else 'workflow_dispatch','GITHUB_REF':'refs/heads/main',
                     'GITHUB_RUN_ID':'123','GITHUB_RUN_ATTEMPT':'1','ANDROID_HOME':'/sdk','GITHUB_STEP_SUMMARY':'summary'}
                with patch.dict(os.environ,env), patch.object(d,'api',side_effect=api), patch.object(d,'gh',side_effect=gh), \
                     patch.object(d,'verify_apk',side_effect=verify), patch.object(d.subprocess,'check_output',return_value=commit), \
                     patch.object(d.subprocess,'run',side_effect=download), \
                     patch('release_plan.require_merge'), patch('release_plan.decide',return_value=('release','')):
                    try: d.distribute(mode,commit,'aa'*32)
                    except (ValueError,subprocess.CalledProcessError):
                        if fail is None: raise
                    else: self.assertIsNone(fail, 'failure must prevent distribution')
                return calls, Path('summary').read_text() if Path('summary').exists() else ''
            finally: os.chdir(original)

    def test_preview_creates_only_unpublished_test_draft_and_summary(self):
        calls,summary=self.simulate('preview')
        self.assertFalse(any('/git/refs' in p and m=='POST' for p,m,_ in calls))
        self.assertFalse(any(m=='PATCH' for _,m,_ in calls))
        create=next(data for path,method,data in calls if path.endswith('/releases') and method=='POST')
        self.assertTrue(create['draft']); self.assertTrue(create['prerelease'])
        self.assertTrue(create['tag_name'].startswith('test-v'))
        self.assertIn('未公開Draft',summary); self.assertIn('APK SHA-256',summary)

    def test_publish_creates_tag_on_validated_commit_then_final_unpublish_draft(self):
        calls,summary=self.simulate('publish')
        tag=next(data for path,method,data in calls if path.endswith('/git/refs') and method=='POST')
        self.assertEqual({'ref':'refs/tags/v0.1.0','sha':'a'*40},tag)
        self.assertEqual('PATCH',calls[-1][1]); self.assertFalse(calls[-1][2]['draft'])
        self.assertIn('正式公開済み',summary)

    def test_partial_failure_never_publishes_or_deletes_and_reports_draft(self):
        for failure in ('signature','upload','download','tag_changed'):
            calls,summary=self.simulate('publish',failure)
            self.assertFalse(any(m in ('PATCH','DELETE') for _,m,_ in calls),failure)
            if failure != 'signature': self.assertIn('未公開Draft作成済み',summary)

    def test_workflow_permissions_dependencies_and_single_manual_path(self):
        content=(ROOT/'.github/workflows/release.yml').read_text()
        self.assertIn('branches: [main]',content)
        self.assertNotIn('tags:',content)
        self.assertNotIn('inputs.mode',content)
        self.assertIn("if: needs.plan.outputs.action != 'skip'", content)
        self.assertIn('group: release-memonow',content)
        sign=content.split('  sign:\n')[1].split('  distribute:\n')[0]
        self.assertIn('environment: release-signing',sign)
        self.assertNotIn('contents: write',sign)
        distribution=content.split('  distribute:\n')[1]
        self.assertIn('needs: [plan, validate, sign]',distribution)
        self.assertIn('contents: write',distribution)
        self.assertNotIn('secrets.',distribution)
