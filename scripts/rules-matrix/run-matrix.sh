#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
node matrix.test.mjs "$@"
if [[ "${RUN_ANDROID_STORE_TEST:-0}" == "1" ]]; then
  cd ../..
  SLUKHAYKA_FIRESTORE_EMULATOR_HOST=127.0.0.1:8080 \
    timeout 240 ./gradlew --no-daemon --max-workers=1 testDebugUnitTest \
      --tests 'com.slukhayka.audiobooks.data.metadata.FirestoreBookMetaStoreEmulatorTest'
fi
