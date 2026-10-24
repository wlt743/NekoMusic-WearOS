package com.neko.music.wear

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Player
import com.neko.music.wear.data.FavoriteMusic
import com.neko.music.wear.data.Music
import com.neko.music.wear.data.PlaylistInfo
import com.neko.music.wear.data.NekoApi
import com.neko.music.wear.player.PlaybackService
import com.neko.music.wear.player.PlayerManager
import com.neko.music.wear.ui.LyricLine
import com.neko.music.wear.ui.currentLyricIndex
import com.neko.music.wear.ui.parseLrc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MainViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val tab: Int = 0,
        val songs: List<Music> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val current: Music? = null,
        val playing: Boolean = false,
        val showPlayer: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val preparing: Boolean = false,
        val lyrics: List<LyricLine> = emptyList(),
        val lyricIndex: Int = -1,
        val cacheSizeBytes: Long = 0L,
        val cachedCount: Int = 0,
        val cachedIds: Set<Int> = emptySet(),
        val token: String = "",
        val nickname: String = "",
        val accountLoading: Boolean = false,
        val favorites: List<FavoriteMusic> = emptyList(),
        val playlists: List<PlaylistInfo> = emptyList(),
        val playlistSongs: List<Music> = emptyList(),
        val serverVersion: String = "",
        val qrImage: String = "",
        val qrStatus: String = "",
        val qrLoading: Boolean = false,
        val libraryLoading: Boolean = false,
        val cachedSongs: List<Music> = emptyList(),
        val downloadingIds: Set<Int> = emptySet(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val player by lazy { PlayerManager.get(getApplication()) }

    private val cacheDir: File by lazy {
        File(getApplication<Application>().filesDir, "offline").apply { mkdirs() }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val prefs by lazy {
        getApplication<Application>().getSharedPreferences("neko_wear", android.content.Context.MODE_PRIVATE)
    }

    private val indexFile: File by lazy { File(cacheDir, "index.json") }


    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(playing = isPlaying) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> _state.update { it.copy(preparing = false) }
                    // 播完自动下一首（队列里只有一首，按当前列表顺序往后取）
                    Player.STATE_ENDED -> {
                        android.util.Log.d("NekoPlay", "播放结束，自动下一首")
                        skip(1)
                    }
                }
            }
        })

        viewModelScope.launch {
            while (isActive) {
                val duration = player.duration
                val position = player.currentPosition.coerceAtLeast(0L)
                _state.update { current ->
                    current.copy(
                        positionMs = position,
                        durationMs = if (duration == C.TIME_UNSET) 0L else duration,
                        lyricIndex = currentLyricIndex(current.lyrics, position),
                    )
                }
                delay(500)
            }
        }

        _state.update {
            it.copy(
                token = prefs.getString(KEY_TOKEN, "").orEmpty(),
                nickname = prefs.getString(KEY_NICKNAME, "").orEmpty(),
            )
        }
        viewModelScope.launch {
            val version = runCatching { NekoApi.serverVersion() }.getOrNull()
            if (!version.isNullOrBlank()) {
                _state.update { it.copy(serverVersion = version) }
            }
        }
        refreshCacheState()
        load(0)
    }

    /** 主动下载：列表里的下载按钮触发，缓存后断网也能播。 */
    fun download(song: Music) {
        if (song.id in _state.value.cachedIds || song.id in _state.value.downloadingIds) return
        _state.update { it.copy(downloadingIds = it.downloadingIds + song.id, error = null) }

        viewModelScope.launch {
            val url = runCatching { NekoApi.resolveAudioUrl(song.id) }.getOrElse { throwable ->
                _state.update {
                    it.copy(
                        downloadingIds = it.downloadingIds - song.id,
                        error = throwable.message ?: "下载失败",
                    )
                }
                return@launch
            }
            cacheSong(song, url)
            _state.update { it.copy(downloadingIds = it.downloadingIds - song.id) }
            refreshCacheState()
        }
    }

    /** 删除指定缓存（可多选）。 */
    fun deleteCached(ids: Set<Int>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                ids.forEach { id ->
                    cacheDir.listFiles()?.filter { it.name.startsWith("$id.") }?.forEach { it.delete() }
                }
            }
            refreshCacheState()
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                cacheDir.listFiles()?.forEach { it.delete() }
            }
            refreshCacheState()
        }
    }

    private fun refreshCacheState() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val mp3 = cacheDir.listFiles()?.filter { it.extension == "mp3" } ?: emptyList()
                Triple(
                    mp3.size,
                    mp3.sumOf { it.length() },
                    mp3.mapNotNull { it.nameWithoutExtension.toIntOrNull() }.toSet(),
                )
            }
            val songs = readCachedSongs()
            _state.update {
                it.copy(
                    cachedCount = result.first,
                    cacheSizeBytes = result.second,
                    cachedIds = result.third,
                    cachedSongs = songs,
                )
            }
        }
    }

    private suspend fun cacheSong(song: Music, url: String) = withContext(Dispatchers.IO) {
        val target = File(cacheDir, "${song.id}.mp3")
        if (!target.exists()) {
            val temp = File(cacheDir, "${song.id}.tmp")
            runCatching {
                URL(url).openStream().use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                temp.renameTo(target)
            }.onFailure { temp.delete() }
        }
        if (target.exists()) {
            // 记住元数据，断网时列表仍然可用
            val cached: List<Music> = readCachedSongs().filterNot { it.id == song.id } + song
            runCatching { indexFile.writeText(json.encodeToString<List<Music>>(cached)) }
        }
    }

    private fun readCachedSongs(): List<Music> = runCatching<List<Music>> {
        if (!indexFile.exists()) emptyList<Music>()
        else json.decodeFromString<List<Music>>(indexFile.readText())
    }.getOrDefault(emptyList())

    fun selectTab(index: Int) {
        if (index == _state.value.tab && _state.value.songs.isNotEmpty()) return
        _state.update { it.copy(tab = index) }
        load(index)
    }

    private fun load(index: Int) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                if (index == 0) NekoApi.ranking() else NekoApi.latest()
            }.onSuccess { songs ->
                _state.update { it.copy(songs = songs, loading = false, error = null) }
            }.onFailure { throwable ->
                val cached = readCachedSongs()
                if (cached.isNotEmpty()) {
                    _state.update {
                        it.copy(
                            songs = cached,
                            loading = false,
                            error = "离线模式：仅显示已缓存歌曲",
                        )
                    }
                } else {
                    _state.update { it.copy(loading = false, error = throwable.message ?: "加载失败") }
                }
            }
        }
    }

    fun refresh() = load(_state.value.tab)

    fun play(song: Music) {
        viewModelScope.launch {
            _state.update { it.copy(preparing = true, error = null) }
            val local = File(cacheDir, "${song.id}.mp3")
            val source = if (local.exists()) {
                Uri.fromFile(local).toString()
            } else {
                runCatching { NekoApi.resolveAudioUrl(song.id) }.getOrElse { throwable ->
                    _state.update {
                        it.copy(preparing = false, error = throwable.message ?: "播放失败")
                    }
                    return@launch
                }
            }

            runCatching { source }
                .onSuccess { url ->
                    val context = getApplication<Application>()
                    PlayerManager.play(context, url, song)
                    context.startService(Intent(context, PlaybackService::class.java))
                    _state.update {
                        it.copy(
                            current = song,
                            showPlayer = true,
                            preparing = false,
                            lyrics = emptyList(),
                            lyricIndex = -1,
                        )
                    }
                    loadLyrics(song.id)
                }
                .onFailure { throwable ->
                    _state.update {
                        it.copy(preparing = false, error = throwable.message ?: "播放失败")
                    }
                }
        }
    }

    private fun loadLyrics(id: Int) {
        viewModelScope.launch {
            runCatching { NekoApi.lyrics(id) }
                .onSuccess { raw ->
                    _state.update { it.copy(lyrics = parseLrc(raw)) }
                }
        }
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    /** 进度条拖动：按比例跳转。 */
    fun seekToRatio(fraction: Float) {
        val duration = _state.value.durationMs
        if (duration <= 0) return
        player.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
    }

    fun skip(offset: Int) {
        android.util.Log.d("NekoPlay", "skip offset=$offset")
        val current = _state.value.current ?: return
        val songs = _state.value.songs
        val index = songs.indexOfFirst { it.id == current.id }
        if (index < 0) return
        val next = songs[(index + offset + songs.size) % songs.size]
        play(next)
    }

    fun openPlayer() {
        if (_state.value.current != null) _state.update { it.copy(showPlayer = true) }
    }

    fun closePlayer() {
        _state.update { it.copy(showPlayer = false) }
    }

    private var qrJob: kotlinx.coroutines.Job? = null

    /** 扫码登录：取二维码 → 订阅状态流 → confirmed 后直接写入 token。 */
    fun startQrLogin() {
        // 防止重复进入页面时开多条会话
        qrJob?.cancel()
        qrJob = viewModelScope.launch {
            _state.update {
                it.copy(qrLoading = true, qrImage = "", qrStatus = "正在获取二维码…", error = null)
            }

            val response = runCatching { NekoApi.qrCreate() }.getOrElse { throwable ->
                _state.update {
                    it.copy(qrLoading = false, qrStatus = throwable.message ?: "获取二维码失败")
                }
                return@launch
            }

            val data = response.data
            if (!response.success || data == null) {
                _state.update {
                    it.copy(qrLoading = false, qrStatus = response.message.ifBlank { "获取二维码失败" })
                }
                return@launch
            }

            android.util.Log.d("NekoQr", "create sid=${data.sessionId} expiresIn=${data.expiresIn}")
            _state.update {
                it.copy(qrImage = data.qrImage, qrLoading = false, qrStatus = "等待手机扫码")
            }

            // 状态流是 SSE 长连接，断了就重连；最多 3 次
            repeat(3) { attempt ->
                val failed = runCatching {
                    NekoApi.qrStatusStream(data.sessionId) { status, token, user ->
                        when (status) {
                            "scanned" -> _state.update {
                                it.copy(qrStatus = "已扫码，请在手机上确认")
                            }

                            "confirmed" -> {
                                // 确认帧可能不带 token，需要用 deliveryKey 领一次
                                if (!token.isNullOrBlank()) {
                                    saveLogin(token, user?.nickname.orEmpty())
                                } else {
                                    viewModelScope.launch {
                                        _state.update { it.copy(qrStatus = "已确认，正在登录…") }
                                        val claim = runCatching {
                                            NekoApi.qrClaim(data.sessionId, data.deliveryKey)
                                        }.getOrNull()
                                        val claimedToken = claim?.data?.token
                                        if (!claimedToken.isNullOrBlank()) {
                                            saveLogin(claimedToken, claim?.data?.user?.nickname.orEmpty())
                                        } else {
                                            _state.update {
                                                it.copy(qrStatus = "领取登录凭证失败，请刷新二维码")
                                            }
                                        }
                                    }
                                }
                            }

                            "expired" -> _state.update {
                                it.copy(qrStatus = "二维码已过期，请刷新")
                            }
                        }
                    }
                }.isFailure

                val loggedIn = _state.value.token.isNotBlank()
                val expired = _state.value.qrStatus.contains("过期")
                if (!failed || loggedIn || expired) return@launch
                android.util.Log.w("NekoQr", "状态流中断，第 ${attempt + 1} 次")
                delay(1000)
            }

            _state.update {
                if (it.token.isBlank()) it.copy(qrStatus = "状态连接中断，请刷新二维码") else it
            }
        }
    }

    private fun saveLogin(token: String, nickname: String) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_NICKNAME, nickname)
            .apply()
        _state.update {
            it.copy(token = token, nickname = nickname, qrStatus = "登录成功")
        }
    }

    /** 离开扫码页时停掉状态流，别让它一直挂着。 */
    fun stopQrLogin() {
        qrJob?.cancel()
        qrJob = null
    }

    fun logout() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_NICKNAME).apply()
        _state.update {
            it.copy(token = "", nickname = "", favorites = emptyList(), playlists = emptyList())
        }
    }

    fun loadFavorites() {
        val token = _state.value.token
        if (token.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(libraryLoading = true, error = null) }
            runCatching { NekoApi.favorites(token) }
                .onSuccess { list ->
                    _state.update { it.copy(favorites = list, libraryLoading = false) }
                }
                .onFailure { throwable ->
                    _state.update {
                        it.copy(libraryLoading = false, error = throwable.message ?: "获取收藏失败")
                    }
                }
        }
    }

    fun loadPlaylists() {
        val token = _state.value.token
        if (token.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(libraryLoading = true, error = null) }
            runCatching { NekoApi.myPlaylists(token) }
                .onSuccess { list ->
                    _state.update { it.copy(playlists = list, libraryLoading = false) }
                }
                .onFailure { throwable ->
                    _state.update {
                        it.copy(libraryLoading = false, error = throwable.message ?: "获取歌单失败")
                    }
                }
        }
    }

    fun openPlaylist(playlistId: Int) {
        val token = _state.value.token
        if (token.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(libraryLoading = true, error = null) }
            runCatching { NekoApi.playlistSongs(token, playlistId) }
                .onSuccess { songs ->
                    _state.update { it.copy(playlistSongs = songs, libraryLoading = false) }
                }
                .onFailure { throwable ->
                    _state.update {
                        it.copy(libraryLoading = false, error = throwable.message ?: "获取歌单内容失败")
                    }
                }
        }
    }

    fun downloadFavorite(favorite: FavoriteMusic) {
        download(
            Music(
                id = favorite.id,
                title = favorite.title,
                artist = favorite.artist,
                album = favorite.album,
                duration = favorite.duration,
            ),
        )
    }

    fun playFavorite(favorite: FavoriteMusic) {
        play(
            Music(
                id = favorite.id,
                title = favorite.title,
                artist = favorite.artist,
                album = favorite.album,
                duration = favorite.duration,
            ),
        )
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }


    private companion object {
        const val KEY_TOKEN = "auth_token"
        const val KEY_NICKNAME = "auth_nickname"
    }
}
