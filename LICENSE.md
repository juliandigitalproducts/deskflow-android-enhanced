# License

Deskflow Android (Enhanced) as a whole is distributed under the
**GNU General Public License, version 2** – the full text is in [`COPYING`](COPYING).

Why GPL: the protocol layer of the client (the files under
`client/src/main/java/org/tfv/deskflow/client/io/msgs/`, `client/io/DataInputStreamExt.kt`,
`client/io/DataOutputStreamExt.kt`, `client/exceptions/` and `client/events/ScreenEvent.kt`)
goes back to synergy-android by Shaun Patterson and the Synergy/Deskflow project, which are licensed
under the GPL version 2. Those files carry their original GPL notices again. A work that contains them
can only be passed on under the GPL version 2.

The 38 GPL files (all under `client/src/main/java/org/tfv/deskflow/client/`):

- `events/ScreenEvent.kt`
- `exceptions/InvalidMessageException.kt`, `exceptions/NoEventAvailableException.kt`
- `io/DataInputStreamExt.kt`, `io/DataOutputStreamExt.kt`
- `io/msgs/`: `BadMessage.kt`, `BusyMessage.kt`, `ClipboardDataMessage.kt`, `ClipboardMessage.kt`,
  `CloseMessage.kt`, `EmptyMessage.kt`, `EnterMessage.kt`, `HelloBackMessage.kt`, `HelloMessage.kt`,
  `IncompatibleMessage.kt`, `InfoAckMessage.kt`, `InfoMessage.kt`, `InvalidMessageException.kt`,
  `KeepAliveMessage.kt`, `KeyDownMessage.kt`, `KeyRepeatMessage.kt`, `KeyUpMessage.kt`, `LeaveMessage.kt`,
  `Message.kt`, `MessageHeader.kt`, `MessageType.kt`, `MouseDownMessage.kt`, `MouseMoveMessage.kt`,
  `MouseRelMoveMessage.kt`, `MouseUpMessage.kt`, `MouseWheelMessage.kt`, `NoOpMessage.kt`,
  `QueryInfoMessage.kt`, `ReadableMessage.kt`, `ResetOptionsMessage.kt`, `ScreenSaverMessage.kt`,
  `SetOptionsMessage.kt`, `UnknownMessage.kt`

## Known license issue: GPL-2.0-only code and Apache-2.0 libraries

This is stated openly so that nobody is misled; it is not legal advice.

- **What was inherited.** The situation was taken over unchanged from upstream
  (jglanz/deskflow-android). Upstream already combined the GPL protocol files listed above with the
  same kind of libraries; it had replaced the GPL notices with MIT notices. This fork restored the
  original notices but did not change the code structure.
- **Which version of the GPL.** The original notices refer to "the GNU General Public License found in
  the file COPYING", and `COPYING` is version 2. Synergy and Deskflow themselves are distributed as
  GPL-2.0-only. This project therefore treats these 38 files as **GPL-2.0-only** and does not rely on
  "or any later version".
- **Which libraries are Apache-2.0.** The APK bundles libraries under the Apache License 2.0, among them
  AndroidX (core, appcompat, activity, lifecycle, navigation, datastore, constraintlayout), Jetpack Compose
  and Material 3, Google Material Components, Accompanist, the Kotlin standard library and kotlinx
  (coroutines, serialization), Arrow and kotlin-logging (plus Gson, Guava and annotations pulled in by
  protobuf-java-util). Protocol Buffers is BSD-3-Clause, SLF4J is MIT – both are compatible with the GPL.
- **Why that matters.** The Free Software Foundation considers the Apache License 2.0 incompatible with
  GPL version 2 (not with version 3). The "system library" exception of GPLv2 covers the Android
  framework on the device, but not libraries that are packed into the APK. Distributing the APK
  therefore combines GPL-2.0-only code with GPL-incompatible libraries.
- **How the project handles it.** The maintainer has decided to accept and disclose this risk for now,
  as many GPLv2 Android apps do, instead of rewriting the protocol layer. The source code stays complete
  and public under the GPL; the copyright notices of all files are kept as they are. If a rights holder
  of the GPL code objects, please open an issue; the protocol layer can then be rewritten from the
  protocol specification.

## Files under the MIT License

All other files written by Jonathan Glanz for deskflow-android, and the changes made in this fork, were
published under the MIT License. They keep that license and their notices; the MIT License allows them
to be part of a GPL-licensed work. The changes made in this fork are
Copyright (c) 2026 juliandigitalproducts and use the same MIT terms. The original MIT notice:

```
MIT License

Copyright (c) 2025 Jonathan Glanz

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## App logo

The app icon, toolbar logo, notification icon and store graphics (`art/brand/`,
`app/src/main/res/drawable/app_logo_*.xml`) are an own design of juliandigitalproducts (2026, MIT License).
They are not the Deskflow logo.

## Passing on APKs

Whoever passes on an APK of this app must also make the complete corresponding source code available
under the GPL version 2 (for example a link to the public repository or the source archive of the
same release next to the APK).
