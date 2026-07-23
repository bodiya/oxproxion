# Dev signing keystore

`dev-signing.keystore` is a **development-only** signing key, intentionally
committed to the repo so that APKs built by the GitHub Actions workflow
(`.github/workflows/build-apk.yml`) always carry the same signature. That lets
you install a newly downloaded build over a previous one without uninstalling
first.

Because it is public, it provides **no security** — do not use it for Play
Store or any production distribution. To sign with a real key instead, set
these environment variables before building:

- `SIGNING_STORE_FILE` — path to your keystore
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`

Keystore details (needed for manual use):

- alias: `oxproxion-dev`
- store/key password: `android`
