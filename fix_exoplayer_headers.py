import re

with open("app/src/main/java/com/example/player/AudioPlayerManager.kt", "r") as f:
    content = f.read()

# Add imports
imports_to_add = """
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
"""

if "DefaultHttpDataSource" not in content:
    content = content.replace("import androidx.media3.exoplayer.ExoPlayer", imports_to_add + "import androidx.media3.exoplayer.ExoPlayer")

# Modify prepareChapter Exoplayer building
old_builder_1 = "val mp = ExoPlayer.Builder(context).build().apply {"
new_builder_1 = """val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .setDefaultRequestProperties(mapOf("Referer" to "https://4read.org/"))
            val mp = ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .build().apply {"""

content = content.replace(old_builder_1, new_builder_1)

# Modify tryFallbackPlayback Exoplayer building
old_builder_2 = "val fallbackMp = ExoPlayer.Builder(context).build().apply {"
new_builder_2 = """val fallbackDataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .setDefaultRequestProperties(mapOf("Referer" to "https://archive.org/"))
            val fallbackMp = ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(fallbackDataSourceFactory))
                .build().apply {"""

content = content.replace(old_builder_2, new_builder_2)

# Also increase timeout from 15s to 45s
content = content.replace("delay(15000L)", "delay(45000L)")
content = content.replace("(15s)", "(45s)")

with open("app/src/main/java/com/example/player/AudioPlayerManager.kt", "w") as f:
    f.write(content)
