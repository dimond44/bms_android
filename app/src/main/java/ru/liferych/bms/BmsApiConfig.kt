package ru.liferych.bms

/**
 * Единая точка доступа к API_KEY для BMS-сервера.
 *
 * Ключ задаётся в корневом local.properties (файл в git не попадает):
 *   BMS_API_KEY=ваш_ключ_с_сервера
 *
 * Не логируйте и не показывайте значение на экране.
 */
object BmsApiConfig {
    val API_KEY: String
        get() = BuildConfig.BMS_API_KEY
}
