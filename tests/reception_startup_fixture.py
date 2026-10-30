"""Exercise untouched packaged HTML/scripts/CSP with an isolated native transport.

The fake transport only records outbound JSON. It cannot grant microphone access
or contact a provider. Resource allowlisting and the bridge name/CSP are read
from the native implementation so browser fixture conveniences cannot hide an
unloadable production page.
"""
import json
from pathlib import Path
import re
from urllib.parse import urlsplit


ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "android/hotel/src/main/assets/reception"
NATIVE = ROOT / "android/hotel/src/main/java/com/agentdeck/ui"


def packaged_page(browser, viewport=None, legacy_object_api=False):
    native = (NATIVE / "ReceptionView.kt").read_text()
    policy = (NATIVE / "ReceptionAssets.kt").read_text()
    host = re.search(r'const val HOST = "([^"]+)"', policy).group(1)
    paths = set(re.findall(r'"(/[^\"]+)"', policy))
    bridge = re.search(r'}, "(\w+Native)"\)', native).group(1)
    csp = re.search(r'"Content-Security-Policy" to "([^"]+)"', native).group(1)
    context = browser.new_context(viewport=viewport or {"width": 837, "height": 471}, service_workers="block")
    errors, loaded = [], []

    def intercept(route):
        url = urlsplit(route.request.url)
        if (url.scheme != "https" or url.netloc != host or url.path not in paths
                or url.query or route.request.method != "GET"):
            errors.append(f"Blocked request: {url.scheme}://{url.netloc}{url.path}")
            route.abort()
            return
        suffix = Path(url.path).suffix
        mime = {".js": "application/javascript", ".css": "text/css", ".html": "text/html"}[suffix]
        loaded.append(url.path)
        route.fulfill(body=(ASSETS / url.path.lstrip("/")).read_bytes(), headers={
            "Content-Type": mime + "; charset=UTF-8", "Content-Security-Policy": csp,
            "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff",
        })

    context.route("**/*", intercept)
    context.add_init_script("""
        window.fixtureMessages = [];
        window[%s] = {postMessage: raw => window.fixtureMessages.push(JSON.parse(raw))};
        %s
    """ % (json.dumps(bridge), "delete Object.hasOwn; delete Object.fromEntries;" if legacy_object_api else ""))
    page = context.new_page()
    page.on("pageerror", lambda error: errors.append(str(error)))
    page.goto(f"https://{host}/index.html")
    return context, page, errors, loaded, paths


def run_startup_fixture(browser, legacy_object_api=False):
    context, page, errors, loaded, paths = packaged_page(browser, legacy_object_api=legacy_object_api)
    try:
        assert not errors, f"Packaged page failed during startup: {errors}"
        assert set(loaded) == paths, f"Packaged resources not loaded: {paths - set(loaded)}"
        assert page.evaluate("fixtureMessages.some(event => event.action === 'info')"), "Native startup handshake missing"
        assert "/locales.js" in loaded, "The actual packaged page must load its offline catalogue under the shipped CSP"
        assert page.locator('[data-action="translation"]').is_visible(), "Home did not render"

        def receive(event):
            page.evaluate("event => window.manzania.receive(event)", event)

        receive({"type": "reception_info", "audio_available": True, "local_audio_state": "off", "staff_language": "en"})
        receive({"type": "language_preferences", "language": "en", "staff_language": "en"})
        page.locator('[data-action="translation"]').click()
        assert page.locator('[data-action="start"]').is_enabled(), "Same-language sessions must support explicit per-turn direction"
        receive({"type": "reception_info", "audio_available": False, "local_audio_state": "off", "audio_gate": "Configure translation with your Fish Audio API key."})
        assert "Configure translation" in page.locator("#translation-readiness").inner_text()
        assert page.locator("#settings").is_visible(), "Configuration help must remain reachable"
        assert page.locator('.setup-start').count() == 1, "Setup must have one primary action"
        assert page.locator('[data-action="start"]').count() == 0, "Do not render a second disabled Start"
        page.locator('[data-action="setup-translation"]').click()
        assert page.evaluate("fixtureMessages.filter(event => event.action === 'voice_setup').length") == 1
        assert not page.evaluate("fixtureMessages.some(event => event.action === 'start')")
        page.locator('#settings').click()
        assert page.evaluate("fixtureMessages.filter(event => event.action === 'voice_setup').length") == 2
        assert not page.evaluate("fixtureMessages.some(event => event.action === 'settings')"), "Translation settings must not open protected hotel administration"
        receive({"type": "visibility", "visible": False})
        receive({"type": "visibility", "visible": True})
        receive({"type": "translation_setup_return"})
        assert page.locator('#language').input_value() == "en"
        assert page.locator('#staff_language').input_value() == "en"
        assert page.locator('[data-action="setup-translation"]').is_enabled(), "Cancelled setup must return to language selection"
        receive({"type": "reception_info", "audio_available": False, "local_audio_state": "off",
                 "audio_gate": "The saved voice key could not be opened. Open Fish Audio setup to replace it."})
        assert "saved voice key could not be opened" in page.locator("#translation-readiness").inner_text()
        assert page.locator('[data-action="setup-translation"]').is_enabled()
        receive({"type": "visibility", "visible": False})
        receive({"type": "visibility", "visible": True})
        receive({"type": "reception_info", "audio_available": True, "local_audio_state": "off"})
        receive({"type": "translation_setup_return"})
        assert page.locator('[data-action="setup-translation"]').count() == 0
        page.locator('#settings').click()
        assert page.evaluate("fixtureMessages.filter(event => event.action === 'voice_setup').length") == 3
        assert not page.evaluate("fixtureMessages.some(event => event.action === 'settings')")
        page.locator('#language').select_option("es")
        assert page.locator('[data-action="start"]').is_enabled(), "Distinct languages with configured audio cannot start"
        page.locator('[data-action="start"]').click()
        starts = page.evaluate("fixtureMessages.filter(event => event.action === 'start')")
        assert len(starts) == 1 and starts[0]["language"] == "es" and starts[0]["staff_language"] == "en", starts
        assert starts[0]["session_id"] and starts[0]["mode"] == "translation", starts
        assert not errors, f"Packaged page runtime errors: {errors}"
        return {"fixture": "packaged_page_startup", "passed": True,
                "object_has_own_available": not legacy_object_api, "resources": sorted(loaded),
                "scope": "unchanged packaged page; isolated bridge; no microphone or provider"}
    finally:
        context.close()


def run_localized_fixture(browser, width=837, height=471, appearance="dark", all_locales=False):
    """Real packaged localization/rendering; synthetic transport is not backend qualification."""
    context, page, errors, loaded, paths = packaged_page(browser, {"width": width, "height": height})
    try:
        def receive(event):
            page.evaluate("event => window.manzania.receive(event)", event)

        def actions(name):
            return page.evaluate("name => fixtureMessages.filter(event => event.action === name)", name)

        def copy(selector, message, language="es", values=None):
            expected = page.evaluate("({message, language}) => window.MANZANILLA_UI.messages[message][language]",
                                     {"message": message, "language": language})
            for key, value in (values or {}).items():
                expected = expected.replace("{" + key + "}", value)
            node = page.locator(selector)
            assert node.is_visible(), f"Localized control is not visible: {selector}"
            assert expected in node.inner_text(), (language, message, node.inner_text())
            return node.inner_text()

        def fits(selector):
            node = page.locator(selector)
            node.scroll_into_view_if_needed()
            rect = node.bounding_box()
            assert rect and rect["x"] >= -1 and rect["y"] >= -1, (selector, rect)
            assert rect["x"] + rect["width"] <= width + 1, (selector, rect)
            assert rect["y"] + rect["height"] <= height + 1, (selector, rect)

        def hotel_ready():
            receive({"type": "reception_info", "audio_available": True, "local_audio_state": "off",
                     "distribution": "personal-test", "configured": True, "state": "ready",
                     "services": {name: True for name in ["checkin", "question", "taxi", "operator", "message"]},
                     "staff_language": "es", "hotel_name": "Isolated <hotel>"})

        hotel_ready()
        receive({"type": "language_preferences", "language": "invalid", "staff_language": "invalid"})
        assert page.locator("html").get_attribute("lang") == "es", "Invalid saved preferences must select Spanish, not browser English"
        receive({"type": "appearance", "value": appearance})
        # The production CSP permits inline styles, but never inline scripts.
        # Enlarge actual text rather than mistaking a larger viewport for text-scale coverage.
        page.add_style_tag(content="""
            html { font-size:20px !important; }
            button, select, input, textarea { font-size:20px !important; }
            .draft, .lead, .caption { font-size:20px !important; }
        """)
        assert page.locator("html").evaluate("node => getComputedStyle(node).fontSize") == "20px"
        if all_locales:
            count = page.evaluate("""() => {
                const ui = window.MANZANILLA_UI;
                const starts = fixtureMessages.filter(event => event.action === 'start').length;
                for (const locale of ui.languages) {
                    const select = document.querySelector('#guest-locale');
                    select.value = locale.code;
                    select.dispatchEvent(new Event('change', {bubbles:true}));
                    if (document.querySelector('#guest-locale').options.length !== 52)
                        throw Error('An offered interface language became unselectable');
                    if (!document.querySelector('#content').textContent.includes(
                        ui.messages['Interface translations have not been reviewed by a human.'][locale.code]))
                        throw Error('Translation review status is hidden for ' + locale.code);
                    const heading = document.querySelector('.welcome h1');
                    if (heading.textContent !== ui.messages['Welcome.'][locale.code])
                        throw Error('Missing runtime welcome for ' + locale.code);
                    if (document.documentElement.lang !== locale.code || document.documentElement.dir !== locale.direction)
                        throw Error('Wrong runtime direction for ' + locale.code);
                    const service = document.querySelector('[data-action=translation]');
                    if (!service.textContent.includes(ui.messages['Live translation'][locale.code]))
                        throw Error('Missing runtime navigation for ' + locale.code);
                    const welcome = ui.messages['Welcome.'][locale.code];
                    delete ui.messages['Welcome.'][locale.code];
                    window.manzania.receive({type:'connection', connected:false});
                    if (document.querySelector('.welcome h1').textContent !==
                        ui.messages['This service is unavailable. Please ask hotel staff.'][locale.code])
                        throw Error('Missing-key fallback escaped selected locale ' + locale.code);
                    ui.messages['Welcome.'][locale.code] = welcome;
                    window.manzania.receive({type:'connection', connected:false});
                }
                if (fixtureMessages.filter(event => event.action === 'start').length !== starts)
                    throw Error('Locale selection opened a microphone/session');
                return ui.languages.length;
            }""")
            assert count == 52
        page.locator("#guest-locale").select_option("es")
        assert "Bienven" in copy(".welcome h1", "Welcome.")
        copy('[data-action="translation"]', "Live translation")
        copy('[data-action="request:checkin"]', "Arrival guide")
        assert not actions("start"), "Changing the home locale must not open a microphone"
        fits("#guest-locale")
        page.locator('[data-action="translation"]').click()
        assert page.locator("#language").input_value() == "es"
        page.locator("#staff_language").select_option("en")
        copy('[data-action="back"]', "Back")
        fits("#language"); fits("#staff_language"); fits('[data-action="start"]')
        page.locator('[data-action="start"]').click()
        scope = {"session_id": actions("start")[-1]["session_id"], "generation": 1}

        def scoped(event):
            receive({**scope, **event})

        # A native connection failure must remain visibly Spanish and offer a truthful stopped retry.
        unavailable = page.evaluate("window.MANZANILLA_UI.messages['This service is unavailable. Please ask hotel staff.'].es")
        scoped({"type": "reception_event", "phase": "error", "text": unavailable})
        scoped({"type": "audio_state", "state": "off"})
        assert unavailable in page.locator("body").inner_text()
        copy('[data-action="restart"]', "Try again")
        assert page.locator('[data-action="restart"]').is_enabled()
        page.locator('[data-action="restart"]').click()
        retry = actions("start")[-1]
        assert retry["session_id"] != scope["session_id"] and retry["language"] == "es"
        scope = {"session_id": retry["session_id"], "generation": 1}
        scoped({"type": "audio_state", "state": "off"})
        scoped({"type": "reception_event", "phase": "stopped"})
        page.locator('[data-action="back"]').click()
        page.locator('[data-action="back"]').click()
        hotel_ready()
        page.locator('[data-action="request:message"]').click()
        copy('[data-action="assist-go"]', "Continue")
        page.locator('[data-action="assist-go"]').click()
        request = actions("start")[-1]
        assert request["mode"] == "reception" and request["language"] == "es"
        scope = {"session_id": request["session_id"], "generation": 1}
        scoped({"type": "audio_state", "state": "off"})
        scoped({"type": "reception_event", "phase": "ready"})
        # These are localized producer fields, not a claim that this fake bridge tests the producer.
        fields = [
            {"name": "original_request", "label": "Mensaje exacto para el personal", "required": True, "max_length": 4000},
            {"name": "guest_name", "label": "Nombre del huésped (opcional)", "required": False, "max_length": 100},
            {"name": "room_number", "label": "Número de habitación (opcional)", "required": False, "max_length": 20},
        ]
        scoped({"type": "operation_form", "action": "message", "fields": fields})
        copy("#operation-form [type=submit]", "Review")
        copy('[data-action="cancel-operation"]', "Cancel")
        assert "Mensaje exacto" in page.locator("#operation-form").inner_text()
        original = "  Exact original <204> & العربية\nKeep these spaces.  "
        note = page.locator('[name="original_request"]')
        note.fill(original); note.focus()
        note.evaluate("node => node.setSelectionRange(3, 9)")
        receive({"type": "connection", "connected": True, "state": "ready"})
        assert note.input_value() == original and note.evaluate("node => node === document.activeElement")
        assert note.evaluate("node => [node.selectionStart, node.selectionEnd]") == [3, 9]
        for language in ["ar", "es"]:
            page.locator("#guest-locale").select_option(language)
            assert note.input_value() == original, "Changing UI locale erased an unsent original"
            assert note.evaluate("node => node === document.activeElement"), "Changing UI locale lost textarea focus"
            assert note.evaluate("node => [node.selectionStart, node.selectionEnd]") == [3, 9]
            copy("#operation-form [type=submit]", "Review", language)
            assert actions("languages")[-1]["language"] == language, "Selected UI locale must immediately refresh the native preference/probe"
            assert actions("start")[-1]["session_id"] == scope["session_id"] and actions("start")[-1]["language"] == "es", "UI locale notification must not replace the original operation lease"
            assert page.locator("#operation-form [type=submit]").is_disabled(), "The previous locale's service capability cannot authorize a new submission"
            copy("#connection-text", "Checking hotel connection", language)
            hotel_ready()
            assert note.input_value() == original and note.evaluate("node => node === document.activeElement"), "A completed locale probe must preserve the unsent form and its focus"
        fits("#operation-form [type=submit]")
        page.locator("#operation-form [type=submit]").click()
        scoped({"type": "reception_event", "phase": "confirming", "draft_id": "localized:cancel", "message": original})
        assert page.locator(".draft").text_content() == original
        assert not page.locator(".draft img").count()
        copy('[data-action="confirm"]', "Confirm")
        copy('[data-action="cancel-draft"]', "Cancel")
        page.locator('[data-action="cancel-draft"]').click()
        assert actions("confirm")[-1]["draft_id"] == "localized:cancel" and actions("confirm")[-1]["approved"] is False
        scoped({"type": "operation_result", "status": "cancelled", "text": "Cancelado. No se ha enviado nada."})
        copy('[data-action="continue"]', "Continue")
        page.locator('[data-action="continue"]').click()
        page.locator('[data-action="message"]').click()
        scoped({"type": "operation_form", "action": "message", "fields": fields})
        page.locator('[name="original_request"]').fill(original)
        page.locator("#operation-form [type=submit]").click()
        scoped({"type": "reception_event", "phase": "confirming", "draft_id": "localized:save", "message": original})
        page.locator('[data-action="confirm"]').click()
        assert actions("confirm")[-1]["draft_id"] == "localized:save" and actions("confirm")[-1]["approved"] is True
        scoped({"type": "operation_result", "status": "saved",
                "text": "Mensaje guardado para recepción. El personal aún no ha confirmado su recepción.",
                "result": {"item_id": "fixture-204-ب"}})
        copy("h2", "Request receipt")
        copy(".receipt", "Receipt: {id}", values={"id": "fixture-204-ب"})
        fits('[data-action="continue"]')
        receipt_message = page.evaluate("window.MANZANILLA_UI.messages['Receipt: {id}'].es")
        page.evaluate("window.MANZANILLA_UI.messages['Receipt: {id}'].es = 'Recibo: {unexpected}'")
        scoped({"type": "operation_result", "status": "saved", "text": "Mensaje guardado.",
                "result": {"item_id": "fixture-204-ب"}})
        copy(".receipt", "This service is unavailable. Please ask hotel staff.")
        assert "{unexpected}" not in page.locator(".receipt").inner_text()
        page.evaluate("value => window.MANZANILLA_UI.messages['Receipt: {id}'].es = value", receipt_message)
        scoped({"type": "reception_event", "phase": "stopped"})
        receive({"type": "visibility", "visible": False})
        receive({"type": "visibility", "visible": True})
        page.locator("#guest-locale").select_option("ar")
        assert page.locator("html").get_attribute("dir") == "rtl"
        assert any("\u0600" <= char <= "\u06ff" for char in copy(".welcome h1", "Welcome.", "ar"))
        starts_before = len(actions("start"))
        page.locator('[data-action="translation"]').click()
        page.locator("#staff_language").select_option("es")
        assert page.locator("#language").input_value() == "ar"
        assert len(actions("start")) == starts_before, "Arabic/Spanish language selection must not start capture"
        copy('[data-action="back"]', "Back", "ar")
        fits("#language"); fits("#staff_language"); fits('[data-action="start"]')
        page.locator('[data-action="start"]').click()
        scope = {"session_id": actions("start")[-1]["session_id"], "generation": 1}
        scoped({"type": "audio_state", "state": "recording"})
        scoped({"type": "reception_event", "phase": "listening"})
        for ident, language, text in [("guest", "ar", "أين الغرفة 204؟"), ("staff", "es", "Su habitación está a la izquierda.")]:
            role = "guest" if language == "ar" else "receptionist"
            scoped({"type": "translation.utterance", "record": {
                "session_id": scope["session_id"], "utterance_id": ident, "revision": 1,
                "source_language": language, "target_language": "es" if role == "guest" else "ar",
                "role": role, "original": text, "translation": "204 · Alex",
                "recognition_status": "final", "translation_status": "complete", "playback_status": "idle",
                "output_generation": 0, "direction_reason": "recognized", "detected_language": language,
            }})
        guest = page.locator(".caption-guest").bounding_box()
        staff = page.locator(".caption-reception").bounding_box()
        assert guest["x"] + guest["width"] / 2 < staff["x"] + staff["width"] / 2, "RTL must not swap physical guest-left and receptionist-right"
        assert "أين الغرفة" in page.locator(".caption-guest").inner_text()
        assert "Su habitación" in page.locator(".caption-reception").inner_text()
        pair = page.locator(".pair-title").inner_text()
        receive({"type": "language_preferences", "language": "en", "staff_language": "de"})
        assert page.locator(".pair-title").inner_text() == pair, "Live language-pair changes must wait for Stop"
        page.locator('[data-action="back"]').click()
        assert actions("stop"), "Leaving a live pair must stop the existing conversation"
        assert not errors, f"Packaged localization errors: {errors}"
        assert set(loaded) == paths
        return {"fixture": "packaged_localized_journey", "passed": True, "languages": ["es", "ar"],
                "all_locale_homes": 52 if all_locales else 0, "viewport": [width, height],
                "appearance": appearance, "text_scale": 1.25,
                "scope": "real packaged UI/CSP; isolated bridge, not backend/provider/audio qualification"}
    finally:
        context.close()
