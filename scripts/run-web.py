#!/usr/bin/env python3
"""Run the private loopback translator and its owned LiveKit process."""
import argparse
from pathlib import Path
import os
import shutil
import socket
import subprocess
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from cloud.web import create_app, default_state_dir
import uvicorn


def start_livekit(runtime, binary):
    owned = runtime.owned_transport
    # Never attach to a process that happens to occupy our saved port. Persisted
    # keys/ports identify cleanup authority; port reuse is not ownership proof.
    for port, kind in ((owned['port'], socket.SOCK_STREAM),
                       (owned['tcp_port'], socket.SOCK_STREAM),
                       (owned['udp_port'], socket.SOCK_DGRAM)):
        with socket.socket(socket.AF_INET, kind) as probe:
            probe.bind(('127.0.0.1', port))
    configuration = {
        'port': owned['port'], 'bind_addresses': ['127.0.0.1'],
        'keys': {owned['key']: owned['secret']},
        'rtc': {'node_ip': '127.0.0.1', 'use_external_ip': False,
                'tcp_port': owned['tcp_port'], 'udp_port': owned['udp_port'],
                'interfaces': {'includes': ['lo', 'lo0']}},
        'logging': {'level': 'warn'},
    }
    # JSON is also valid YAML. Keep credentials in a private file, never argv.
    runtime.write_private('livekit.json', configuration)
    environment = {name: value for name, value in os.environ.items()
                   if not name.startswith(('LIVEKIT_', 'FISH_', 'GEMINI_', 'OPENAI_'))}
    process = subprocess.Popen([binary, '--config', str(runtime.directory / 'livekit.json')],
                               stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL, env=environment)
    try:
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError('LiveKit exited during startup')
            try:
                with socket.create_connection(('127.0.0.1', owned['port']), timeout=.2):
                    return process
            except OSError:
                time.sleep(.1)
        raise RuntimeError('LiveKit startup timed out')
    except BaseException:
        stop_livekit(process)
        raise


def stop_livekit(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


def main():
    parser = argparse.ArgumentParser(description='Run Manzanilla browser translation on loopback only.')
    parser.add_argument('--port', type=int, default=4173)
    parser.add_argument('--livekit-server', help='Path to the actual livekit-server binary (otherwise PATH).')
    parser.add_argument('--state-dir', type=Path, default=default_state_dir(),
                        help='Private local state directory; never use the production database directory.')
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error('--port must be between 1 and 65535')
    binary = shutil.which(args.livekit_server or 'livekit-server')
    if not binary:
        parser.exit(1, 'LiveKit server is required. Install livekit-server (macOS: brew install livekit), or pass --livekit-server /absolute/path/to/livekit-server. Docker is not required.\n')
    app = None
    process = None
    try:
        app = create_app(state_dir=args.state_dir)
        if args.port in [app.state.web.owned_transport[name] for name in ('port', 'tcp_port', 'udp_port')]:
            raise ValueError('HTTP and transport ports must differ')
        process = start_livekit(app.state.web, binary)
        print(f'Open http://127.0.0.1:{args.port} — provider access is not checked; no provider session starts until Start and microphone publication.', flush=True)
        uvicorn.run(app, host='127.0.0.1', port=args.port, workers=1, proxy_headers=False,
                    access_log=False, server_header=False)
    except (OSError, ValueError, RuntimeError):
        parser.exit(1, 'Cannot start private translator. Check state-directory ownership, installed LiveKit binary and availability of saved ports (defaults 17880–17882). Stop any previous translator process; do not rotate or delete transport.json to bypass pending cleanup.\n')
    finally:
        if process is not None:
            stop_livekit(process)
        if app is not None and app.state.web.store is not None:
            app.state.web.close()


if __name__ == '__main__':
    main()
