package ru.liferych.bms

/**
 * Единая конфигурация BMS API.
 *
 * Адрес сервера задаётся здесь и не редактируется из UI сервисного приложения.
 * Ключ задаётся в корневом local.properties (файл в git не попадает):
 *   BMS_API_KEY=ваш_ключ_с_сервера
 *
 * Не логируйте и не показывайте ключ на экране.
 */
object BmsApiConfig {
    const val BASE_URL = "http://5.3.87.2:3101"

    val API_KEY: String
        get() = BuildConfig.BMS_API_KEY
}
