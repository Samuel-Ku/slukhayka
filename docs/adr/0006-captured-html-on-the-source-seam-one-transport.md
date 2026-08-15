---
status: accepted
---

# Captured-HTML import rides the Source seam; one HTTP transport

Parsing a captured WebView page is a Source capability, so it lives on the Source adapter interface as an optional method with a default of "not mine" — sources without a WebView import door implement nothing. The two concrete implementations (the primary catalog source and the sluhay source) override it under one name; the import doors stop downcasting to concrete classes. All HTTP goes through the shared fetcher: it gains a binary stream method with the same degrade-never-throw convention as its text method, and the offline download loop becomes its client. The offline-download user agent moves to the download policy beside the already-centralized per-source header rules.

## Consequences

A future WebView-pattern source works through the same captured-page door with no changes outside its own adapter. The transport seam keeps two adapters (the real JVM fetcher and the fixture fake); the fake gains a stream override serving in-memory bytes. The repository holds no HTTP code of its own.
