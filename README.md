# light-link

Light Phone tool for talking to an Omarchy computer. The long-term idea: send
things both ways, run named actions, and drive herdr sessions from the phone —
over the LAN first, through a relay later.

**Status:** first increment — **send to computer**. Type text or a link on the
phone; it arrives in LocalSend on the computer. LAN only. Working on a real
LP3 against LocalSend 1.18.2 (protocol 2.2).

Sibling of `light-herdr`, `light-agent-inbox` and `light-training` (tool +
vendored `sdk/`). Do not hand-edit `sdk/`.

## How it works

Omarchy ships [LocalSend](https://localsend.org), and its firewall already opens
LocalSend's port (53317). The tool speaks the sender half of
[LocalSend protocol v2](https://github.com/localsend/protocol), so the computer
needs nothing installed:

1. **Pair** — enter the computer's IP. The tool calls `GET /api/localsend/v2/info`,
   shows the computer's name, and on confirmation stores its address and the
   SHA-256 fingerprint of the certificate it presented.
2. **Send** — `POST /prepare-upload` with one `text/plain` file whose `preview`
   is the text. LocalSend shows it as a message (`204`), or hands back a token
   and the same bytes go up via `POST /upload`.

The phone runs no server and never needs to be reachable.

### Trust

LocalSend serves a self-signed certificate on a bare LAN IP, so neither the CA
chain nor the hostname can be checked. Trust is the fingerprint pinned at
pairing (trust on first use). A send to a computer presenting any other
certificate is refused with "Pair again".

It is mutual TLS. LocalSend's server demands a client certificate and closes
the connection without one ("certificate required") — the protocol README does
not mention this. The phone's identity is an EC key in the Android Keystore,
which also issues its self-signed certificate (`ClientIdentity`).

LocalSend holds `prepare-upload` open until someone answers its prompt on the
computer, so the phone waits up to 60 s and then reports "Not accepted".

### Off the LAN

Not supported. A send that cannot connect says "Computer not found. Connect to
home Wi-Fi and open LocalSend." A relay is the plan for later.

## Not yet

- Receiving on the phone (needs the tool to run a LocalSend HTTPS server while open)
- Discovery — pairing is by IP; a multicast announce is the planned fallback when it changes
- Photos (in-tool camera capture)
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
switch it to `com.thelightphone.sdk.emulator` for the emulator.

## Layout

- `tool/` — the Light Phone tool
  - `localsend/` — protocol models and the pinned HTTPS client
  - `SendScreen.kt` — the initial screen
  - `PairScreen.kt` — pair / re-pair / forget
- `sdk/` — vendored `light-sdk` (from `lightphone/light-sdk` @ `57bbbd0`). Read-only.
