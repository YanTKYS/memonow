import base64
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import release_plan as p


class ReleasePlan(unittest.TestCase):
    def test_new_version_releases_same_version_skips_and_partial_states_fail(self):
        previous={'tag_name':'v0.1.0','draft':False,'prerelease':False,'html_url':'old'}
        current=dict(previous,tag_name='v0.1.1',html_url='current')
        with patch.dict(os.environ,GH_REPO='YanTKYS/memonow'), patch.object(p,'published_version',return_value=('0.1.0',1)):
            with patch.object(p,'api',return_value=None), patch.object(p,'all_releases',return_value=[previous]):
                self.assertEqual(('release',''),p.decide('0.1.1',2))
                for name,code in [('0.1.1',1),('0.0.9',2)]:
                    with self.assertRaises(ValueError): p.decide(name,code)
            with patch.object(p,'api',return_value={'object':{'sha':'a'*40}}), patch.object(p,'all_releases',return_value=[previous]):
                self.assertEqual(('skip','old'),p.decide('0.1.0',1))
                with self.assertRaises(ValueError): p.decide('0.1.0',2)
            for ref,releases in [(None,[dict(current,draft=True)]),({'object':{}},[]),(None,[current])]:
                with patch.object(p,'api',return_value=ref), patch.object(p,'all_releases',return_value=releases):
                    with self.assertRaises(ValueError): p.decide('0.1.1',2)

    def test_rollback_to_older_published_version_is_rejected(self):
        old={'tag_name':'v0.1.0','draft':False,'prerelease':False,'html_url':'old'}
        new=dict(old,tag_name='v0.1.1')
        with patch.dict(os.environ,GH_REPO='YanTKYS/memonow'), patch.object(p,'api',return_value={'object':{}}), \
             patch.object(p,'all_releases',return_value=[new,old]), \
             patch.object(p,'published_version',side_effect=lambda tag: ('0.1.0',1) if tag=='v0.1.0' else ('0.1.1',2)):
            with self.assertRaises(ValueError): p.decide('0.1.0',1)

    def test_plan_outputs_release_or_skip_before_any_build(self):
        original=Path.cwd()
        commit='a'*40
        with tempfile.TemporaryDirectory() as temp:
            os.chdir(temp)
            try:
                Path('version.properties').write_text('versionName=0.1.1\nversionCode=2')
                Path('docs/releases').mkdir(parents=True)
                Path('docs/releases/v0.1.1.md').write_text('# v0.1.1\nNo status flag')
                env={'GITHUB_EVENT_NAME':'push','GITHUB_REF':'refs/heads/main','GITHUB_SHA':commit,
                     'GITHUB_OUTPUT':'outputs','GITHUB_STEP_SUMMARY':'summary'}
                for action in ('release','skip'):
                    with patch.dict(os.environ,env),patch.object(p,'require_merge') as approval, \
                         patch.object(p,'decide',return_value=(action,'https://example.invalid/release')), \
                         patch.object(p.subprocess,'check_output',return_value=commit):
                        p.plan()
                    approval.assert_called_once_with(commit)
                    self.assertIn('action='+action,Path('outputs').read_text())
                    self.assertIn('## '+action,Path('summary').read_text())
                env.update(GITHUB_EVENT_NAME='workflow_dispatch',GITHUB_REF='refs/heads/feature')
                with patch.dict(os.environ,env),patch.object(p.subprocess,'check_output',return_value=commit),self.assertRaises(ValueError):
                    p.plan()
            finally: os.chdir(original)

    def test_test_drafts_are_not_official_versions(self):
        with patch.dict(os.environ,GH_REPO='YanTKYS/memonow'), patch.object(p,'api',return_value=None), \
             patch.object(p,'all_releases',return_value=[{'tag_name':'test-v0.1.1-run1','draft':True,'prerelease':True}]):
            self.assertEqual(('release',''),p.decide('0.1.1',2))

    def test_version_is_read_from_published_tag_and_mismatch_rejected(self):
        content=base64.b64encode(b'versionName=0.1.0\nversionCode=1').decode()
        with patch.dict(os.environ,GH_REPO='YanTKYS/memonow'), patch.object(p,'api',return_value={'content':content}) as api:
            self.assertEqual(('0.1.0',1),p.published_version('v0.1.0'))
            self.assertIn('ref=v0.1.0',api.call_args.args[0])

    def test_only_owner_merged_main_pr_matches_push_commit(self):
        commit='a'*40
        good={'merged_at':'time','merge_commit_sha':commit,'base':{'ref':'main','repo':{'full_name':'YanTKYS/memonow'}},'merged_by':{'login':'YanTKYS'}}
        with tempfile.TemporaryDirectory() as temp:
            event=Path(temp)/'event.json'
            event.write_text(json.dumps({'after':commit,'forced':False,'deleted':False}))
            with patch.dict(os.environ,GH_REPO='YanTKYS/memonow',GITHUB_EVENT_PATH=str(event)):
                with patch.object(p,'api',side_effect=[[{'number':22}],good]): p.require_merge(commit)
                for detail in [dict(good,merged_by={'login':'other'}),dict(good,merged_at=None),dict(good,merge_commit_sha='b'*40),dict(good,base={'ref':'feature'})]:
                    with patch.object(p,'api',side_effect=[[{'number':22}],detail]), self.assertRaises(ValueError): p.require_merge(commit)
                with patch.object(p,'api',return_value=[]),self.assertRaises(ValueError): p.require_merge(commit)
                event.write_text(json.dumps({'after':commit,'forced':True}))
                with patch.object(p,'api') as api,self.assertRaises(ValueError): p.require_merge(commit)
                api.assert_not_called()

    def test_workflow_has_lightweight_gate_and_never_exposes_secrets_before_it(self):
        text=(Path(__file__).resolve().parents[2]/'.github/workflows/release.yml').read_text()
        plan=text.split('  plan:\n')[1].split('  validate:\n')[0]
        self.assertNotIn('secrets.',plan);self.assertNotIn('gradlew',plan)
        self.assertIn("needs.plan.outputs.action != 'skip'",text)
        self.assertIn('needs: [plan, validate]',text)
        self.assertNotIn('pull_request:',text)
        self.assertIn('pull-requests: read',text)
