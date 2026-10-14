#!/usr/bin/env python3
"""UPSTREAM-SCONFIG: pin the page's local build to the reviewed fork tag."""
from pathlib import Path
import re
import subprocess
import unittest

ROOT = Path(__file__).resolve().parent.parent
TAG = 'fork-main-727eb3074a'
REPOSITORY = 'https://github.com/kastoestoramadus/sHOCON.git'


class SconfigForkPinTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        refs = subprocess.check_output(
            ['git', 'ls-remote', REPOSITORY, f'refs/tags/{TAG}', f'refs/tags/{TAG}^{{}}'],
            text=True, timeout=60,
        ).splitlines()
        resolved = dict(line.split() for line in refs)
        cls.sha = next((sha for sha, ref in resolved.items() if ref.endswith('^{}')),
                       next(iter(resolved), ''))
        if not re.fullmatch(r'[0-9a-f]{40}', cls.sha):
            raise AssertionError(f'{TAG} did not resolve to a full SHA: {refs}')
        cls.script = (ROOT / 'scripts/fetch-sconfig-fork.sh').read_text()
        cls.build = (ROOT / 'build.sbt').read_text()
        cls.docs = (ROOT / 'docs/site.md').read_text()

    def test_script_pins_reviewed_tag(self):
        pins = re.findall(r'^SHA=([0-9a-f]{40})$', self.script, re.M)
        self.assertEqual(pins, [self.sha])
        self.assertIn('SHORT=${SHA:0:10}', self.script)
        self.assertIn('VERSION="2.1.0-hocon-fmt-$SHORT"', self.script)
        self.assertIn('++3.9.0!', self.script)

    def test_build_version_matches_reviewed_tag(self):
        versions = re.findall(r'^val sconfigFork = "([^"]+)"$', self.build, re.M)
        self.assertEqual(versions, [f'2.1.0-hocon-fmt-{self.sha[:10]}'])
        self.assertRegex(self.build, r'(?m)^val sconfig = "2\.0\.0"',)

    def test_documented_sha_matches_reviewed_tag(self):
        pins = re.findall(r'Pinned fork commit: `([0-9a-f]{40})`', self.docs)
        self.assertEqual(pins, [self.sha])
        self.assertIn(f'`{TAG}`', self.docs)


if __name__ == '__main__':
    unittest.main(verbosity=2)
