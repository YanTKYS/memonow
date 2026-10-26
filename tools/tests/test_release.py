import contextlib
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('release', ROOT / 'tools/release.py')
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseGuards(unittest.TestCase):
    def test_only_matching_tags_or_manual_main_are_eligible(self):
        release.validate_ref('push', 'refs/tags/v0.1.0', '0.1.0')
        release.validate_ref('workflow_dispatch', 'refs/heads/main', '0.1.0')
        for event, ref in [('pull_request', 'refs/pull/10/merge'), ('push', 'refs/heads/main'),
                           ('workflow_dispatch', 'refs/heads/feature'), ('push', 'refs/tags/v9.0.0')]:
            with self.assertRaises(ValueError):
                release.validate_ref(event, ref, '0.1.0')

    def test_cli_rejects_unmerged_commit_even_with_a_matching_tag_name(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            def git(*args):
                return subprocess.check_output(['git', '-C', str(root), *args], stderr=subprocess.DEVNULL, text=True).strip()
            git('init', '-b', 'main')
            git('config', 'user.name', 'Release guard test')
            git('config', 'user.email', 'fixture@example.invalid')
            (root / 'version.properties').write_text('versionCode=1\nversionName=0.1.0\n')
            (root / 'docs/releases').mkdir(parents=True)
            (root / 'docs/releases/v0.1.0.md').write_text('<!-- release-status: ready -->\n# v0.1.0')
            git('add', '.')
            git('commit', '-m', 'fixture main')
            trusted = git('rev-parse', 'HEAD')
            git('update-ref', 'refs/remotes/origin/main', trusted)
            git('switch', '-c', 'untrusted')
            (root / 'unmerged').write_text('unreviewed')
            git('add', '.')
            git('commit', '-m', 'fixture unmerged commit')
            env = dict(os.environ, GITHUB_EVENT_NAME='push', GITHUB_REF='refs/tags/v0.1.0', GITHUB_OUTPUT=str(root / 'output'))
            result = subprocess.run(['python3', str(ROOT / 'tools/release.py'), 'check-ref'], cwd=root, env=env, capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse((root / 'output').exists())
            git('switch', 'main')
            env.update(GITHUB_EVENT_NAME='workflow_dispatch', GITHUB_REF='refs/heads/main')
            result = subprocess.run(['python3', str(ROOT / 'tools/release.py'), 'check-ref'], cwd=root, env=env, capture_output=True, text=True)
            self.assertEqual(0, result.returncode)
            self.assertIn('commit=' + trusted, (root / 'output').read_text())

    def test_version_name_and_android_code_bounds(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)
            (path / 'version.properties').write_text('versionCode=1\nversionName=0.1.0\n')
            self.assertEqual(('0.1.0', 1), release.version(path))
            for code, name in [(0, '0.1.0'), (2100000001, '0.1.0'), (1, '../main'), (1, '00.1.0')]:
                (path / 'version.properties').write_text(f'versionCode={code}\nversionName={name}\n')
                with self.assertRaises(ValueError):
                    release.version(path)

    def test_draft_notes_allow_manual_candidate_but_block_tag_publication(self):
        release.validate_notes('workflow_dispatch', '<!-- release-status: draft -->')
        release.validate_notes('push', '<!-- release-status: ready -->\n# v0.1.0')
        with self.assertRaises(ValueError):
            release.validate_notes('push', '<!-- release-status: draft -->')
        with self.assertRaises(ValueError):
            release.validate_notes('push', 'not ready')

    def test_certificate_normalization_and_invalid_pins(self):
        self.assertEqual('aa' * 32, release.fingerprint(':'.join(['AA'] * 32)))
        for value in ['', 'wrong', 'aa' * 31, 'gg' * 32]:
            with self.assertRaises(ValueError):
                release.fingerprint(value)

    def test_verifier_rejects_wrong_identity_certificate_version_or_debug_apk(self):
        certificate = 'aa' * 32
        good_certs = f'Signer #1 certificate SHA-256 digest: {certificate}\nSigner #1 certificate DN: CN=memonow\n'
        good_package = "package: name='com.yantkys.memonow' versionCode='1' versionName='0.1.0'\n"
        def verify(certs, package, pin=certificate):
            results = [subprocess.CompletedProcess([], 0, stdout=certs), subprocess.CompletedProcess([], 0, stdout=package)]
            with patch.object(release.subprocess, 'run', side_effect=results), contextlib.redirect_stdout(io.StringIO()):
                release.verify_apk(Path('app.apk'), '0.1.0', 1, pin, '/sdk')
        verify(good_certs, good_package)
        for certs, package, pin in [
            (good_certs, good_package, 'bb' * 32),
            (good_certs + 'Android Debug', good_package, certificate),
            (good_certs, good_package.replace("versionCode='1'", "versionCode='2'"), certificate),
            (good_certs, good_package.replace('com.yantkys.memonow', 'other.app'), certificate),
            (good_certs, good_package + 'application-debuggable\n', certificate),
        ]:
            with self.assertRaises(ValueError):
                verify(certs, package, pin)

    def test_missing_signing_configuration_fails_without_printing_values(self):
        env = {key: value for key, value in os.environ.items() if not key.startswith('MEMONOW_RELEASE_')}
        result = subprocess.run(['python3', str(ROOT / 'tools/prepare-signing.py')], env=env, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('MEMONOW_RELEASE_KEYSTORE_BASE64', result.stderr)

    def test_temporary_keystore_is_private_and_only_its_path_is_exported(self):
        import base64
        with tempfile.TemporaryDirectory() as folder:
            env = dict(os.environ, RUNNER_TEMP=folder, GITHUB_ENV=str(Path(folder) / 'env'),
                       MEMONOW_RELEASE_KEYSTORE_BASE64=base64.b64encode(b'fixture-not-a-key').decode(),
                       MEMONOW_RELEASE_STORE_PASSWORD='fixture-only', MEMONOW_RELEASE_KEY_PASSWORD='fixture-only',
                       MEMONOW_RELEASE_KEY_ALIAS='fixture', MEMONOW_RELEASE_CERT_SHA256='aa' * 32)
            result = subprocess.run(['python3', str(ROOT / 'tools/prepare-signing.py')], env=env, capture_output=True, text=True)
            self.assertEqual(0, result.returncode)
            path = Path(folder) / 'memonow-signing/release.jks'
            self.assertEqual(b'fixture-not-a-key', path.read_bytes())
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
            self.assertNotIn('fixture-only', result.stdout + result.stderr)
            self.assertEqual(f'MEMONOW_RELEASE_KEYSTORE_PATH={path}\n', (Path(folder) / 'env').read_text())


if __name__ == '__main__':
    unittest.main()
