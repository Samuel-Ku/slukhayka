# #1796 signed-graph acceptance test artifacts

**Test-only public host. Not production activation.** Approved production
HTTPS delivery remains blocked. This branch exists only so the Android
acceptance agent can fetch the signed `formatVersion=1` container and the
negative controls over public HTTPS.

No workflows, releases, merges, timers, ports or tunnels. No catalogue,
vector cache, credentials or private keys. The only key material here is the
public key below.

## Valid artifact

- `graph.signed.json`
- SHA256: `3ec6d8d8cd5487645ab689aea4e234734e33ed0432363cb4c85b30562e2ab094`
- `formatVersion=1`, graph 8 201 504 bytes, signature 72 bytes.
- Expiry: 2026-12-04T08:58:41Z.
- Public key (DER SPKI base64):
  `MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEHsvDhg/Ibg/tnfTVswBjdKsp86yNOFND0FfIoDFmtYoTT5G/77w7TqgjB2iKDqoezW2CB9mTU1C3z0OrTU8XhA==`

Expected: signature verifies, graph parses, TTL is still valid on the test day.

## Negative controls

### `negative/tampered-graph-old-signature.json`

- SHA256: `029946f57e3e78162ace9211c7a1d2ec5b3bd0b46799929ec084d31945bb57b9`
- The embedded graph has `expiresAt` incremented by 1 ms; the original valid
  signature is retained.

Expected: cryptographic signature verification fails.

### `negative/wrong-signature.json`

- SHA256: `13e15972d790a89d0d64219bfbbe3dd6989c743159255f3d8193dc6a60ac532a`
- The valid graph is paired with the public seed-fixture signature (a valid
  ECDSA signature from a different test key, not the operator key).

Expected: signature verification fails.

### `negative/expired-graph.json`

- SHA256: `41c82f8fb3a739a2a1848f5a7d4381d577844d92495a7213d101d3f18c7f4c41`
- Signed by the **existing operator key**; the signature itself is valid
  (`Verified OK`), but `expiresAt` is 2026-11-26T09:19:18Z (already past).

Expected: signature verifies, but expiry/TTL rejection occurs before the graph
can enter ranking.

## Constraints

- No private keys are present or transmitted.
- No raw catalogue, vector cache or credentials.
- The valid artifact is unchanged; negative controls are separate files.
- This branch is not production activation and must not be merged.
