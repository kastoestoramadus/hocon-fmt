#!/usr/bin/env python3
"""Exercise release.yml itself, without secrets, registries or a GitHub runner.

Shell extraction covers this workflow's indentation and literal run blocks only.
Publishing guards and secret placement are checked on parsed YAML; GitHub expression
evaluation and environment approval remain runner responsibilities.
"""
from copy import deepcopy
import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
import unittest

import yaml


WORKFLOW = (Path(__file__).resolve().parent.parent / '.github/workflows/release.yml').read_text()
UPLOAD_CONDITION = "startsWith(github.ref, 'refs/tags/v') && !inputs.dry_run"


def release_guard_errors(workflow):
    errors = []
    tag_guard = re.compile(r"startsWith\s*\(\s*github\.ref\s*,\s*['\"]refs/tags/v['\"]\s*\)")
    dry_run_guard = re.compile(r'!\s*inputs\.dry_run\b')
    publish_commands = re.compile(
        r'\bsonaUpload\b|\b(?:mvn|mvnw)\b[^\n]*\bdeploy\b|'
        r'\b(?:gradle|gradlew)\b[^\n]*\bpublishPlugins\b|'
        r'\bmill\b[^\n]*\bpublishAll\b|\bgh\s+release\s+create\b'
    )

    def guarded(condition):
        condition = str(condition or '')
        return bool(tag_guard.search(condition) and dry_run_guard.search(condition))

    def strings(value):
        if isinstance(value, dict):
            for key, item in value.items():
                yield from strings(key)
                yield from strings(item)
        elif isinstance(value, list):
            for item in value:
                yield from strings(item)
        elif isinstance(value, str):
            yield value

    def references_secrets(value):
        return any(re.search(r'\bsecrets\s*(?:\.|\[)', s) for s in strings(value))

    for key, value in workflow.items():
        if key != 'jobs' and references_secrets(value):
            errors.append(f'{key}: secrets must belong to a job with environment: release')
    for name, source in workflow['jobs'].items():
        environment = source.get('environment')
        if isinstance(environment, dict):
            environment = environment.get('name')
        if name in ('central', 'release') and environment != 'release':
            errors.append(f'{name}: requires environment: release')
        if references_secrets(source) and environment != 'release':
            errors.append(f'{name}: secrets require environment: release')
        if name == 'release' and not guarded(source.get('if')):
            errors.append('release: job if must require a version tag and !inputs.dry_run')
        for index, item in enumerate(source.get('steps', [])):
            # publishSigned stages locally; sonaUpload sends that staging repository remotely.
            # publishM2, publishLocal, publishToMavenLocal and artifact builds are also dry-run work.
            if publish_commands.search(item.get('run', '')):
                # A job condition gates all its steps, including `gh release create`.
                if not (guarded(source.get('if')) or guarded(item.get('if'))):
                    label = item.get('name', f'step {index + 1}')
                    errors.append(f'{name}/{label}: publishing if must require a version tag and !inputs.dry_run')
    return errors


class ReleaseGuardMutationTest(unittest.TestCase):
    def workflow(self):
        return deepcopy(yaml.safe_load(WORKFLOW))

    def publishing_step(self, workflow, title):
        return next(s for s in workflow['jobs']['central']['steps'] if s.get('name') == title)

    def test_workflow_keeps_release_guards(self):
        self.assertEqual(release_guard_errors(self.workflow()), [])

    def test_upload_steps_reject_missing_dry_run_or_tag_guard(self):
        for title in ('Upload, sbt', 'Upload, Maven', 'Upload, Gradle', 'Upload, Mill'):
            for guard in ("startsWith(github.ref, 'refs/tags/v')", '!inputs.dry_run'):
                with self.subTest(step=title, removed=guard):
                    workflow = self.workflow()
                    source = self.publishing_step(workflow, title)
                    source['if'] = source['if'].replace(guard + ' && ', '')
                    self.assertTrue(release_guard_errors(workflow), f'{title} lost {guard}')

    def test_release_job_rejects_missing_dry_run_or_tag_guard(self):
        for guard in ("startsWith(github.ref, 'refs/tags/v')", '!inputs.dry_run'):
            with self.subTest(removed=guard):
                workflow = self.workflow()
                source = workflow['jobs']['release']
                source['if'] = source['if'].replace(guard + ' && ', '')
                self.assertTrue(release_guard_errors(workflow), f'release job lost {guard}')

    def test_release_jobs_require_the_release_environment(self):
        for name in ('central', 'release'):
            for environment in (None, 'other', {'name': 'other'}):
                with self.subTest(job=name, environment=environment):
                    workflow = self.workflow()
                    source = workflow['jobs'][name]
                    if environment is None:
                        source.pop('environment', None)
                    else:
                        source['environment'] = environment
                    self.assertTrue(release_guard_errors(workflow), f'{name} lost release environment')

    def test_environment_mapping_is_supported(self):
        workflow = self.workflow()
        for name in ('central', 'release'):
            workflow['jobs'][name]['environment'] = {'name': 'release'}
        self.assertEqual(release_guard_errors(workflow), [])

    def test_secrets_require_a_release_environment_in_any_job(self):
        for reference in ('${{ secrets.NEW_TOKEN }}', "${{ secrets['NEW_TOKEN'] }}"):
            for location in ('env', 'steps'):
                with self.subTest(reference=reference, location=location):
                    workflow = self.workflow()
                    source = workflow['jobs']['version']
                    if location == 'env':
                        source['env'] = {'TOKEN': reference}
                    else:
                        source['steps'].append({'run': 'echo fixture', 'env': {'TOKEN': reference}})
                    self.assertTrue(release_guard_errors(workflow), 'secret outside release environment')

    def test_new_publish_steps_are_checked_without_names(self):
        for command in ('sbt sonaUpload', './mvnw -Prelease deploy',
                        './gradlew publishPlugins',
                        './mill mill.javalib.SonatypeCentralPublishModule/publishAll',
                        'gh release create v1.2.3',
                        'npm publish cli/.js/target/npm-package',
                        'pnpm publish cli/.js/target/npm-package',
                        'yarn npm publish cli/.js/target/npm-package',
                        'twine upload dist/*',
                        'uv publish --token fixture',
                        'gh release upload v1.2.3 hocon-fmt-linux-x86_64'):
            for condition in (None, '!inputs.dry_run', "startsWith(github.ref, 'refs/tags/v')"):
                with self.subTest(command=command, condition=condition):
                    workflow = self.workflow()
                    source = {'run': command}
                    if condition is not None:
                        source['if'] = condition
                    workflow['jobs']['central']['steps'].append(source)
                    self.assertTrue(release_guard_errors(workflow), f'unguarded new step: {command}')
                    source['if'] = UPLOAD_CONDITION
                    self.assertEqual(release_guard_errors(workflow), [])

    def test_registry_publishers_are_pinned_in_unguarded_jobs(self):
        # A job with no secrets and an unguarded if must still pin a bare registry publisher.
        for command in ('npm publish', 'pnpm publish', 'yarn npm publish',
                        'twine upload dist/*', 'uv publish', 'gh release upload v1.2.3 out'):
            with self.subTest(command=command):
                workflow = self.workflow()
                workflow['jobs']['web']['steps'].append({'run': command})
                self.assertTrue(release_guard_errors(workflow),
                                f'unguarded registry publisher: {command}')

    def test_new_jobs_and_global_secrets_are_checked(self):
        workflow = self.workflow()
        workflow['jobs']['new-channel'] = {'steps': [{'run': 'gh release create v1.2.3'}]}
        self.assertTrue(release_guard_errors(workflow))
        workflow['jobs']['new-channel']['if'] = UPLOAD_CONDITION
        self.assertEqual(release_guard_errors(workflow), [])
        workflow['jobs']['new-channel']['env'] = {'TOKEN': '${{ secrets.TOKEN }}'}
        self.assertTrue(release_guard_errors(workflow))
        workflow['jobs']['new-channel']['environment'] = 'release'
        self.assertEqual(release_guard_errors(workflow), [])
        workflow['env'] = {'TOKEN': '${{ secrets.TOKEN }}'}
        self.assertTrue(release_guard_errors(workflow))

    def test_local_staging_and_job_guarded_release_are_allowed(self):
        workflow = self.workflow()
        workflow['jobs']['central']['steps'].append({'run': 'sbt coreJVM/publishSigned'})
        workflow['jobs']['release']['steps'].append({'run': 'gh release create v1.2.3'})
        self.assertEqual(release_guard_errors(workflow), [])


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
                            ('central', 'Preflight')):
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

    targets = ('core', 'sbt-plugin', 'maven-plugin', 'gradle-plugin',
               'mill-plugin', 'native', 'npm', 'pypi')
    default_targets = 'core,sbt-plugin,maven-plugin,gradle-plugin'
    credentials = ('SONATYPE_USERNAME', 'SONATYPE_PASSWORD', 'PGP_SECRET',
                   'PGP_PASSPHRASE', 'GRADLE_PUBLISH_KEY', 'GRADLE_PUBLISH_SECRET', 'GH_TOKEN')

    def selection(self, event='push', variable='', selected=()):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            output, summary = root / 'output', root / 'summary'
            output.touch()
            summary.touch()
            env = {f'INPUT_{t.upper().replace("-", "_")}': str(t in selected).lower()
                   for t in self.targets}
            result = run_shell(step('version', 'Select targets'), root, **env,
                               GITHUB_EVENT_NAME=event, RELEASE_TARGETS=variable,
                               GITHUB_OUTPUT=str(output), GITHUB_STEP_SUMMARY=str(summary))
            return result, output.read_text(), summary.read_text()

    def test_tag_selection_default_and_subsets(self):
        for variable, expected in (('', self.default_targets), ('native', 'native'),
                                   (' maven-plugin,core,core ', 'core,maven-plugin'),
                                   ('npm,pypi', 'npm,pypi')):
            with self.subTest(variable=variable):
                result, output, summary = self.selection(variable=variable)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn(f'selected={expected}\n', output)
                self.assertIn(expected, summary)

    def test_dispatch_selection_ignores_repository_variable(self):
        for selected in (('core',), ('native', 'gradle-plugin'), tuple(self.default_targets.split(','))):
            with self.subTest(selected=selected):
                result, output, _ = self.selection('workflow_dispatch', 'unknown', selected)
                self.assertEqual(result.returncode, 0, result.stderr)
                expected = ','.join(t for t in self.targets if t in selected)
                self.assertIn(f'selected={expected}\n', output)

    def test_selection_unknown_names_fail(self):
        for variable in ('banana', 'core,banana', 'CORE', 'core;echo unsafe'):
            with self.subTest(variable=variable):
                result, output, _ = self.selection(variable=variable)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('Unknown release target', result.stdout)
                self.assertNotIn('selected=', output)

    def test_selection_empty_fails(self):
        for event, variable in (('push', ' '), ('push', ','), ('push', 'core,'),
                                ('workflow_dispatch', '')):
            with self.subTest(event=event, variable=variable):
                result, output, _ = self.selection(event, variable)
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn('selected=', output)

    def test_selection_wiring_and_defaults(self):
        source = step('version', 'Select targets')
        self.assertIn('RELEASE_TARGETS: ${{ vars.RELEASE_TARGETS }}', source)
        self.assertIn('selected: ${{ steps.targets.outputs.selected }}', job('version'))
        self.assertNotIn('secrets.', job('version'))
        self.assertLess(steps('version').index(source), 1)
        for target in self.targets:
            default = 'true' if target in self.default_targets.split(',') else 'false'
            self.assertRegex(WORKFLOW, rf'      {target}:\n        description: [^\n]+\n'
                                      rf'        type: boolean\n        default: {default}')
            expression = f'inputs.{target}' if '-' not in target else f"inputs['{target}']"
            name = target.upper().replace('-', '_')
            self.assertIn(f'INPUT_{name}: ${{{{ {expression} }}}}', source)
        self.assertRegex(WORKFLOW, r'dry_run:\n(?:.*\n)*?        default: true')

    def preflight(self, selected, upload='true', missing=(), central=False):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            summary, marker, calls = root / 'summary', root / 'uploads', root / 'curl-calls'
            summary.touch()
            stub = root / 'curl'
            stub.write_text('#!/bin/bash\necho "$*" >> "$CURL_CALLS"\n'
                            'if [[ "$*" == *"$MISSING_ARTIFACT"* ]] && [ -n "$MISSING_ARTIFACT" ]; then exit 22; fi\n'
                            'exit "$CENTRAL_EXIT"\n')
            stub.chmod(0o755)
            env = {c: '' if c in missing else 'fixture' for c in self.credentials}
            result = run_shell(step('central', 'Preflight'), root, **env,
                               SELECTED_TARGETS=selected, CAN_UPLOAD=upload, VERSION='1.2.3',
                               PATH=f'{root}:{os.environ["PATH"]}', CENTRAL_EXIT='0' if central else '22',
                               CURL_CALLS=str(calls), MISSING_ARTIFACT='hocon-fmt-java-api' if central == 'api-missing' else '',
                               GITHUB_STEP_SUMMARY=str(summary))
            # A failing guard must stop the runner before the first registry upload.
            if result.returncode == 0 and upload == 'true' and 'core' in selected.split(','):
                sbt = root / 'sbt'
                sbt.write_text('#!/bin/bash\necho "$*" >> "$UPLOAD_MARKER"\n')
                sbt.chmod(0o755)
                run = run_shell(step('central', 'Upload, sbt'), root,
                                SELECTED_TARGETS=selected, PATH=f'{root}:{os.environ["PATH"]}',
                                MAVEN_REPO_LOCAL=str(root / 'm2'), UPLOAD_MARKER=str(marker),
                                GITHUB_STEP_SUMMARY=str(summary))
                self.assertEqual(run.returncode, 0, run.stderr)
            print(f'preflight selected={selected} upload={upload} missing={missing}: '
                  f'exit {result.returncode}; {result.stdout.strip()}; upload={marker.exists()}', flush=True)
            return result, summary.read_text(), marker.exists(), calls.read_text() if calls.exists() else ''

    def test_selected_missing_credentials_fail_before_upload(self):
        for target in ('core', 'sbt-plugin', 'maven-plugin', 'gradle-plugin', 'native'):
            required = ('GH_TOKEN',) if target == 'native' else self.credentials[:4]
            if target == 'gradle-plugin':
                required = ('PGP_SECRET', 'PGP_PASSPHRASE', 'GRADLE_PUBLISH_KEY', 'GRADLE_PUBLISH_SECRET')
            for credential in required:
                with self.subTest(target=target, credential=credential):
                    selected = target if target == 'core' else f'core,{target}'
                    result, summary, uploaded, _ = self.preflight(selected, missing=(credential,))
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn(credential, result.stdout)
                    self.assertIn('::error::', result.stdout)
                    self.assertIn(credential, summary)
                    self.assertFalse(uploaded)

    def test_unavailable_targets_fail_before_upload(self):
        for target in ('mill-plugin', 'npm', 'pypi'):
            with self.subTest(target=target):
                result, summary, uploaded, _ = self.preflight(f'core,{target}')
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(target, result.stdout)
                self.assertIn('not enabled', summary)
                self.assertFalse(uploaded)

    def test_not_selected_needs_no_credentials(self):
        for selected, needed in (('native', ('GH_TOKEN',)),
                                  ('core', self.credentials[:4]),
                                  ('gradle-plugin', ('PGP_SECRET', 'PGP_PASSPHRASE',
                                                     'GRADLE_PUBLISH_KEY', 'GRADLE_PUBLISH_SECRET'))):
            with self.subTest(selected=selected):
                result, _, _, _ = self.preflight(selected,
                                                missing=tuple(c for c in self.credentials if c not in needed),
                                                central=True)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_plugins_require_core_or_visible_central_artifacts(self):
        for target in ('sbt-plugin', 'maven-plugin', 'gradle-plugin'):
            with self.subTest(target=target):
                result, _, uploaded, calls = self.preflight(target)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('select core', result.stdout)
                self.assertIn('1.2.3', result.stdout)
                self.assertFalse(uploaded)
                self.assertIn('https://repo.maven.apache.org/maven2/eu/ww86/', calls)
                result, _, _, calls = self.preflight(target, central=True)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn('hocon-fmt-core_3/1.2.3/hocon-fmt-core_3-1.2.3.pom', calls)
                self.assertIn('hocon-fmt-java-api/1.2.3/hocon-fmt-java-api-1.2.3.pom', calls)
                result, _, _, calls = self.preflight(f'core,{target}')
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertEqual(calls, '')

    def test_plugins_reject_missing_java_api_even_when_core_visible(self):
        result, _, uploaded, calls = self.preflight('maven-plugin', central='api-missing')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('hocon-fmt-java-api 1.2.3', result.stdout)
        self.assertIn('hocon-fmt-core_3', calls)
        self.assertIn('hocon-fmt-java-api', calls)
        self.assertFalse(uploaded)

    def test_dry_run_reports_dependency_and_needs_no_upload_credentials(self):
        result, summary, uploaded, calls = self.preflight('gradle-plugin', 'false',
                                                        ('SONATYPE_USERNAME', 'SONATYPE_PASSWORD',
                                                         'GRADLE_PUBLISH_KEY', 'GRADLE_PUBLISH_SECRET'))
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('Dry run:', summary)
        self.assertFalse(uploaded)
        self.assertEqual(calls, '')

    def test_dry_run_still_requires_signing_credentials(self):
        result, _, uploaded, _ = self.preflight('core', 'false', ('PGP_SECRET',))
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(uploaded)

    def test_preflight_wiring_and_order(self):
        preflight = step('central', 'Preflight')
        self.assertIn(f'CAN_UPLOAD: ${{{{ {UPLOAD_CONDITION} }}}}', job('central'))
        self.assertIn('SELECTED_TARGETS: ${{ needs.version.outputs.selected }}', job('central'))
        for credential in self.credentials:
            expression = 'github.token' if credential == 'GH_TOKEN' else f'secrets.{credential}'
            self.assertIn(f'{credential}: ${{{{ {expression} }}}}', preflight)
        ordered = steps('central')
        self.assertLess(ordered.index(preflight), ordered.index(step('central', 'Import the signing key')))
        for title in ('Upload, sbt', 'Upload, Maven', 'Upload, Gradle', 'Upload, Mill'):
            self.assertLess(ordered.index(preflight), ordered.index(step('central', title)))
        for name in ('native', 'npm', 'web'):
            self.assertIn('needs: [version, central]', job(name))
        self.assertIn('needs: [version, native, web, central]', job('release'))

    def test_sbt_upload_selection_shell(self):
        for selected, expected in (('core', ('coreJVM', 'catsJVM', 'cliJVM', 'javaApi')),
                                   ('sbt-plugin', ('sbtPlugin',)),
                                   ('core,sbt-plugin', ('coreJVM', 'catsJVM', 'cliJVM', 'javaApi', 'sbtPlugin'))):
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                stub = root / 'sbt'
                stub.write_text('#!/bin/bash\nprintf "%s\\n" "$@"\n')
                stub.chmod(0o755)
                for title, task in (('Signing check, sbt', 'publishSigned'), ('Upload, sbt', 'publishSigned')):
                    result = run_shell(step('central', title), root, SELECTED_TARGETS=selected,
                                       PATH=f'{root}:{os.environ["PATH"]}', MAVEN_REPO_LOCAL=str(root / 'm2'),
                                       GITHUB_STEP_SUMMARY=str(root / 'summary'))
                    self.assertEqual(result.returncode, 0, result.stderr)
                    actual = re.findall(r'([A-Za-z]+)/(?:publishSigned)', result.stdout)
                    self.assertEqual(actual, list(expected))
                    self.assertEqual('sonaUpload' in result.stdout, title == 'Upload, sbt')

    def test_target_conditions_use_one_selection_output(self):
        self.assertEqual(WORKFLOW.count('vars.RELEASE_TARGETS'), 1)
        for name in ('native', 'npm', 'web', 'release'):
            self.assertIn('needs.version.outputs.selected', job(name))
        for title, target in (('Signing check, Maven', 'maven-plugin'),
                              ('Build and sign, Gradle', 'gradle-plugin'),
                              ('Upload, Maven', 'maven-plugin'), ('Upload, Gradle', 'gradle-plugin')):
            source = step('central', title)
            self.assertIn('needs.version.outputs.selected', source)
            self.assertIn(f"'{target}'", source)
        self.assertIn('if: false &&', step('central', 'Upload, Mill'))

    def test_summary_reports_selection_skips_dry_runs_and_uploads(self):
        result, summary, _, _ = self.preflight('core', 'false')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('core | dry run', summary)
        for target in self.targets[1:]:
            self.assertIn(f'{target} | skipped (not selected)', summary)
        for title in ('Upload, sbt', 'Upload, Maven', 'Upload, Gradle'):
            self.assertIn('uploaded', shell(step('central', title)))
        self.assertIn('native | uploaded', shell(step('release', 'Attach to the release')))

    def test_obsolete_wave_variable_is_removed_everywhere(self):
        root = Path(__file__).resolve().parent.parent
        files = subprocess.check_output(['git', '-C', str(root), 'ls-files'], text=True).splitlines()
        obsolete = 'RELEASE_' + 'WAVE'
        matches = [name for name in files if obsolete.encode() in (root / name).read_bytes()]
        self.assertEqual(matches, [])

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
