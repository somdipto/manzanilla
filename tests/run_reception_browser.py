"""Run shipped reception UI fixtures in isolated Chromium, without hotel/provider calls.

Install requirements-browser.txt and `python -m playwright install chromium`,
then run `python tests/run_reception_browser.py`. A system browser can be selected
with --chromium /usr/bin/chromium. These are browser checks, not Android/audio tests.
"""
import argparse
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
from threading import Thread
from urllib.parse import urlsplit

from playwright.sync_api import sync_playwright
from reception_startup_fixture import run_startup_fixture


ROOT = Path(__file__).resolve().parent.parent
FIXTURES = (
    ("reception_caption_fixture.js", "runReceptionCaptionFixture"),
    ("reception_operations_fixture.js", "runReceptionOperationsFixture"),
    ("reception_appearance_fixture.js", "runReceptionAppearanceFixture"),
)


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, *_args):
        pass


def run(chromium=None):
    server = ThreadingHTTPServer(("127.0.0.1", 0), partial(QuietHandler, directory=str(ROOT)))
    worker = Thread(target=server.serve_forever, daemon=True)
    worker.start()
    origin = f"http://127.0.0.1:{server.server_port}"
    results = []
    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(
                executable_path=chromium, headless=True, args=["--no-sandbox"])
            version = browser.version
            # Keep packaged script order, CSP, resource interception, and startup intact.
            results.append(run_startup_fixture(browser))
            results.append(run_startup_fixture(browser, legacy_object_api=True))
            for module, entrypoint in FIXTURES:
                context = browser.new_context(viewport={"width": 1000, "height": 650}, service_workers="block")
                errors = []
                # Fail closed if a fixture accidentally reaches a real service.
                def intercept(route):
                    url = urlsplit(route.request.url)
                    if url.scheme == "http" and url.netloc == f"127.0.0.1:{server.server_port}":
                        route.continue_()
                    else:
                        errors.append(f"Blocked non-fixture request: {url.scheme}://{url.netloc}")
                        route.abort()
                context.route("**/*", intercept)
                page = context.new_page()
                page.on("pageerror", lambda error: errors.append(str(error)))
                page.goto(origin)
                result = page.evaluate("""async ({module, entrypoint}) => {
                    const run = async () => {
                        const fixture = await import('/tests/' + module);
                        const result = await fixture[entrypoint]();
                        return Object.fromEntries(['passed', 'checks', 'assertions', 'replayMs', 'result']
                            .filter(key => result[key] !== undefined).map(key => [key, result[key]]));
                    };
                    return await Promise.race([run(), new Promise((_, reject) =>
                        setTimeout(() => reject(Error('Fixture exceeded 30 seconds')), 30000))]);
                }""", {"module": module, "entrypoint": entrypoint})
                if errors:
                    raise AssertionError(f"{module}: {errors}")
                results.append({"fixture": module, "passed": True, **result})
                context.close()
            browser.close()
        return {"browser": version, "fixtures": results, "scope": "isolated browser; no native or audio qualification"}
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=5)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--chromium", help="Optional installed Chromium executable")
    options = parser.parse_args()
    print(json.dumps(run(options.chromium), indent=2))
