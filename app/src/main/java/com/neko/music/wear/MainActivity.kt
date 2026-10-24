package com.neko.music.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.MaterialTheme
import com.neko.music.wear.ui.DownloadsScreen
import com.neko.music.wear.ui.FavoritesScreen
import com.neko.music.wear.ui.LoginScreen
import com.neko.music.wear.ui.PlaylistSongsScreen
import com.neko.music.wear.ui.PlaylistsScreen
import com.neko.music.wear.ui.ProfileScreen
import com.neko.music.wear.ui.HomeScreen
import com.neko.music.wear.ui.MenuScreen
import com.neko.music.wear.ui.MiniPlayer
import com.neko.music.wear.ui.NekoTheme
import com.neko.music.wear.ui.PlayerScreen

private enum class Screen { Home, Menu, Downloads, Player, Account, Login, Favorites, Playlists, PlaylistSongs }

/** 每个覆盖层按返回时的上一级；null 表示回到首页。 */
private fun parentOf(screen: Screen?): Screen? = when (screen) {
    Screen.Menu -> null
    Screen.Account -> Screen.Menu
    Screen.Downloads -> Screen.Menu
    Screen.Login -> Screen.Account
    Screen.Favorites -> Screen.Account
    Screen.Playlists -> Screen.Account
    Screen.PlaylistSongs -> Screen.Playlists
    else -> null
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    /** 当前覆盖层：菜单 / 已下载。放 Activity 上，返回键才拿得到。 */
    private var overlay by mutableStateOf<Screen?>(null)

    /** 打开歌单时记住名字，给歌单内容页做标题。 */
    private var playlistTitle by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    viewModel.state.value.showPlayer -> viewModel.closePlayer()
                    overlay != null -> {
                        val target = parentOf(overlay)
                        viewModel.stopQrLogin()
                        overlay = target
                    }
                    else -> {
                        // 已经在首页，交回系统退出
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

        setContent {
            NekoTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()

                // 播放状态由 ViewModel 决定，优先级高于菜单 / 已下载页
                val target = when {
                    state.showPlayer -> Screen.Player
                    overlay != null -> overlay!!
                    else -> Screen.Home
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0B0B0F),
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    AnimatedContent(
                        targetState = target,
                        transitionSpec = {
                            when (targetState) {
                                Screen.Player -> {
                                    (slideInVertically(tween(280)) { it / 6 } + fadeIn(tween(220))) togetherWith
                                        fadeOut(tween(140))
                                }

                                Screen.Home -> {
                                    (fadeIn(tween(240)) + slideInVertically(tween(240)) { it / 10 }) togetherWith
                                        fadeOut(tween(180))
                                }

                                else -> {
                                    (fadeIn(tween(200)) + slideInHorizontally(tween(240)) { it / 5 }) togetherWith
                                        (fadeOut(tween(140)) + slideOutHorizontally(tween(200)) { -it / 8 })
                                }
                            }
                        },
                        label = "screen",
                    ) { screen ->
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier.fillMaxSize(),
                        ) {
                        when (screen) {
                            Screen.Player -> PlayerScreen(
                                state = state,
                                onTogglePlay = viewModel::togglePlayPause,
                                onPrevious = { viewModel.skip(-1) },
                                onNext = { viewModel.skip(1) },
                                onSeek = viewModel::seekToRatio,
                                onBack = viewModel::closePlayer,
                            )

                            Screen.Menu -> MenuScreen(
                                state = state,
                                onBack = { overlay = null },
                                onOpenDownloads = { overlay = Screen.Downloads },
                                onOpenAccount = { overlay = Screen.Account },
                            )

                            Screen.Account -> ProfileScreen(
                                state = state,
                                onBack = { overlay = Screen.Menu },
                                onLogin = { overlay = Screen.Login },
                                onFavorites = {
                                    viewModel.loadFavorites()
                                    overlay = Screen.Favorites
                                },
                                onPlaylists = {
                                    viewModel.loadPlaylists()
                                    overlay = Screen.Playlists
                                },
                                onLogout = viewModel::logout,
                            )

                            Screen.Login -> {
                                androidx.compose.runtime.LaunchedEffect(Unit) {
                                    viewModel.startQrLogin()
                                }
                                // 登录成功后停掉状态流，短暂展示“登录成功”再自动跳到账号页
                                androidx.compose.runtime.LaunchedEffect(state.token) {
                                    if (state.token.isNotBlank()) {
                                        viewModel.stopQrLogin()
                                        viewModel.loadFavorites()
                                        viewModel.loadPlaylists()
                                        kotlinx.coroutines.delay(800)
                                        overlay = Screen.Account
                                    }
                                }
                                LoginScreen(
                                    state = state,
                                    onStart = viewModel::startQrLogin,
                                    onBack = {
                                        viewModel.stopQrLogin()
                                        overlay = Screen.Account
                                    },
                                )
                            }

                            Screen.Favorites -> FavoritesScreen(
                                state = state,
                                onBack = { overlay = Screen.Account },
                                onPlay = { viewModel.playFavorite(it) },
                                onDownload = viewModel::downloadFavorite,
                                onRefresh = viewModel::loadFavorites,
                            )

                            Screen.Playlists -> PlaylistsScreen(
                                state = state,
                                onBack = { overlay = Screen.Account },
                                onOpen = {
                                    viewModel.openPlaylist(it.id)
                                    playlistTitle = it.name
                                    overlay = Screen.PlaylistSongs
                                },
                                onRefresh = viewModel::loadPlaylists,
                            )

                            Screen.PlaylistSongs -> PlaylistSongsScreen(
                                state = state,
                                title = playlistTitle,
                                onBack = { overlay = Screen.Playlists },
                                onPlay = { viewModel.play(it) },
                                onDownload = viewModel::download,
                            )

                            Screen.Downloads -> DownloadsScreen(
                                state = state,
                                onBack = { overlay = Screen.Menu },
                                onDelete = viewModel::deleteCached,
                                onDeleteAll = viewModel::clearCache,
                            )

                            Screen.Home -> HomeScreen(
                                state = state,
                                onTabSelected = viewModel::selectTab,
                                onSongClick = viewModel::play,
                                onOpenPlayer = viewModel::openPlayer,
                                onOpenMenu = { overlay = Screen.Menu },
                                onDownload = viewModel::download,
                            )
                        }

                        // 播放页以外，所有页面底部常驻迷你播放条
                        if (screen != Screen.Player && state.current != null) {
                            MiniPlayer(
                                title = state.current?.title.orEmpty().ifBlank { "未知歌曲" },
                                playing = state.playing,
                                onTogglePlay = viewModel::togglePlayPause,
                                onPrevious = { viewModel.skip(-1) },
                                onNext = { viewModel.skip(1) },
                                onOpen = viewModel::openPlayer,
                                modifier = Modifier
                                    .align(androidx.compose.ui.Alignment.BottomCenter)
                                    .fillMaxWidth(0.62f)
                                    .padding(bottom = 18.dp),
                            )
                        }
                        }
                    }
                }
            }
        }
    }
}
