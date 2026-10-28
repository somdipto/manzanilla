"""Check stable package identity and prevent obvious credential literals.

The separate release_policy.py validates version bumps against committed trees.
This guard checks only source material; it does not assert provider entitlement
or whether a key is embedded in a release artifact.
"""
from pathlib import Path
import re
import subprocess
import sys


def main():
    root = Path(subprocess.run(['git', 'rev-parse', '--show-toplevel'], check=True,
                               text=True, capture_output=True).stdout.strip())
    gradle = (root / 'android/hotel/build.gradle.kts').read_text(encoding='utf-8')
    if not re.search(r'applicationId\s*=\s*"dev\.orange\.hotel"', gradle):
        print('release-policy FAIL: package identity changed from dev.orange.hotel', file=sys.stderr)
        return 1
    # Print paths only, never matched values. Exclude deliberate fixtures and docs.
    scan = subprocess.run(['git', 'grep', '-lIE',
        r'(nvapi-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{35}|eyJ[A-Za-z0-9_-]{20,}\.|BEGIN [A-Z ]*PRIVATE KEY)',
        '--', '.', ':!tests', ':!docs'], cwd=root, text=True, capture_output=True)
    if scan.returncode not in (0, 1):
        print('release-policy FAIL: credential scan could not complete', file=sys.stderr)
        return 1
    if scan.returncode == 0:
        print('release-policy FAIL: credential-shaped literal in ' + ', '.join(scan.stdout.splitlines()), file=sys.stderr)
        return 1
    print('release-policy PASS: package identity and credential-literal scan')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
