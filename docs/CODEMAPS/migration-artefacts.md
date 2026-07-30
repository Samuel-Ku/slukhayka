# Migration Artefacts

<!-- Generated: 2026-07-30 | Files scanned: ~50 root scripts | Token estimate: ~900 -->

## Purpose

The repository root contains ~50 Python and JavaScript scripts left over from the ExoPlayer migration (commit `ef90563 feat(audio): migrate to ExoPlayer and add HTML import`). This codemap inventories them so future maintainers can decide what to keep, document, or delete.

## Status Legend

- 🟢 **LIVE** — still referenced by `app/src/main/` or build process
- 🟡 **ORPHAN** — was a one-shot fix, not referenced anymore; safe to delete after verification
- 🔴 **DEAD** — confirmed unused; delete

> Phase 2 audit will fill in the actual status column. This table is the inventory; the "live?" column will be confirmed during the audit.

## JS Files

| File | Size | Status | Original Purpose | Action |
|---|---|---|---|---|
| `playerjs6.js` | 292 KB | 🟡 ORPHAN? | Vendored 3rd-party player framework (referenced by 4read HTML pages) | **Verify:** search app assets/ for references; if unused, delete |
| `p.js` | 690 B | 🟡 ORPHAN | Likely dev test of playerjs6 | Delete |
| `evaluate_playerjs.js` | 1044 B | 🟡 ORPHAN | Eval harness for playerjs | Delete |

## Python Scripts — `fix_*.py` (one-shot file fixers)

| File | Size | Status | Original Purpose | Action |
|---|---|---|---|---|
| `fix_repo.py` | 5600 B | 🟡 ORPHAN | Repo-wide regex fixes | Phase 2 audit |
| `fix_repo2.py` … `fix_repo12.py` | 300-4600 B | 🟡 ORPHAN | Iterations of repo fixes | Phase 2 audit |
| `fix_repo_regex.py`, `fix_repo_regex2.py` | 1918 + 6092 B | 🟡 ORPHAN | Regex-based repo mutations | Phase 2 audit |
| `fix_manifest.py` … `fix_manifest5.py` | 261-398 B | 🟡 ORPHAN | Manifest fix iterations | Phase 2 audit |
| `fix_cookies.py`, `fix_cookies2.py` | 552-588 B | 🟡 ORPHAN | Cookie handling fixes | Phase 2 audit |
| `fix_attribution.py` | 876 B | 🟡 ORPHAN | Attribution fix | Phase 2 audit |
| `fix_beep.py` | 839 B | 🟡 ORPHAN | Audio beep fix | Phase 2 audit |
| `fix_exoplayer_headers.py` | 2045 B | 🟡 ORPHAN | ExoPlayer HTTP header fix | Phase 2 audit |
| `fix_iframe_js.py` | 1223 B | 🟡 ORPHAN | iframe JS injection fix | Phase 2 audit |
| `fix_js_interface.py` | 933 B | 🟡 ORPHAN | JS interface fix | Phase 2 audit |
| `fix_mp.py` | 828 B | 🟡 ORPHAN | MP fix | Phase 2 audit |
| `fix_newlines.py` | 719 B | 🟡 ORPHAN | Newline normalization | Phase 2 audit |
| `fix_syntax.py` | 566 B | 🟡 ORPHAN | Syntax fix | Phase 2 audit |
| `fix_webview.py` | 1596 B | 🟡 ORPHAN | WebView fix | Phase 2 audit |
| `fix_wrapper.py` | 631 B | 🟡 ORPHAN | Wrapper fix | Phase 2 audit |

## Python Scripts — `parse_*.py` / `rewrite_*.py` (AST-based code mutators)

| File | Size | Status | Original Purpose | Action |
|---|---|---|---|---|
| `parse_repo.py` | 6115 B | 🟡 ORPHAN | Parse repo structure | Phase 2 audit |
| `parse_test.py` | 2067 B | 🟡 ORPHAN | Parse test files | Phase 2 audit |
| `parse_viewmodel.py` | 710 B | 🟡 ORPHAN | Parse ViewModel | Phase 2 audit |
| `parse_webview.py` | 668 B | 🟡 ORPHAN | Parse WebView | Phase 2 audit |
| `parse_webview2.py` | 708 B | 🟡 ORPHAN | Parse WebView v2 | Phase 2 audit |
| `rewrite_prep.py` | 8586 B | 🟡 ORPHAN | Rewrite preparation | Phase 2 audit |
| `rewrite_player.py` | 866 B | 🟡 ORPHAN | Rewrite player code | Phase 2 audit |
| `rewrite_fallback.py` | 7031 B | 🟡 ORPHAN | Rewrite fallback logic | Phase 2 audit |
| `rewrite_synth.py` | 4662 B | 🟡 ORPHAN | Rewrite synthesis | Phase 2 audit |
| `refactor_player.py` | 824 B | 🟡 ORPHAN | Player refactoring | Phase 2 audit |

## Python Scripts — `test_*.py` (root-level, NOT the same as `app/src/test/`)

| File | Size | Status | Original Purpose | Action |
|---|---|---|---|---|
| `test_parse.py` | 462 B | 🟡 ORPHAN | Test parse functions | Phase 2 audit |
| `test_parse_html.py` | 4659 B | 🟡 ORPHAN | Test HTML parsing | Phase 2 audit |
| `test_remove_attr.py` | 443 B | 🟡 ORPHAN | Test attr removal | Phase 2 audit |
| `test_empty_tag.py` | 446 B | 🟡 ORPHAN | Test empty tag handling | Phase 2 audit |
| `test_wrapper.py` | 875 B | 🟡 ORPHAN | Test wrapper logic | Phase 2 audit |

## Kotlin (root-level)

| File | Size | Status | Original Purpose | Action |
|---|---|---|---|---|
| `TestEncode.kt` | 335 B | 🔴 DEAD? | Standalone test of encoding logic | Move to `app/src/test/` or delete |

## Other Root Files

| File | Purpose | Action |
|---|---|---|
| `metadata.json` | App metadata (size, version?) | Inspect, archive or commit decision |
| `.env.example` | Sample env file for secrets plugin | KEEP — used by build |
| `package.json` | npm manifest (size: 55 B — likely stub) | Inspect, may be vestigial |
| `package-lock.json` | npm lockfile | Match `package.json` decision |

## How to Determine Status (Phase 2 audit checklist)

For each file:
1. `grep -r "<basename>" app/src/main/` — is it referenced?
2. `grep -r "import" app/src/main/ | grep "<basename>"` — is it imported?
3. Check `build.gradle.kts` for tasks that invoke the script
4. Check `.gitignore` — if a script is gitignored, it's definitely dead
5. Run `git log --all -- <file>` to see if it was ever committed then removed
6. Run `git log --follow -- <file>` to see full history including renames

## Recommended Disposition (after Phase 2 audit)

- 🟢 LIVE: keep in repo with brief KDoc explaining purpose
- 🟡 ORPHAN: move to `archive/migration-2026-07-30/` and add README explaining origin
- 🔴 DEAD: `git rm` with commit `chore: remove dead post-migration scripts`

## Note

Even after cleanup, preserve the **commit history** — `git log --follow` and `git log --all -- <path>` will tell future maintainers what each script did. Do NOT force-push or rewrite history.
