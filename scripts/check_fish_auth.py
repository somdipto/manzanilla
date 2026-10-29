"""Check Fish credentials without creating provider objects or printing secrets.

The response confirms only that the account may list agent sessions; it does not
prove agent creation, speech entitlement, billing, or audio quality.
"""
import argparse
import http.client
import os
import secrets
import sys


def check(key, connection_factory=http.client.HTTPSConnection):
    if not key or not key.strip():
        return "missing", None
    connection = None
    try:
        connection = connection_factory("api.fish.audio", timeout=15)
        # A synthetic identifier avoids retrieving existing session data. The
        # body is never read or logged. HTTPSConnection never follows redirects.
        path = "/v1/agent/sessions?end_user_id=ci-" + secrets.token_hex(16) + "&page_size=1"
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
    parser.add_argument("--distribution", required=True, choices=("personal-test", "hotel"))
    distribution = parser.parse_args(argv).distribution
    key = os.environ.get("FISH_API_KEY", "")
    if distribution == "hotel":
        if key.strip():
            print("Hotel construction must not receive a Fish credential.", file=sys.stderr)
            return 1
        print("Hotel construction requires no Fish credential or provider check.")
        return 0
    result, status = check(key)
    if result == "accepted":
        print("Fish authentication check passed (HTTP %d); live voice is not verified." % status)
        return 0
    if result == "missing":
        print("Fish authentication check failed: build secret is not configured.", file=sys.stderr)
    elif result == "rejected":
        print("Fish authentication check failed: access denied (HTTP %d)." % status, file=sys.stderr)
    elif status is not None:
        print("Fish authentication check inconclusive (HTTP %d)." % status, file=sys.stderr)
    else:
        print("Fish authentication check inconclusive: connection failed or timed out.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
