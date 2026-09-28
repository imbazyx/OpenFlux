# Security policy

## What this app handles

OpenFluxAndroid is a VPN client. It holds, or can hold:

- the shared encryption secret for a session (AES-256-GCM);
- a transport's document credentials or room token;
- a server login for deploying an exit node over SSH.

Treat those as secrets. Do not paste them into an issue, a log screenshot, or
a commit.

## Reporting a vulnerability

Please report privately, through GitHub's
[private vulnerability reporting](https://docs.github.com/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)
on this repository, rather than opening a public issue.

Include what you observed, the app version (visible in the app), the device
and Android version, and the steps to reproduce. A log excerpt from
`adb logcat` is welcome, but check it for the items above first — the app's
own **Logs** screen is the same data.

You can expect an acknowledgement, and an assessment with any fix or
mitigation. The core's sources live in this repository, under `OpenFlux/`, and
are also released separately as
[imbazyx/OpenFlux](https://github.com/imbazyx/OpenFlux) — so a vulnerability
there should say which repository and commit it was seen in.

## Signing

Release APKs are signed with a private key that is **not** in this
repository. The build reads it from the environment:

| Variable | Meaning |
|---|---|
| `ANDROID_KEYSTORE_FILE` | path to the `.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | store password |
| `ANDROID_KEY_ALIAS` | key alias |
| `ANDROID_KEY_PASSWORD` | key password |

The CI release workflow expects the keystore itself as
`ANDROID_KEYSTORE_BASE64`. Losing this key means the published APKs can no
longer be updated in place — Android refuses to install over them. Back it up
somewhere you control.

The release key's SHA-256 fingerprint is
`60487280f6a493f6afd525d2727fa8b1772c217a24dd6ee148f90f3114a1f156`
(`CN=OpenFlux, OU=Release, O=OpenFlux`, valid to 2056-09-20). It is printed in
`scripts/wsl-audit.sh` and the audit **fails** any `dist/` whose APKs carry a
different certificate. That is deliberate: an APK signed by some other key
cannot update an installed copy, and nothing else in the build would notice.

If you fork this, mint your own key and set your own fingerprint — do not reuse
ours, and do not set `OF_EXPECTED_CERT` to match whatever you happen to build.
The audit treats that override as voiding the check, and says so on exit.
