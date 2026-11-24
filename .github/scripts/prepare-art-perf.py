#!/usr/bin/env python3
"""Prepare the exact standalone Edition-id memo and its committed probe."""
from pathlib import Path
import hashlib,json,os,subprocess,sys
BASE='85c4423b6c608b18162f440ff3044d47e76d6451'
CANDIDATE='aacca018c7171b808db9e5ddd6b62e8887842960'
MAIN='app/src/main/java/com/slukhayka/audiobooks/data/EditionId.kt'
TEST='app/src/test/java/com/slukhayka/audiobooks/data/EditionIdTest.kt'
def blob(commit,path):
    return subprocess.check_output(['git','show',commit+':'+path],text=True)
def sha(text):
    return hashlib.sha256(text.encode()).hexdigest()
def main():
    assert subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()==BASE
    root=Path(sys.argv[1]) if len(sys.argv)>1 else Path.cwd()
    manifest={'base':BASE,'candidate':CANDIDATE,'workflowCommit':os.environ.get('GITHUB_SHA'),
              'scope':'standalone PR1805 debug APK; isolated ART operations, not UI or physical ARM','production':[],'probes':[]}
    production=blob(CANDIDATE,MAIN)
    assert blob(BASE,MAIN)!=production
    origin=blob(CANDIDATE,TEST)
    assert origin.count('object RunEditionMemoBenchmark')==1
    body=origin[origin.index('object RunEditionMemoBenchmark'):]
    assert '@Test' not in body and 'class EditionIdTest' not in body
    probe='package com.slukhayka.audiobooks.data\nimport java.security.MessageDigest\nimport kotlin.system.measureNanoTime\n\n'+body
    destination='app/src/androidTest/java/com/slukhayka/audiobooks/data/RunEditionMemoBenchmark.kt'
    harness='app/src/androidTest/java/com/slukhayka/audiobooks/performance/PerformanceLoopBenchmarkTest.kt'
    for path,text in [(MAIN,production),(destination,probe),(harness,HARNESS)]:
        output=root/path;output.parent.mkdir(parents=True,exist_ok=True);output.write_text(text)
    manifest['production'].append({'path':MAIN,'commits':[CANDIDATE],'sha256':sha(production)})
    manifest['probes'].append({'commit':CANDIDATE,'origin':TEST,'destination':destination,'originSha256':sha(origin),'manualBodySha256':sha(body),'sha256':sha(probe),'extraction':'manual object and reference only; JVM JUnit class excluded for DEX compatibility'})
    manifest['harness']={'path':harness,'sha256':sha(HARNESS)}
    if root.resolve()==Path.cwd().resolve():
        changed=subprocess.check_output(['git','diff','--name-only',BASE,'--','app/src/main'],text=True).splitlines()
        assert changed==[MAIN],changed
    output=root/'.mutations/evidence/art-perf/source-manifest.json';output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(manifest,indent=2)+'\n');print(json.dumps(manifest))
HARNESS='''package com.slukhayka.audiobooks.performance
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slukhayka.audiobooks.data.RunEditionMemoBenchmark
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class PerformanceLoopBenchmarkTest {
    @Test fun compareEditionMemo() {
        Log.i("PerfLoop", "ENV sdk=${Build.VERSION.SDK_INT} abi=${Build.SUPPORTED_ABIS.toList()} vm=${System.getProperty("java.vm.version")} fingerprint=${Build.FINGERPRINT}")
        Log.i("PerfLoop", "BEGIN edition-memo")
        RunEditionMemoBenchmark.main(emptyArray())
        Log.i("PerfLoop", "END edition-memo")
        Log.i("PerfLoop", "ALL_BENCHMARKS_DONE")
    }
}
'''
if __name__=='__main__':main()
