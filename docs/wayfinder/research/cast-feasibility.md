# Cast feasibility — wayfinder ticket «Cast feasibility» (#33)

Status: resolved 2026-08-07. Go/no-go research for Chromecast support (stage 4).

## The decision

**GO, as stage-4 optional work, with the Default Media Receiver fast-track.** Casting is a natural fit for «слухати годинами» (listen on TV speakers), and Media3's first-party `media3-cast` module makes it far cheaper than the old SDK dance.

## Facts

- **Cast SDK is not deprecated.** The core `com.google.android.gms:play-services-cast-framework` (Cast v3) is actively maintained; only niche add-ons (Remote Display, GameManager) were deprecated. Google Home APIs and Matter are for smart-home automation, not media streaming — they don't replace Cast. Cast Connect is an extension for Android TV receivers, not a replacement.
- **Modern path = Media3.** `androidx.media3:media3-cast` provides `CastPlayer` (implements the standard `Player` interface) plus a local `ExoPlayer`; switching local ↔ Chromecast is one builder call and the system media notification/route transfer handles the rest:
  ```kotlin
  val castPlayer = CastPlayer.Builder(context)
      .setLocalPlayer(exoPlayer)
      .build()
  mediaSession = MediaSession.Builder(context, castPlayer).build()
  ```
- **Receivers:** the **Default Media Receiver** works with **no registration and no fee** (good enough to validate the pipeline; basic playback UI on the TV). Custom branding/chapter UI needs a Styled/Custom receiver → Cast SDK developer account, **one-time $5 fee**.
- **Testing requires a real device**: phone and Chromecast on the same Wi-Fi, device serial registered in the Cast Developer Console (≈15 min activation) for custom receivers; the default receiver doesn't need registration.

## Implications for the app

- Audiobook chapters map onto Cast's media queue; a custom receiver would be needed for chapter/bookmark display on the TV — defer that.
- Cast adds a real test surface (device in the loop) and is only meaningful for a subset of users → keep it at the back of stage 4, after Android Auto.

## Verdict

**GO — but last.** Fast-track: `media3-cast` + Default Media Receiver, no $5 account, no custom receiver. Revisit a Styled Receiver only if chapter UI on TV becomes a product ask.

Sources: Google Cast SDK docs, androidx.media3 cast extension docs, Cast Developer Console.
