package com.neko.music.wear.data

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray

/**
 * 防重放：后端要求所有受保护接口携带一次性 `X-Neko-Nonce`。
 *
 * 流程：换题（challenge + seed + difficulty）→ 本地解 SHA-256 前导零比特得到 proof
 * → 用 proof 兑换一批 nonce → 每个 nonce 只用于一次请求。
 *
 * 关键约束：
 * - nonce 一次性，取出即消耗，失败也不回池（回池等于自己制造重放）。
 * - 读池 / 写池分开：GET 与其它方法在服务端是两个独立池，混用会立刻 409。
 * - 并发补货单飞：多个请求同时缺货时只换一次题、只解一次 PoW，其余等待复用。
 * - 求解可取消且有超时：难度由服务端下发，不允许无限占线程。
 */
object NonceClient {
    private const val TAG = "NekoNonce"

    /** 单批兑换量：读池取用频繁多备一些，写池（POST/PUT）用得少。 */
    private const val READ_BATCH = 8
    private const val WRITE_BATCH = 4

    /** SHA-256 是 256 bit，但难度 32 已约 40 亿次，远超实际下发值；越界一律夹紧。 */
    private const val MAX_DIFFICULTY = 32

    /** 单次 PoW 求解上限，超时视为服务端难度异常。 */
    private const val SOLVE_TIMEOUT_MS = 20_000L

    /** 单次补货最多尝试两轮（题目可能过期）。 */
    private const val FILL_ATTEMPTS = 2

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

    /** 保护两个队列本身；补货走独立的单飞锁，避免持锁等网络。 */
    private val poolLock = Mutex()
    private val readFillLock = Mutex()
    private val writeFillLock = Mutex()

    private val readPool = ArrayDeque<String>()
    private val writePool = ArrayDeque<String>()

    /**
     * 取一个可用 nonce。池空时同步补货。
     * @param write true 表示这是写请求（非 GET），从写池取。
     */
    suspend fun next(write: Boolean = false): String {
        val pool = if (write) writePool else readPool
        poolLock.withLock { pool.removeFirstOrNull() }?.let { return it }

        val fillLock = if (write) writeFillLock else readFillLock
        fillLock.withLock {
            // 等锁期间别的协程可能已经补好货，二次检查，避免重复换题
            poolLock.withLock { pool.removeFirstOrNull() }?.let { return it }
            fill(write, if (write) WRITE_BATCH else READ_BATCH)
            return poolLock.withLock { pool.removeFirstOrNull() }
                ?: throw IOException("无法获取请求 nonce")
        }
    }

    /**
     * 服务端判定重放（409）后作废本地池。
     * @param write 只作废对应方向的池；传 null 清空两边。
     */
    suspend fun invalidate(write: Boolean? = null) {
        poolLock.withLock {
            when (write) {
                true -> writePool.clear()
                false -> readPool.clear()
                null -> {
                    readPool.clear()
                    writePool.clear()
                }
            }
        }
        Log.d(TAG, "invalidated pool write=$write")
    }

    /** 启动 / 登录后预热：后台先备好读池，首次播放不必等换题 + PoW。 */
    suspend fun prefetch(): Unit = readFillLock.withLock {
        val cached = poolLock.withLock { readPool.size }
        if (cached >= 2) return
        runCatching { fill(false, READ_BATCH) }
            .onFailure { Log.w(TAG, "prefetch failed: ${it.message}") }
    }

    /** 当前缓存量，调试用。 */
    suspend fun cached(): Pair<Int, Int> = poolLock.withLock { readPool.size to writePool.size }

    // ---------------------------------------------------------------- 补货

    private suspend fun fill(write: Boolean, count: Int) {
        var lastError: Throwable? = null
        repeat(FILL_ATTEMPTS) { attempt ->
            try {
                val nonces = requestNonces(write, count)
                if (nonces.isEmpty()) throw IOException("服务端返回空 nonce 列表")
                poolLock.withLock {
                    val pool = if (write) writePool else readPool
                    // 去重：极端情况下服务端可能给出重复值，重复值会让第二个请求必然重放
                    val known = pool.toHashSet()
                    nonces.forEach { if (known.add(it)) pool.addLast(it) }
                }
                return
            } catch (t: Throwable) {
                currentCoroutineContext().ensureActive()  // 取消要穿透出去，不当成失败
                lastError = t
                Log.w(TAG, "fill attempt ${attempt + 1} failed: ${t.message}")
                if (attempt + 1 < FILL_ATTEMPTS) delay(300L * (attempt + 1))
            }
        }
        throw IOException("兑换 nonce 失败：${lastError?.message}", lastError)
    }

    private suspend fun requestNonces(write: Boolean, count: Int): List<String> {
        val readCount = if (write) 0 else count
        val writeCount = if (write) count else 0

        val challengeBody = client.get(
            "${ApiConfig.BASE}/api/replay/challenge?read=$readCount&write=$writeCount",
        ).bodyAsText()

        val data = json.parseToJsonElement(challengeBody).objectAt("data")
            ?: throw IOException("换题响应异常：${challengeBody.take(120)}")
        val id = data.stringAt("challenge") ?: throw IOException("换题响应缺少 challenge")
        val seed = data.stringAt("seed") ?: throw IOException("换题响应缺少 seed")
        val difficulty = (data["difficulty"] as? JsonPrimitive)?.intOrNull ?: 0
        if (difficulty < 0) throw IOException("难度非法：$difficulty")

        val proof = solveProof(seed, difficulty)

        val nonceBody = client.get(
            "${ApiConfig.BASE}/api/replay/nonce" +
                "?challenge=${encode(id)}&proof=${encode(proof)}",
        ).bodyAsText()

        val list = json.parseToJsonElement(nonceBody)
            .objectAt("data")
            ?.objectAt("nonces")
            ?.get(if (write) "write" else "read")
            ?.jsonArray
            ?: throw IOException("兑换 nonce 失败：${nonceBody.take(160)}")

        return list.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
    }

    // ---------------------------------------------------------------- PoW

    private suspend fun solveProof(seed: String, difficulty: Int): String {
        // 难度为 0 时首个 counter 即可满足，无需哈希
        if (difficulty <= 0) return "0"

        val clamped = difficulty.coerceAtMost(MAX_DIFFICULTY)
        val startedAt = System.currentTimeMillis()
        val proof = withTimeoutOrNull(SOLVE_TIMEOUT_MS) {
            withContext(Dispatchers.Default) { solve(seed, clamped) }
        } ?: throw IOException("PoW 求解超时（难度 $difficulty）")

        Log.d(TAG, "pow difficulty=$difficulty cost=${System.currentTimeMillis() - startedAt}ms")
        return proof
    }

    /** 找最小的 counter，使 SHA-256("seed:counter") 前导零比特数 >= difficulty。 */
    private suspend fun solve(seed: String, difficulty: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val prefix = "$seed:"
        var counter = 0L
        var sinceCheck = 0
        while (true) {
            val hash = digest.digest("$prefix$counter".toByteArray(Charsets.UTF_8))
            if (leadingZeroBits(hash) >= difficulty) return counter.toString()
            counter++
            // 纯计算循环不会自动响应取消，定期检查一次
            if (++sinceCheck >= 8192) {
                sinceCheck = 0
                currentCoroutineContext().ensureActive()
            }
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

    // ---------------------------------------------------------------- JSON

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** JsonNull 也是 JsonElement，不能直接 as JsonObject；统一做安全取值。 */
    private fun JsonElement.objectAt(key: String): JsonObject? =
        ((this as? JsonObject)?.get(key)) as? JsonObject

    private fun JsonObject.stringAt(key: String): String? =
        (this[key] as? JsonPrimitive)?.content
}
