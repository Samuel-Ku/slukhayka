# Domain docs

This is a single-context repository.

Before exploring or changing the codebase, read:

- root `CONTEXT.md` for the ubiquitous language;
- the decision under `docs/adr/` that the task touches — its concept, its
  rules, or a reference in the diff — rather than the whole directory;
- `CONTEXT-MAP.md` instead if a future multi-context layout introduces it.

Use glossary terms exactly and avoid synonyms that `CONTEXT.md` rejects. If a
proposal contradicts an ADR, surface the conflict explicitly rather than
silently overriding it. Missing domain documents are not blockers; proceed
silently until a real modeling decision requires them.
