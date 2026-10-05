# Baseline audit — 2026-09-30

The supplied ZIP is a UI handoff, not a complete hotel product. All 752 entries were extracted; README, manifest inventory, backend integration handoff, Gradle configuration, Android activity, bridge, voice scenes and audio classes were inspected. The desktop backend and laptop Python bridge are explicitly excluded by the export README. No live hotel connector or agent runtime is supplied.

## Android
`android/app` uses Kotlin 1.9.22, AGP 8.2.2, SDK 34, min SDK 26, Java 17, native Canvas views and a packaged WebView/Three.js companion. MainActivity is a roughly 120 KB general-purpose controller; state uses Model.kt and view fields rather than Compose. BridgeClient discovers `_agentdeck._tcp.` with Android NSD and connects to `ws://host:port/deck` without production authentication. Manifest permits cleartext and declares HOME/LAUNCHER; becoming the selected default Home is required for startup, not guaranteed by this declaration alone.

MicStreamer captures handset VOICE_COMMUNICATION PCM16 mono at 16 kHz, uses gain boost, tags binary packets 0x01, and is coupled to BridgeClient. **MainActivity sets useDeviceMicrophone=false and explicitly documents the laptop microphone as the MVP hardware fallback.** Therefore standalone phone microphone function and existing translation cannot be claimed verified. LiveSpeaker plays 24 kHz PCM16 using a bounded queue; PcmEnvelope drives measured animation energy. LiveVoiceScene contains laptop-specific copy. LiveCompanionView serves packaged graphics with network blocked and no native JS bridge.

## Reuse
Reuse LiveCompanionView, bundled voice-companion assets, LiveVoiceScene drawing and call controls, LiveSpeaker and PcmEnvelope. Adapt the microphone capture concept to 24 kHz and an audio sink. Keep original `app` intact. Add independent `hotel` module, excluding camera, pets, games, Stream Deck and desktop remote controls from its APK. Existing desktop React workspace and Mac Arc have no role in the hotel MVP; original archive remains available unchanged.

## Missing/replacement
Replace local PC discovery and bridge in the hotel module with authenticated HTTPS/WSS cloud sessions. Add tenant config, device credentials, gateway/policy, persistent notes/inbox, real provider boundaries, bounded Edge, fleet metadata and deployment docs. No PMS vendor, hotel records, telephony credentials, OpenAI key, signing key or Android device is provided. There is no way to validate booking access or an answered operator call from this archive alone.

## Baseline verification
Handoff SHA-256 verifier was run. JDK 17 is present. No Android SDK or cached Gradle distribution is supplied; initial wrapper attempt failed because the default Gradle cache is outside writable roots. A workspace cache is used for subsequent attempts. No APK/device behavior is marked verified on source inspection alone.
