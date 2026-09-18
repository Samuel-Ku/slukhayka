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

    /** Every state goes to a FILE too: logcat rotates and loses the early steps. */
    private fun log(line: String) {
        println(line)
        runCatching {
            java.io.File(context.filesDir, "tdlib-spike.log").appendText(line + "\n")
        }
    }

    private lateinit var context: android.content.Context

    /**
     * Sends a request and REPORTS a refusal: TDLib answers errors as
     * `TdApi.Error`, and a silent rejection (an expired code, a wrong password)
     * otherwise looks exactly like "still waiting".
     */
    private fun send(label: String, function: TdApi.Function<*>) {
        client.send(function) { result ->
            if (result is TdApi.Error) {
                log("SPIKE tdlib: $label REFUSED — ${result.code}: ${result.message}")
            } else {
                log("SPIKE tdlib: $label accepted")
            }
        }
    }

    @Test
    fun the_device_can_log_in_with_the_listeners_own_account() {
        assumeTrue("needs api_id/api_hash/phone", apiId != null && !apiHash.isNullOrBlank() && !phone.isNullOrBlank())

        context = InstrumentationRegistry.getInstrumentation().targetContext
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
                    send("parameters", parameters)
                    log("SPIKE tdlib: WaitTdlibParameters → parameters sent")
                }

                is TdApi.AuthorizationStateWaitPhoneNumber -> {
                    val settings = TdApi.PhoneNumberAuthenticationSettings()
                    settings.allowFlashCall = false
                    settings.allowMissedCall = false
                    settings.isCurrentPhoneNumber = false
                    settings.allowSmsRetrieverApi = false
                    send("phone", TdApi.SetAuthenticationPhoneNumber(phone, settings))
                }

                is TdApi.AuthorizationStateWaitCode -> {
                    if (!code.isNullOrBlank()) {
                        send("code", TdApi.CheckAuthenticationCode(code))
                    } else {
                        // The host may not know the code when this run starts: the
                        // owner reads it from Telegram while the attempt WAITS.
                        // So the state stays alive and watches a file the host can
                        // write from outside (adb → run-as), and the code is used
                        // by THIS attempt — no second phone request, no new code.
                        log("SPIKE tdlib: WAIT_CODE — чекаю код у files/tdlib-code.txt")
                        Thread {
                            repeat(90) {
                                val value = runCatching {
                                    java.io.File(context.filesDir, "tdlib-code.txt")
                                        .readText().trim()
                                }.getOrNull()
                                if (!value.isNullOrBlank()) {
                                    log("SPIKE tdlib: код з файлу, відправляю")
                                    send("code", TdApi.CheckAuthenticationCode(value))
                                    return@Thread
                                }
                                Thread.sleep(3_000)
                            }
                            log("SPIKE tdlib: код так і не зʼявився у файлі")
                        }.start()
                    }
                }

                is TdApi.AuthorizationStateWaitPassword -> {
                    if (password.isNullOrBlank()) {
                        log("SPIKE tdlib: WAIT_PASSWORD — rerun with …arguments.password=<2FA password>")
                    } else {
                        send("password", TdApi.CheckAuthenticationPassword(password))
                    }
                }

                is TdApi.AuthorizationStateReady -> {
                    log("SPIKE tdlib: READY — the session is local to this device")
                    // AC: the listener can always leave. Proven here, not promised.
                    send("logout", TdApi.LogOut())
                }

                is TdApi.AuthorizationStateClosed -> {
                    log("SPIKE tdlib: CLOSED — the local session is gone")
                    ready.countDown()
                }

                else -> log("SPIKE tdlib: state=${state.javaClass.simpleName}")
            }
        }

        client = Client.create(handler, { e ->
            failure = e.message
            log("SPIKE tdlib: handler exception ${e.message}")
        }, { e ->
            failure = e.message
            log("SPIKE tdlib: exception ${e.message}")
        })

        val finished = ready.await(5, TimeUnit.MINUTES)
        runCatching { client.send(TdApi.Close(), Client.ResultHandler { }) }
        assertTrue(
            "the spike must reach a terminal state; see files/tdlib-spike.log (failure=$failure)",
            finished
        )
    }

    private companion object {
        lateinit var client: Client
    }
}
