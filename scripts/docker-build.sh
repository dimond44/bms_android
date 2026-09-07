#!/usr/bin/env bash
# Build user+service debug APKs inside Docker (no host JDK/SDK required).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="${ANDROID_BUILD_IMAGE:-thyrlian/android-sdk:latest}"
GRADLE_CACHE_VOL="${GRADLE_CACHE_VOL:-bms_android_gradle_cache}"

cd "$ROOT"
chmod +x ./gradlew

if [[ ! -f local.properties ]]; then
  printf '%s\n' 'BMS_API_KEY=' > local.properties
fi

echo "Image: ${IMAGE}"
echo "Project: ${ROOT}"
docker pull "$IMAGE"

docker run --rm \
  -v "${ROOT}:/project" \
  -v "${GRADLE_CACHE_VOL}:/root/.gradle" \
  -w /project \
  -e ANDROID_HOME=/opt/android-sdk \
  -e ANDROID_SDK_ROOT=/opt/android-sdk \
  "$IMAGE" \
  bash -lc '
    set -euo pipefail
    export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/cmdline-tools/tools/bin:$ANDROID_HOME/tools/bin:$ANDROID_HOME/platform-tools:$PATH"
    if ! command -v sdkmanager >/dev/null 2>&1; then
      echo "sdkmanager not found; listing $ANDROID_HOME:" >&2
      ls -la "$ANDROID_HOME" >&2 || true
      find "$ANDROID_HOME" -name sdkmanager 2>/dev/null | head >&2 || true
      exit 1
    fi
    yes | sdkmanager --licenses >/tmp/sdk-licenses.log 2>&1 || true
    sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
    ./gradlew --no-daemon assembleUserDebug assembleServiceDebug
  '

echo
echo "APKs:"
ls -lah app/build/outputs/apk/user/debug/*.apk app/build/outputs/apk/service/debug/*.apk
