package com.neko.music.wear.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.io.IOException
import io.ktor.utils.io.readUTF8Line
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

object NekoApi {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 20_000
            socketTimeoutMillis = 20_000
        }
        defaultRequest {
            ApiConfig.clientHeaders.forEach { (name, value) -> header(name, value) }
        }
    }

    suspend fun ranking(limit: Int = 20): List<Music> =
        decodeList(body("${ApiConfig.BASE}/api/music/ranking?limit=$limit", needNonce = false))

    suspend fun latest(limit: Int = 30): List<Music> =
        decodeList(body("${ApiConfig.BASE}/api/music/latest?limit=$limit", needNonce = false))

    /** 音质接口返回 JSON 里的真实媒体地址，不能直接交给播放器。 */
    suspend fun resolveAudioUrl(id: Int, quality: String = "hq"): String {
        val raw = body("${ApiConfig.BASE}/api/music/file/$id?quality=$quality", needNonce = true)
        val response = json.decodeFromString(AudioUrlResponse.serializer(), raw)
        val resolved = response.data?.url?.takeIf { it.isNotBlank() }
            ?: throw IOException(response.message.ifBlank { "无法解析音频地址" })
        // 服务端返回的是站内相对路径（/media/...），交给播放器前补全域名
        return if (resolved.startsWith("http")) resolved else ApiConfig.BASE + resolved
    }

    /** 歌词在 /api/music/info/{id} 的 data.lyrics（LRC 字符串）。 */
    suspend fun lyrics(id: Int): String {
        val raw = body("${ApiConfig.BASE}/api/music/info/$id", needNonce = true)
        val response = json.decodeFromString(MusicInfoResponse.serializer(), raw)
        return response.data?.lyrics.orEmpty()
    }

    suspend fun favorites(token: String): List<FavoriteMusic> {
        val raw = body("${ApiConfig.BASE}/api/user/favorites", authToken = token)
        return json.decodeFromString(FavoriteListResponse.serializer(), raw).favorites
    }

    suspend fun myPlaylists(token: String): List<PlaylistInfo> {
        val raw = body("${ApiConfig.BASE}/api/user/playlists", authToken = token)
        val response = json.decodeFromString(PlaylistListResponse.serializer(), raw)
        return response.playlists ?: response.results.orEmpty()
    }

    suspend fun playlistSongs(token: String, playlistId: Int): List<Music> {
        val raw = body(
            "${ApiConfig.BASE}/api/user/playlist/music/$playlistId",
            authToken = token,
        )
        val response = json.decodeFromString(PlaylistMusicListResponse.serializer(), raw)
        return response.musicList.orEmpty().map {
            Music(
                id = it.id,
                title = it.title,
                artist = it.artist,
                album = it.album,
                duration = it.duration,
            )
        }
    }

    /** 扫码登录：新建会话，返回 base64 二维码与 sessionId。 */
    /** SSE 专用客户端：不设请求总超时，否则长连接会被 HttpTimeout 掐断。 */
    private val sseClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = Long.MAX_VALUE
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = Long.MAX_VALUE
        }
        defaultRequest {
            ApiConfig.clientHeaders.forEach { (name, value) -> header(name, value) }
        }
    }

    suspend fun qrCreate(): QrCreateResponse {
        val raw = body("${ApiConfig.BASE}/api/user/qrlogin/create", method = HttpMethod.Post)
        return json.decodeFromString(QrCreateResponse.serializer(), raw)
    }

    /** 确认后用 deliveryKey 领取登录 token（一次性）。 */
    suspend fun qrClaim(sessionId: String, deliveryKey: String): QrClaimResponse {
        val payload = json.encodeToString(
            QrClaimRequest.serializer(),
            QrClaimRequest(sessionId, deliveryKey),
        )
        val raw = body(
            "${ApiConfig.BASE}/api/user/qrlogin/claim",
            method = HttpMethod.Post,
            requestBody = payload,
        )
        return json.decodeFromString(QrClaimResponse.serializer(), raw)
    }

    /**
     * 扫码登录状态流（SSE）。confirmed 帧里直接带 token 和用户信息。
     * 该端点在后端是免 nonce 的，这里也不带。
     */
    suspend fun qrStatusStream(sessionId: String, onEvent: (String, String?, UserData?) -> Unit) {
        sseClient.prepareGet("${ApiConfig.BASE}/api/user/qrlogin/status?sessionId=$sessionId") {
            header("Accept", "text/event-stream")
        }.execute { response ->
            val channel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                val obj = runCatching {
                    json.parseToJsonElement(payload).jsonObject
                }.getOrNull() ?: continue

                val status = (obj["status"] as? JsonPrimitive)?.content ?: continue
                // 只记状态，不记原始帧（里面带一次性 token）
                android.util.Log.d("NekoQr", "status=$status")
                val token = (obj["token"] as? JsonPrimitive)?.content
                val user = (obj["user"] as? JsonObject)?.let {
                    runCatching { json.decodeFromJsonElement(UserData.serializer(), it) }.getOrNull()
                }
                onEvent(status, token, user)
                if (status == "confirmed" || status == "expired") break
            }
        }
    }

    /** 版本检查：/version 返回 Android 端最新版本信息，字段 `ver`。 */
    suspend fun serverVersion(): String? {
        val raw = body("${ApiConfig.BASE}/version")
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        return (obj["ver"] as? JsonPrimitive)?.content
    }

    private fun decodeList(raw: String): List<Music> =
        json.decodeFromString(MusicListResponse.serializer(), raw).data

    private suspend fun body(
        url: String,
        method: HttpMethod = HttpMethod.Get,
        requestBody: String? = null,
        authToken: String? = null,
        needNonce: Boolean = true,
    ): String {
        if (!needNonce && authToken == null && requestBody == null && method == HttpMethod.Get) {
            return client.request(url).bodyAsText()
        }

        val write = method != HttpMethod.Get
        var lastError = "请求失败"
        repeat(3) {
            val nonce = if (needNonce) NonceClient.next(write = write) else null
            val response = client.request(url) {
                this.method = method
                if (nonce != null) header("X-Neko-Nonce", nonce)
                if (authToken != null) header("Authorization", authToken)
                if (requestBody != null) {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }
            }
            when (response.status) {
                HttpStatusCode.Conflict -> {
                    // 服务端判重放 / nonce 过期：只作废该方向的池，换新题重试
                    NonceClient.invalidate(write = write)
                    lastError = "nonce 被拒绝（409）"
                }
                HttpStatusCode.TooManyRequests -> {
                    // 限流时连着重试只会更糟，直接抛给上层提示用户
                    throw IOException("请求过于频繁（429），请稍后再试")
                }
                else -> return response.bodyAsText()
            }
        }
        throw IOException(lastError)
    }
}
