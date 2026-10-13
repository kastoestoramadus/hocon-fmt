#!/usr/bin/env python3
"""Exercise release.yml itself, without secrets, registries or a GitHub runner.

Extraction covers this workflow's job/step indentation and literal run blocks only;
GitHub expression evaluation and environment approval remain runner responsibilities.
"""
import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
import unittest


WORKFLOW = (Path(__file__).resolve().parent.parent / '.github/workflows/release.yml').read_text()
UPLOAD_CONDITION = "startsWith(github.ref, 'refs/tags/v') && !inputs.dry_run"


def job(name):
    match = re.search(rf'^  {name}:\n(.*?)(?=^  [\w-]+:|\Z)', WORKFLOW, re.M | re.S)
    if match is None:
        raise AssertionError(f'missing job: {name}')
    return match.group(1)


def steps(name):
    return re.findall(r'^      - .*?(?=^      - |\Z)', job(name), re.M | re.S)


def step(name, title):
    matches = [s for s in steps(name) if s.startswith(f'      - name: {title}\n')]
    if len(matches) != 1:
        raise AssertionError(f'expected one {name} step named {title}, got {len(matches)}')
    return matches[0]


def shell(source):
    match = re.search(r'^        run: \|\n((?:^          .*\n|^\n)+)', source, re.M)
    if match:
        return textwrap.dedent(match.group(1))
    match = re.search(r'^        run: (.+)$', source, re.M)
    if match:
        return match.group(1)
    raise AssertionError('missing run shell')


def run_shell(source, directory, **env):
    return subprocess.run(
        ['bash', '--noprofile', '--norc', '-e', '-o', 'pipefail', '-c', shell(source)],
        cwd=directory, env={**os.environ, **env}, text=True, capture_output=True,
    )


def git(directory, *args):
    return subprocess.check_output(['git', '-C', str(directory), *args], text=True,
                                   stderr=subprocess.STDOUT).strip()


class ReleaseWorkflowTest(unittest.TestCase):
    def ancestry(self, scenario):
        source = step('version', "Tag is on main's history")
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            origin, tree = root / 'origin.git', root / 'tree'
            git(root, 'init', '--bare', str(origin))
            git(root, 'init', '-b', 'main', str(tree))
            git(tree, 'config', 'user.name', 'Release fixture')
            git(tree, 'config', 'user.email', 'fixture@example.invalid')
            git(tree, 'commit', '--allow-empty', '-m', 'base')
            base = git(tree, 'rev-parse', 'HEAD')
            git(tree, 'tag', '-a', 'v1.2.3', '-m', 'annotated historical tag')
            annotated = git(tree, 'rev-parse', 'v1.2.3')
            git(tree, 'commit', '--allow-empty', '-m', 'main advances')
            main = git(tree, 'rev-parse', 'HEAD')
            git(tree, 'remote', 'add', 'origin', str(origin))
            git(tree, 'push', 'origin', 'main')
            git(tree, 'switch', '-c', 'topic', base)
            git(tree, 'commit', '--allow-empty', '-m', 'unmerged topic')
            topic = git(tree, 'rev-parse', 'HEAD')
            target = {'current': main, 'historical': annotated, 'topic': topic,
                      'fetch-failure': main, 'branch': topic}[scenario]
            # A stale origin/main must be refreshed, and an unavailable origin must fail closed.
            git(tree, 'update-ref', 'refs/remotes/origin/main', base)
            if scenario == 'fetch-failure':
                git(tree, 'remote', 'set-url', 'origin', str(root / 'missing.git'))
            result = run_shell(source, tree, GITHUB_SHA=target,
                               GITHUB_REF='refs/heads/topic' if scenario == 'branch'
                               else 'refs/tags/v1.2.3')
            print(f'ancestry {scenario}: exit {result.returncode}; '
                  f'{result.stdout.strip()} {result.stderr.strip()}', flush=True)
            return result

    def test_ancestry_wiring(self):
        checkout = next(s for s in steps('version') if 'uses: actions/checkout@' in s)
        self.assertIn('fetch-depth: 0', checkout)
        self.assertIn('needs: version', job('central'))

    def test_guards_cannot_be_skipped_or_ignored(self):
        for name, title in (('version', "Tag is on main's history"),
                            ('central', 'Upload mode')):
            source = step(name, title)
            for key in ('if', 'continue-on-error'):
                with self.subTest(job=name, key=key):
                    self.assertNotRegex(source, re.compile(rf'^        {key}:', re.M))
            ordered = steps(name)
            # Local publishing is preparation; registry/artifact upload steps must follow guards.
            for upload in ordered:
                if re.search(r'^      - name: Upload,|uses: actions/upload-artifact@', upload, re.M):
                    self.assertLess(ordered.index(source), ordered.index(upload))

    def test_branch_dry_run_skips_tag_ancestry(self):
        self.assertEqual(self.ancestry('branch').returncode, 0)

    def test_current_main_tag_passes(self):
        self.assertEqual(self.ancestry('current').returncode, 0)

    def test_historical_annotated_tag_passes(self):
        self.assertEqual(self.ancestry('historical').returncode, 0)

    def test_unmerged_tag_fails(self):
        result = self.ancestry('topic')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('::error::', result.stdout)

    def test_failed_main_fetch_fails(self):
        self.assertNotEqual(self.ancestry('fetch-failure').returncode, 0)

    def preflight(self, wave, upload, key, secret):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            summary = root / 'summary'
            marker = root / 'uploads'
            summary.touch()
            result = run_shell(step('central', 'Upload mode'), root,
                               RELEASE_WAVE=wave, CAN_UPLOAD=upload,
                               GRADLE_PUBLISH_KEY=key, GRADLE_PUBLISH_SECRET=secret,
                               GITHUB_STEP_SUMMARY=str(summary))
            # Execute the next upload only if the runner would continue after the preflight.
            if result.returncode == 0 and upload == 'true':
                stub = root / 'sbt'
                stub.write_text('#!/bin/bash\necho sbt-upload >> "$UPLOAD_MARKER"\n')
                stub.chmod(0o755)
                run = run_shell(step('central', 'Upload, sbt'), root,
                                PATH=f'{root}:{os.environ["PATH"]}',
                                MAVEN_REPO_LOCAL=str(root / 'm2'), UPLOAD_MARKER=str(marker))
                self.assertEqual(run.returncode, 0, run.stderr)
            print(f'preflight wave={wave or "unset"} upload={upload} '
                  f'key={bool(key)} secret={bool(secret)}: exit {result.returncode}; '
                  f'{result.stdout.strip()}; summary={summary.read_text().strip()}; '
                  f'upload={marker.exists()}', flush=True)
            return result, summary.read_text(), marker.exists()

    def test_wave1_missing_credentials_warn_and_continue(self):
        for wave in ('', '1'):
            for key, secret in (('', ''), ('fixture-key', ''), ('', 'fixture-secret')):
                with self.subTest(wave=wave, key=bool(key), secret=bool(secret)):
                    result, summary, uploaded = self.preflight(wave, 'true', key, secret)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertIn('::warning::', result.stdout)
                    self.assertIn('Plugin Portal', summary)
                    self.assertTrue(uploaded)

    def test_wave2_missing_credentials_fail_before_upload(self):
        for key, secret in (('', ''), ('fixture-key', ''), ('', 'fixture-secret')):
            with self.subTest(key=bool(key), secret=bool(secret)):
                result, summary, uploaded = self.preflight('2', 'true', key, secret)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('::error::', result.stdout)
                self.assertIn('Plugin Portal', summary)
                self.assertFalse(uploaded)

    def test_dry_runs_allow_missing_credentials(self):
        for wave in ('1', '2'):
            for key, secret in (('', ''), ('fixture-key', 'fixture-secret')):
                result, summary, uploaded = self.preflight(wave, 'false', key, secret)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn('Dry run:', result.stdout)
                self.assertNotIn('::warning::', result.stdout)
                self.assertNotIn('::error::', result.stdout)
                self.assertEqual(summary, '')
                self.assertFalse(uploaded)

    def test_complete_credentials_allow_upload(self):
        for wave in ('1', '2'):
            result, summary, uploaded = self.preflight(wave, 'true', 'fixture-key', 'fixture-secret')
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertNotIn('::warning::', result.stdout)
            self.assertNotIn('::error::', result.stdout)
            self.assertEqual(summary, '')
            self.assertTrue(uploaded)

    def test_preflight_wiring_and_order(self):
        preflight = step('central', 'Upload mode')
        for name, expression in (
            ('CAN_UPLOAD', UPLOAD_CONDITION),
            ('RELEASE_WAVE', "vars.RELEASE_WAVE || '1'"),
            ('GRADLE_PUBLISH_KEY', 'secrets.GRADLE_PUBLISH_KEY'),
            ('GRADLE_PUBLISH_SECRET', 'secrets.GRADLE_PUBLISH_SECRET'),
        ):
            self.assertIn(f'{name}: ${{{{ {expression} }}}}', preflight)
        ordered = steps('central')
        # Check order as well as membership, including Mill so enabling it stays guarded.
        uploads = [step('central', name) for name in (
            'Upload, sbt', 'Upload, Maven', 'Upload, Gradle (or explain why skipped)',
            'Upload, Mill')]
        for upload in uploads:
            self.assertLess(ordered.index(preflight), ordered.index(upload))
        self.assertIn(f'if: {UPLOAD_CONDITION}', uploads[0])
        self.assertIn(f'if: {UPLOAD_CONDITION}', uploads[1])
        self.assertIn(f'CAN_UPLOAD: ${{{{ {UPLOAD_CONDITION} }}}}', uploads[2])
        self.assertRegex(WORKFLOW, r'dry_run:\n(?:.*\n)*?        default: true')

    def test_gradle_upload_and_skip_shell(self):
        for upload, key, secret, expected in (
            ('true', '', '', False), ('true', 'fixture-key', '', False),
            ('true', '', 'fixture-secret', False),
            ('false', 'fixture-key', 'fixture-secret', False),
            ('true', 'fixture-key', 'fixture-secret', True),
        ):
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                stub = root / 'gradlew'
                stub.write_text('#!/bin/bash\necho STUB-publishPlugins\n')
                stub.chmod(0o755)
                result = run_shell(step('central', 'Upload, Gradle (or explain why skipped)'),
                                   root, CAN_UPLOAD=upload, GRADLE_PUBLISH_KEY=key,
                                   GRADLE_PUBLISH_SECRET=secret, MAVEN_REPO_LOCAL=str(root / 'm2'))
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual('STUB-publishPlugins' in result.stdout, expected)

    def test_only_release_has_contents_write(self):
        self.assertRegex(WORKFLOW, re.compile(r'^permissions:\n  contents: read\n', re.M))
        for name in re.findall(r'^  ([\w-]+):$', WORKFLOW, re.M):
            if name in ('push', 'workflow_dispatch', 'contents'):
                continue
            if name == 'release':
                self.assertIn('    permissions:\n      contents: write', job(name))
            else:
                self.assertNotIn('contents: write', job(name))

    def test_actions_have_full_commit_pins(self):
        refs = re.findall(r'uses: (\S+)', WORKFLOW)
        self.assertTrue(refs)
        for ref in refs:
            self.assertRegex(ref, r'@[0-9a-f]{40}$')


if __name__ == '__main__':
    unittest.main(verbosity=2)
