package com.neko.music.wear.data

/**
 * 与后端「客户端身份 v1」对齐的配置。
 *
 * 后端为 Wear OS 客户端单独登记了公开客户端（无 secret），但要求实例身份：
 * 本机生成密钥 → 挑战注册 → 实例令牌 → 每请求 ECDSA 签名。
 *
 * 两个平台标记必须分开且精确匹配，不能互换：
 * - `X-Neko-Client` 自报 `<平台>+<版本>`，平台取 `androidwatch`
 * - `User-Agent` 必须是 `NekoMusic-AWatch/<版本>`，后端据此把 UA 分类成 `awatch`
 *
 * 两者任一写错都会被后端判成「客户端身份无效」直接 401。
 */
object ApiConfig {
    const val BASE = "https://music.nekocore.cn"
    const val CLIENT_VERSION = "0.1.0"

    /** 后端登记的公开客户端 ID（confidential=false，不带 secret）。 */
    const val CLIENT_ID = "nkc_pub_android_watch_prod"
    /** 自报平台：挑战参数、注册请求体与 X-Neko-Client 都用它。 */
    const val CLIENT_PLATFORM = "androidwatch"

    const val CLIENT_REGISTER_URL = "$BASE/api/client/register"
    const val CLIENT_TOKEN_URL = "$BASE/api/client/token"

    val clientHeaders: Map<String, String> = mapOf(
        "X-Neko-Client" to "$CLIENT_PLATFORM+$CLIENT_VERSION",
        // 必须保留 AWatch 这个写法：改成 NekoMusic-android/... 会被判成手机端而 401
        "User-Agent" to "NekoMusic-AWatch/$CLIENT_VERSION",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
        "Accept-Encoding" to "identity",
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "$BASE/",
    )

    fun coverUrl(id: Int): String = "$BASE/api/music/cover/$id"
}
