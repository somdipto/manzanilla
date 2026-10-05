# Run and deploy

From the extracted `orange/` root:
```sh
python -m venv .venv
. .venv/bin/activate
pip install -r requirements-dev.txt
export ORANGE_ADMIN_TOKEN="$(python -c 'import secrets; print(secrets.token_urlsafe(32))')"
export ORANGE_DB=data/orange.db
# Set OPENAI_API_KEY and OPENAI_REALTIME_MODEL in your secret environment before voice testing.
uvicorn cloud.app:app --host 127.0.0.1 --port 8080 --workers 1 --ws-max-size 65536
```
Open http://127.0.0.1:8080/admin for local setup; the Android app and Edge require a publicly trusted HTTPS/WSS endpoint. Set the generated token in the dashboard password field; create hotel-1 with the JSON editor, then issue one device token. Provision token + HTTPS base URL in Android's staff enrollment screen. Issuance binds a stable ID and property server-side; no separate APK per hotel.

Tests: `python -m pytest tests -q`. Android:
```sh
cd android
export ANDROID_HOME=/path/to/android-sdk
bash gradlew :hotel:assembleDebug --no-daemon
# Optional, only with signing env vars configured:
bash gradlew :hotel:assembleRelease --no-daemon
adb install -r hotel/build/outputs/apk/debug/hotel-debug.apk
```
The supplied `app` remains the legacy desktop-dependent SKU. Build `:hotel`, not `:app`, for this product. Debug APK is for development only. Set Orange Reception as Android's default Home and grant microphone permission. HOME intent does not alone grant device-owner/kiosk privileges. Check mic/hardware key handling and start after a physical reboot.

Backend deployment:
```sh
docker build -f infra/Dockerfile -t orange-cloud:0.1.0 .
# Create volume once; retain it across upgrades.
docker volume create orange-data
docker run -d --name orange-cloud --restart unless-stopped --env-file .env \
  -p 127.0.0.1:8080:8080 -v orange-data:/app/data orange-cloud:0.1.0
```
Set your real DNS name in infra/Caddyfile and install/run Caddy on the host; its automatic TLS proxy supports WebSocket upgrades. Do not publish port 8080 publicly. Confirm /health and authenticated heartbeat over HTTPS. Docker/Caddy deployment commands are supplied, not executed against a real host here. Test an encrypted SQLite backup/restore before rollout.

## DANI Edge
Install only if local reservation exports are needed. On Linux create a restricted orange-edge user, copy the project under /opt/orange, create .venv, install requirements, set /etc/orange-edge.env to only three Edge variables, grant read access to reservation export, and install infra/orange-edge.service as /etc/systemd/system/orange-edge.service. Run `sudo systemctl daemon-reload` then `sudo systemctl enable --now orange-edge`. Cloud hotel.connector becomes `edge`. Do not enable arbitrary shell/browser tools.

Windows (PowerShell, install under C:\ProgramData\Orange with system-readable Python 3.12):
```powershell
cd C:\ProgramData\Orange
python -m venv .venv
.venv\Scripts\python.exe -m pip install -r requirements.txt
# Create C:\ProgramData\Orange\edge.env with ORANGE_EDGE_URL, ORANGE_EDGE_TOKEN, ORANGE_RESERVATION_FILE.
# Restrict env file and reservation export ACLs to SYSTEM and Administrators.
# From elevated PowerShell:
.\infra\Install-Edge.ps1 -Root C:\ProgramData\Orange -EnvFile C:\ProgramData\Orange\edge.env
Get-ScheduledTask -TaskName OrangeEdge
```
Foreground alternative: set Edge variables in the shell and run `python -m edge.executor`. Startup scripts are not verified on a Windows hotel PC.

## Hotel #1 checklist; repeat for all four
1. Obtain actual PMS/CRM vendor, API sandbox/access and approved reservation lookup policy. Use connector=unconfigured until real data is available. Demo mode is clearly synthetic, never a live integration.
2. Create hotel ID/name/timezone, supported languages, services/knowledge, operator and backup E.164 numbers, taxi number/mode and emergency information. Confirm check-in instructions contain no unauthenticated door code.
3. Issue separate device credential; provision network, cloud URL and token. Confirm stable device ID, tenant assignment, app/config version and heartbeat.
4. Verify physical handset mic and speaker. Test supported languages, natural turns and interruption with real key/account. Inspect session errors without recording raw guest conversations.
5. Confirm note → actual morning inbox after refresh/restart. Staff take responsibility for queued taxi requests; otherwise configure and test a real taxi/telephony provider before relying on it.
6. Verify actual operator dialer/SIM and primary/backup numbers; add answered/no-answer detection or managed SIP transfer before claiming reliable connected handoff.
7. Run verified reservation lookup/guidance. Staff retain sensitive check-in actions and identity checks. Install Edge only for required legacy access; disconnect PC to prove core cloud operation survives.
8. Lock staff setup/enrollment with managed device provisioning, configure privacy/retention, backup/restore and release key. Deploy signed APK to one device first, then other three after acceptance.
9. Execute docs/TEST-PLAN.md and record evidence per hotel. Do not mark a pilot complete using mock test results.

## Updates and rollback
Use release workflow to create a signed APK artifact; publish to your private managed distribution/GitHub Release after validation. Keep the same signing key and increasing versionCode. Deploy to one hotel, observe 24h, then remaining devices. A previous APK with lower versionCode cannot normally be installed in place; ship a recovery build of known-good code with a higher versionCode. Backend changes/config do not require APK release. Keep the previous container image and take SQLite backup before any schema migration; roll back code only when schema remains compatible. Migration runner/staged remote installer are not yet implemented.
