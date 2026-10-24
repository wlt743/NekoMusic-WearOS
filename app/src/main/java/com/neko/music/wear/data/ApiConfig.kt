package com.neko.music.wear.data

/** 与 PC / Android 端一致的后端地址与客户端标识。 */
object ApiConfig {
    const val BASE = "https://music.nekocore.cn"
    const val CLIENT_VERSION = "0.1.0"

    val clientHeaders: Map<String, String> = mapOf(
        "X-Neko-Client" to "AndroidWatch+$CLIENT_VERSION",
        // AWatch UA 目前仍被 403，等放行后改回这行：
        // "User-Agent" to "NekoMusic-AWatch/$CLIENT_VERSION",
        "User-Agent" to "NekoMusic-android/$CLIENT_VERSION",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
    )

    fun coverUrl(id: Int): String = "$BASE/api/music/cover/$id"
}
