import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / 'release.py'
SHA = 'a' * 40
CERT = 'b' * 64


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue(SCRIPT.is_file(), 'Local release command is missing')
        spec = importlib.util.spec_from_file_location('local_release', SCRIPT)
        self.release = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.release)

    def test_versions_are_ordered_and_invalid_tags_rejected(self):
        for version, code in [('1.1.0', 1001000), ('1.1.1', 1001001), ('2.0.0', 2000000),
                              ('0.0.2', 2), ('2099.999.999', 2099999999)]:
            self.assertEqual(self.release.version_code(version), code)
        for version in ['0.0.0', '2100.0.0', '1.1000.0', '1.01.0', 'v1.1.0',
                        '1.1.0-beta', '1.1.0\n', '1.1.0;echo bad']:
            with self.subTest(version=version), self.assertRaises(self.release.ReleaseError):
                self.release.version_code(version)

    def test_only_unambiguous_github_origins_are_allowed(self):
        for url in ['git@github.com:owner/project.git', 'https://github.com/owner/project.git',
                    'ssh://git@github.com/owner/project']:
            self.assertEqual(self.release.github_repo(url), 'owner/project')
        for url in ['https://github.com.evil/owner/project', 'https://token@github.com/owner/project',
                    'git@elsewhere:owner/project.git', 'https://github.com/owner/project/extra']:
            with self.assertRaises(self.release.ReleaseError):
                self.release.github_repo(url)

    def test_signing_credentials_do_not_reach_build_or_github(self):
        env = {'PATH': 'tools', 'ANDROID_KEYSTORE_PATH': 'private.keystore',
               'ANDROID_KEYSTORE_PASSWORD': 'store-secret', 'ANDROID_KEY_PASSWORD': 'key-secret',
               'ANDROID_KEY_ALIAS': 'private-alias', 'ANDROID_KEYSTORE_BASE64': 'secret-data',
               'GH_TOKEN': 'github-token', 'GITHUB_TOKEN': 'another-token'}
        self.assertEqual(self.release.child_env(env), {'PATH': 'tools'})
        self.assertEqual(self.release.child_env(env, github=True),
                         {'PATH': 'tools', 'GH_TOKEN': 'github-token', 'GITHUB_TOKEN': 'another-token'})
        self.assertIn('ANDROID_KEY_PASSWORD', env)  # caller's environment is not mutated

    def test_wrong_signature_version_package_or_debug_build_is_rejected(self):
        badge = "package: name='com.smartcalc.app' versionCode='1001000' versionName='1.1.0'\napplication-label:'Калькулятор'\n"
        cert = 'Signer #1 certificate SHA-256 digest: ' + CERT + '\n'
        self.release.verify_metadata(badge, cert, CERT, '1.1.0')
        for bad_badge, bad_cert in [
            (badge, cert.replace(CERT, 'c' * 64)),
            (badge.replace('1001000', '1'), cert),
            (badge.replace('com.smartcalc.app', 'com.smartcalc.app.preview'), cert),
            (badge + 'application-debuggable\n', cert),
            (badge.replace('Калькулятор', 'Preview'), cert),
        ]:
            with self.assertRaises(self.release.ReleaseError):
                self.release.verify_metadata(bad_badge, bad_cert, CERT, '1.1.0')

    def test_archive_contains_only_committed_sources(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / 'repo'
            root.mkdir()
            def git(*args):
                return subprocess.check_output(['git', '-c', 'safe.directory=' + root.as_posix(),
                    '-C', str(root), *args], stderr=subprocess.PIPE).decode().strip()
            git('init')
            git('config', 'user.email', 'test@example.invalid')
            git('config', 'user.name', 'Release test')
            (root/'source.txt').write_text('committed')
            git('add', 'source.txt')
            git('commit', '-m', 'fixture')
            (root/'private.keystore').write_text('must never enter build snapshot')
            runner = self.release.Runner(root, self.release.child_env(os.environ))
            head = self.release.clean_head(runner)
            dest = Path(tmp) / 'snapshot'
            dest.mkdir()
            self.release.archive_source(runner, head, dest)
            self.assertEqual((dest/'source.txt').read_text(), 'committed')
            self.assertFalse((dest/'private.keystore').exists())
            (root/'source.txt').write_text('uncommitted')
            with self.assertRaises(self.release.ReleaseError):
                self.release.clean_head(runner)

    def test_build_keeps_passwords_exclusive_to_signer(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            sdk_tools = root / 'sdk/build-tools/35.0.0'
            java = root / 'jdk/bin' / ('java.exe' if os.name == 'nt' else 'java')
            aapt = sdk_tools / ('aapt.exe' if os.name == 'nt' else 'aapt')
            signer = sdk_tools / 'lib/apksigner.jar'
            for tool in (java, aapt, signer):
                tool.parent.mkdir(parents=True, exist_ok=True)
                tool.touch()
            (root / 'release-signing.sha256').write_text(CERT)
            calls = []
            class FakeRunner:
                def run(inner, args, **kwargs):
                    command = list(map(str, args))
                    env = dict(kwargs['env'])
                    calls.append((command, env))
                    stdout = ''
                    if 'org.gradle.wrapper.GradleWrapperMain' in command:
                        unsigned = root / 'app/build/outputs/apk/release/app-release-unsigned.apk'
                        unsigned.parent.mkdir(parents=True)
                        unsigned.write_bytes(b'unsigned')
                    elif 'sign' in command:
                        Path(command[command.index('--out') + 1]).write_bytes(b'signed')
                    elif 'verify' in command:
                        stdout = 'Signer #1 certificate SHA-256 digest: ' + CERT + '\n'
                    elif 'badging' in command:
                        stdout = "package: name='com.smartcalc.app' versionCode='1001000' versionName='1.1.0'\napplication-label:'Калькулятор'\n"
                    return subprocess.CompletedProcess(command, 0, stdout, '')
            credentials = {'ANDROID_KEYSTORE_PASSWORD': 'store-secret', 'ANDROID_KEY_PASSWORD': 'key-secret',
                           'GH_TOKEN': 'github-secret', 'GITHUB_TOKEN': 'other-github-secret'}
            config = {'java_home': str(root / 'jdk'), 'android_sdk': str(root / 'sdk'),
                      'keystore': str(root / 'private.keystore'), 'key_alias': 'alias'}
            with patch.dict(os.environ, credentials):
                apk = self.release.build_and_sign(FakeRunner(), root, root/'out', '1.1.0', config, True)
            self.assertTrue(apk.with_suffix('.apk.sha256').is_file())
            for command, env in calls:
                self.assertFalse(any(value in command for value in credentials.values()))
                self.assertNotIn('GH_TOKEN', env)
                self.assertNotIn('GITHUB_TOKEN', env)
                self.assertNotIn('ANDROID_KEYSTORE_PASSWORD', env)
                if 'sign' in command:
                    self.assertEqual(env['LOCAL_RELEASE_STORE_PASSWORD'], 'store-secret')
                    self.assertEqual(env['LOCAL_RELEASE_KEY_PASSWORD'], 'key-secret')
                else:
                    self.assertNotIn('LOCAL_RELEASE_STORE_PASSWORD', env)
                    self.assertNotIn('LOCAL_RELEASE_KEY_PASSWORD', env)

    def publisher(self, *, published=False, remote_sha=None, upload_error=False, api_error=False, absent=False, annotated=False, foreign=False, versions=(), branch='main', main_sha=SHA):
        # GitHub is an external side-effect boundary; record the real publisher's commands.
        calls = []
        class FakeRunner:
            def run(inner, args, **kwargs):
                calls.append(args)
                if args == ['git', 'branch', '--show-current']:
                    return subprocess.CompletedProcess(args, 0, branch + '\n', '')
                if args == ['git', 'rev-parse', 'HEAD']:
                    return subprocess.CompletedProcess(args, 0, SHA + '\n', '')
                if args == ['git', 'ls-remote', '--heads', 'origin', 'refs/heads/main']:
                    return subprocess.CompletedProcess(args, 0, '' if main_sha is None else
                        main_sha + '\trefs/heads/main\n', '')
                if args[:3] == ['git', 'ls-remote', '--tags']:
                    return subprocess.CompletedProcess(args, 0, '' if remote_sha is None else
                        ('d'*40 + '\trefs/tags/v1.1.0\n' + remote_sha + '\trefs/tags/v1.1.0^{}\n'
                         if annotated else remote_sha + '\trefs/tags/v1.1.0\n'), '')
                if args[:2] == ['gh', 'api']:
                    if args[2] == 'repos/owner/project/releases':
                        return subprocess.CompletedProcess(args, 0, '\n'.join(versions), '')
                    if api_error:
                        return subprocess.CompletedProcess(args, 1, '', 'HTTP 503')
                    if absent:
                        return subprocess.CompletedProcess(args, 1, '', 'HTTP 404')
                    return subprocess.CompletedProcess(args, 0,
                        '{"draft": ' + ('false' if published else 'true') + ', "body": "' +
                        ('Manual draft' if foreign else '<!-- smartcalc-local-release: ' + SHA + ' -->') + '"}', '')
                if args[:3] == ['gh', 'release', 'upload'] and upload_error:
                    raise self.release.ReleaseError('upload interrupted')
                return subprocess.CompletedProcess(args, 0, '', '')
        return FakeRunner(), calls

    def test_publish_requires_main_at_pushed_commit(self):
        for options in [{'branch': 'codex/feature'}, {'branch': ''},
                        {'main_sha': 'f'*40}, {'main_sha': None}]:
            with self.subTest(options=options):
                runner, calls = self.publisher(absent=True, **options)
                with self.assertRaises(self.release.ReleaseError):
                    self.release.publish(runner, 'owner/project', 'v1.1.0', SHA, Path('out/Calculator.apk'))
                self.assertFalse(any(c[:2] == ['git', 'push'] or c[:2] == ['gh', 'release'] for c in calls))

    def test_publish_rejects_checkout_changed_during_build(self):
        runner, calls = self.publisher(absent=True)
        with self.assertRaises(self.release.ReleaseError):
            self.release.publish(runner, 'owner/project', 'v1.1.0', 'e'*40, Path('out/Calculator.apk'))
        self.assertFalse(any(c[:2] == ['git', 'push'] or c[:2] == ['gh', 'release'] for c in calls))

    def test_new_release_pushes_exact_commit_and_uploads_before_publishing(self):
        runner, calls = self.publisher(absent=True)
        self.release.publish(runner, 'owner/project', 'v1.1.0', SHA, Path('out/Calculator.apk'))
        self.assertIn(['git', 'push', 'origin', SHA + ':refs/tags/v1.1.0'], calls)
        changes = [c[:3] for c in calls if c[:2] == ['gh', 'release']]
        self.assertEqual(changes, [['gh', 'release', 'create'], ['gh', 'release', 'upload'], ['gh', 'release', 'edit']])
        create = next(c for c in calls if c[:3] == ['gh', 'release', 'create'])
        self.assertIn('--draft', create)
        self.assertIn('--verify-tag', create)

    def test_annotated_tag_is_compared_by_commit(self):
        runner, calls = self.publisher(remote_sha=SHA, annotated=True)
        self.assertEqual(self.release.remote_tag(runner, 'v1.1.0'), SHA)

    def test_unrelated_draft_or_missing_remote_tag_is_never_published(self):
        for options in [{'remote_sha': SHA, 'foreign': True}, {}]:
            runner, calls = self.publisher(**options)
            with self.assertRaises(self.release.ReleaseError):
                self.release.publish(runner, 'owner/project', 'v1.1.0', SHA, Path('out/Calculator.apk'))
            self.assertFalse(any(c[:2] == ['git', 'push'] or c[:2] == ['gh', 'release'] for c in calls))

    def test_equal_or_older_release_is_rejected_before_mutations(self):
        for version in ['v1.1.0', 'v1.2.0', 'v2.0.0']:
            runner, calls = self.publisher(absent=True, versions=['v0.9.0', version])
            with self.assertRaises(self.release.ReleaseError):
                self.release.publish(runner, 'owner/project', 'v1.1.0', SHA, Path('out/Calculator.apk'))
            self.assertFalse(any(c[:2] == ['git', 'push'] or c[:2] == ['gh', 'release'] for c in calls))

    def test_older_published_versions_allow_new_release(self):
        runner, calls = self.publisher(absent=True, versions=['v1.0.9', 'v0.9.0', 'unversioned'])
        self.release.publish(runner, 'owner/project', 'v1.1.0', SHA, Path('out/Calculator.apk'))
        self.assertEqual(calls[-1][:3], ['gh', 'release', 'edit'])

    def test_published_release_conflicting_tag_and_api_errors_never_upload(self):
        for opts in [{'published': True}, {'remote_sha': 'c'*40}, {'api_error': True}]:
            runner, calls = self.publisher(**opts)
            with self.assertRaises(self.release.ReleaseError):
                self.release.publish(runner, 'owner/project', 'v1.1.0', SHA, Path('out/Calculator.apk'))
            self.assertFalse(any(c[:3] == ['gh', 'release', 'upload'] for c in calls))
            self.assertFalse(any(c[:2] == ['git', 'push'] for c in calls))

    def test_draft_retry_uploads_only_apk_and_checksum_then_publishes(self):
        runner, calls = self.publisher(remote_sha=SHA)
        self.release.publish(runner, 'owner/project', 'v1.1.0', SHA, Path('out/Calculator.apk'))
        upload = next(c for c in calls if c[:3] == ['gh', 'release', 'upload'])
        self.assertIn(str(Path('out/Calculator.apk')), upload)
        self.assertIn(str(Path('out/Calculator.apk.sha256')), upload)
        self.assertIn('--clobber', upload)
        self.assertEqual(calls[-1][:3], ['gh', 'release', 'edit'])
        self.assertFalse(any(c[:2] == ['git', 'push'] for c in calls))

    def test_failed_upload_keeps_release_draft(self):
        runner, calls = self.publisher(remote_sha=SHA, upload_error=True)
        with self.assertRaises(self.release.ReleaseError):
            self.release.publish(runner, 'owner/project', 'v1.1.0', SHA, Path('out/Calculator.apk'))
        self.assertFalse(any(c[:3] == ['gh', 'release', 'edit'] for c in calls))


if __name__ == '__main__':
    unittest.main()
