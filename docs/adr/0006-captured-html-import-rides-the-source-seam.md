---
status: accepted
---

# Captured-HTML import rides the Source seam; one HTTP transport

The multi-source architecture gave every source a [SourceAdapter] seam, but
two of the seam's consumers still reached past it. The captured-page import
doors (the WebView-pattern sources 4read and sluhay, whose book pages only
come from the live browser session past Cloudflare) downcast the adapter to
the concrete `FourReadAdapter` / `SluhayAdapter` classes and called two
differently-named parse methods (`parseCapturedPage` vs
`detailFromCapturedHtml`) — a future WebView-pattern source would need a
third name, a third cast, and a third branch in the import module. And the
offline download loop ran its own `HttpURLConnection` with its own
user-agent constant, duplicating the request setup the shared
[HttpFetcher] already owns.

## Decision

**Captured-page import is a Source-adapter capability.** [SourceAdapter]
carries one optional method, `parseCapturedPage(html, url)`, with a default
that means "not mine" (null). The two WebView-pattern adapters — 4read and
sluhay — override it under this one name; every other source inherits the
default. Both captured-page doors in the Library Import module call the
interface method; no import door downcasts an adapter to a concrete class.
A future WebView-pattern source works through the same doors with no changes
outside its adapter.

**One HTTP transport.** [HttpFetcher] gains a binary stream method,
`getStream(url, extraHeaders)`, with the same degrade-never-throw convention
as its text method (null on any failure). The offline download loop becomes
its client — it performs no HTTP of its own; the per-source Referer rules
(`headersFor`) ride along as extra headers. The offline user agent moves to
the download policy (`DownloadPolicy.OFFLINE_USER_AGENT`) beside those
per-source header rules, and the fetcher the download path uses is
constructed with it.

## Consequences

- The import module has no knowledge of any concrete adapter: both
  captured-page doors (`importWebSourcePage`, `importAudiobookFromHtml`) are
  one interface call over the source seam.
- A non-WebView source's default `parseCapturedPage` returns null and the
  door surfaces "nothing playable" — identical to the old cast-to-null
  behavior, without the cast.
- The download loop and the adapters share one HTTP stack, one timeout
  policy and one failure convention; the offline user agent lives beside the
  per-source header policy instead of in the module that happened to use it.
- Fixture tests pin the seam: the adapter tests exercise
  `parseCapturedPage` through the `SourceAdapter` interface for both
  WebView sources (existing HTML-fixture pattern), and the download-path
  test serves in-memory bytes from a fake `getStream` — the real loop runs
  with no network.
