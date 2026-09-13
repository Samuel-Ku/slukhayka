package com.slukhayka.audiobooks.spike

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/** SPIKE #777 — the in-process resolve the AC asks for. Never merged. */
object YtDlpProbe {

    fun resolve(context: Context, url: String): String {
        val started = System.currentTimeMillis()
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
        val initMs = System.currentTimeMillis() - started
        val callStart = System.currentTimeMillis()
        val result = Python.getInstance()
            .getModule("ytdlp_probe")
            .callAttr("resolve", url)
            .toString()
        val resolveMs = System.currentTimeMillis() - callStart
        Log.w("YtDlpProbe", "diag pythonInitMs=$initMs resolveMs=$resolveMs result=$result")
        return result
    }
}
