# light-link

Light Phone tool for talking to the devices around it, starting with an Omarchy computer. The long-term idea: send
things both ways, run named actions, and drive herdr sessions from the phone —
over the LAN first, through a relay later.

**Status:** a LocalSend peer for text. Link finds LocalSend devices on the
Wi-Fi — computers, phones, other Light Phones running Link — sends them text or
links, and receives text from them. LAN only. Working on a real LP3 against
LocalSend 1.18.2 (protocol 2.2); Light Phone ↔ Light Phone is untested.

Sibling of `light-herdr`, `light-agent-inbox` and `light-training` (tool +
vendored `sdk/`). Do not hand-edit `sdk/`.

## How it works

The tool speaks [LocalSend protocol v2](https://github.com/localsend/protocol),
so the other side needs only LocalSend. Omarchy ships it, and its firewall
already opens LocalSend's port (53317).

The screens follow LocalSend's tabs, as icons in the bottom bar — **Send**
(send icon), **Receive** (Wi-Fi) and **Settings** (gear) — with the tab's name at
the top. Receive opens first: the name others see, the `#` id from the last part
of the IP, **Receive via link**, and history top-left. Send lists devices, with
Add (by address) top-left and refresh top-right.

While the tool is in front — any screen — it runs a LocalSend server on 53317
(`LocalSendServer`) and discovery (`Discovery`), all owned by `Link`. Both stop
when the app pauses.

### Finding devices

Every route ends in a TLS exchange, which is where a device's identity — the
SHA-256 of its certificate — comes from:

1. **Announce.** A multicast announcement on open and every 15 s. Every
   LocalSend that hears it `POST`s `/register` to the phone, so each side lists
   the other. This is what finds computers and phones.
2. **Saved devices.** The phone `POST`s `/register` to each one directly, pinned,
   so they are found where multicast is not. A saved device found at a new IP is
   updated.
3. **Scan.** 3 s after opening (and on refresh), `/register` to every
   host on the /24. This is what finds another Light Phone.

The LP3 **does not receive multicast** — Android filters it without a
`MulticastLock`, whose permission the Light SDK does not allow. Announcing works
(checked from the computer); hearing announcements does not (checked by sending
them to the phone: unicast arrives, multicast does not). So two Light Phones
cannot hear each other, and rely on the scan.

**Add** (Send tab, top-left) is for anything the above misses: type an IP, or
**Scan** a "Receive via link" QR code with the camera.

- LocalSend's code is `http://<ip>:<port>` by default, and while it is showing,
  that device serves only unencrypted HTTP. Link won't use that (Android keeps
  apps off cleartext, and trust here comes from TLS), so it says to close the
  link page there and keeps trying HTTPS for a minute; LocalSend goes back to
  HTTPS as soon as the page closes. Trusted on first use, as with a typed IP.
- Link's own code adds `?fp=<fingerprint>` (browsers and LocalSend's page ignore
  it), and Link serves HTTPS beside its HTTP link, so a Light Phone scanning
  another connects straight away, pinned to the device on the code: if
  something else answers at that address, adding fails.

LocalSend itself has no scanner; it has been asked for (localsend#812, #3464).

### Sending

`POST /prepare-upload` with one `text/plain` file whose `preview` is the text.
LocalSend shows it as a message (`204`), or hands back a token and the same
bytes go up via `POST /upload`. It holds `prepare-upload` open until someone
answers its prompt, so the phone waits up to 60 s and then reports "Not accepted".

### Receiving

Text only. Files are a placeholder: the phone shows what was offered and
declines it (`403`, which the sender shows as "rejected"). A message from a **saved** device opens
straight away; from anyone else the phone asks first (Accept / Decline, 60 s).
Either way it is shown full screen and kept in the Inbox (last 50).

### Receive via link

As LocalSend: while that screen is open, a browser on the same Wi-Fi opens
`http://<ip>:53317` (shown with a QR code) and gets LocalSend's own upload page
(`tool/src/main/assets/web/upload.html`, Apache-2.0, vendored unmodified with
its licence). Plain HTTP, as LocalSend's link is by default, so there is no
certificate warning.

LocalSend restarts its whole server unencrypted while its link is open. Link
does not: one port serves both, told apart by the first byte of each connection
(a TLS handshake opens with `0x16`), so LocalSend devices keep talking HTTPS to
it. A connection without a client certificate — plain HTTP, or HTTPS without
one — is let in only while the screen is open, may use only the page's routes,
and counts as "Web Browser", never a saved device. Leaving the screen shuts it
out again. Its uploads are files, so for now they hit the placeholder above.

### Trust

LocalSend serves a self-signed certificate on a bare LAN IP, so neither the CA
chain nor the hostname can be checked. A device is its certificate fingerprint,
learned when it is discovered (trust on first use) and pinned on every send. A
send to a device presenting any other certificate is refused.

It is mutual TLS both ways. LocalSend's server demands a client certificate and
closes the connection without one — the protocol README does not mention this.
The phone's identity is an EC key in the Android Keystore, which also issues its
self-signed certificate (`ClientIdentity`), and it presents that same
certificate as a server: peers pin the fingerprint it announces. Its server
takes any client certificate, reads the fingerprint off the TLS session, and
ignores the one a request body claims (a `/register` claiming someone else's is
dropped, as LocalSend does).

### Off the LAN

Not supported. A send that cannot connect says so. A relay is the plan for later.

## Not yet

- Receiving files: storing uploads (and then Settings → Destination). This is
  also what makes Receive via link useful
- Photos: sending from the gallery (waiting on `READ_MEDIA_IMAGES` being
  allowlisted) or from an in-tool camera
- Settings that can be changed: they only show values so far (Quick Save,
  Require PIN, device name…)
- Renaming the tool and repo after LocalSend (e.g. light-send)
- Let the user change the phone's LocalSend name — how it appears in other
  LocalSend clients. Today it is fixed: "Light Phone" + the first 4 hex of its
  certificate fingerprint (`LinkStore.self()`).
- Named actions / herdr / clipboard (needs a desktop daemon)
- Anything off the LAN (relay)

## Build

```bash
export JAVA_HOME=/home/will/.jdks/jbr-21.0.11
./gradlew :tool:testDebugUnitTest :tool:assembleDebug
```

System Java 26 breaks Android jlink — always use JBR 21. `local.properties`
(untracked) needs `sdk.dir=/home/will/Projects/Android`.

`tool/lighttool.toml` targets real hardware (`serverPackage = "com.lightos"`);
switch it to `com.thelightphone.sdk.emulator` for the emulator, and build with
`-DlightSdk.allowAltServerPackage=true` (the SDK refuses any other value without it).

## Layout

- `tool/` — the Light Phone tool
  - `Link.kt` — the phone as a peer: server, discovery, devices, incoming messages
  - `localsend/` — protocol models, the pinned client, the server, discovery
  - `HomeScreen.kt` — the initial screen: the tab bar, hosting `SendTab.kt`, `ReceiveTab.kt`, `SettingsTab.kt`
  - `WebShareScreen.kt` — Receive via link; `HistoryScreen.kt`, `ComposeScreen.kt`, `AddDeviceScreen.kt`, `IncomingModal.kt`
  - `assets/web/` — LocalSend's upload page and its licence
- `sdk/` — vendored `light-sdk` (from `lightphone/light-sdk` @ `311aa2d`, SDK 0.2.0). Read-only.
