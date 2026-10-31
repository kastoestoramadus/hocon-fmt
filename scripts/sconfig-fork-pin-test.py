#!/usr/bin/env python3
"""UPSTREAM-SCONFIG: check the fork pin and bootstrap paths without remote state."""
from pathlib import Path
import os
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
REPOSITORY = 'https://github.com/kastoestoramadus/sHOCON.git'
ARTIFACTS = ('sconfig_3', 'sconfig_sjs1_3', 'sconfig_native0.5_3')


def split_sbt_commands(line: str) -> list[str]:
    """Split one sbt command line on the semicolons that sit outside double quotes."""
    commands: list[str] = []
    current: list[str] = []
    quoted = False
    for character in line:
        if character == '"':
            quoted = not quoted
        if character == ';' and not quoted:
            commands.append(''.join(current).strip())
            current = []
        else:
            current.append(character)
    commands.append(''.join(current).strip())
    return [command for command in commands if command]


class SconfigForkPinTest(unittest.TestCase):
    script: str
    build: str
    docs: str
    sha: str
    version: str

    @classmethod
    def setUpClass(cls) -> None:
        cls.script = (ROOT / 'scripts/fetch-sconfig-fork.sh').read_text()
        cls.build = (ROOT / 'build.sbt').read_text()
        cls.docs = (ROOT / 'docs/site.md').read_text()
        pins = re.findall(r'^SHA=([0-9a-f]{40})$', cls.script, re.M)
        if len(pins) != 1:
            raise AssertionError(f'Expected one full SHA, got {pins}')
        cls.sha = pins[0]
        cls.version = f'2.1.0-hocon-fmt-{cls.sha[:10]}'

    def test_script_pins_fork_and_scala(self) -> None:
        self.assertIn(REPOSITORY, self.script)
        self.assertIn('HOCON_FMT_FORK_REPOSITORY', self.script)
        self.assertIn('SHORT=${SHA:0:10}', self.script)
        self.assertIn('VERSION="2.1.0-hocon-fmt-$SHORT"', self.script)
        self.assertIn('++3.9.0!', self.script)

    def test_one_build_version_for_every_module(self) -> None:
        versions = re.findall(r'^val sconfigFork = "([^"]+)"$', self.build, re.M)
        self.assertEqual(versions, [self.version])
        self.assertRegex(self.build, r'(?m)^val sconfig\s*= sconfigFork$')

    def test_documented_sha_and_version(self) -> None:
        pins = re.findall(r'Pinned fork commit: `([0-9a-f]{40})`', self.docs)
        self.assertEqual(pins, [self.sha])
        self.assertIn(f'`{self.version}`', self.docs)

    def test_all_platforms_publish_to_both_repositories(self) -> None:
        for platform in ('JVM', 'JS', 'Native'):
            for task in ('publishLocal', 'publishM2'):
                self.assertIn(f'sconfig{platform}/{task}', self.script)
        for artifact in ARTIFACTS:
            self.assertIn(f'"{artifact}"', self.build)
        # One resolving project: `core`'s cross-build; the page runs it through `coreJS`.
        self.assertEqual(self.build.count('update.dependsOn(ThisBuild / checkSconfigFork)'), 1)

    def test_every_sbt_workflow_job_bootstraps_fork(self) -> None:
        for workflow in ('ci.yml', 'pages.yml', 'release.yml'):
            content = (ROOT / '.github/workflows' / workflow).read_text()
            for job in re.split(r'(?m)^  [\w-]+:\n', content)[1:]:
                if 'uses: sbt/setup-sbt@' in job:
                    self.assertIn('run: scripts/fetch-sconfig-fork.sh', job, workflow)
                    self.assertLess(job.index('run: scripts/fetch-sconfig-fork.sh'),
                                    job.index('run: sbt') if 'run: sbt' in job else len(job))
        precommit = (ROOT / 'scripts/pre-commit-e2e.sh').read_text()
        self.assertIn('"$root/scripts/fetch-sconfig-fork.sh"', precommit)

    def probe_cache(self, missing: str | None) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            for artifact in ARTIFACTS:
                files = (
                    home / '.ivy2/local/org.ekrich' / artifact / self.version / 'jars' / f'{artifact}.jar',
                    home / '.ivy2/local/org.ekrich' / artifact / self.version / 'ivys/ivy.xml',
                    home / '.m2/repository/org/ekrich' / artifact / self.version / f'{artifact}-{self.version}.jar',
                    home / '.m2/repository/org/ekrich' / artifact / self.version / f'{artifact}-{self.version}.pom',
                )
                for path in files:
                    if missing and missing in str(path):
                        continue
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text('cached fixture')
            bin_dir = home / 'bin'
            bin_dir.mkdir()
            git = bin_dir / 'git'
            git.write_text('#!/bin/sh\necho "fetch required" >&2\nexit 42\n')
            git.chmod(0o755)
            env = {key: value for key, value in os.environ.items()
                   if not key.startswith('HOCON_FMT_FORK_') and key != 'MAVEN_REPO_LOCAL'}
            env.update(HOME=str(home), PATH=f'{bin_dir}:{env["PATH"]}',
                       HOCON_FMT_FORK_CACHE=str(home / 'source'))
            return subprocess.run(['bash', str(ROOT / 'scripts/fetch-sconfig-fork.sh')],
                                  env=env, text=True, capture_output=True, check=False)

    def test_complete_cache_skips_build(self) -> None:
        result = self.probe_cache(None)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('already published', result.stdout)

    def test_partial_cache_requires_build(self) -> None:
        for missing in ('sconfig_3.jar', 'sconfig_sjs1_3.jar', 'sconfig_native0.5_3.jar',
                        '.m2/', 'ivy.xml'):
            with self.subTest(missing=missing):
                result = self.probe_cache(missing)
                self.assertEqual(result.returncode, 42, result.stdout + result.stderr)
                self.assertIn('fetch required', result.stderr)

    def stubbed_sbt_argv(self) -> list[str]:
        """Run the bootstrap against a partial cache with an sbt stub that records its argv."""
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            bin_dir = home / 'bin'
            bin_dir.mkdir()
            git = bin_dir / 'git'
            git.write_text('#!/bin/sh\nexit 0\n')
            git.chmod(0o755)
            recorded = home / 'sbt-argv.txt'
            sbt = bin_dir / 'sbt'
            sbt.write_text(f'#!/bin/sh\nprintf \'%s\\n\' "$@" > "{recorded}"\nexit 1\n')
            sbt.chmod(0o755)
            env = {key: value for key, value in os.environ.items()
                   if not key.startswith('HOCON_FMT_FORK_') and key != 'MAVEN_REPO_LOCAL'}
            env.update(HOME=str(home), PATH=f'{bin_dir}:{env["PATH"]}',
                       HOCON_FMT_FORK_CACHE=str(home / 'source'))
            subprocess.run(['bash', str(ROOT / 'scripts/fetch-sconfig-fork.sh')],
                           env=env, text=True, capture_output=True, check=False)
            return recorded.read_text().splitlines()

    def test_sbt_commands_arrive_as_one_semicolon_separated_argument(self) -> None:
        """The sbt 2 thin client joins command arguments with spaces before parsing;
        only one ';'-separated argument parses identically with and without it."""
        argv = self.stubbed_sbt_argv()
        flags = [arg for arg in argv if arg.startswith('-')]
        command_args = [arg for arg in argv if not arg.startswith('-')]
        self.assertIn('-batch', flags)
        self.assertTrue(any(arg.startswith('-Dsbt.ivy.home=') for arg in flags))
        self.assertTrue(any(arg.startswith('-Dmaven.repo.local=') for arg in flags))
        expected = [f'set ThisBuild / version := "{self.version}"', '++3.9.0!']
        expected += [f'sconfig{platform}/{task}'
                     for platform in ('JVM', 'JS', 'Native')
                     for task in ('publishLocal', 'publishM2')]
        self.assertEqual(len(command_args), 1, command_args)
        self.assertEqual(split_sbt_commands(' '.join(command_args)), expected)


if __name__ == '__main__':
    unittest.main(verbosity=2)
