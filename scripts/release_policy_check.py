"""PR-time release-policy gate (branch-protection check `release-policy`).

Encodes the standing publication rules from .agents/skills/publish-reception
and docs/ENVIRONMENT.md as checks that run WITHOUT signing or publishing:

1. android/VERSION is stable major.minor.patch SemVer, VERSION_CODE numeric.
2. A version-code bump moves VERSION with it, strictly upward (no silent or
   sideways bumps).
3. The Fish credential is only ever compiled from CI secrets: the Gradle
   config reads it from the environment, and no credential-shaped literal
   enters the tree.
4. The package identity stays dev.orange.hotel.

Exit 1 blocks the merge; every failure names its rule.
"""
from __future__ import annotations

import os
import re
import subprocess
import sys

ROOT = subprocess.run(['git', 'rev-parse', '--show-toplevel'],
                      check=True, text=True, capture_output=True).stdout.strip()
sys.path.insert(0, os.path.join(ROOT, 'android'))
from release_identity import semver  # noqa: E402

failures = []


def check(rule, ok, detail=''):
    if not ok:
        failures.append(f'{rule}: {detail}')


def read(path):
    with open(os.path.join(ROOT, path), encoding='utf-8') as handle:
        return handle.read().strip()


# Rule 1: explicit stable publication identity.
version = read('android/VERSION')
code = read('android/VERSION_CODE')
try:
    semver(version)
except ValueError as exc:
    check('stable-semver', False, str(exc))
check('version-code-numeric', code.isdigit(), f'VERSION_CODE={code!r} is not numeric')

# Rule 2: a code bump is explicit and strictly upward, name and code together.
base = os.environ.get('POLICY_BASE') or 'HEAD~1'
try:
    subprocess.run(['git', 'rev-parse', '--verify', base], check=True,
                   cwd=ROOT, capture_output=True)
    diff = subprocess.run(['git', 'diff', '--name-only', base, 'HEAD'],
                          check=True, text=True, cwd=ROOT, capture_output=True).stdout.split()
except subprocess.CalledProcessError:
    diff = []
if 'android/VERSION_CODE' in diff:
    check('version-bump-together', 'android/VERSION' in diff,
          'VERSION_CODE moved without VERSION')
    old_code = subprocess.run(['git', 'show', f'{base}:android/VERSION_CODE'],
                              check=True, text=True, cwd=ROOT, capture_output=True).stdout.strip()
    if code.isdigit() and old_code.isdigit():
        check('version-code-upward', int(code) > int(old_code),
              f'{old_code} -> {code} is not an increase')

# Rule 3: Fish credential only ever from CI environment, never literals.
gradle = read('android/hotel/build.gradle.kts')
check('fish-from-env',
      'FISH_API_KEY' in gradle and 'FISH_VOICE_ID' in gradle,
      'Gradle no longer compiles the Fish credential from CI secrets')
scan = subprocess.run(['git', 'grep', '-nIE',
                       r'(nvapi-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{35}|eyJ[A-Za-z0-9_-]{20,}\.|BEGIN [A-Z ]*PRIVATE KEY)',
                       '--', ':!tests' ':!docs' ':!.git'],
                      text=True, cwd=ROOT, capture_output=True).stdout.strip()
check('no-credential-literals', not scan,
      'credential-shaped literal in tree:\n' + scan[:500])

# Rule 4: package identity.
check('package-identity', 'dev.orange.hotel' in gradle,
      'application id changed from dev.orange.hotel')

if failures:
    print('release-policy FAIL')
    for failure in failures:
        print(' -', failure)
    raise SystemExit(1)
print(f'release-policy PASS: {version} / {code}, Fish from CI env, identity dev.orange.hotel')
