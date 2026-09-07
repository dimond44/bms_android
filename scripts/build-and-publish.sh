#!/usr/bin/env bash
# Собрать user+service debug APK и опубликовать в АРМ (раздел ПО).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
chmod +x ./gradlew ./scripts/publish-to-arm.sh

./gradlew assembleUserDebug assembleServiceDebug
./scripts/publish-to-arm.sh "$@"
