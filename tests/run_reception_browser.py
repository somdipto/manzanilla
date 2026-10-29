"""Run shipped reception UI fixtures and an isolated admin/API property in Chromium.

Install requirements-browser.txt and `python -m playwright install chromium`,
then run `python tests/run_reception_browser.py`. A system browser can be selected
with --chromium /usr/bin/chromium. These are browser checks, not Android/audio tests.
The admin case starts a temporary backend with blank provider credentials; all
other cases use isolated bridge fixtures. No deployed hotel or provider is called.
"""
import argparse
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
from threading import Thread
import socket
import subprocess
import sys
import tempfile
import time
from urllib.parse import urlsplit

from playwright.sync_api import sync_playwright
import httpx
from reception_startup_fixture import run_localized_fixture, run_startup_fixture


ROOT = Path(__file__).resolve().parent.parent
FIXTURES = (
    ("reception_caption_fixture.js", "runReceptionCaptionFixture"),
    ("reception_operations_fixture.js", "runReceptionOperationsFixture"),
    ("reception_appearance_fixture.js", "runReceptionAppearanceFixture"),
)


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, *_args):
        pass


def run_approved_content_fixture(browser):
    """Real isolated API/admin browser interaction; no model or tablet proof."""
    with tempfile.TemporaryDirectory(prefix="manzanilla-approved-browser-") as temporary:
        environment={**os.environ,"ORANGE_DB":str(Path(temporary)/"hotel.db"),
                     "ORANGE_ADMIN_TOKEN":"isolated-browser-admin-"+"x"*40,
                     "GEMINI_API_KEY":"","FISH_API_KEY":""}
        listener=socket.socket()
        listener.bind(("127.0.0.1",0)); listener.listen(128)
        origin=f"http://127.0.0.1:{listener.getsockname()[1]}"
        with (Path(temporary)/"backend.log").open("w+") as log:
            process=subprocess.Popen(
                [sys.executable,"-m","uvicorn","cloud.app:app","--fd",str(listener.fileno()),
                 "--log-level","error"],cwd=ROOT,env=environment,
                pass_fds=(listener.fileno(),),stdout=log,stderr=log)
            listener.close()
            context=None
            try:
                with httpx.Client(base_url=origin,timeout=2,trust_env=False) as api:
                    deadline=time.monotonic()+20
                    while True:
                        try:
                            if api.get("/health").status_code==200: break
                        except httpx.TransportError:
                            pass
                        if process.poll() is not None or time.monotonic()>deadline:
                            raise AssertionError("Isolated approved-content backend did not start")
                        time.sleep(.05)
                    admin={"Authorization":"Bearer "+environment["ORANGE_ADMIN_TOKEN"]}
                    original="  07:00–10:00 <b>exact source, not HTML</b>\n  "
                    hotel={"id":"browser-property","name":"Isolated review property",
                           "default_language":"es","supported_languages":["es","en","ar"],
                           "knowledge":{"breakfast":original},"checkin_instructions":"Ask reception."}
                    saved=api.put("/api/hotels/browser-property",headers=admin,json=hotel)
                    assert saved.status_code==200
                    device=api.post("/api/hotels/browser-property/credentials",
                                    headers=admin,json={"role":"device"}).json()
                    device_headers={"Authorization":"Bearer "+device["token"]}
                    sid="approved-browser"
                    assert api.post("/api/device/operations/start",headers=device_headers,json={
                        "session_id":sid,"language":"es","staff_language":"en","mode":"reception"}).status_code==200
                    def question(call_id):
                        response=api.post("/api/device/operations/"+sid+"/action",
                            headers=device_headers,json={"action":"question","call_id":call_id,
                                                        "arguments":{"query":"breakfast?"}})
                        assert response.status_code==200
                        return response.json()
                    absent=question("before-review")
                    assert absent["result"]["matches"]=={} and absent["result"]["help_action"]=="operator"
                    context=browser.new_context(service_workers="block")
                    errors=[]
                    def intercept(route):
                        if route.request.url.startswith(origin+"/"): route.continue_()
                        else:
                            errors.append("Blocked non-fixture browser request")
                            route.abort()
                    context.route("**/*",intercept)
                    page=context.new_page()
                    page.on("pageerror",lambda error:errors.append(str(error)))
                    page.goto(origin+"/admin")
                    page.locator("#token").fill(environment["ORANGE_ADMIN_TOKEN"])
                    page.locator("#hotel").fill("browser-property")
                    page.locator("#load").click()
                    page.locator("#content-source option",has_text="breakfast").wait_for(state="attached")
                    page.locator("#content-source").select_option(label="breakfast")
                    page.locator("#content-language").select_option("es")
                    assert page.locator("#content-original").text_content()==original
                    assert page.locator("#content-original b").count()==0
                    page.locator("#content-title").fill("Desayuno")
                    approved_body="  De 7 a 10 h. Texto revisado.\n  "
                    page.locator("#content-body").fill(approved_body)
                    with page.expect_response(lambda response:response.url.endswith("/content/draft")) as drafted:
                        page.locator("#content-draft").click()
                    assert drafted.value.status==503
                    page.wait_for_function("""() => document.querySelector('#content-status').textContent ===
                        MANZANILLA_UI.messages['Translation drafting is unavailable. You can edit and approve the text manually.'].es""")
                    assert page.locator("#content-original").text_content()==original
                    assert page.locator("#content-title").input_value()=="Desayuno"
                    assert page.locator("#content-body").input_value()==approved_body
                    assert question("after-failed-draft")["result"]["matches"]=={}
                    with page.expect_response(lambda response:response.url.endswith("/content/approve")) as approved:
                        page.locator("#content-approve").click()
                    assert approved.value.status==200
                    page.wait_for_function("() => !document.querySelector('#content-approve').disabled")
                    answer=question("after-approval")
                    assert answer["result"]["matches"]=={"Desayuno":approved_body}
                    assert answer["result"]["fact_refs"][0]["language"]=="es"
                    assert approved_body in answer["text"]
                    # Mutating the real property behind the form makes its reviewed
                    # hash stale; approval must conflict instead of replacing it.
                    hotel["knowledge"]["breakfast"]="08:00–11:00 changed source"
                    assert api.put("/api/hotels/browser-property",headers=admin,json=hotel).status_code==200
                    page.locator("#content-body").fill("An obsolete review must not be saved.")
                    with page.expect_response(lambda response:response.url.endswith("/content/approve")) as conflict:
                        page.locator("#content-approve").click()
                    assert conflict.value.status==409
                    page.wait_for_function("""() => document.querySelector('#content-original').textContent ===
                        '08:00–11:00 changed source'""")
                    assert page.locator("#content-title").input_value()==""
                    assert page.locator("#content-body").input_value()==""
                    page.wait_for_function("""() => document.querySelector('#content-status').textContent ===
                        MANZANILLA_UI.messages['Hotel content changed. Review the current source before approving.'].es""")
                    stale=question("after-approval")
                    assert stale["result"]["matches"]=={} and stale["result"]["help_action"]=="operator"
                    assert approved_body not in stale["text"]
                    overview=api.get("/api/hotels/browser-property/overview",headers=admin).json()
                    assert not overview["items"], "FAQ/help guidance must not create staff requests automatically"
                    for code in page.evaluate("MANZANILLA_UI.languages.map(language => language.code)"):
                        page.locator("#staff-locale").select_option(code)
                        assert page.locator("#content-review h2").text_content()==page.evaluate(
                            "code => MANZANILLA_UI.messages['Approved hotel answers'][code]",code)
                    assert not errors, errors
                    return {"fixture":"approved-content-admin","passed":True,"locales":52,
                            "scope":"real isolated HTTP backend and admin browser; no provider, native, tablet or human translation qualification"}
            finally:
                if context: context.close()
                process.terminate()
                try: process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill(); process.wait(timeout=5)


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
            for width, height in [(837, 471), (1024, 600)]:
                for appearance in ["dark", "light"]:
                    results.append(run_localized_fixture(
                        browser, width, height, appearance,
                        all_locales=(width == 837 and appearance == "dark")))
            results.append(run_approved_content_fixture(browser))
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
        return {"browser": version, "fixtures": results, "scope": "isolated browser and temporary backend; no provider, native, audio or human language qualification"}
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=5)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--chromium", help="Optional installed Chromium executable")
    options = parser.parse_args()
    print(json.dumps(run(options.chromium), indent=2))
