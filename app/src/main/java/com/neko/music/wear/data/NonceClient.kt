package com.neko.music.wear.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * 后端要求所有 /api/ 接口携带一次性 X-Neko-Nonce。
 * 流程：换题 → 本地解 SHA-256 前导零比特 → 兑换 nonce。
 * GET 用读池，其余方法用写池。
 */
object NonceClient {
    private const val BATCH = 8

    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
        defaultRequest {
            ApiConfig.clientHeaders.forEach { (name, value) -> header(name, value) }
        }
    }

    private val mutex = Mutex()
    private val readPool = ArrayDeque<String>()
    private val writePool = ArrayDeque<String>()

    suspend fun next(write: Boolean = false): String = mutex.withLock {
        val pool = if (write) writePool else readPool
        if (pool.isEmpty()) fill(write, BATCH, pool)
        pool.removeFirstOrNull() ?: error("无法获取请求 nonce")
    }

    /** 服务端判定重放后清空本地池。 */
    suspend fun invalidate() = mutex.withLock {
        readPool.clear()
        writePool.clear()
    }

    private suspend fun fill(write: Boolean, count: Int, pool: ArrayDeque<String>) {
        val read = if (write) 0 else count
        val writeCount = if (write) count else 0

        val challengeBody = client.get(
            "${ApiConfig.BASE}/api/replay/challenge?read=$read&write=$writeCount"
        ).bodyAsText()

        val data = json.parseToJsonElement(challengeBody).objectAt("data")
            ?: error("换题响应异常")
        val id = data.stringAt("challenge") ?: error("缺少 challenge")
        val seed = data.stringAt("seed") ?: error("缺少 seed")
        val difficulty = data["difficulty"]?.jsonPrimitive?.intOrNull ?: 0

        val proof = withContext(Dispatchers.Default) { solve(seed, difficulty) }

        val nonceBody = client.get(
            "${ApiConfig.BASE}/api/replay/nonce?challenge=$id&proof=$proof"
        ).bodyAsText()

        val list = json.parseToJsonElement(nonceBody)
            .objectAt("data")
            ?.objectAt("nonces")
            ?.get(if (write) "write" else "read")
            ?.jsonArray ?: error("兑换 nonce 失败：$nonceBody")

        list.forEach { pool.addLast(it.jsonPrimitive.content) }
    }

    /** JsonNull 也是 JsonElement，不能直接 as JsonObject；统一做安全取值。 */
    private fun JsonElement.objectAt(key: String): JsonObject? =
        ((this as? JsonObject)?.get(key)) as? JsonObject

    private fun JsonObject.stringAt(key: String): String? =
        (this[key] as? JsonPrimitive)?.content

    /** 找到使 SHA-256("seed:counter") 前导零比特数达到 difficulty 的 counter。 */
    private fun solve(seed: String, difficulty: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val prefix = "$seed:"
        var counter = 0L
        while (true) {
            val hash = digest.digest("$prefix$counter".toByteArray(Charsets.UTF_8))
            if (leadingZeroBits(hash) >= difficulty) return counter.toString()
            counter++
        }
    }

    private fun leadingZeroBits(hash: ByteArray): Int {
        var bits = 0
        for (byte in hash) {
            val value = byte.toInt() and 0xFF
            if (value == 0) {
                bits += 8
                continue
            }
            bits += Integer.numberOfLeadingZeros(value) - 24
            break
        }
        return bits
    }
}
