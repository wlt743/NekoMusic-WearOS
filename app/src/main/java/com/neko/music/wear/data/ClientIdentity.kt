package com.neko.music.wear.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * 客户端身份 v1：证明「哪个应用 + 哪个安装实例」在请求。
 *
 * 流程：
 * 1. 本机生成 EC P-256 密钥对，私钥 PKCS8 / 公钥 SPKI 存应用私有目录
 * 2. `GET /api/client/challenge?purpose=register` 取挑战，用私钥签名注册
 * 3. `POST /api/client/register` 换回实例令牌（带过期时间）
 * 4. 每个受保护请求附带 `X-Neko-Client-*` 头，签名覆盖 method / path / query /
 *    body 的 SHA-256 / nonce / 时间戳，服务端据此判重放与篡改
 * 5. 令牌到期前自动 `/api/client/token` 续签
 *
 * 签名串（字段顺序不能改）：
 * ```
 * neko-client-v1
 * <METHOD>
 * <PATH>
 * <RAW_QUERY>
 * <client_id>
 * <instance_id>
 * <platform>
 * <timestamp>
 * <body_sha256_hex>
 * <nonce>
 * ```
 */
object ClientIdentity {
    private const val TAG = "NekoIdentity"
    private const val PREF = "neko_client_identity"

    private const val KEY_PRI = "pri_key"
    private const val KEY_PUB = "pub_key"
    private const val KEY_IID = "instance_id"
    private const val KEY_TOKEN = "token"
    private const val KEY_EXPIRES = "expires_at"

    /** 空 body 的 SHA-256，避免每次请求都算。 */
    private const val EMPTY_BODY_HASH =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    /** 令牌到期前多久续签。 */
    private const val RENEW_MARGIN_MS = 60_000L

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val bootstrapClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 20_000
            socketTimeoutMillis = 20_000
        }
        defaultRequest {
            ApiConfig.clientHeaders.forEach { (name, value) -> header(name, value) }
        }
    }

    private var prefs: SharedPreferences? = null
    private val mutex = Mutex()

    private var privateKey: java.security.PrivateKey? = null
    private var publicKeyB64: String = ""
    private var instanceId: String = ""
    private var token: String = ""
    private var expiresAt: Long = 0L

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        load()
    }

    // ------------------------------------------------------------ 对外接口

    /** 启动预热：提前完成实例注册，避免首次播放才等。 */
    suspend fun warmUp() {
        runCatching { ensureToken() }
            .onSuccess { Log.i(TAG, "warmUp ok instance=$instanceId") }
            .onFailure { Log.w(TAG, "warmUp failed: ${it.message}") }
    }

    /** 服务端判定令牌失效时调用，下次请求自动重新注册。 */
    suspend fun invalidate() = mutex.withLock {
        Log.w(TAG, "invalidate instance token")
        token = ""
        expiresAt = 0L
        prefs?.edit()?.putString(KEY_TOKEN, "")?.putLong(KEY_EXPIRES, 0L)?.apply()
    }

    /**
     * 生成某个请求的身份头。nonce 必须已经拿到——签名串里包含它。
     * @param rawQuery 不含 `?`，无查询串时传空字符串。
     */
    suspend fun headers(
        method: String,
        path: String,
        rawQuery: String,
        bodyHash: String,
        nonce: String,
    ): Map<String, String> {
        ensureToken()
        val key = privateKey ?: throw IOException("客户端私钥缺失")
        if (instanceId.isEmpty() || token.isEmpty()) throw IOException("客户端身份不可用")

        val timestamp = System.currentTimeMillis() / 1000
        val canonical = listOf(
            "neko-client-v1",
            method.uppercase(),
            path,
            rawQuery,
            ApiConfig.CLIENT_ID,
            instanceId,
            ApiConfig.CLIENT_PLATFORM,
            timestamp.toString(),
            bodyHash,
            nonce,
        ).joinToString("\n")

        val signature = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(sign(canonical, key))

        return mapOf(
            "X-Neko-Client-Id" to ApiConfig.CLIENT_ID,
            "X-Neko-Client-Instance" to instanceId,
            "X-Neko-Client-Token" to token,
            "X-Neko-Client-Timestamp" to timestamp.toString(),
            "X-Neko-Client-Signature" to signature,
        )
    }

    /** 拆分 URL：签名要用原始 path 与 query，不能带域名。 */
    fun splitUrl(url: String): Pair<String, String> {
        val uri = URI(url)
        val path = uri.rawPath ?: "/"
        val query = uri.rawQuery ?: ""
        return path to query
    }

    fun bodyHash(body: String?): String {
        if (body.isNullOrEmpty()) return EMPTY_BODY_HASH
        val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------ 令牌生命周期

    private suspend fun ensureToken() {
        val now = System.currentTimeMillis()
        if (token.isNotEmpty() && expiresAt - now > RENEW_MARGIN_MS) return

        mutex.withLock {
            // 等锁期间可能已经被别的协程续好了
            if (token.isNotEmpty() && expiresAt - System.currentTimeMillis() > RENEW_MARGIN_MS) {
                return
            }
            if (privateKey == null || publicKeyB64.isEmpty()) {
                generateKeyPair()
            }
            if (instanceId.isEmpty()) {
                instanceId = UUID.randomUUID().toString()
                prefs?.edit()?.putString(KEY_IID, instanceId)?.apply()
            }
            if (token.isNotEmpty()) {
                // 有实例令牌说明之前注册过，优先续签（省一次公钥注册）
                runCatching { renew() }
                    .onFailure { Log.w(TAG, "renew failed, fallback to register: ${it.message}") }
                    .onSuccess { return }
            }
            register()
        }
    }

    private suspend fun register() {
        val key = privateKey ?: throw IOException("客户端私钥缺失")
        val challenge = fetchChallenge("register")
        val timestamp = System.currentTimeMillis() / 1000
        val publicKey = base64UrlOf(publicKeyB64)

        val canonical = listOf(
            "neko-client-register-v1",
            ApiConfig.CLIENT_ID,
            ApiConfig.CLIENT_PLATFORM,
            instanceId,
            publicKey,
            challenge,
            timestamp.toString(),
        ).joinToString("\n")

        val payload = buildJson(
            "client_id" to ApiConfig.CLIENT_ID,
            "platform" to ApiConfig.CLIENT_PLATFORM,
            "instance_id" to instanceId,
            "public_key" to publicKey,
            "challenge" to challenge,
            "timestamp" to timestamp.toString(),
            "signature" to base64Url(sign(canonical, key)),
        )

        val data = postJson(ApiConfig.CLIENT_REGISTER_URL, payload)
        applyToken(data)
        Log.i(TAG, "registered instance=$instanceId expires=$expiresAt")
    }

    private suspend fun renew() {
        val key = privateKey ?: throw IOException("客户端私钥缺失")
        val challenge = fetchChallenge("token")
        val timestamp = System.currentTimeMillis() / 1000

        val canonical = listOf(
            "neko-client-token-v1",
            ApiConfig.CLIENT_ID,
            ApiConfig.CLIENT_PLATFORM,
            instanceId,
            challenge,
            timestamp.toString(),
        ).joinToString("\n")

        val payload = buildJson(
            "client_id" to ApiConfig.CLIENT_ID,
            "platform" to ApiConfig.CLIENT_PLATFORM,
            "instance_id" to instanceId,
            "challenge" to challenge,
            "timestamp" to timestamp.toString(),
            "signature" to base64Url(sign(canonical, key)),
        )

        val data = postJson(ApiConfig.CLIENT_TOKEN_URL, payload)
        applyToken(data)
        Log.i(TAG, "renewed instance=$instanceId expires=$expiresAt")
    }

    private fun applyToken(data: kotlinx.serialization.json.JsonObject?) {
        val newToken = (data?.get("token") as? JsonPrimitive)?.content
        val newExpires = (data?.get("expires_at") as? JsonPrimitive)?.longOrNull
        if (newToken.isNullOrEmpty() || newExpires == null) throw IOException("客户端注册失败")
        token = newToken
        expiresAt = newExpires * 1000
        prefs?.edit()?.putString(KEY_TOKEN, token)?.putLong(KEY_EXPIRES, expiresAt)?.apply()
    }

    private suspend fun fetchChallenge(purpose: String): String {
        val url = "${ApiConfig.BASE}/api/client/challenge" +
            "?client_id=${enc(ApiConfig.CLIENT_ID)}" +
            "&platform=${enc(ApiConfig.CLIENT_PLATFORM)}" +
            "&purpose=${enc(purpose)}"
        val text = bootstrapClient.get(url).bodyAsText()
        val data = envelopeData(text) ?: throw IOException("获取客户端挑战失败")
        return (data["challenge"] as? JsonPrimitive)?.content
            ?: throw IOException("挑战响应缺少 challenge")
    }

    private suspend fun postJson(url: String, body: String): kotlinx.serialization.json.JsonObject? {
        val text = bootstrapClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.bodyAsText()
        return envelopeData(text) ?: throw IOException(errorMessage(text))
    }

    private fun envelopeData(text: String): kotlinx.serialization.json.JsonObject? =
        runCatching { json.parseToJsonElement(text).jsonObject["data"] as? kotlinx.serialization.json.JsonObject }
            .getOrNull()

    private fun errorMessage(text: String): String =
        runCatching { (json.parseToJsonElement(text).jsonObject["message"] as? JsonPrimitive)?.content }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: "客户端身份请求失败"

    // ------------------------------------------------------------ 本机密钥

    private fun load() {
        val p = prefs ?: return
        privateKey = null
        publicKeyB64 = p.getString(KEY_PUB, "").orEmpty()
        instanceId = p.getString(KEY_IID, "").orEmpty()
        token = p.getString(KEY_TOKEN, "").orEmpty()
        expiresAt = p.getLong(KEY_EXPIRES, 0L)

        val priBase64 = p.getString(KEY_PRI, "").orEmpty()
        if (priBase64.isEmpty() || publicKeyB64.isEmpty()) {
            // 只剩半个密钥时全部作废，下次重新生成并注册
            if (instanceId.isNotEmpty()) {
                Log.w(TAG, "half key found, reset instance")
                instanceId = ""
                token = ""
                expiresAt = 0L
                p.edit().clear().apply()
            }
            return
        }
        privateKey = runCatching {
            val der = Base64.getDecoder().decode(priBase64)
            KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der))
        }.onFailure { Log.w(TAG, "load key failed: ${it.message}") }.getOrNull()
    }

    private fun generateKeyPair() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair: KeyPair = generator.generateKeyPair()

        privateKey = pair.private
        publicKeyB64 = Base64.getEncoder().encodeToString(pair.public.encoded)
        // 换密钥必须换实例：旧实例的令牌绑在旧公钥上
        instanceId = UUID.randomUUID().toString()
        token = ""
        expiresAt = 0L

        prefs?.edit()
            ?.putString(KEY_PRI, Base64.getEncoder().encodeToString(pair.private.encoded))
            ?.putString(KEY_PUB, publicKeyB64)
            ?.putString(KEY_IID, instanceId)
            ?.putString(KEY_TOKEN, "")
            ?.putLong(KEY_EXPIRES, 0L)
            ?.apply()
        Log.i(TAG, "generated keypair")
    }

    private fun sign(canonical: String, key: java.security.PrivateKey): ByteArray {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(key)
        signer.update(canonical.toByteArray(Charsets.UTF_8))
        return signer.sign()
    }

    // ------------------------------------------------------------ 工具

    private fun buildJson(vararg pairs: Pair<String, String>): String =
        pairs.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            "\"$k\":\"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        }

    private fun base64Url(data: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    /** 存的是标准 base64，注册时转 base64url 去 padding。 */
    private fun base64UrlOf(standard: String): String =
        standard.replace('+', '-').replace('/', '_').trimEnd('=')

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
