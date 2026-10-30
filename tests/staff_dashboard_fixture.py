"""Real temporary-backend staff scenarios; no provider/native acceptance claims."""
from contextlib import contextmanager
from datetime import datetime, timezone
from urllib.parse import parse_qs, urlparse

from playwright.sync_api import expect


def run_staff_dashboard_fixture(browser, api, origin, admin, admin_token):
    context = browser.new_context(service_workers="block", viewport={"width": 1024, "height": 768}, timezone_id="UTC")
    # Playwright clocks are context-wide. Install once before any page timers;
    # opening another staff tab must not reinstall the shared clock.
    context.clock.install(time=0)
    context.clock.pause_at(1000)
    errors = []
    context.on("page", lambda page: page.on("pageerror", lambda error: errors.append(str(error))))
    context.route("**/*", lambda route: route.continue_() if route.request.url.startswith(origin + "/") else route.abort())
    hotel_id = "browser-fulfilment"
    base = "/api/hotels/" + hotel_id
    overview = origin + base + "/overview"

    @contextmanager
    def overview_loaded(page):
        # A poll may join a visibility refresh whose headers already arrived.
        # Wait for actual output, not a second response. This mutation runs after
        # load's synchronous render/finally has scheduled the next timer.
        completion = page.evaluate_handle("""() => {
            const completion = {rendered: false};
            completion.observer = new MutationObserver(() => {
                completion.rendered = true;
                completion.observer.disconnect();
            });
            completion.observer.observe(document.querySelector('#sessions'), {childList: true});
            return completion;
        }""")
        try:
            yield
            page.wait_for_function("completion => completion.rendered", arg=completion, polling=100)
        finally:
            page.evaluate("completion => completion.observer.disconnect()", completion)
            completion.dispose()

    def show_dashboard(page):
        # A real tab activation can refresh immediately, before its next timer.
        # Consume that render before advancing this page's paused clock.
        if page.evaluate("document.hidden"):
            with overview_loaded(page):
                page.bring_to_front()
        else:
            page.bring_to_front()
        page.wait_for_function("() => !document.hidden && document.visibilityState === 'visible'", polling=100)

    def open_dashboard(token):
        page = context.new_page()
        page.goto(origin + "/admin")
        page.bring_to_front()
        page.locator("#token").fill(token)
        page.locator("#hotel").fill(hotel_id)
        with overview_loaded(page):
            page.locator("#load").click()
        page.locator("#staff-locale").select_option("en")
        return page

    def current_config():
        response = api.get(base + "/overview", headers=admin)
        assert response.status_code == 200
        return response.json()["hotel"]

    def card(page, item_id):
        return page.locator('article[data-item-id="' + item_id + '"]')

    def wait_version(page, item_id, version):
        expect(card(page, item_id)).to_have_attribute("data-version", str(version))

    def poll(page):
        show_dashboard(page)
        with overview_loaded(page):
            page.clock.fast_forward(5001)

    def submit_message(call_id, original):
        response = api.post("/api/device/operations/browser-staff-session/action", headers=device_headers,
                            json={"action": "message", "call_id": call_id,
                                  "arguments": {"original_request": original,
                                                "guest_name": "Guest <i>literal</i>", "room_number": "204"}})
        assert response.status_code == 200
        draft = response.json()
        response = api.post("/api/device/operations/browser-staff-session/confirm", headers=device_headers,
                            json={"confirmation_id": draft["confirmation_id"], "accepted": True})
        assert response.status_code == 200
        receipt = response.json()
        assert receipt["result"]["pms_delivered"] is False
        return receipt["result"]["item_id"], receipt

    try:
        # This is the first hotel in the temporary database. Creation goes through
        # the actual blank labelled form, not a seeded/example policy or API PUT.
        page = context.new_page()
        page.goto(origin + "/admin")
        page.locator("#token").fill(admin_token)
        page.locator("#hotel").fill(hotel_id)
        with page.expect_response(origin + "/api/hotels"):
            page.locator("#create").click()
        expect(page.locator("#hotel-name")).to_be_enabled()
        assert page.locator("#hotel-name").input_value() == ""
        assert page.locator("#timezone").input_value() == ""
        assert page.locator("#arrival-policy").input_value() == ""
        assert page.locator(".dictionary-row").count() == 0
        assert page.locator("#supported-languages option:checked").count() == 0
        page.locator("#hotel-name").fill("Isolated staff property <b>not markup</b>")
        page.locator("#timezone").fill("Europe/Madrid")
        page.locator("#default-language").select_option("es")
        page.locator("#supported-languages").select_option(["es", "en", "ar"])
        page.locator("#operator-primary").fill("+34911234567")
        page.locator("#add-service").click()
        service = page.locator("#services-rows .dictionary-row").last
        service.locator('[data-field="key"]').fill("existing/private service")
        service.locator('[data-field="value"]').fill("  Staff-entered exact service\n ")
        page.locator("#add-knowledge").click()
        knowledge = page.locator("#knowledge-rows .dictionary-row").last
        knowledge.locator('[data-field="key"]').fill("breakfast <em>literal</em>")
        knowledge.locator('[data-field="value"]').fill("  07:00–10:00\n  ")
        with page.expect_response(lambda response: response.url == origin + base and response.request.method == "PUT") as saved:
            page.locator("#save").click()
        assert saved.value.status == 200
        expect(page.locator("#refresh-config")).to_be_enabled()
        config = current_config()
        assert config["config_version"] == 1
        assert config["checkin_instructions"] == ""
        assert config["services"] == {"existing/private service": "  Staff-entered exact service\n "}
        assert config["knowledge"] == {"breakfast <em>literal</em>": "  07:00–10:00\n  "}

        # Scalar edits preserve untouched dictionaries, contacts, modes and policy.
        page.locator("#hotel-name").fill("Staff property renamed")
        with page.expect_response(lambda response: response.url == origin + base and response.request.method == "PUT"):
            page.locator("#save").click()
        expect(page.locator("#save")).to_be_enabled()
        renamed = current_config()
        assert renamed["services"] == config["services"] and renamed["knowledge"] == config["knowledge"]
        assert renamed["operator_primary"] == "+34911234567" and renamed["taxi_mode"] == "staff_request"

        # Dirty form, dictionary editing and focus survive real five-second reads.
        page.locator("#hotel-name").fill("Unsaved local name")
        local_value = "  Unsaved policy <script>literal</script>\n "
        page.locator("#arrival-policy").fill(local_value)
        page.locator("#arrival-policy").focus()
        poll(page)
        assert page.locator("#hotel-name").input_value() == "Unsaved local name"
        assert page.locator("#arrival-policy").input_value() == local_value
        expect(page.locator("#arrival-policy")).to_be_focused()
        # Another real admin advances CAS. Polling cannot replace those edits or
        # silently move their base version forward.
        competing = current_config()
        competing["emergency_contact"] = "Changed by another admin"
        assert api.put(base, headers=admin, json=competing).status_code == 200
        poll(page)
        with page.expect_response(lambda response: response.url == origin + base and response.request.method == "PUT") as conflict:
            page.locator("#save").click()
        assert conflict.value.status == 409
        expect(page.locator("#save")).to_be_disabled()
        assert page.locator("#arrival-policy").input_value() == local_value
        assert page.locator("#hotel-name").input_value() == "Unsaved local name"
        page.locator("#staff-locale").select_option("ar")
        expect(page.locator("html")).to_have_attribute("dir", "rtl")
        assert page.locator("#arrival-policy").input_value() == local_value
        assert page.locator("#token").input_value() == admin_token
        page.once("dialog", lambda dialog: dialog.dismiss())
        page.locator("#refresh-config").click()
        assert page.locator("#arrival-policy").input_value() == local_value
        page.once("dialog", lambda dialog: dialog.accept())
        with overview_loaded(page):
            page.locator("#refresh-config").click()
        expect(page.locator("#save")).to_be_enabled()
        assert page.locator("#emergency-contact").input_value() == "Changed by another admin"
        assert page.locator("#hotel-name").input_value() == "Staff property renamed"

        # A pending save belongs to its original scope. Changing hotel must
        # release local busy ownership; its late completion cannot clear a new save.
        target_id = "browser-save-scope-target"
        target_base = "/api/hotels/" + target_id
        target_config = {**current_config(), "id": target_id, "name": "Independent scope target", "config_version": 1}
        assert api.put(target_base, headers=admin, json=target_config).status_code == 200
        switcher = open_dashboard(admin_token)
        old_save, new_save = [], []
        def hold_old_save(route):
            if route.request.method == "PUT":
                old_save.append(route)
            else:
                route.continue_()
        switcher.route(origin + base, hold_old_save)
        with switcher.expect_request(lambda request: request.url == origin + base and request.method == "PUT"):
            switcher.locator("#save").click()
        switcher.locator("#hotel").fill(target_id)
        with overview_loaded(switcher):
            switcher.locator("#load").click()
        expect(switcher.locator("#hotel-name")).to_be_enabled()
        switcher.locator("#hotel-name").fill("New scope remains editable")
        def hold_new_save(route):
            if route.request.method == "PUT":
                new_save.append(route)
            else:
                route.continue_()
        switcher.route(origin + target_base, hold_new_save)
        with switcher.expect_request(lambda request: request.url == origin + target_base and request.method == "PUT"):
            switcher.locator("#save").click()
        old_route = old_save.pop()
        with switcher.expect_response(lambda response: response.url == origin + base and response.request.method == "PUT") as old_completed:
            old_route.fulfill(response=old_route.fetch())
        old_completed.value.body()
        expect(switcher.locator("#save")).to_be_disabled()
        new_route = new_save.pop()
        new_route.fulfill(response=new_route.fetch())
        expect(switcher.locator("#save")).to_be_enabled()
        assert switcher.locator("#hotel-name").input_value() == "New scope remains editable"
        switcher.close()

        staff = []
        for _ in range(2):
            response = api.post(base + "/credentials", headers=admin, json={"role": "staff"})
            assert response.status_code == 200
            staff.append(response.json())
        device = api.post(base + "/credentials", headers=admin, json={"role": "device"}).json()
        device_headers = {"Authorization": "Bearer " + device["token"]}
        assert api.post("/api/device/operations/start", headers=device_headers,
                        json={"session_id": "browser-staff-session", "language": "en",
                              "staff_language": "es", "mode": "reception"}).status_code == 200
        original = "  Please keep <img src=x onerror=alert(1)> literally.\nOriginal guest wording.  "
        item_id, receipt = submit_message("browser-message-one", original)
        owner = open_dashboard(staff[0]["token"])
        other = open_dashboard(staff[1]["token"])
        wait_version(owner, item_id, 0)
        assert original in card(owner, item_id).text_content()
        assert card(owner, item_id).locator("img,i").count() == 0
        for control in owner.locator("#config-fields").locator("input, select, textarea, button").all():
            expect(control).to_be_disabled()
        expect(owner.locator("#content-approve")).to_be_disabled()
        expect(owner.locator("#issue")).to_be_disabled()
        staff_headers = {"Authorization": "Bearer " + staff[0]["token"]}
        assert api.put(base, headers=staff_headers, json=current_config()).status_code == 401
        source = api.get(base + "/overview", headers=admin).json()["content_review"][0]
        forbidden_approval = {"kind": source["kind"], "content_key": source["content_key"],
                              "source_hash": source["source_hash"], "language": "es", "title": "Title", "body": "Body"}
        assert api.post(base + "/content/approve", headers=staff_headers, json=forbidden_approval).status_code == 401

        # Return an earlier real overview after the claim; its stale version
        # must not undo successful acknowledgement.
        snapshot = api.get(base + "/overview", headers=staff_headers)
        snapshot.raise_for_status()
        show_dashboard(owner)
        held = []
        def hold_snapshot(route):
            held.append(route)
        owner.route(overview, hold_snapshot)
        with owner.expect_request(overview):
            owner.clock.fast_forward(5001)
        owner.clock.fast_forward(15000)
        assert len(held) == 1, "An in-flight overview must prevent overlapping polls"
        card(owner, item_id).locator("input").fill("Reception shift A")
        with owner.expect_response(lambda response: response.url.endswith("/items/" + item_id) and response.request.method == "PATCH") as acknowledged:
            card(owner, item_id).locator('[data-action="acknowledge"]').click()
        assert acknowledged.value.status == 200
        assert acknowledged.value.request.post_data_json == {"status": "acknowledged", "expected_version": 0,
                                                           "responsible_name": "Reception shift A"}
        route = held.pop()
        with overview_loaded(owner):
            route.fulfill(status=snapshot.status_code, content_type="application/json", body=snapshot.content)
        owner.unroute(overview, hold_snapshot)
        wait_version(owner, item_id, 1)
        poll(other)
        wait_version(other, item_id, 1)
        expect(card(other, item_id).locator('[data-action="resolve"]')).to_be_disabled()
        assert "Reception shift A" in card(other, item_id).text_content()
        show_dashboard(owner)
        private_note = "  Private staff outcome <b>not guest content</b>\n  "
        note = card(owner, item_id).locator("textarea")
        note.fill(private_note)
        note.focus()
        poll(owner)
        assert note.input_value() == private_note
        expect(note).to_be_focused()
        # A new request belongs at the top without replacing an unchanged card
        # or interrupting its in-progress private note.
        retained_card = card(owner, item_id).element_handle()
        newest_id, _ = submit_message("browser-ordering", "Newest request should lead the inbox")
        poll(owner)
        expect(owner.locator("#inbox article").first).to_have_attribute("data-item-id", newest_id)
        assert retained_card.evaluate("(node) => node === document.querySelector('[data-item-id=\"' + node.dataset.itemId + '\"]')")
        assert note.input_value() == private_note
        expect(note).to_be_focused()
        # A timezone change must update existing cards as well as future cards.
        old_time = card(owner, item_id).locator("dd").first.text_content()
        config = current_config()
        assert api.put(base, headers=admin, json={**config, "timezone": "Asia/Tokyo"}).status_code == 200
        poll(owner)
        expect(card(owner, item_id).locator("dd").first).not_to_have_text(old_time)
        expect(owner.locator("#handoff-timezone")).to_have_text("Asia/Tokyo")
        assert card(owner, item_id).locator("textarea").input_value() == private_note
        assert api.put(base, headers=admin, json={**current_config(), "timezone": "Europe/Madrid"}).status_code == 200
        poll(owner)
        expect(card(owner, item_id).locator("dd").first).to_have_text(old_time)
        owner.locator("#staff-locale").select_option("ar")
        expect(owner.locator("html")).to_have_attribute("dir", "rtl")
        assert card(owner, item_id).locator("textarea").input_value() == private_note
        # Control the hidden case even on headless backends that keep background
        # tabs visible; restore native visibility before later tab switches.
        reads = []
        owner.on("request", lambda request: reads.append(request.url) if request.url == overview else None)
        owner.evaluate("Object.defineProperty(document, 'hidden', {configurable:true,get:()=>true}); document.dispatchEvent(new Event('visibilitychange'))")
        owner.clock.fast_forward(20000)
        assert not reads
        with overview_loaded(owner):
            owner.evaluate("delete document.hidden; document.dispatchEvent(new Event('visibilitychange'))")
        with owner.expect_response(lambda response: response.url.endswith("/items/" + item_id)) as resolved:
            card(owner, item_id).locator('[data-action="resolve"]').click()
        assert resolved.value.status == 200
        wait_version(owner, item_id, 2)
        expect(card(owner, item_id).locator("textarea")).to_have_count(0)
        public = api.get("/api/device/operations/browser-staff-session/items/" + item_id, headers=device_headers).json()
        assert public["status"] == "resolved" and public["version"] == 2
        assert set(public) == {"item_id", "status", "version", "created", "acknowledged_at", "resolved_at"}
        assert private_note not in str(public)
        stored = next(item for item in api.get(base + "/overview", headers=admin).json()["items"] if item["id"] == item_id)
        assert stored["payload"]["original_request"] == original and stored["resolution_note"] == private_note
        replay = api.post("/api/device/operations/browser-staff-session/action", headers=device_headers,
                          json={"action": "message", "call_id": "browser-message-one",
                                "arguments": {"original_request": original, "guest_name": "Guest <i>literal</i>", "room_number": "204"}}).json()
        assert replay == receipt, "Staff status must not rewrite the execution receipt"

        # A claim by another credential denies stale acknowledgement. Refresh
        # ownership without discarding edits or authorizing the denied actor.
        second_id, _ = submit_message("browser-message-two", "Second exact original")
        poll(other)
        poll(owner)
        show_dashboard(other)
        card(other, second_id).locator("input").fill("Another shift typed this")
        external = api.patch(base + "/items/" + second_id, headers=admin,
                             json={"status": "acknowledged", "expected_version": 0, "responsible_name": "Admin responsibility"})
        assert external.status_code == 200
        with other.expect_response(lambda response: response.url.endswith("/items/" + second_id)) as stale:
            card(other, second_id).locator('[data-action="acknowledge"]').click()
        assert stale.value.status == 403
        wait_version(other, second_id, 1)
        expect(card(other, second_id).locator('[data-action="review"]')).to_be_visible()
        expect(card(other, second_id).locator('[data-action="resolve"]')).to_be_disabled()
        card(other, second_id).locator('[data-action="review"]').click()
        expect(card(other, second_id).locator('[data-action="review"]')).to_have_count(0)
        expect(card(other, second_id).locator('[data-action="resolve"]')).to_be_disabled()

        # A conflicting resolution retains the typed private note even though the
        # actual stored outcome is already final; it cannot be replayed over it.
        poll(page)
        page.locator("#staff-locale").select_option("en")
        card(page, second_id).locator('[data-action="review"]').click()
        card(page, second_id).locator("textarea").fill("My unsaved private note")
        assert api.patch(base + "/items/" + second_id, headers=admin,
                         json={"status": "resolved", "expected_version": 1, "resolution_note": "Other recorded outcome"}).status_code == 200
        with page.expect_response(lambda response: response.url.endswith("/items/" + second_id)) as note_conflict:
            card(page, second_id).locator('[data-action="resolve"]').click()
        assert note_conflict.value.status == 409
        wait_version(page, second_id, 2)
        assert card(page, second_id).locator("textarea").input_value() == "My unsaved private note"
        expect(card(page, second_id).locator("textarea")).to_have_attribute("readonly", "")
        assert "Other recorded outcome" in card(page, second_id).text_content()
        # The browser itself is UTC; date inputs are explicitly hotel-local.
        expect(page.locator("#handoff-timezone")).to_have_text("Europe/Madrid")
        page.locator("#start").fill("2026-07-15T00:00")
        page.locator("#end").fill("2026-07-15T08:00")
        with page.expect_response(lambda response: "/morning?" in response.url) as handoff:
            page.locator("#morning").click()
        assert handoff.value.status == 200
        bounds = parse_qs(urlparse(handoff.value.url).query)
        assert float(bounds["start"][0]) == datetime(2026, 7, 14, 22, tzinfo=timezone.utc).timestamp()
        assert float(bounds["end"][0]) == datetime(2026, 7, 15, 6, tzinfo=timezone.utc).timestamp()
        handoff_requests = []
        page.on("request", lambda request: handoff_requests.append(request.url) if "/morning?" in request.url else None)
        for start, end in [("2026-03-29T02:30", "2026-03-29T04:00"),
                           ("2026-10-25T02:30", "2026-10-25T04:00")]:
            page.locator("#start").fill(start)
            page.locator("#end").fill(end)
            page.locator("#morning").click()
            expect(page.locator("#status")).to_have_text("Choose a valid, unambiguous hotel-local time. Daylight-saving transitions may skip or repeat times.")
        assert not handoff_requests, "Skipped or repeated hotel-local times must never guess a UTC boundary"

        # Exercise real bounded pages and preserve a private draft while its
        # request is not returned by either history paging or a status filter.
        waiting_id, _ = submit_message("browser-pagination-waiting", "Older unfinished request")
        assert api.patch(base + "/items/" + waiting_id, headers=admin,
                         json={"status": "acknowledged", "expected_version": 0, "responsible_name": "Admin desk"}).status_code == 200
        poll(page)
        pagination_note = "Private draft must survive another history page"
        card(page, waiting_id).locator("textarea").fill(pagination_note)
        for index in range(102):
            history_id, _ = submit_message("browser-history-" + str(index), "Resolved history " + str(index))
            assert api.patch(base + "/items/" + history_id, headers=admin).status_code == 200
        poll(page)
        expect(card(page, waiting_id)).to_have_count(0)
        expect(page.locator("#inbox-next")).to_be_enabled()
        expected_page = api.get(base + "/overview", headers=admin).json()
        assert page.locator("#inbox article").evaluate_all("(nodes) => nodes.map(node => node.dataset.itemId)") == [item["id"] for item in expected_page["items"]]
        with overview_loaded(page):
            page.locator("#inbox-next").click()
        expect(page.locator("#inbox-page")).to_have_text("2")
        assert card(page, waiting_id).locator("textarea").input_value() == pagination_note
        expect(page.locator("#inbox-next")).to_be_disabled()
        with overview_loaded(page):
            page.locator("#inbox-back").click()
        expect(card(page, waiting_id)).to_have_count(0)
        with overview_loaded(page):
            page.locator("#inbox-filter").select_option("unfinished")
        expect(page.locator("#inbox-page")).to_have_text("1")
        assert card(page, waiting_id).locator("textarea").input_value() == pagination_note
        assert set(page.locator("#inbox article").evaluate_all("(nodes) => nodes.map(node => node.dataset.itemId)")) == {waiting_id, newest_id}
        with overview_loaded(page):
            page.locator("#inbox-filter").select_option("resolved")
        expect(card(page, waiting_id)).to_have_count(0)
        with overview_loaded(page):
            page.locator("#inbox-filter").select_option("unfinished")
        assert card(page, waiting_id).locator("textarea").input_value() == pagination_note
        # Changing token/hotel stops old-scope timers, clears private notes and
        # prevents a delayed response from restoring a previous tenant's inbox.
        show_dashboard(other)
        held_scope = []
        def hold_scope(route):
            held_scope.append((route, route.fetch()))
        other.route(overview, hold_scope)
        with other.expect_request(overview):
            other.clock.fast_forward(5001)
        other.locator("#hotel").fill("different-property")
        assert other.locator("#inbox article").count() == 0
        for route, snapshot in held_scope:
            try:
                route.fulfill(response=snapshot)
            except Exception:
                pass  # Aborting the in-flight read may close its request first.
        other.unroute(overview, hold_scope)
        requests_after_scope = []
        other.on("request", lambda request: requests_after_scope.append(request.url) if request.url == overview else None)
        other.clock.fast_forward(20000)
        assert not requests_after_scope and other.locator("#inbox article").count() == 0
        assert not errors, errors
        return {"fixture": "staff-fulfilment-admin", "passed": True,
                "scope": "real isolated backend/browser: blank creation, labelled edits, CAS, owner transitions, private outcome, single-flight/hidden/scoped refresh, focus, RTL, stale snapshots, history paging/draft retention, newest-first order, hotel timezone refresh and DST-safe handoff; no provider/native/staff-operator qualification"}
    finally:
        context.close()
