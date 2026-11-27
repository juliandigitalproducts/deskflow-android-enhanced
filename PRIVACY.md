# Privacy Policy – Deskflow Android Enhanced

*Last updated: 10 November 2026*

Deskflow Android Enhanced is an open-source app by juliandigitalproducts. It has **no user accounts, no analytics,
no ads and no server of its own**. It is a fork of [jglanz/deskflow-android](https://github.com/jglanz/deskflow-android).

## What the app processes
- **Keyboard and mouse input** from the Deskflow server you connect to. The accessibility service turns it into
  taps, gestures and key presses on this device and shows a mouse pointer.
- **Screen name, address and port** of your Deskflow server and the **fingerprints of server certificates** you
  confirmed. They are stored locally on the device.
- **Clipboard** – only if you switch on *Share clipboard with the computer* (off by default). Then text you copy is
  sent to the connected computer and text copied there is placed on this device's clipboard. Clips that apps mark
  as sensitive (password managers, one-time codes) are never sent.

## Where data goes
Data is exchanged **only with the Deskflow server you set up** in your own network. With TLS enabled the connection
is encrypted, and the app only connects after you confirmed the server's certificate fingerprint once. Automatic
server search only switches to servers whose fingerprint you confirmed.

To find servers, the app scans your local network (the Deskflow port on addresses of your Wi-Fi subnet and
mDNS/Bonjour announcements). The scan only reads a server's certificate; this device's own certificate is not sent.
To show a readable name for a server found in the scan, the app asks the DNS server configured in your network for
the name of that local address (a reverse lookup). Depending on your router this request may be forwarded to your
DNS provider; it only contains the local IP address. No other data is sent to the internet.

Without TLS the connection is not encrypted; the app shows a warning in that case.

## Storage and security
The app's client certificate is created on the device and kept in the Android Keystore. The app's data is excluded
from Android backups and device transfers. Uninstalling the app deletes all its data. Confirmed server certificates
can be forgotten in the settings.

## Permissions
Accessibility service (perform input, show the pointer), input method (on-screen keyboard), internet and network
state (connect to your server), notifications (status messages).

## Contact
Source code: https://github.com/juliandigitalproducts/deskflow-android-enhanced
