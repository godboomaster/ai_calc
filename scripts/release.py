#!/usr/bin/env python3
"""Build locally, sign with Android SDK locally, upload only APK + SHA256 to GitHub."""
import argparse
import getpass
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile


class ReleaseError(Exception):
    pass


def version_code(version):
    if not re.fullmatch(r'(0|[1-9]\d{0,3})\.(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})', version):
        raise ReleaseError('Version must be MAJOR.MINOR.PATCH, for example 1.1.0')
    major, minor, patch = map(int, version.split('.'))
    code = major * 1_000_000 + minor * 1_000 + patch
    if major > 2099 or code == 0:
        raise ReleaseError('Version is outside the Android versionCode range')
    return code


def github_repo(url):
    match = re.fullmatch(r'(?:git@github\.com:|https://github\.com/|ssh://git@github\.com/)'
                         r'([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)', url.strip())
    if not match:
        raise ReleaseError('origin must be a GitHub SSH or HTTPS URL without embedded credentials')
    return match[1].removesuffix('.git')


def child_env(environ, github=False):
    return {k: v for k, v in environ.items()
            if not k.upper().startswith(('ANDROID_KEY', 'LOCAL_RELEASE_'))
            and (github or k.upper() not in ('GH_TOKEN', 'GITHUB_TOKEN', 'GH_ENTERPRISE_TOKEN'))}


class Runner:
    def __init__(self, root, env):
        self.root, self.env = root, env

    def run(self, args, *, cwd=None, env=None, check=True, stream=False, binary=False):
        command = list(map(str, args))
        if command[0] == 'git':
            command[1:1] = ['-c', 'safe.directory=' + self.root.as_posix()]
        result = subprocess.run(command, cwd=cwd or self.root, env=env or self.env,
            capture_output=not stream, text=not binary,
            **({} if binary else {'encoding': 'utf-8', 'errors': 'replace'}))
        if check and result.returncode:
            detail = result.stderr or ''
            if isinstance(detail, bytes):
                detail = detail.decode('utf-8', errors='replace')
            raise ReleaseError(f'{args[0]} failed ({result.returncode}). {detail.strip()}')
        return result


def clean_head(runner):
    # Untracked personal files are never included in the committed source archive.
    if runner.run(['git', 'status', '--porcelain', '--untracked-files=no']).stdout.strip():
        raise ReleaseError('Commit or stash tracked changes before publishing')
    return runner.run(['git', 'rev-parse', 'HEAD']).stdout.strip()


def archive_source(runner, head, dest):
    data = runner.run(['git', 'archive', '--format=tar', head], binary=True).stdout
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        for member in archive:
            name = PurePosixPath(member.name)
            target = dest.joinpath(*name.parts).resolve()
            if name.is_absolute() or '..' in name.parts or not target.is_relative_to(dest.resolve()):
                raise ReleaseError('Unsafe source archive path')
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
            elif member.isfile():
                target.parent.mkdir(parents=True, exist_ok=True)
                with archive.extractfile(member) as src, target.open('wb') as out:
                    shutil.copyfileobj(src, out)
            else:
                raise ReleaseError('Release source snapshots do not support links or special files')


def verify_metadata(badging, certificate, expected, version):
    package = f"package: name='com.smartcalc.app' versionCode='{version_code(version)}' versionName='{version}'"
    if package not in badging or "application-label:'Калькулятор'" not in badging:
        raise ReleaseError('APK package, version or application label does not match this release')
    if 'application-debuggable' in badging:
        raise ReleaseError('Refusing to publish a debuggable APK')
    fingerprints = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})$',
                              certificate, re.MULTILINE)
    if not re.fullmatch('[a-f0-9]{64}', expected) or [f.lower() for f in fingerprints] != [expected]:
        raise ReleaseError('Signing certificate changed; refusing an incompatible update')


def remote_tag(runner, tag):
    lines = runner.run(['git', 'ls-remote', '--tags', 'origin',
                        f'refs/tags/{tag}', f'refs/tags/{tag}^{{}}']).stdout.splitlines()
    refs = dict(reversed(line.split()) for line in lines if line.strip())
    return refs.get(f'refs/tags/{tag}^{{}}', refs.get(f'refs/tags/{tag}'))


def draft_marker(head):
    return f'<!-- smartcalc-local-release: {head} -->'


def release_state(runner, repo, tag, head):
    result = runner.run(['gh', 'api', f'repos/{repo}/releases/tags/{tag}'], check=False)
    if result.returncode:
        if 'HTTP 404' in result.stderr:
            return None
        raise ReleaseError('Cannot check GitHub release: ' + result.stderr.strip())
    state = json.loads(result.stdout)
    if state.get('draft') is not True:
        raise ReleaseError('Release already published; use a new version')
    if draft_marker(head) not in (state.get('body') or '').splitlines():
        raise ReleaseError('Existing draft was not created by this command for this commit')
    return state


def check_target(runner, repo, tag, head):
    remote = remote_tag(runner, tag)
    if remote is not None and remote != head:
        raise ReleaseError('Remote version tag points to another commit')
    state = release_state(runner, repo, tag, head)
    if state is not None and remote is None:
        raise ReleaseError('Existing draft has no matching remote tag; check it manually')
    published = runner.run(['gh', 'api', f'repos/{repo}/releases', '--paginate',
        '--jq', '.[] | select(.draft == false and .prerelease == false) | .tag_name']).stdout
    requested_code = version_code(tag.removeprefix('v'))
    for previous_tag in published.splitlines():
        try:
            previous_code = version_code(previous_tag.removeprefix('v'))
        except ReleaseError:
            continue  # unrelated/non-semver releases cannot describe an Android versionCode
        if requested_code <= previous_code:
            raise ReleaseError('Version must be newer than published release ' + previous_tag)
    return remote, state


def publish(runner, repo, tag, head, apk):
    # Recheck after the build. Never overwrite another commit's tag or published release.
    remote, state = check_target(runner, repo, tag, head)
    if remote is None:
        runner.run(['git', 'push', 'origin', f'{head}:refs/tags/{tag}'])
    if state is None:
        runner.run(['gh', 'release', 'create', tag, '--repo', repo, '--verify-tag',
                    '--draft', '--title', tag, '--generate-notes', '--notes', draft_marker(head)])
    runner.run(['gh', 'release', 'upload', tag, str(apk), str(apk.with_suffix('.apk.sha256')),
                '--repo', repo, '--clobber'])
    # An upload failure raises above, leaving the draft unpublished for a later retry.
    runner.run(['gh', 'release', 'edit', tag, '--repo', repo, '--draft=false'])


def load_config(root):
    path = root / 'release.local.json'
    if not path.is_file():
        raise ReleaseError('Create release.local.json using release.local.json.example (paths and alias only)')
    config = json.loads(path.read_text(encoding='utf-8-sig'))
    allowed = {'java_home', 'android_sdk', 'gradle_user_home', 'keystore', 'key_alias'}
    if set(config) - allowed:
        raise ReleaseError('Unexpected local configuration fields; passwords belong only in hidden prompts or environment variables')
    for name in ('java_home', 'android_sdk', 'keystore', 'key_alias'):
        if not isinstance(config.get(name), str) or not config[name].strip():
            raise ReleaseError('Missing local configuration field: ' + name)
    for name in ('java_home', 'android_sdk', 'gradle_user_home', 'keystore'):
        if name in config:
            config[name] = str(Path(config[name]).expanduser().resolve())
    if not Path(config['keystore']).is_file():
        raise ReleaseError('Configured keystore does not exist')
    # Never accept a private key that would be included in the committed source snapshot.
    key = Path(config['keystore'])
    if key.is_relative_to(root):
        runner = Runner(root, child_env(os.environ))
        tracked = runner.run(['git', 'ls-files', '--', key.relative_to(root).as_posix()]).stdout
        if tracked.strip():
            raise ReleaseError('The signing keystore must not be tracked by Git')
    return config


def build_and_sign(runner, source, output, version, config, offline):
    java = Path(config['java_home']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
    sdk_tools = Path(config['android_sdk']) / 'build-tools' / '35.0.0'
    signer = sdk_tools / 'lib/apksigner.jar'
    aapt = sdk_tools / ('aapt.exe' if os.name == 'nt' else 'aapt')
    for tool in (java, signer, aapt):
        if not tool.is_file():
            raise ReleaseError('Required local tool is missing: ' + str(tool))
    env = child_env(os.environ)
    env.update(JAVA_HOME=config['java_home'], ANDROID_HOME=config['android_sdk'],
               ANDROID_SDK_ROOT=config['android_sdk'])
    if config.get('gradle_user_home'):
        env['GRADLE_USER_HOME'] = config['gradle_user_home']
    # Direct Java invocation avoids Windows batch-file argument parsing.
    args = [java, '-classpath', source / 'gradle/wrapper/gradle-wrapper.jar',
            'org.gradle.wrapper.GradleWrapperMain', '--no-daemon', '--max-workers=2',
            f'-PappVersion={version}', ':app:testDebugUnitTest', ':app:lintRelease', ':app:assembleRelease']
    if offline:
        args.append('--offline')
    print('Building and checking unsigned APK...', flush=True)
    runner.run(args, cwd=source, env=env, stream=True)
    unsigned = source / 'app/build/outputs/apk/release/app-release-unsigned.apk'
    if not unsigned.is_file():
        raise ReleaseError('Gradle did not produce the expected unsigned release APK')
    output.mkdir(parents=True, exist_ok=True)
    apk = output / 'Calculator.apk'
    password = os.environ.get('ANDROID_KEYSTORE_PASSWORD') or getpass.getpass('Keystore password: ')
    key_password = os.environ.get('ANDROID_KEY_PASSWORD') or getpass.getpass('Key password (Enter = same): ') or password
    sign_env = dict(env, LOCAL_RELEASE_STORE_PASSWORD=password, LOCAL_RELEASE_KEY_PASSWORD=key_password)
    try:
        runner.run([java, '-jar', signer, 'sign', '--ks', config['keystore'],
            '--ks-key-alias', config['key_alias'], '--ks-pass', 'env:LOCAL_RELEASE_STORE_PASSWORD',
            '--key-pass', 'env:LOCAL_RELEASE_KEY_PASSWORD', '--v4-signing-enabled', 'false',
            '--out', apk, unsigned], env=sign_env)
    finally:
        sign_env.clear()
        password = key_password = None
    certificate = runner.run([java, '-jar', signer, 'verify', '--print-certs', apk], env=env).stdout
    badging = runner.run([aapt, 'dump', 'badging', apk], env=env).stdout
    expected = (source / 'release-signing.sha256').read_text(encoding='ascii').strip()
    verify_metadata(badging, certificate, expected, version)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    apk.with_suffix('.apk.sha256').write_text(f'{digest}  Calculator.apk\n', encoding='ascii')
    print('Verified APK, version and original signing certificate: ' + str(apk), flush=True)
    return apk


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('version', help='Stable version, for example 1.1.0')
    parser.add_argument('--build-only', action='store_true', help='Check/build/sign current files; do not contact GitHub')
    parser.add_argument('--offline', action='store_true', help='Use cached Gradle dependencies (GitHub publishing still needs network)')
    args = parser.parse_args(argv)
    version_code(args.version)
    root = Path(__file__).resolve().parents[1]
    config = load_config(root)
    runner = Runner(root, child_env(os.environ, github=True))
    output = root / 'build/releases' / ('v' + args.version)
    if args.build_only:
        build_and_sign(runner, root, output, args.version, config, args.offline)
        return 0
    head = clean_head(runner)
    repo = github_repo(runner.run(['git', 'remote', 'get-url', 'origin']).stdout)
    tag = 'v' + args.version
    local = runner.run(['git', 'rev-parse', '--verify', f'refs/tags/{tag}^{{commit}}'], check=False)
    if local.returncode == 0 and local.stdout.strip() != head:
        raise ReleaseError('Local version tag points to another commit')
    permission = json.loads(runner.run(['gh', 'repo', 'view', repo, '--json', 'nameWithOwner,viewerPermission']).stdout)
    if permission.get('nameWithOwner', '').lower() != repo.lower() or permission.get('viewerPermission') not in ('WRITE', 'MAINTAIN', 'ADMIN'):
        raise ReleaseError('GitHub CLI needs write access to the origin repository')
    check_target(runner, repo, tag, head)
    print(f'Releasing {tag} from {head} to {repo}', flush=True)
    output.parent.mkdir(parents=True, exist_ok=True)
    # The temporary directory is owned by this invocation; no user directories are deleted.
    with tempfile.TemporaryDirectory(prefix='source-', dir=output.parent) as temporary:
        source = Path(temporary).resolve()
        if not source.is_relative_to(output.parent.resolve()):
            raise ReleaseError('Unexpected temporary source directory')
        archive_source(runner, head, source)
        apk = build_and_sign(runner, source, output, args.version, config, args.offline)
    publish(runner, repo, tag, head, apk)
    print(f'https://github.com/{repo}/releases/tag/{tag}')
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ReleaseError, OSError, ValueError, KeyboardInterrupt) as exc:
        print('Release stopped: ' + str(exc), file=sys.stderr)
        sys.exit(1)
