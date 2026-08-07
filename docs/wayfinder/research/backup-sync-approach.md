# Backup & sync approach — wayfinder ticket «Backup & sync approach» (#35)

Status: resolved 2026-08-07. Decision for «нічого не губиться після перезапуску/перевстановлення» (stage 4).

## The decision

Adopt **two layers, no backend**:
1. **Auto Backup for Apps** (cloud + device-to-device) for all small data — free, zero code beyond manifest rules.
2. **Manual export/import via SAF** for a full backup the user owns (Room dump + list of imported folders).

**Cloud sync (own backend / Drive API) is ruled OUT of scope for this map** — it needs accounts, server ops, and conflict resolution; not needed to satisfy "nothing gets lost".

## What Auto Backup covers (facts)

- Enabled by `android:allowBackup="true"`; on Android 12+ configured via a **`dataExtractionRules` XML** with two sections: `<cloud-backup>` and `<device-transfer>` (include/exclude per domain).
- **Room DB is in `databases/` → included by default.** Books, chapters, positions, bookmarks, stats are tiny (tens of KB) — comfortably inside limits.
- Cloud backup has a **~25 MB per-app cap**; files over the cap and large media are dropped or excluded. The app's **audio files in `filesDir` exceed this** — they are **not** a reliable cloud-backup payload, and they shouldn't be (they're the user's own files).
- SAF-picked folder access: persisted URI permissions (`takePersistableUriPermission`) survive app updates but **do not survive uninstall** — after a reinstall the user re-picks the folder and the app re-scans (this is exactly the «Повторне сканування» flow, ticket «Re-scan, duplicates & missing files»).

## What the app should do (stage-4 ticket)

1. Add `dataExtractionRules` (cloud-backup + device-transfer) explicitly including the Room DB and settings; no exclusion needed for audio (it exceeds the cap anyway).
2. Add a **«Експортувати резервну копію»** action (SAF): write a JSON/Room dump + the list of imported folder URIs/titles into a user-chosen location; **«Імпортувати»** restores it and prompts to re-pick each folder.
3. Verify restore on a fresh install (reinstall → DB restored → folder re-picked → positions intact).

## Verdict

**GO, cheap and high-value.** Auto Backup alone gives «reinstall/перезапуск → позиції, закладки, медіатека повернулись» with almost no code; the SAF export closes the gap for deliberate full backups. Cloud sync stays off the map.

Sources: developer.android.com — Backup for Apps / dataExtractionRules / Auto Backup.
