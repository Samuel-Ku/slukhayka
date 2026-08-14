# [Spec] Rebrand: «Слухайка» — the app is no longer only 4read.org — 2026-08-11

> **Status:** Approved — grilling session 2026-08-11 («rebranding, тепер вже не тільки 4read.org»); scope, name and icon decided with the user.
> **Tracker:** filed as `spec-12` issues (`spec-12` + `ready-for-agent` labels).

## Problem Statement

The product has outgrown its name. The app started as a player for the single source 4read.org, but since spec-10 M1 it aggregates **5+ Ukrainian audiobook sources** (4read, sound-books.net, audiobook-mp3.com/uk, lihtar.in.ua, sluhay.com.ua — with the WebView-pattern sources sluhay.com / sluhayknigi.com charted next) plus the user's own local files. The launcher still says **«4Read Audiobooks»** and the launcher icon is 4read-derived — the user-facing identity promises a single-source product while the app delivers a unified multi-source library. From the user's perspective: **«На іконці 4read, а в застосунку книги з п'яти джерел — який це продукт?»**

## Solution

Rebrand the app to a neutral, source-independent identity: **«Слухайка»**. The brand lives in exactly two places — the launcher label and the launcher icon; both change. The source badge «4read» in search results stays, because it names the *website*, not the app (like the «Sound-Books», «Sluhay», «Lihtar» badges). The user sees one calm product identity that no longer implies a single source.

## User Stories

1. As a listener, I want the app to be named independently of any single source, so that its identity matches what it actually does — an aggregator.
2. As a listener, I want a Ukrainian, calm app name, so that the product feels native to its content language.
3. As a listener, I want the launcher icon to match the new name, so that a 4read logo does not contradict the identity.
4. As a listener, I want the name change to keep the installed app's data, so that I do not lose my library, positions or bookmarks.
5. As a listener, I want the media notification and app attribution to show the new name, so that the identity is consistent everywhere the name appears.
6. As a listener, I want search-result badges to keep naming the *source site* («4read», «Sound-Books», «Sluhay»…), so that I can still tell where a book comes from.
7. As a maintainer, I want the rename to touch only the user-facing brand surface, so that source ids, class names, URLs and the database stay untouched.
8. As a maintainer, I want the icon regenerated for every launcher density, so that devices on API 24–25 (no adaptive icons) also get the new identity.
9. As a maintainer, I want the existing app-name assertion updated with the rename, so that the test suite stays green.

## Implementation Decisions

- **New app name: «Слухайка»** — chosen by the user over «Оповідь» / «Моя аудіокнига» / «Камертон»; short, Ukrainian, calm, and deliberately NOT sharing the root «слух-» with the sluhay/sluhayua *sources* was considered, but the user's choice stands. Known UI footnote: the source badge «Sluhay» and the name «Слухайка» both contain «слух/слух» — acceptable; if it ever feels confusing, a follow-up can disambiguate badge copy.
- **Single brand surface.** The app name is one string resource consumed by the launcher label, the app attribution and the media notification — no other user-facing copy mentions the brand (all UI text is hardcoded in Compose and carries no «4read»). Legacy placeholder authors («4read.org», «Аудиокнига 4read.org») are already filtered from display and stay untouched.
- **Icon: new vector art, generated in-repo.** «Book + sound wave» glyph in the brand accent `#E9A13B` (warm amber — the single design-system accent) on the dark graphite background, matching the dark-primary design direction (wayfinder #23). Adaptive foreground/background (API 26+) plus legacy raster webps for API 24–25 regenerated from the same art with PIL + cwebp at the standard densities (mdpi–xxxhdpi, 48–192 px, round included).
- **Non-goals (locked in the grilling):** applicationId stays `com.aistudio.audiobook.read` (already neutral — changing it pre-release adds risk for zero user value); repo name `slukhayka` stays (GitHub history); source id `4read`, `FourReadAdapter`, `FourReadWebScreen`, all `4read.org` URLs stay (functional); the «4read» source badge stays (factual site label); the source-badge display-name mapping is untouched.
- **English name:** deferred — added when a store listing exists; the code ships Ukrainian-only today.

## Testing Decisions

- **One seam: the resource/string seam.** The existing `ExampleRobolectricTest` reads the app label via `getString(R.string.app_name)` — its assertion is updated from «4Read Audiobooks» to «Слухайка», pinning the rename at the highest available seam (the only test that observes the brand). No new seam is justified for a name + icon change.
- **Icon:** a launcher icon is a visual artifact with no JVM-renderable seam — verified by `assembleDebug` (resources compile) and a manual launcher check on the OnePlus 8 Pro (device check), not by unit tests.
- Prior art: `ExampleRobolectricTest` (string read); device-check convention from spec-8/spec-10 (`docs/phone-test/PLAN.md`).

## Out of Scope

- **Application id change** — stays `com.aistudio.audiobook.read`.
- **Repo rename** — stays `slukhayka`.
- **Source badge copy** — «4read» badge in search results and the `sourceDisplayName` mapping stay.
- **Legacy placeholder cleanup** — DB rows with «4read.org» placeholder authors stay (already display-filtered).
- **English/localized app name** — deferred to store listing.
- **Icon polish iterations** — the generated vector is v1; art direction can iterate later.

## Further Notes

- Decided in the 2026-08-11 grilling session (scope → name → icon). Facts gathered from the codebase: the user-facing brand surface is exactly `app_name` + the launcher icon; the package name, source ids and all 4read.org URLs are functional, not brand.
- Design anchor: wayfinder #23 design system — dark graphite-navy primary, warm amber accent, calm editorial style; the icon follows it.
