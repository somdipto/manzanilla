# Environment

Python 3.12+, Java 17, SDK 34, build tools 34.0.0, Gradle 8.5 wrapper, Kotlin 1.9.22 and AGP 8.2.2. Runtime Python dependencies are pinned in requirements.txt. CI actions use version tags; organization supply-chain policy may replace them with reviewed commit hashes.

`.env.example` documents every credential. Python does not automatically load .env. Load it into the process environment or use your service manager. Use a random ORANGE_ADMIN_TOKEN of 32+ characters (`python -c "import secrets; print(secrets.token_urlsafe(32))"`). Never share an admin token with the APK: issue a separate device credential from the dashboard. ORANGE_DB is persistent SQLite path. OPENAI_API_KEY and OPENAI_REALTIME_MODEL are backend-only. Missing API key reports voice unavailable without simulating a conversation.

Per-hotel REST adapter expects PMS_<UPPERCASE_HOTEL_ID_WITH_UNDERSCORES>_URL and _TOKEN, e.g. hotel-1 → PMS_HOTEL_1_URL. URL must use HTTPS and is never provided by a guest/model. The API contract is in shared/protocol.md. Secrets are not accepted as hotel configuration fields.

Edge: ORANGE_EDGE_URL must be WSS, ORANGE_EDGE_TOKEN must have Edge role, ORANGE_RESERVATION_FILE must point to an owner-controlled JSON export. Never use the demo file for a live hotel. Restrict the file to the service account and keep it fresh.

Signing: ORANGE_KEYSTORE, ORANGE_STORE_PASSWORD, ORANGE_KEY_ALIAS, ORANGE_KEY_PASSWORD. Set these only for release build. Missing key produces an unsigned release, never a production-ready signed APK. GitHub release workflow additionally uses ORANGE_KEYSTORE_BASE64.

Transport: public HTTPS/WSS terminated by Caddy or a managed proxy. Only loopback port 8080 is published on the host. No cleartext hotel-client exception, certificate bypass or guest-supplied upstream URL. Docker receives .env via --env-file. One worker only. Configure OS disk encryption, access controls, encrypted backups, retention and provider data settings before real guest use.
