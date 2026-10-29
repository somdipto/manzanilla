"""One-off synthetic provider contract diagnostic. No user speech or real agents.

Creates tools under fresh random names only. Any successful creation is fetched,
matched against its unique name, and deleted; no existing tool is modified.
"""
import copy
import http.client
import json
import os
from pathlib import Path
import re
import secrets
import sys


def request(method, path, key, body=None):
    connection = http.client.HTTPSConnection("api.fish.audio", timeout=20)
    try:
        headers = {"Authorization": "Bearer " + key}
        if body is not None:
            headers["Content-Type"] = "application/json"
        connection.request(method, path, json.dumps(body) if body is not None else None, headers)
        response = connection.getresponse()
        raw = response.read(65536)
        try:
            payload = json.loads(raw)
        except (ValueError, UnicodeDecodeError):
            payload = None
        return response.status, payload
    finally:
        connection.close()


def sanitized_validation(payload):
    # Never print provider input, arbitrary bodies, or exception text.
    allowed = {"body", "name", "tool_type", "description", "arguments", "expects_response",
               "execution_mode", "url", "method", "value_error", "type_error", "string", "type", "missing"}
    entries = payload if isinstance(payload, list) else payload.get("detail", []) if isinstance(payload, dict) else []
    if not isinstance(entries, list):
        entries = []
    for item in entries[:20]:
        if not isinstance(item, dict):
            continue
        loc = item.get("loc", [])
        safe_loc = [str(part) if isinstance(part, int) or part in allowed else "other" for part in loc] if isinstance(loc, list) else []
        kind = item.get("type")
        kind = kind if isinstance(kind, str) and re.fullmatch(r"[a-z_]{1,40}", kind) else "other"
        print("validation location=" + ".".join(safe_loc) + " type=" + kind)
    if isinstance(payload, dict):
        print("response_fields=" + ",".join(sorted(k for k in payload if k in {"status", "message", "reason", "detail"})))
    # Classify fixed vocabulary only; never emit the server's text.
    messages = []
    if isinstance(payload, dict):
        messages.extend(payload.get(k, "") for k in ("message", "reason", "detail"))
    messages.extend(item.get("msg", "") for item in entries if isinstance(item, dict))
    text = " ".join(value.lower() for value in messages if isinstance(value, str))
    for term in ("execution_mode", "client", "webhook", "fire_and_forget", "duplicate", "name", "arguments", "description", "url", "method", "required"):
        if term in text:
            print("validation mentions " + term)


def probe(label, definition, key):
    data = copy.deepcopy(definition)
    name = "qa_caption_" + secrets.token_hex(12)
    data["name"] = name
    if label == "without_execution_mode":
        data.pop("execution_mode", None)
    status, payload = request("POST", "/v1/agent/tools", key, data)
    print(label + " create HTTP " + str(status), flush=True)
    if status == 422:
        sanitized_validation(payload)
        return
    if not 200 <= status < 300 or not isinstance(payload, dict):
        raise RuntimeError("synthetic create did not return a usable response")
    ident = payload.get("tool_id")
    if not isinstance(ident, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", ident):
        print("cleanup requires manual investigation of unique synthetic name " + name)
        raise RuntimeError("synthetic tool ID unavailable")
    path = "/v1/agent/tools/" + ident
    status, tool = request("GET", path, key)
    if status != 200 or not isinstance(tool, dict) or tool.get("name") != name:
        print("cleanup requires manual investigation of unique synthetic name " + name)
        raise RuntimeError("synthetic identity could not be verified")
    status, _ = request("DELETE", path, key)
    print(label + " cleanup HTTP " + str(status), flush=True)
    if status not in (200, 202, 204):
        print("cleanup requires manual investigation of unique synthetic name " + name)
        raise RuntimeError("synthetic cleanup failed")


def main():
    key = os.environ.get("FISH_API_KEY", "").strip()
    if not key:
        print("Fish secret unavailable", file=sys.stderr)
        return 1
    definition = json.loads(Path("android/hotel/src/main/assets/fish-caption-tool.json").read_text())
    try:
        probe("original", definition, key)
        probe("without_execution_mode", definition, key)
    except Exception:
        # Do not emit errors: provider/transport exceptions can contain secrets.
        print("Synthetic probe could not complete safely", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
