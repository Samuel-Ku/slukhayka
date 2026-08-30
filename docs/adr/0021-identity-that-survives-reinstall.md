---
status: accepted
---

# Identity that survives reinstall (spec-40 #275/#276)

The app had no listener concept at all: every cloud feature so far is an
anonymous crowd cache (`book_durations`, `book_profiles`, `search_results`,
`universe_resolutions`, `book_covers`). Spec-40 adds listener-owned
collections (book reviews) — and those need an identity. The product rule
from the ticket is strict: NO login screens. First launch silently creates
a profile; the only visible trace is a nickname in ⚙️ Профіль. And the
profile must survive reinstall — a wiped app must not silently fork a
listener's reviews onto a new account they never chose.

## Decision

A silent generated identity on public Firebase APIs, persisted three ways:
locally, through Android Auto Backup, and through a per-device Firestore
binding.

1. **Seam** — `ListenerIdentity` (`data/identity`): `ensure()` /
   `current()` / `setNickname()` / `recoveryCode()` / `restoreFromCode()`.
   `ensure()` is silent bootstrap: idempotent and never-throws-outward by
   contract — the same degrade-never rule as every store. Two
   implementations: `FirebaseListenerIdentity` (thin glue,
   constructor-injected FirebaseAuth + device bindings, no unit tests) and
   `LocalOnlyIdentity` (no Firebase keys / offline first launch → a local
   `local-…` profile; a later successful launch upgrades it to a real
   account carrying the nickname over). The JVM fake pins the seam contract.
2. **Generated credentials** — Anonymous Auth alone would expire (Firebase
   reaps anonymous accounts), so the anonymous session is immediately
   elevated: `linkWithCredential(EmailAuthProvider.getCredential(genEmail,
   genPassword))`, with `<rand>@slukhayka.local` and a random password from
   the seeded-pure generators. Three attempts with fresh pairs absorb any
   collision; if all fail the bare anonymous session is kept honestly — no
   fabricated credentials are ever persisted. Default nickname
   «Слухач-%04d», editable in ⚙️ Профіль (the pushed destination beside
   «Завантаження та пам'ять» and «Приватність мережі», ADR-0018).
3. **Survival layer 1 — Auto Backup** — the credential pair lives in its
   own SharedPreferences file (`listener_identity`) listed explicitly in
   `backup_rules.xml` (< API 31) and `data_extraction_rules.xml`
   (cloud-backup AND device-transfer). Any `<include>` makes the rules an
   allowlist, so the OTHER durable state travels with it: the Room database
   (Listening State) plus the settings stores. Caches, downloads and audio
   files stay out of the backup on purpose — they would burn quota for
   nothing. Restored credentials sign back in via
   `signInWithCredential` → same uid, zero UI.
4. **Survival layer 2 — device binding** — after every successful
   bootstrap/sign-in the app writes
   `device_bindings/{ANDROID_ID} → { uid, cred }` (own uid only — enforced
   again by the rules). On a fresh install WITHOUT a backup, `ensure()`
   looks the binding up before creating anything and silently signs in.
   ANDROID-ID only: IMEI and hardware ids are forbidden. Reads are public
   BY DESIGN (the lookup happens pre-auth); the payload is therefore
   sealed client-side (`DeviceBindingCipher`: AES-GCM, key derived from
   ANDROID_ID + constant salt via PBKDF2) — the same phone derives the key
   after reinstall, any other reader gets opaque bytes and a pseudonymous
   uid. Tampered/wrong-device ciphertext decodes to null, never a crash.
5. **Recovery code** — the encoded stored pair
   (`SLK1.<base64url(email:password)>`, pure `RecoveryCodec`; garbage →
   null). ⚙️ Профіль shows it ONLY behind BiometricPrompt
   (`androidx.biometric`, BIOMETRIC_WEAK | DEVICE_CREDENTIAL), copyable;
   entering it elsewhere calls `restoreFromCode` →
   `signInWithCredential` → same uid. MainActivity becomes a
   FragmentActivity for exactly this reason.

## Consequences

- A listener never sees a login screen; the profile exists from the first
  launch and survives uninstall via backup, binding, or code — in that
  order of convenience.
- Reviews and future listener-owned collections can anchor to ONE stable
  uid across devices without any auth UX.
- The publicly readable `device_bindings` documents hold only ciphertext +
  a pseudonymous uid. The threat model accepts a device-holder who knows a
  victim's ANDROID_ID and extracts the app's salt: the stakes are a
  pseudonymous library profile (nickname, prefs), not credentials that open
  anything else — the `.local` addresses deliver nowhere.
- The backup allowlist drops caches/transient prefs from backups; durable
  user choices (listen-block layout, privacy route, playback speed) are
  explicitly included, so nothing visible regresses.
- Firebase deployment prerequisites: Email/Password AND Anonymous providers
  enabled; App Check enforcement unchanged (writes already require a valid
  token).
- The release workflow restores `app/src/release/google-services.json` from
  the `GOOGLE_SERVICES_RELEASE_JSON_BASE64` repository secret and fails before
  building when it is absent. A local debug build without
  `app/src/debug/google-services.json` remains deliberately local-only and the
  profile screen identifies that limitation instead of promising that a
  network retry will create a recovery code.

## Considered options

- **Plain anonymous Auth, no elevation.** Rejected: Firebase reaps
  anonymous accounts; progress keyed to them would die quietly.
- **Real email/password signup.** Rejected by the ticket: no login screens,
  ever.
- **Plaintext creds in `device_bindings`.** Rejected: public read would
  leak working credentials to anyone.
- **Server-side trust keyed to ANDROID_ID** (custom tokens minted from the
  id). Rejected: needs a trusted backend the project does not have; client
  sealing achieves the same-phone property on public APIs alone.
- **Storing everything under one shared prefs allowlist entry.** Rejected:
  the identity file IS the contract; keeping it separate makes the backup
  rules self-documenting.

## Follow-ups

- **Nickname across devices** is carried only by the credential pair's
  account record locally; a server-side `listener_profiles/{uid}` document
  would sync nicknames without auth UX (deferred until a second
  listener-owned collection actually needs it).
- **Device smoke test**: the Firebase paths are exercised by JVM fixture
  tests over the seam (fake-based ensure idempotence, codec/cipher round
  trips) — the live base and real biometric flow are verified manually on a
  device (out of scope here).
- **Account deletion** has no surface yet; when reporting/moderation land
  (spec-30 sequencing), a delete-my-profile door should join it.
