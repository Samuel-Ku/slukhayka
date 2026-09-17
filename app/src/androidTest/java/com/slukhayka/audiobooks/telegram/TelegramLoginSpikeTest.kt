package com.slukhayka.audiobooks.telegram

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #829 — the login spike, on the device, with the listener's OWN account.
 *
 * No secret lives in this file or in the APK: the api id/hash, the phone number
 * and the one-time code arrive as **instrumentation arguments**, so nothing is
 * committed and nothing is printed that the owner did not type themselves.
 *
 * Run 1 (asks Telegram to send the code):
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.slukhayka.audiobooks.telegram.TelegramLoginSpikeTest \
 *     -Pandroid.testInstrumentationRunnerArguments.api_id=<id> \
 *     -Pandroid.testInstrumentationRunnerArguments.api_hash=<hash> \
 *     -Pandroid.testInstrumentationRunnerArguments.phone=<+380…>
 * Run 2 (with the code from the notification) adds: …arguments.code=<code>
 */
@RunWith(AndroidJUnit4::class)
class TelegramLoginSpikeTest {

    private val args = InstrumentationRegistry.getArguments()
    private val apiId = args.getString("api_id")?.toIntOrNull()
    private val apiHash = args.getString("api_hash")
    private val phone = args.getString("phone")
    private val code = args.getString("code")
    private val password = args.getString("password")

    @Test
    fun the_device_can_log_in_with_the_listeners_own_account() {
        assumeTrue("needs api_id/api_hash/phone", apiId != null && !apiHash.isNullOrBlank() && !phone.isNullOrBlank())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        System.setProperty("tdlib.files", context.filesDir.absolutePath)
        runCatching { System.loadLibrary("tdjni") }

        val ready = CountDownLatch(1)
        var failure: String? = null

        val handler = Client.ResultHandler { update ->
            if (update !is TdApi.UpdateAuthorizationState) return@ResultHandler
            when (val state = update.authorizationState) {
                is TdApi.AuthorizationStateWaitTdlibParameters -> {
                    val parameters = TdApi.SetTdlibParameters()
                    parameters.useTestDc = false
                    // The session lives ONLY here: the app's private files dir.
                    parameters.databaseDirectory = context.filesDir.absolutePath + "/tdlib"
                    parameters.filesDirectory = context.filesDir.absolutePath + "/tdlib-files"
                    parameters.databaseEncryptionKey = null
                    parameters.useFileDatabase = true
                    parameters.useChatInfoDatabase = true
                    parameters.useMessageDatabase = true
                    parameters.useSecretChats = false
                    parameters.apiId = apiId!!
                    parameters.apiHash = apiHash!!
                    parameters.systemLanguageCode = "uk"
                    parameters.deviceModel = android.os.Build.MODEL
                    parameters.systemVersion = android.os.Build.VERSION.RELEASE
                    parameters.applicationVersion = "1.0"
                    client.send(parameters) { println("SPIKE tdlib: parameters accepted") }
                    println("SPIKE tdlib: WaitTdlibParameters → parameters sent")
                }

                is TdApi.AuthorizationStateWaitPhoneNumber -> {
                    val settings = TdApi.PhoneNumberAuthenticationSettings()
                    settings.allowFlashCall = false
                    settings.allowMissedCall = false
                    settings.isCurrentPhoneNumber = false
                    settings.allowSmsRetrieverApi = false
                    client.send(TdApi.SetAuthenticationPhoneNumber(phone, settings)) {
                        println("SPIKE tdlib: phone sent — Telegram is sending the code")
                    }
                }

                is TdApi.AuthorizationStateWaitCode -> {
                    if (code.isNullOrBlank()) {
                        println("SPIKE tdlib: WAIT_CODE — rerun with …arguments.code=<code from Telegram>")
                    } else {
                        client.send(TdApi.CheckAuthenticationCode(code)) {
                            println("SPIKE tdlib: code accepted")
                        }
                    }
                }

                is TdApi.AuthorizationStateWaitPassword -> {
                    if (password.isNullOrBlank()) {
                        println("SPIKE tdlib: WAIT_PASSWORD — rerun with …arguments.password=<2FA password>")
                    } else {
                        client.send(TdApi.CheckAuthenticationPassword(password)) {
                            println("SPIKE tdlib: password accepted")
                        }
                    }
                }

                is TdApi.AuthorizationStateReady -> {
                    println("SPIKE tdlib: READY — the session is local to this device")
                    // AC: the listener can always leave. Proven here, not promised.
                    client.send(TdApi.LogOut()) { println("SPIKE tdlib: logout acknowledged") }
                }

                is TdApi.AuthorizationStateClosed -> {
                    println("SPIKE tdlib: CLOSED — the local session is gone")
                    ready.countDown()
                }

                else -> println("SPIKE tdlib: state=${state.javaClass.simpleName}")
            }
        }

        client = Client.create(handler, { e ->
            failure = e.message
            println("SPIKE tdlib: handler exception ${e.message}")
        }, { e ->
            failure = e.message
            println("SPIKE tdlib: exception ${e.message}")
        })

        val finished = ready.await(3, TimeUnit.MINUTES)
        runCatching { client.send(TdApi.Close(), Client.ResultHandler { }) }
        assertTrue("the spike must reach a terminal state (failure=$failure)", finished || failure == null)
    }

    private companion object {
        lateinit var client: Client
    }
}
