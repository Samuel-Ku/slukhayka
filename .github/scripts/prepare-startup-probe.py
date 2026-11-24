#!/usr/bin/env python3
"""Temporary #1742 probes. Plain startup timings use a separate, unmodified APK."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import sys

PREFIX = 'com.slukhayka.audiobooks.perf.Startup1742Probe'


def sha(text):
    return hashlib.sha256(text.encode()).hexdigest()


def main():
    destination = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.cwd()
    paths = ['app/src/main/java/com/slukhayka/audiobooks/MainActivity.kt',
             'app/src/main/java/com/slukhayka/audiobooks/App.kt',
             'app/src/main/java/com/slukhayka/audiobooks/data/db/AudiobookDatabase.kt']
    records = []
    for path in paths:
        text = Path(path).read_text()
        before = text
        # Each current factory is one builder chain without an inner build().
        # Reject unknown source shapes instead of silently omitting a probe.
        for kind, pattern in [('COIL', r'(?:coil\.)?ImageLoader\.Builder\('),
                              ('ROOM', r'(?:androidx\.room\.)?Room\.databaseBuilder\(')]:
            matches = list(re.finditer(pattern, text))
            for match in reversed(matches):
                end = text.find('.build()', match.start())
                assert end >= 0, (path, kind)
                end += len('.build()')
                expression = text[match.start():end]
                assert ';' not in expression, (path, kind, 'not one builder expression')
                text = text[:match.start()] + PREFIX + '.measure("' + kind + '") {\n' + expression + '\n}' + text[end:]
        if path.endswith('/MainActivity.kt'):
            start = '    override fun onCreate(savedInstanceState: Bundle?) {\n'
            assert text.count(start) == 1
            text = text.replace(start, start + '        ' + PREFIX + '.event("CREATE_BEGIN")\n')
            anchor = '        enableEdgeToEdge()'
            assert text.count(anchor) == 1
            text = text.replace(anchor, anchor + '\n        ' + PREFIX + '.installFirstDrawMarker(window.decorView)')
            end = '\n    }\n\n    override fun onStart()'
            assert text.count(end) == 1
            text = text.replace(end, '\n        ' + PREFIX + '.event("CREATE_END")' + end)
        output = destination / path
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(text)
        records.append({'path': path, 'originalSha256': sha(before), 'diagnosticSha256': sha(text),
                        'coilProbes': text.count('.measure("COIL")'), 'roomProbes': text.count('.measure("ROOM")')})
    assert sum(r['coilProbes'] for r in records) >= 1
    assert sum(r['roomProbes'] for r in records) == 1
    path = 'app/src/main/java/com/slukhayka/audiobooks/perf/Startup1742Probe.kt'
    output = destination / path
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(PROBE)
    report = {'sourceCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
              'scope': 'diagnostic APK only; plain timings are recorded with unmodified APK',
              'files': records, 'probePath': path, 'probeSha256': sha(PROBE)}
    output = destination / '.mutations/evidence/startup-profile/probe-manifest.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


PROBE = """package com.slukhayka.audiobooks.perf

import android.os.Debug
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import java.util.concurrent.atomic.AtomicBoolean

/** [DEBUG-startup-1742] CI diagnostic only; never part of the product PR. */
object Startup1742Probe {
    private val firstDrawCompleted = AtomicBoolean(false)
    private const val TAG = "Startup1742"

    fun event(name: String) {
        Log.i(TAG, "[DEBUG-startup-1742] event=$name pid=${Process.myPid()} wallNs=${SystemClock.elapsedRealtimeNanos()}")
    }

    fun <T> measure(kind: String, block: () -> T): T {
        val main = Looper.myLooper() == Looper.getMainLooper()
        val beforeDraw = !firstDrawCompleted.get()
        val wall = SystemClock.elapsedRealtimeNanos()
        val cpu = Debug.threadCpuTimeNanos()
        Log.i(TAG, "[DEBUG-startup-1742] BEGIN kind=$kind main=$main beforeFirstDraw=$beforeDraw pid=${Process.myPid()}")
        try {
            return block()
        } finally {
            Log.i(TAG, "[DEBUG-startup-1742] END kind=$kind main=$main beforeFirstDraw=$beforeDraw wallNs=${SystemClock.elapsedRealtimeNanos() - wall} cpuNs=${Debug.threadCpuTimeNanos() - cpu}")
        }
    }

    fun installFirstDrawMarker(view: View) {
        val listener = object : ViewTreeObserver.OnDrawListener {
            private var scheduled = false
            override fun onDraw() {
                if (scheduled) return
                scheduled = true
                // Post after this traversal: do not mark completion before child drawing.
                view.post {
                    firstDrawCompleted.set(true)
                    event("FIRST_DRAW_END")
                    if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(this)
                }
            }
        }
        view.viewTreeObserver.addOnDrawListener(listener)
    }
}
"""

if __name__ == '__main__':
    main()
