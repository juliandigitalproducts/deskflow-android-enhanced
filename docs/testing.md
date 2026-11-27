# Testing

## Unit tests (JVM)

```bash
./gradlew :client:test :app:testDebugUnitTest
```

`:client:test` covers protocol encoding/limits, TLS pinning, clipboard parsing, the logger cache under
concurrency and the reconnect race (a late end of an old socket must not close a new connection).
`:app:testDebugUnitTest` covers the port field rules, the clipboard share filter and the log redaction.

## Protocol harness (JVM, fake Deskflow server)

```bash
./gradlew :client:harnessTest
```

`FakeDeskflowServer` (client/src/test/.../harness) is a minimal plain-TCP Deskflow server. The harness tests
drive the real client (`Client` → `FullDuplexSocket` → `MessageParser` → `MessageHandler`) against it:

| Area | What is checked |
|---|---|
| Handshake | HelloBack 1.6 with screen name, DINF with screen size, CIAK; Synergy-style hello; reject of another major version (no immediate retry) |
| Keep-alive | every CALV is answered; without CALV the client closes after ~7 s (measured value is printed as `HARNESS keep-alive timeout measured`); a 6 s gap is tolerated; an 8 s gap drops the connection (open question 7 s vs. Deskflow's 9 s) |
| Disconnect/reconnect | server close and CBYE give exactly one `Disconnected` and a reconnect within the backoff; retry while the server is down |
| Malformed packets | negative, too small and > 16 MiB length fields disconnect; unknown codes and truncated messages are dropped without disconnect; byte-wise fragmented messages are reassembled; bogus clipboard lengths are dropped |
| Clipboard | sharing on: server text reaches the app; sharing off or screen not entered: nothing is collected; device → server on leaving the screen |

The run takes about one minute (keep-alive timing). It is not part of `:client:test` (and therefore not of CI).

## Instrumented tests (device/emulator)

Package `org.tfv.deskflow.e2e`:

- `SettingsScreenPortTest` – port field: clearing, too long/out of range/non-numeric input, saving an empty port (24800) and a valid port.
- `ClipSourceTest` – clipboard source on real Android clip classes (mixed MIME clip, sensitive flag).
- `ConnectFlowTest` – connect flow against the fake server on the host (10.0.2.2); skipped unless `harnessPort` is passed.
- `PermissionPromptsTest` – "Later" (accessibility) and "Ignore" (notifications) survive a configuration change; "Ignore" is stored.
- `SettingsScreenLayoutTest` – no field is focused when the settings open; compact header in landscape.
- `ShortcutsListWidgetTest` – the shortcuts card has no edit button without a function.
- `ConnectionStatusTest` – after three failed attempts the reason (TLS error / not reachable) is shown; toolbar connection light.

Run on an arm64 emulator (API 34+, e.g. `Pixel_3a_API_35`):

```bash
# terminal 1: fake server on the host (all interfaces, port 24800)
./gradlew :client:runFakeServer

# terminal 2: emulator running, then
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=org.tfv.deskflow.e2e \
  -Pandroid.testInstrumentationRunnerArguments.harnessPort=24800
```

Run the `e2e` package only: the older `GlobalKeyboardManagerTest` fails as is (`@Rule` on a Kotlin property
without `@get:`, and it binds the accessibility service directly). Turn the Deskflow accessibility service off during `ConnectFlowTest`, otherwise the app's own
connection shares the event bus with the test.

## Platform behaviour to keep in mind

- `adb shell am kill` does not end the process while the accessibility service is bound (the service and
  `START_STICKY` keep it alive); real process death cannot be triggered with the service enabled.
- `adb shell am force-stop` (and "Force stop" in the app info) makes Android remove Deskflow from
  `enabled_accessibility_services`. Re-enable it afterwards
  (`adb shell settings put secure enabled_accessibility_services <component>` or in the system settings).
- German UI: `adb shell cmd locale set-app-locales de.juliandigitalproducts.deskflow --locales de-DE`.
