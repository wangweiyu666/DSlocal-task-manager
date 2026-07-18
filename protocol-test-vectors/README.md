# DST1 protocol test vectors

These fixtures are the shared contract for the Android Sub implementation and the future Dom web implementation.

- `manifest.json` is the only test inventory and records both protocol and current Android expectations.
- `valid/` contains vectors accepted by the current Android build.
- `future-valid/` contains valid DST1 v1 capabilities that Android currently reports as `CAPABILITY_NOT_IMPLEMENTED`.
- `invalid/` contains envelope, field, combination, and boundary failures with stable error codes.
- Large limit cases are generated deterministically from manifest entries instead of being committed as giant files.

Unknown fields and non-NFC ordinary text are rejected. See `docs/dst1-test-vectors.md` and `docs/dst1-schema.json` for the normative rules.
