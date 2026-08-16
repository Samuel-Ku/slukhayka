# Repository carries no internal QA artifacts

The repository is a public open-source showcase, but its tree carried ~305 MB
of internal QA evidence — device screenshots (`audit/`, `docs/phone-test/`),
one-off migration scripts (`archive/migration-2026-07-30/`). We removed all of
them: audit conclusions live as markdown in `docs/audits/`, and every removed
file remains recoverable from git history. The stale codemaps were removed with
them, then regenerated fresh from current HEAD as `docs/CODEMAPS/` (8 modules,
119 files) — codemaps are worth keeping as long as they track the code; each
file carries its scan date and line counts so staleness is visible. Some recent
docs still reference the removed paths (`audit/screens-39105/`, `audit/round2/`)
— that provenance is intentional; the evidence itself is in history, not in
every clone.

Future audit rounds commit conclusions as markdown only. Screenshots and
binary evidence stay out of the repository unless a case for keeping them
outweighs the clone weight. Codemaps stay a markdown-only artifact with a
visible freshness header, regenerated when the module-to-file mapping drifts.
