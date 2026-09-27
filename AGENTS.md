Build your own changes before handing them over. `./gradlew :tool:compileDebugKotlin` is the fast check; `./gradlew :tool:testDebugUnitTest :tool:assembleDebug` runs the tests and produces a signed debug APK.

**Always** `JAVA_HOME=/home/will/.jdks/jbr-21.0.11` — system Java 26 breaks Android jlink.

Android SDK path comes from `sdk.dir` in `local.properties` (untracked).

Available icons: `sdk/ui/.../LightIcons.kt`. Page-level actions go on `LightBottomBar`, max 3. Prefer `LightIcons` / `LightBarButton.Text`.

## Working with `sdk/`

`sdk/` is vendored Light Phone `light-sdk`. **Do not hand-edit.** Consume public APIs only from `tool/`. If something is missing, work around in `tool/` or flag upstream.

## LocalSend

- The protocol reference is https://github.com/localsend/protocol. Check it before changing wire formats; do not guess field names.
- LocalSend is mutual TLS: every connection must present `ClientIdentity`'s certificate, including pairing.
- Certificate trust is the pinned fingerprint in `LinkStore`. Never fall back to trusting any certificate on a send — only `probe` (pairing) may capture an unknown one.
- The address the user types ends up in a URL. It goes through `parseAddress`; keep it that way.
