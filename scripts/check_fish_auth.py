"""Manually check a user's Fish credential without storing or printing it.

Not a build or release prerequisite. Credentials are entered privately, never read
from CI environment variables. Account session-list access does not prove realtime
ASR/TTS entitlement, billing, recognition, translation, or audio quality.
"""
import argparse
import http.client
import getpass
import secrets
import sys
import warnings


def check(key, connection_factory=http.client.HTTPSConnection):
    if not key or not key.strip():
        return "missing", None
    connection = None
    try:
        connection = connection_factory("api.fish.audio", timeout=15)
        # A synthetic identifier avoids retrieving existing session data. The
        # body is never read or logged. HTTPSConnection never follows redirects.
        path = "/v1/agent/sessions?end_user_id=manual-check-" + secrets.token_hex(16) + "&page_size=1"
        connection.request("GET", path, headers={"Authorization": "Bearer " + key.strip()})
        response = connection.getresponse()
        status = response.status
        if 200 <= status < 300:
            return "accepted", status
        if status in (401, 403):
            return "rejected", status
        return "inconclusive", status
    except Exception:
        # Exception text can contain request details; never emit it.
        return "inconclusive", None
    finally:
        if connection is not None:
            try:
                connection.close()
            except Exception:
                pass


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--user-credential", action="store_true", required=True,
                        help="Privately prompt for your own Fish credential; never used to build an APK")
    parser.parse_args(argv)
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", getpass.GetPassWarning)
            key = getpass.getpass("Your Fish API key (not saved): ")
    except (EOFError, KeyboardInterrupt, getpass.GetPassWarning):
        print("Fish authentication check cancelled.", file=sys.stderr)
        return 1
    result, status = check(key)
    if result == "accepted":
        print("Fish authentication check passed (HTTP %d); live voice is not verified." % status)
        return 0
    if result == "missing":
        print("Fish authentication check failed: no user credential supplied.", file=sys.stderr)
    elif result == "rejected":
        print("Fish authentication check failed: access denied (HTTP %d)." % status, file=sys.stderr)
    elif status is not None:
        print("Fish authentication check inconclusive (HTTP %d)." % status, file=sys.stderr)
    else:
        print("Fish authentication check inconclusive: connection failed or timed out.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
