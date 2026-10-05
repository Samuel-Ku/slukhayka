#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

# Spec-40 #283 — the Android SDK contract tests run FIRST, while the emulator
# still carries its own permissive rules (`firestore-emulator.rules`).
#
# The order is load-bearing, not cosmetic. `matrix.test.mjs` loads the
# repository's real shape rules into the emulator's project, and from then on a
# client without an App Check token cannot write at all — which is exactly what
# these contract tests have to do. They check the CLIENT's write semantics (a
# partial update must merge, not replace the document), not the rules; the rules
# are what the matrix itself proves.
if [[ "${RUN_ANDROID_STORE_TEST:-0}" == "1" ]]; then
  cd ../..
  android_gradle_args=()
  if [[ "${ANDROID_STORE_RERUN_TEST:-0}" == "1" ]]; then
    android_gradle_args+=(--rerun)
  fi
  SLUKHAYKA_FIRESTORE_EMULATOR_HOST=127.0.0.1:8080 \
    timeout 600 ./gradlew --no-daemon --max-workers=1 testDebugUnitTest \
      "${android_gradle_args[@]}" \
      --tests 'com.slukhayka.audiobooks.data.metadata.FirestoreBookMetaStoreEmulatorTest' \
      --tests 'com.slukhayka.audiobooks.data.collections.FirestoreListenerCollectionsSharedStoreEmulatorTest'
  cd scripts/rules-matrix
fi

node matrix.test.mjs "$@"
