# Reception screens

## 17.0.21 release-source fixtures

Current browser renderer at 844×390 CSS pixels. Session/caption/microphone events are synthetic; no provider audio, physical microphone or real hotel request is demonstrated.

| Screen | Image |
| --- | --- |
| Home | [17.0.21-home-fixture.png](17.0.21-home-fixture.png) |
| Independent translation language selectors | [17.0.21-languages-fixture.png](17.0.21-languages-fixture.png) |
| Honest unlinked captions and visible microphone status | [17.0.21-translation-fixture.png](17.0.21-translation-fixture.png) |
| Microphone-off hotel assistance | [17.0.21-assistance-fixture.png](17.0.21-assistance-fixture.png) |

## Earlier working screens

Earlier browser renders at 837×471 CSS pixels. These are not live hotel conversations.

| Screen | Image |
| --- | --- |
| Earlier honest-caption fixture | [voice-repair-unlinked-fixture.png](voice-repair-unlinked-fixture.png), browser renderer; not a provider conversation |
| Home | [current-home.png](current-home.png) |
| Live translation language choice | [current-languages.png](current-languages.png) |
| Hotel assistance language choice | [current-assistance.png](current-assistance.png) |
| Translation greeting, green microphone orb | [current-translation-greeting.png](current-translation-greeting.png) |
| Translation listening, red microphone orb | [current-translation-listening.png](current-translation-listening.png) |

## 17.0.10 staff-led support

Isolated verification captures, not real hotel or guest requests:

| Screen | Evidence |
| --- | --- |
| Native reception home | [17.0.10-native-fixture.png](17.0.10-native-fixture.png), actual isolated app |
| Browser reception home | [17.0.10-home.png](17.0.10-home.png), actual browser renderer |
| Live-audio eligibility gate | [17.0.10-audio-gate.png](17.0.10-audio-gate.png), no provider connection |
| Typed staff message | [17.0.10-message-form-fixture.png](17.0.10-message-form-fixture.png), real backend field contract through an emulated native bridge |
| Confirmed inbox receipt | [17.0.10-message-receipt-fixture.png](17.0.10-message-receipt-fixture.png), real isolated backend receipt, not staff acknowledgement |
| Published stock update offer | [17.0.10-update-offer.png](17.0.10-update-offer.png), actual 17.0.8 updater after public download/verification |
| Published app after system update | [17.0.10-installed.png](17.0.10-installed.png), actual 17.0.10/code 40 with retained bright appearance |

[Secret-free staff-led fixture video](../videos/17.0.10-staff-led-fixture.webm). The browser bridge is emulated; this does not establish native audio, physical echo handling or hotel acceptance.

## 17.0.6 design history

Actual Android emulator captures at 960×540 landscape:

| Screen | Evidence |
| --- | --- |
| Reception home | [17.0.6-home.png](17.0.6-home.png), exact CI-built signed APK |
| Guest/receptionist language selection | [17.0.6-languages.png](17.0.6-languages.png), signed local APK |
| Original-over-translation dual bubbles | [17.0.6-conversation-fixture.png](17.0.6-conversation-fixture.png), injected synthetic captions in credential-free Android debug fixture |
| Stopped/restart control | [17.0.6-stopped-fixture.png](17.0.6-stopped-fixture.png), same synthetic fixture |

The conversation captures prove the Android renderer, not live speech association,
microphone capture or translation quality. Current live captions remain
explicitly unlinked where no reliable original/translation association exists.

## Historical reception screens

Earlier Android emulator captures (854×480 landscape), not the current design:

| Screen | File |
| --- | --- |
| Bell, "Ring for reception" | [1-bell.png](1-bell.png) |
| After ringing: Live translation or Hotel assistance | [2-choose-help.png](2-choose-help.png) |
| Language menu (8 languages) | [3-languages.png](3-languages.png) |
| Hotel assistance with the four shortcuts | [4-hotel-assistance.png](4-hotel-assistance.png) |
| Check in shortcut | [5-check-in.png](5-check-in.png) |
| Night reception: language menu | [6-night-languages.png](6-night-languages.png) |
| Night reception: assistance | [7-night-assistance.png](7-night-assistance.png) |

Screen recording of the whole flow: [../videos/reception-demo.mp4](../videos/reception-demo.mp4)


## 17.0.27 candidate browser fixtures

Synthetic Chromium captures of the shipped HTML/CSS/JS, with a fake native bridge:

- [Day appearance](17.0.27-day-fixture.png)
- [Night appearance](17.0.27-night-fixture.png)
- [Day translation](17.0.27-translation-day-fixture.png)

These verify visual rendering only, not Android installation, microphone capture,
Fish output, physical latency or delivery to hotel staff.
