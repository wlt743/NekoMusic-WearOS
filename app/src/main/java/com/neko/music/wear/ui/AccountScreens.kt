package com.neko.music.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.TextButton
import com.neko.music.wear.MainViewModel
import com.neko.music.wear.data.FavoriteMusic
import com.neko.music.wear.data.Music
import com.neko.music.wear.data.PlaylistInfo

@Composable
private fun PageHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(34.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                modifier = Modifier.size(18.dp),
            )
        }
        Text(text = title, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
fun LoginScreen(
    state: MainViewModel.UiState,
    onStart: () -> Unit,
    onBack: () -> Unit,
) {
    val bitmap = remember(state.qrImage) { decodeQr(state.qrImage) }
    var expanded by remember { mutableStateOf(false) }

    if (expanded && bitmap != null) {
        // 铺满屏幕放大显示，方便小屏幕手表被手机扫到
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.White)
                .clickable { expanded = false },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = "登录二维码（放大）",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp),
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PageHeader(title = "扫码登录", onBack = onBack)

        if (bitmap != null) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .background(androidx.compose.ui.graphics.Color.White, CircleShape)
                    .clickable { expanded = true }
                    .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "登录二维码",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else if (state.qrLoading) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
        }

        Text(
            text = state.qrStatus.ifBlank { "正在准备二维码…" },
            style = MaterialTheme.typography.labelSmall,
            color = androidx.compose.ui.graphics.Color(0xFFC9C3D3),
            textAlign = TextAlign.Center,
        )

        if (bitmap != null) {
            Text(
                text = "点二维码放大",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        TextButton(onClick = onStart) { Text("刷新") }
    }
}

/** 把后端返回的 data:image/png;base64,... 解成位图。 */
private fun decodeQr(dataUrl: String): androidx.compose.ui.graphics.ImageBitmap? {
    if (dataUrl.isBlank()) return null
    val base64 = dataUrl.substringAfter("base64,", dataUrl)
    return runCatching {
        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        bitmap?.asImageBitmap()
    }.getOrNull()
}

@Composable
fun ProfileScreen(
    state: MainViewModel.UiState,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onFavorites: () -> Unit,
    onPlaylists: () -> Unit,
    onLogout: () -> Unit,
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, top = 22.dp, end = 8.dp, bottom = 64.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item { PageHeader(title = "账号", onBack = onBack) }

        if (state.token.isBlank()) {
            item {
                Text(
                    text = "未登录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
            }
            item { TextButton(onClick = onLogin) { Text("去登录") } }
        } else {
            item {
                Text(
                    text = state.nickname.ifBlank { "已登录" },
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            item {
                Card(
                    onClick = onFavorites,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Column {
                        Text("我的收藏", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "${state.favorites.size} 首",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            item {
                Card(
                    onClick = onPlaylists,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Column {
                        Text("我的歌单", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "${state.playlists.size} 个",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            item { TextButton(onClick = onLogout) { Text("退出登录") } }
        }
    }
}

@Composable
fun FavoritesScreen(
    state: MainViewModel.UiState,
    onBack: () -> Unit,
    onPlay: (FavoriteMusic) -> Unit,
    onDownload: (FavoriteMusic) -> Unit,
    onRefresh: () -> Unit,
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, top = 22.dp, end = 8.dp, bottom = 64.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item { PageHeader(title = "我的收藏", onBack = onBack) }

        if (state.libraryLoading) {
            item { CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp) }
        }

        if (state.favorites.isEmpty() && !state.libraryLoading) {
            item {
                Text(
                    text = "还没有收藏",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
            item { TextButton(onClick = onRefresh) { Text("刷新") } }
        }

        items(state.favorites) { favorite ->
            SwipeDownloadRow(
                title = favorite.title.ifBlank { "未知歌曲" },
                subtitle = favorite.artist.ifBlank { "未知歌手" },
                coverUrl = com.neko.music.wear.data.ApiConfig.coverUrl(favorite.id),
                cached = favorite.id in state.cachedIds,
                downloading = favorite.id in state.downloadingIds,
                onClick = { onPlay(favorite) },
                onDownload = { onDownload(favorite) },
            )
        }
    }
}

@Composable
fun PlaylistsScreen(
    state: MainViewModel.UiState,
    onBack: () -> Unit,
    onOpen: (PlaylistInfo) -> Unit,
    onRefresh: () -> Unit,
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, top = 22.dp, end = 8.dp, bottom = 64.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item { PageHeader(title = "我的歌单", onBack = onBack) }

        if (state.libraryLoading) {
            item { CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp) }
        }

        if (state.playlists.isEmpty() && !state.libraryLoading) {
            item {
                Text(
                    text = "还没有歌单",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
            item { TextButton(onClick = onRefresh) { Text("刷新") } }
        }

        items(state.playlists) { playlist ->
            Card(
                onClick = { onOpen(playlist) },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val cover = playlist.coverPath ?: playlist.firstMusicCover
                    if (!cover.isNullOrBlank()) {
                        coil3.compose.AsyncImage(
                            model = if (cover.startsWith("http")) cover else com.neko.music.wear.data.ApiConfig.BASE + cover,
                            contentDescription = null,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = playlist.name.ifBlank { "未命名歌单" },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${playlist.musicCount} 首",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistSongsScreen(
    state: MainViewModel.UiState,
    title: String,
    onBack: () -> Unit,
    onPlay: (Music) -> Unit,
    onDownload: (Music) -> Unit,
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, top = 22.dp, end = 8.dp, bottom = 64.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item { PageHeader(title = title, onBack = onBack) }

        if (state.libraryLoading) {
            item { CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp) }
        }

        items(state.playlistSongs) { song ->
            SwipeDownloadRow(
                title = song.title.ifBlank { "未知歌曲" },
                subtitle = song.artist.ifBlank { "未知歌手" },
                cached = song.id in state.cachedIds,
                downloading = song.id in state.downloadingIds,
                onClick = { onPlay(song) },
                onDownload = { onDownload(song) },
            )
        }
    }
}
