Build your own changes before handing them over. `./gradlew :tool:compileDebugKotlin` is the fast check; `./gradlew :tool:testDebugUnitTest :tool:assembleDebug` runs the tests and produces a signed debug APK.

**Always** `JAVA_HOME=/home/will/.jdks/jbr-21.0.11` — system Java 26 breaks Android jlink.

Android SDK path comes from `sdk.dir` in `local.properties` (untracked).

Available icons: `sdk/ui/.../LightIcons.kt`. Page-level actions go on `LightBottomBar`, max 3. Prefer `LightIcons` / `LightBarButton.Text`.

## Working with `sdk/`

`sdk/` is vendored Light Phone `light-sdk`. **Do not hand-edit.** Consume public APIs only from `tool/`. If something is missing, work around in `tool/` or flag upstream.

## LocalSend

- The protocol reference is https://github.com/localsend/protocol. Check it before changing wire formats; do not guess field names.
- LocalSend is mutual TLS: every connection must present `ClientIdentity`'s certificate, in both directions. The phone's server presents the same certificate — peers pin the fingerprint it announces.
- A device's identity is its certificate fingerprint. Only discovery may learn an unknown one: `LocalSendClient.register` without `expected` (subnet scan, adding by address), or a peer's client certificate when it registers with our server. Every send pins. Never trust a fingerprint a request body claims — take it from the TLS session.
- The LP3 does not receive multicast (no `MulticastLock`: its permission is not allowlisted). Announcing works; hearing others relies on them registering back, or on the scan.
- The address the user types ends up in a URL. It goes through `parseAddress`; keep it that way.
