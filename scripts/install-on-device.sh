#!/usr/bin/env bash
# Сборка (опционально) и установка APK на подключённое устройство через adb.
#
# Примеры:
#   ./scripts/install-on-device.sh service
#   ./scripts/install-on-device.sh user
#   ./scripts/install-on-device.sh service --no-build
#   ADB_SERIAL=XXXXXXXX ./scripts/install-on-device.sh service
#
# Android 10 (без «Беспроводной отладки»):
#   1) На ПК, куда воткнут кабель:
#        adb devices
#        adb tcpip 5555
#   2) Узнать IP телефона (Wi‑Fi) и на сервере:
#        adb connect 192.168.x.x:5555
#   3) Запустить этот скрипт.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ADB:-$HOME/.android-sdk/platform-tools/adb}"
FLAVOR="${1:-service}"
DO_BUILD=1

shift || true
while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-build) DO_BUILD=0; shift ;;
    -h|--help)
      sed -n '2,20p' "$0"
      exit 0
      ;;
    *)
      echo "Неизвестный аргумент: $1" >&2
      exit 1
      ;;
  esac
done

case "$FLAVOR" in
  user|service) ;;
  *)
    echo "Flavor: user | service (сейчас: $FLAVOR)" >&2
    exit 1
    ;;
esac

if [[ ! -x "$ADB" ]]; then
  if command -v adb >/dev/null 2>&1; then
    ADB="$(command -v adb)"
  else
    echo "adb не найден: $HOME/.android-sdk/platform-tools/adb" >&2
    exit 1
  fi
fi

echo "adb: $ADB"
"$ADB" start-server >/dev/null

wait_for_device() {
  local i
  for i in $(seq 1 30); do
    if [[ -n "${ADB_SERIAL:-}" ]]; then
      if "$ADB" -s "$ADB_SERIAL" get-state 2>/dev/null | grep -q device; then
        return 0
      fi
    else
      local count
      count="$("$ADB" devices | awk 'NR>1 && $2=="device" {c++} END{print c+0}')"
      if [[ "$count" -ge 1 ]]; then
        return 0
      fi
    fi
    echo "Жду устройство… ($i/30). Подключите телефон / adb connect IP:5555"
    sleep 2
  done
  echo "Устройство не найдено." >&2
  "$ADB" devices -l >&2
  return 1
}

wait_for_device

ADB_ARGS=()
if [[ -n "${ADB_SERIAL:-}" ]]; then
  ADB_ARGS=(-s "$ADB_SERIAL")
else
  # если устройств несколько — берём первое в состоянии device
  ADB_SERIAL="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
  ADB_ARGS=(-s "$ADB_SERIAL")
fi

echo "Устройство: $ADB_SERIAL"

APK="$ROOT/app/build/outputs/apk/${FLAVOR}/debug/app-${FLAVOR}-debug.apk"

if [[ "$DO_BUILD" -eq 1 ]]; then
  echo "Сборка ${FLAVOR}Debug…"
  if [[ -x "$ROOT/scripts/docker-build.sh" ]]; then
    # docker-build собирает оба flavor — это ок для быстрых тестов
    "$ROOT/scripts/docker-build.sh"
  else
    (
      cd "$ROOT"
      chmod +x ./gradlew
      ./gradlew --no-daemon "assemble${FLAVOR^}Debug"
    )
  fi
fi

if [[ ! -f "$APK" ]]; then
  echo "APK не найден: $APK" >&2
  echo "Соберите сначала или уберите --no-build только после успешной сборки." >&2
  exit 1
fi

echo "Установка $APK …"
"$ADB" "${ADB_ARGS[@]}" install -r "$APK"

PACKAGE="ru.liferych.bms"
ACTIVITY="ru.liferych.bms.MainActivity"
if [[ "$FLAVOR" == "service" ]]; then
  PACKAGE="ru.liferych.bms.service"
fi

echo "Запуск $PACKAGE/$ACTIVITY"
"$ADB" "${ADB_ARGS[@]}" shell am start -n "$PACKAGE/$ACTIVITY" >/dev/null

echo "Готово."
