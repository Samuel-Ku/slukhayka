package com.slukhayka.audiobooks.accessibility

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import com.slukhayka.audiobooks.MainActivity
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.ui.screens.BookDetailIdentityHeader
import com.slukhayka.audiobooks.ui.screens.bookDetailPresentation
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import java.io.File
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModelProvider
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.components.applySourceCoverHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Pixel evidence at the production header: edge bands survive without stretching. */
class BookDetailCoverLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun portraitAndSquareCoversKeepTheirEdgesAndMissingCoverIsVisible() {
        val book = AudiobookEntity(id = "cover-layout", title = "Книга без обкладинки", author = "Автор", narrator = "", description = "",
            coverDrawableRes = 0, genre = "", sourceUrl = "https://example.invalid/book")
        val shown = mutableStateOf(book)
        rule.runOnUiThread {
            rule.activity.setContent {
                AudiobookTheme(darkTheme = true) {
                    Surface {
                        Column {
                            BookDetailIdentityHeader(shown.value, bookDetailPresentation(shown.value, emptyList(), emptyList()),
                                requestInitialFocus = false)
                        }
                    }
                }
            }
        }
        for ((width, height) in listOf(200 to 400, 200 to 200)) {
            val file = File(rule.activity.cacheDir, "cover-layout-$width-$height.png")
            try {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(if (width == height) AndroidColor.GREEN else AndroidColor.BLUE)
                val paint = Paint().apply { color = AndroidColor.RED }
                canvas.drawRect(0f, 0f, width.toFloat(), 20f, paint)
                canvas.drawRect(0f, (height - 20).toFloat(), width.toFloat(), height.toFloat(), paint)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                rule.runOnUiThread { shown.value = book.copy(coverImageUrl = file.toURI().toString()) }
                rule.waitForIdle()
                // Distinct centers prevent the previous image satisfying the next load.
                rule.waitUntil(10_000) {
                    val pixels = rule.onNodeWithTag("book_detail_cover").captureToImage().toPixelMap()
                    val center = pixels[pixels.width / 2, pixels.height / 2]
                    if (width == height) center.green > 0.9f && center.blue < 0.1f
                    else center.blue > 0.9f && center.green < 0.1f
                }
                val image = rule.onNodeWithTag("book_detail_cover").captureToImage()
                val pixels = image.toPixelMap()
                val x = pixels.width / 2
                val redRows = (0 until pixels.height).filter { y ->
                    val color = pixels[x, y]
                    color.red > 0.8f && color.blue < 0.2f
                }
                assertTrue("both original edge bands must survive for $width x $height", redRows.any { it < pixels.height / 3 } && redRows.any { it > pixels.height * 2 / 3 })
                val displayedHeight = redRows.last() - redRows.first() + 1
                val expectedHeight = if (width == height) pixels.width else pixels.height
                assertTrue("cover aspect ratio must be preserved", kotlin.math.abs(displayedHeight - expectedHeight) <= 4)
                saveScreen("553-cover-$width-$height.png")
            } finally {
                file.delete()
            }
        }
        rule.runOnUiThread { shown.value = book }
        rule.waitForIdle()
        rule.onNodeWithTag("book_detail_cover").assertExists()
        saveScreen("553-cover-missing.png")
    }

    @Test fun inspectSavedBookOriginalCover() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveCover") == "true")
        val vm = ViewModelProvider(rule.activity)[MainViewModel::class.java]
        rule.waitUntil(20_000) { vm.libraryBooks.value.any { it.book.title == "Трохи ненависті" } }
        val book = vm.libraryBooks.value.first { it.book.title == "Трохи ненависті" }.book
        val url = requireNotNull(book.coverImageUrl)
        val result = runBlocking(Dispatchers.IO) {
            rule.activity.imageLoader.execute(ImageRequest.Builder(rule.activity)
                .data(url).applySourceCoverHeaders(url).size(Size.ORIGINAL).allowHardware(false).build())
        }
        assertTrue("original cover must load", result is SuccessResult)
        val original = (result as SuccessResult).drawable.toBitmap()
        File(rule.activity.getExternalFilesDir(null), "553-original-cover.png").outputStream().use {
            original.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        File(rule.activity.getExternalFilesDir(null), "553-original-cover.txt").writeText(
            "${original.width} x ${original.height}\n$url\n"
        )
        // Inspect the real book page without starting playback or editing saved data.
        rule.runOnUiThread { vm.selectBook(book.id) }
        rule.onNodeWithTag("book_detail_cover").assertExists()
        rule.waitForIdle()
        saveScreen("553-live-book.png")
    }

    private fun saveScreen(name: String) {
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(rule.activity.getExternalFilesDir(null), name).outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }
}
