---
status: accepted
---

# Web transport parses sources server-side, audio goes direct-first (spec-43)

A browser cannot do what the Android WebView sessions do: reading a Source
page's HTML cross-origin is blocked by CORS, the per-source Referer/UA rules
and DoH routing have no web equivalents, and the listener's privacy route
(Tor/proxy, spec-38) cannot be honoured from a page. Yet streaming the audio
itself through our infrastructure forever would make the free product pay
for every minute listened. We decided on a split transport: a thin Cloudflare
Worker resolves Source pages into structured catalog/book JSON (the six
adapters ported beside the Kotlin ones), while playback points `<audio>`
straight at the Source — and the SAME worker relays a stream ONLY when the
Source refuses direct hotlinking.

## Decision

1. **HTML→JSON at the edge.** One Worker owns source-page fetching and
   parsing; the Web Client never fetches a Source origin directly. Adapter
   fixtures and parsers are ported so both runtimes agree on one catalog
   interpretation.
2. **Direct-first audio, relay-as-fallback.** The player tries the Source's
   own stream URL first (no CORS needed for media elements). If the Source
   blocks it (hotlink/referer checks) the player switches that stream to the
   Worker relay. An exhausted fallback surfaces the honest «Книга
   недоступна» state exactly like StreamHealPolicy (ADR-0019) — never a
   fabricated retry loop.
3. **Cloudflare, not Firebase Functions/Vercel, for this layer.** The relay
   is bandwidth-shaped work; Workers' free tier does not bill it and their
   fair-use welcomes transformation traffic, while Vercel restricts media
   proxying and Firebase Functions bills egress. Identity/data stay on
   Firebase untouched.
4. **Stated limitation.** The web has no privacy route: requests come from
   the listener's browser (audio) and from the Worker (page resolution).
   This is recorded honestly in the web privacy text rather than papered
   over.

## Consequences

- Catalog freshness, self-healing re-resolution and index pairing move to
  where page fetching happens; the Web Client consumes plain structured
  data and stays thin.
- Relay traffic grows only with the share of Sources that refuse direct
  playback — measured by the validation prototype before any scale worry.
- Two clouds appear in the stack (Firebase data/identity + Cloudflare
  transport/hosting); accepted deliberately for the economics.

## Considered options

- **Everything through our proxy.** Rejected as default: every listened
  minute becomes our bandwidth bill; kept only as per-stream fallback.
- **Public CORS proxies / client-side hacks.** Rejected: unreliable,
  third-party-visible, hostile to the privacy position.
- **All-Firebase deployment.** Rejected for this layer on cost and fair-use
  grounds above; Firebase remains the identity/data system of record.
