# Repository carries no internal QA artifacts

The repository is a public open-source showcase, but its tree carried ~305 MB
of internal QA evidence — device screenshots (`audit/`, `docs/phone-test/`),
one-off migration scripts (`archive/migration-2026-07-30/`). We removed all of
them from the tree: audit conclusions live as markdown in `docs/audits/`, and
the binary blobs were then purged from git history entirely (`git filter-repo`,
2026-08-16 — paths `audit/`, `archive/`, `docs/phone-test/` binaries, plus any
blob above 5 MB): a fresh clone's `.git` now weighs ~18 MB instead of ~300 MB.
The stale codemaps were removed with the tree cleanup, then regenerated fresh
from current HEAD as `docs/CODEMAPS/` (8 modules, 119 files) — codemaps are
worth keeping as long as they track the code; each file carries its scan date
and line counts so staleness is visible. Some recent docs still reference the
removed paths (`audit/screens-39105/`, `audit/round2/`) — that provenance is
intentional; the evidence itself is not in the repository at all. The
force-push landed the same day (2026-08-16): `main` was replaced and the
release tag `v1.0` re-pointed to its rewritten equivalent commit so no ref
keeps the old blobs alive — a fresh clone now weighs 36 MB on disk (18 MB
tree + 18 MB `.git`; GitHub repo size ~18 MB). The pre-rewrite history (all
~305 MB of evidence included) survives only in the maintainer's local mirror
backup (`/tmp/slukhayka-backup-2026-08-16.git`), deleted after collaborators
have re-cloned (see `docs/history-cleanup-force-push.md`).

Future audit rounds commit conclusions as markdown only. Screenshots and
binary evidence stay out of the repository unless a case for keeping them
outweighs the clone weight. Codemaps stay a markdown-only artifact with a
visible freshness header, regenerated when the module-to-file mapping drifts.
