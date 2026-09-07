#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARM_ROOT="${ARM_ROOT:-/srv/projects/arm-liferych}"
RELEASES_DIR="${ARM_ROOT}/public/releases"
META_FILE="${RELEASES_DIR}/software-releases.json"
GRADLE_FILE="${ROOT}/app/build.gradle.kts"
BUILD_TYPE="${BUILD_TYPE:-debug}"
CHANGELOG_MSG=""
CHANGELOG_FILE="${ROOT}/CHANGELOG_RELEASE.md"

usage() {
  cat <<'EOF'
Usage: ./scripts/publish-to-arm.sh [-m "changelog"] [-f changelog.md] [--release]

  -m TEXT     Changelog items separated by ";" (default: "сборка от <date>")
  -f FILE     Read changelog lines from file (non-empty lines)
  --release   Use release APKs instead of debug
  ARM_ROOT    Override ARM project path (default: /srv/projects/arm-liferych)
  BUILD_TYPE  Override build type (debug|release)
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -m)
      CHANGELOG_MSG="${2:-}"
      shift 2
      ;;
    -f)
      CHANGELOG_FILE="${2:-}"
      shift 2
      ;;
    --release)
      BUILD_TYPE="release"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if [[ ! -f "$GRADLE_FILE" ]]; then
  echo "Не найден ${GRADLE_FILE}" >&2
  exit 1
fi

if [[ ! -d "$ARM_ROOT" ]]; then
  echo "Не найден проект АРМ: ${ARM_ROOT}" >&2
  exit 1
fi

VERSION_NAME="$(sed -n 's/.*versionName *= *"\([^"]*\)".*/\1/p' "$GRADLE_FILE" | head -n1)"
VERSION_CODE="$(sed -n 's/.*versionCode *= *\([0-9][0-9]*\).*/\1/p' "$GRADLE_FILE" | head -n1)"

if [[ -z "$VERSION_NAME" || -z "$VERSION_CODE" ]]; then
  echo "Не удалось прочитать versionName/versionCode из ${GRADLE_FILE}" >&2
  exit 1
fi

mkdir -p "$RELEASES_DIR"

changelog_items=()
if [[ -n "$CHANGELOG_MSG" ]]; then
  IFS=';' read -r -a parts <<< "$CHANGELOG_MSG"
  for part in "${parts[@]}"; do
    trimmed="$(echo "$part" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    [[ -n "$trimmed" ]] && changelog_items+=("$trimmed")
  done
elif [[ -f "$CHANGELOG_FILE" ]]; then
  while IFS= read -r line || [[ -n "$line" ]]; do
    trimmed="$(echo "$line" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//;s/^[-*][[:space:]]*//')"
    [[ -n "$trimmed" ]] && changelog_items+=("$trimmed")
  done < "$CHANGELOG_FILE"
fi

if [[ ${#changelog_items[@]} -eq 0 ]]; then
  changelog_items+=("сборка от $(date '+%Y-%m-%d %H:%M')")
fi

json_escape() {
  python3 -c 'import json,sys; print(json.dumps(sys.stdin.read().rstrip("\n")))' <<<"$1"
}

changelog_json="["
for i in "${!changelog_items[@]}"; do
  [[ $i -gt 0 ]] && changelog_json+=", "
  changelog_json+=$(json_escape "${changelog_items[$i]}")
done
changelog_json+="]"

publish_one() {
  local flavor="$1"
  local display_name="$2"
  local src_glob="${ROOT}/app/build/outputs/apk/${flavor}/${BUILD_TYPE}/*.apk"
  local src
  src="$(ls -1t $src_glob 2>/dev/null | head -n1 || true)"

  if [[ -z "$src" || ! -f "$src" ]]; then
    local flavor_task type_task
    flavor_task="$(flavor_cap "$flavor")"
    type_task="$(flavor_cap "$BUILD_TYPE")"
    echo "Не найден APK для ${flavor}/${BUILD_TYPE}." >&2
    echo "Соберите: ./gradlew assemble${flavor_task}${type_task}" >&2
    echo "Ожидался путь: ${ROOT}/app/build/outputs/apk/${flavor}/${BUILD_TYPE}/" >&2
    exit 1
  fi

  local dest_name="liferych-bms-${flavor}-${VERSION_NAME}.apk"
  local dest="${RELEASES_DIR}/${dest_name}"

  # Remove previous APKs for this flavor (any version)
  find "$RELEASES_DIR" -maxdepth 1 -type f -name "liferych-bms-${flavor}-*.apk" -delete

  cp -f "$src" "$dest"
  echo "Опубликован ${display_name}: ${dest_name} ← $(basename "$src")"

  python3 - "$META_FILE" "$flavor" "$display_name" "$VERSION_NAME" "$VERSION_CODE" "$dest_name" "$changelog_json" <<'PY'
import json, sys, datetime
from pathlib import Path

meta_path = Path(sys.argv[1])
flavor, name, version_name, version_code, file_name = sys.argv[2:7]
changelog = json.loads(sys.argv[7])

if meta_path.exists():
    data = json.loads(meta_path.read_text(encoding="utf-8"))
else:
    data = {"updatedAt": "", "apps": []}

apps = data.get("apps") or []
entry = {
    "id": flavor,
    "name": name,
    "versionName": version_name,
    "versionCode": int(version_code),
    "file": file_name,
    "changelog": changelog,
}

replaced = False
for i, app in enumerate(apps):
    if app.get("id") == flavor:
        apps[i] = entry
        replaced = True
        break
if not replaced:
    apps.append(entry)

# Keep stable order: user, service, then others
order = {"user": 0, "service": 1}
apps.sort(key=lambda a: (order.get(a.get("id", ""), 99), a.get("id", "")))

data["apps"] = apps
data["updatedAt"] = datetime.datetime.now().astimezone().isoformat(timespec="seconds")
meta_path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
PY
}

# Bash capitalize helper for Gradle task names: user+debug -> UserDebug
flavor_cap() {
  local f="$1"
  echo "$(tr '[:lower:]' '[:upper:]' <<< "${f:0:1}")${f:1}"
}

echo "Версия: ${VERSION_NAME} (${VERSION_CODE}), build=${BUILD_TYPE}"
echo "АРМ: ${ARM_ROOT}"

publish_one "user" "ЛИФЕРЫЧ BMS"
publish_one "service" "ЛИФЕРЫЧ Сервис"

echo "Готово. Метаданные: ${META_FILE}"
echo "Откройте в АРМ раздел ПО (/software)."
