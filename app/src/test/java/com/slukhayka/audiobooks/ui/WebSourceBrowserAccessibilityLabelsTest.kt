package com.slukhayka.audiobooks.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.ui.screens.webSourceBrowserActionLabels
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WebSourceBrowserAccessibilityLabelsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun browserActionsNameTheSourceAndExactAddressInUkrainian() {
        val labels = webSourceBrowserActionLabels(
            context = context,
            sourceName = "Sluhay",
            currentAddress = "https://sluhay.com/kobzar",
            enteredAddress = "sluhay.com/lesya"
        )

        assertEquals("Закрити браузер джерела «Sluhay»", labels.closeSource)
        assertEquals("Назад у браузері джерела «Sluhay»", labels.back)
        assertEquals("Вперед у браузері джерела «Sluhay»", labels.forward)
        assertEquals("Оновити сторінку джерела «Sluhay»", labels.reload)
        assertEquals("Відкрити головну сторінку джерела «Sluhay»", labels.home)
        assertEquals(
            "Відкрити адресу https://sluhay.com/kobzar у зовнішньому браузері",
            labels.openExternal
        )
        assertEquals("Перейти до адреси sluhay.com/lesya", labels.goToAddress)
        assertEquals("Закрити повідомлення про маршрут браузера", labels.closeRouteMessage)
        assertEquals("Закрити повідомлення про мережеву помилку", labels.closeNetworkError)
    }
}
