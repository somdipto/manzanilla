# Translation startup repair (17.0.29 candidate)

The message reported on 17.0.28, “Translation could not connect. Check the
internet connection or ask staff to check service access,” comes from the
session-creation catch in `FishVoice`. It occurs before connecting LiveKit or
opening the microphone. It does not establish that the device is offline.

The earlier 17.0.28 checks exercised WebView compatibility, the native bridge,
microphone permission and signed installation. They used key-free builds and
did not establish successful provider provisioning or a live translation.

## Changes

- Each startup operation retains its stage. Rejected credentials, access
  restrictions, billing, usage limits, missing resources, incompatible setup,
  unavailable services, DNS, TLS and timeouts have separate repair messages.
  The message includes a safe reference such as
  `TR-agent_create-billing-HTTP402`. Responses, credentials, transport tokens,
  URLs and exception messages are never copied into these diagnostics.
- A direct session using a cached agent repairs its setup once after a received
  HTTP 404 or 409. Fish documents these session refusals as missing agent/no
  published version; the existing admission policy treats them as noncreation.
  The rejected agent is excluded from rediscovery, and a replacement uses the
  current app configuration. The app never modifies that rejected agent's draft.
- Repair does not retry a lost response, timeout, HTTP 5xx, billing refusal or
  usage-limit refusal. Uncertain session ownership remains blocked until cleanup
  is confirmed. Cancellation fences subsequent provisioning and session creation.
- The managed backend sends an optional, strictly allowlisted
  `X-Manzanilla-Voice-Failure: stage:category:status` diagnostic. This distinguishes
  a provider credential refusal from a hotel device-credential refusal, and a
  daily hotel duration cap from a network failure. Older backends still yield
  safe HTTP diagnostics; a 422 from the hotel session endpoint points staff to
  app/backend compatibility.

The diagnostic header is advisory. Only the existing separate session-creation
proof and persisted ownership rules may authorize another session. Model,
language, provider selection, hotel budgets and audio policy are unchanged.

## What staff can do on an installed device

The existing generic 17.0.28 message cannot identify which request was refused.
Keep the app data and saved settings. Check whether staff configured a hotel
service or a device translation credential; a configured hotel service takes
precedence. Verify that the chosen account has agent/session access and credit.
For managed devices, the backend must support `language-v2` and have its provider
credential and voice configured. Do not paste credentials into a support chat.

After installing the repair, use the reference from one deliberate Start attempt
to select the remedy. For example, HTTP 401 concerns the identified credential,
HTTP 402 concerns account credit, HTTP 403 concerns access, and a configuration
reference concerns the failed setup operation. Repeated retries cannot repair
account permissions or credit. A managed hotel budget is reserved conservatively
even when provisioning fails; this change does not release those reservations.

## Verification and delivery

Regression checks use synthetic credentials, intercepted HTTP requests,
temporary databases and a disposable emulator. They do not create a real
provider session or establish live speech quality or acoustic latency.

17.0.29/code 59 is a source candidate. An installed 17.0.28 app will receive it only
after the existing signed GitHub Release workflow publishes it successfully.
Publishing the APK and deploying the managed backend are separate actions.
The reported device's actual service refusal is still unknown without its
request status or the new safe reference; no particular account fault is claimed.
