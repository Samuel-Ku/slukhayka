---
status: accepted
---

# Web mirrors Listening State only, after deliberate linking (spec-43)

The Web Client (ADR-0024's surface) made cross-device progress possible — an
iPhone listener starts a book in Safari and continues on Android. But until
now NOTHING personal ever left a device: the README promises «історія
прослуховування зберігається на телефоні», and the shared base holds only
anonymous crowd facts plus reviews. Uploading progress by default would break
that promise silently. We decided the narrowest honest version of "one
profile everywhere": **Progress Sync** carries ONLY the Listening State row
(position, chapter index, completion, preferredSpeed per Edition), it begins
only when the listener deliberately links a device by entering their Recovery
Code, it never carries Library Entries, Metadata Overrides or Tombstones, and
a visible switch turns it off.

## Decision

1. **Consent is the act of linking.** An unlinked Web Client uploads nothing
   about its listener — same silent-anonymous posture as Android. Entering a
   Recovery Code IS the opt-in; no second toggle stands between the listener
   and sync except the off-switch afterwards.
   **Scope of this clause:** it governs LINKED CLIENTS — surfaces that joined
   an existing profile through a deliberate act. The Android device where the
   silent identity was first created (ADR-0021) is the profile's ORIGIN, not
   a client joining it: its uploads are the listener acting through their own
   already-existing account, so Android ships with sync ON and the visible
   ⚙️ Профіль switch is the consent surface there (spec-43 T6). A fresh
   Android install without restore mints a NEW local uid and pushes only to
   that empty account of its own — strangers' rows never meet.
2. **One document per listener-Edition pair** (`listening_state/{uid}_{editionId}`)
   holding the Listening State mirror plus a SERVER timestamp. Conflicts
   resolve last-write-wins by that server time — device clocks never
   arbitrate. Smart Rewind stays a local playback rule (ADR-0003) and never
   enters the sync payload's semantics.
3. **Both platforms ride it.** Android gains the upload/download path around
   its existing Room Listening State; the web client writes through the same
   collection under the same App Check enforcement (web reCAPTCHA provider).
4. **Honest copy.** The README/privacy text changes from «зберігається на
   телефоні» to the precise truth: progress mirrors between YOUR linked
   devices only, nothing else personal leaves a device.

## Consequences

- The felt need («почав на телефоні — продовж у браузері») works without
  opening the #290 recommendation-upload gates or any library-wide sync.
- Library Entries stay device-local on web for now; «обране» differs across
  platforms until a later spec extends Progress Sync deliberately.
- New Firestore collection + rules join the App Check envelope; anonymous
  unlinked browsers remain write-nothing readers of public crowd data.

## Considered options

- **Sync-on-by-default for every anonymous profile.** Rejected: maximum
  convenience against the project's core privacy stance; uploads would start
  before any deliberate listener act.
- **Furthest-position-wins conflict rule.** Rejected: hides real state,
  resurrects completed books, surprises the listener who deliberately
  rewound.
- **Ask-the-user conflict dialog.** Rejected: interrupts the resume moment;
  LWW matches how listeners actually think («де я зупинявся останнім»).
