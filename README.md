# ЛИФЕРЫЧ BMS для Android

Нативное Android-приложение на Kotlin для подключения к BMS Daly по Bluetooth LE.

## Что уже работает

- поиск BLE-устройств и подключение к Daly;
- чтение команд Daly `0x90–0x98` без опасного перебора неизвестных команд;
- напряжение, ток, SOC, остаточная ёмкость, MOS, температуры и ячейки;
- чтение и изменение поддерживаемых настроек BMS;
- журнал событий и ошибок;
- обращения в техническую поддержку с вложениями;
- отправка телеметрии на существующий сервер;
- профиль пользователя с локальным сохранением;
- интерфейс в жёлто-белом стиле шаблона ЛИФЕРЫЧ.

## Открытие в Cursor

1. Распакуйте архив в отдельную папку, например `D:\Cursor\LiferychBMS`.
2. В Cursor выберите **File → Open Folder** и укажите папку `LiferychBMS`.
3. Для сборки и запуска откройте эту же папку в Android Studio и дождитесь Gradle Sync.

## Сборка APK

В Android Studio: **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.

Или из терминала:

```bash
./gradlew assembleUserDebug assembleServiceDebug
```

Готовый файл появится в `app/build/outputs/apk/user/debug/` или `app/build/outputs/apk/service/debug/`.

### Публикация в АРМ (раздел ПО)

После сборки скопируйте актуальные APK в АРМ:

```bash
./scripts/build-and-publish.sh -m "краткое описание; ещё пункт"
```

Скрипт собирает user+service debug APK и вызывает `publish-to-arm.sh`. Можно сначала собрать вручную, затем опубликовать:

```bash
./gradlew assembleUserDebug assembleServiceDebug
./scripts/publish-to-arm.sh -m "краткое описание; ещё пункт"
```

Скрипт читает версию из `app/build.gradle.kts`, кладёт APK в `/srv/projects/arm-liferych/public/releases/` и обновляет `software-releases.json`. В АРМ откройте раздел **ПО**.

Проект использует JDK 17, Android Gradle Plugin 8.7.3, Kotlin 2.0.21, `compileSdk 35` и `minSdk 23`.

## Важное перед выпуском

API-ключ BMS задаётся в `local.properties` (`BMS_API_KEY`). Адрес сервера по умолчанию — `http://5.3.87.2:3101`. Перед публикацией настройте HTTPS и не коммитьте секреты.

## Сборки

- клиент: `userDebug` / `userRelease` (`ru.liferych.bms`);
- сервис: `serviceDebug` / `serviceRelease` (`ru.liferych.bms.service`).

Текущая версия: **0.2.43** (`versionCode` 103).

## Основные файлы

- `app/src/main/java/ru/liferych/bms/MainActivity.kt` — интерфейс, BLE и протокол Daly;
- `app/src/main/AndroidManifest.xml` — разрешения Bluetooth, интернет и медиа;
- `app/src/main/res/drawable/` — логотипы и значок приложения;
- `app/build.gradle.kts` — параметры Android-приложения;
- `config/` — шаблоны конфигурации BMS.
