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

Готовый файл появится в `app/build/outputs/apk/debug/app-debug.apk`.

Проект использует JDK 17, Android Gradle Plugin 8.7.3, Kotlin 2.0.21, `compileSdk 35` и `minSdk 23`.

## Важное перед выпуском

В `MainActivity.kt` пока сохранены тестовый API-ключ и HTTP-адрес существующего сервера из исходного проекта. Перед публикацией замените ключ, настройте HTTPS и вынесите секрет из исходного кода.

## Основные файлы

- `app/src/main/java/ru/liferych/bms/MainActivity.kt` — интерфейс, BLE и протокол Daly;
- `app/src/main/AndroidManifest.xml` — разрешения Bluetooth, интернет и медиа;
- `app/src/main/res/drawable/` — логотипы и значок приложения;
- `app/build.gradle.kts` — параметры Android-приложения.
