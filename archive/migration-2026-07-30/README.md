# Migration Artefacts — ExoPlayer migration (2026-07-30)

One-off Python/JS scripts and debug files left over from the ExoPlayer
migration (commit `ef90563 feat(audio): migrate to ExoPlayer and add HTML
import`). Moved here on 2026-08-10 so the repository root stays clean.

**None of these files are referenced by `app/`, the build, or CI.** They are
kept only for reference. Delete this directory (`git rm -r archive/`) whenever
you are confident they are no longer needed.

## Contents

### JS
- `playerjs6.js` — vendored 3rd-party player framework (used by 4read HTML pages, not by the app)
- `p.js`, `evaluate_playerjs.js` — puppeteer dev harnesses for playerjs6

### Python — `fix_*.py` (one-shot file fixers)
- `fix_repo.py` … `fix_repo12.py`, `fix_repo_regex.py`, `fix_repo_regex2.py` — repo-wide regex fixes
- `fix_manifest.py` … `fix_manifest5.py` — manifest fix iterations
- `fix_cookies.py`, `fix_cookies2.py` — cookie handling fixes
- `fix_attribution.py`, `fix_beep.py`, `fix_exoplayer_headers.py`, `fix_iframe_js.py`,
  `fix_js_interface.py`, `fix_mp.py`, `fix_newlines.py`, `fix_syntax.py`,
  `fix_webview.py`, `fix_wrapper.py` — assorted WebView/audio fixes

### Python — `parse_*.py` / `rewrite_*.py` (AST-based code mutators)
- `parse_repo.py`, `parse_test.py`, `parse_viewmodel.py`, `parse_webview.py`, `parse_webview2.py`
- `rewrite_prep.py`, `rewrite_player.py`, `rewrite_fallback.py`, `rewrite_synth.py`
- `refactor_player.py`

### Python — `test_*.py` (root-level, NOT `app/src/test/`)
- `test_parse.py`, `test_parse_html.py`, `test_remove_attr.py`, `test_empty_tag.py`, `test_wrapper.py`

### Other
- `TestEncode.kt` — standalone encoding test
- `4read.html` — local debug copy of the site's HTML (never loaded by the app)
- `metadata.json` — Freebuff app metadata (6 lines)
- `package.json`, `package-lock.json` — npm stub; sole dependency (puppeteer) was only used by `p.js`

## Origin inventory

See `docs/CODEMAPS/migration-artefacts.md` for the per-file inventory with
sizes and statuses. Git history (`git log --follow -- <path>`) documents what
each script did.
