# Reception screens

## 17.0.10 staff-led support

Isolated verification captures, not real hotel or guest requests:

| Screen | Evidence |
| --- | --- |
| Native reception home | [17.0.10-native-fixture.png](17.0.10-native-fixture.png), actual isolated app |
| Browser reception home | [17.0.10-home.png](17.0.10-home.png), actual browser renderer |
| Live-audio eligibility gate | [17.0.10-audio-gate.png](17.0.10-audio-gate.png), no provider connection |
| Typed staff message | [17.0.10-message-form-fixture.png](17.0.10-message-form-fixture.png), real backend field contract through an emulated native bridge |
| Confirmed inbox receipt | [17.0.10-message-receipt-fixture.png](17.0.10-message-receipt-fixture.png), real isolated backend receipt, not staff acknowledgement |

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
