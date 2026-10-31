package com.neko.music.wear.ui

import android.content.Intent
import com.neko.music.wear.BuildConfig
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.TextButton
import coil3.compose.AsyncImage
import com.neko.music.wear.MainViewModel
import com.neko.music.wear.data.Music
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    state: MainViewModel.UiState,
    onTabSelected: (Int) -> Unit,
    onSongClick: (Music) -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenMenu: () -> Unit,
    onDownload: (Music) -> Unit,
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, top = 28.dp, end = 8.dp, bottom = 76.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onOpenMenu, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Menu,
                        contentDescription = "菜单",
                        modifier = Modifier.size(18.dp),
                    )
                }
                Column(modifier = Modifier.padding(start = 2.dp)) {
                    Text("NekoWear", style = MaterialTheme.typography.titleSmall)
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onTabSelected(0) }) { Text("排行") }
                TextButton(onClick = { onTabSelected(1) }) { Text("最新") }
            }
        }

        if (state.loading && state.songs.isEmpty()) {
            item { CircularProgressIndicator() }
        }

        state.error?.let { message ->
            item {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        items(state.songs) { song ->
            SongCard(
                song = song,
                cached = song.id in state.cachedIds,
                downloading = song.id in state.downloadingIds,
                onClick = { onSongClick(song) },
                onDownload = { onDownload(song) },
            )
        }

        if (state.songs.isNotEmpty()) {
            item {
                TextButton(onClick = onOpenPlayer) { Text("播放页") }
            }
        }
    }
}

@Composable
fun MenuScreen(
    state: MainViewModel.UiState,
    onBack: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenEgg: () -> Unit,
) {
    val context = LocalContext.current
    // 彩蛋：连点版本号 7 下（间隔 800ms 内）进入游戏
    var eggTaps by remember { mutableIntStateOf(0) }
    var eggLastTap by remember { mutableLongStateOf(0L) }
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, top = 28.dp, end = 8.dp, bottom = 64.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            ListHeader { Text("菜单", style = MaterialTheme.typography.titleSmall) }
        }

        item {
            MenuCard(
                title = "账号",
                subtitle = if (state.token.isBlank()) "未登录" else state.nickname.ifBlank { "已登录" },
                onClick = onOpenAccount,
            )
        }

        item {
            MenuCard(
                title = "已下载",
                subtitle = if (state.cachedCount == 0) {
                    "暂无下载 · 左滑歌曲可下载"
                } else {
                    "${state.cachedCount} 首 · ${formatSize(state.cacheSizeBytes)}"
                },
                onClick = onOpenDownloads,
            )
        }

        item {
            MenuCard(
                title = "版本",
                subtitle = if (isNewerVersion(state.watchLatestVersion, BuildConfig.VERSION_NAME)) {
                    "${BuildConfig.VERSION_NAME} · 有新版本 ${state.watchLatestVersion}"
                } else {
                    BuildConfig.VERSION_NAME
                },
                onClick = {
                    val now = System.currentTimeMillis()
                    eggTaps = if (now - eggLastTap < 800L) eggTaps + 1 else 1
                    eggLastTap = now
                    if (eggTaps >= 7) {
                        eggTaps = 0
                        onOpenEgg()
                    }
                },
            )
        }

        item { MenuCard(title = "开发者", subtitle = "wlt743", onClick = null) }

        item {
            MenuCard(
                title = "GitHub 仓库",
                subtitle = REPO_LABEL,
                onClick = {
                    // 手表上没有浏览器的情况下静默失败，不影响其它操作
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
                    }
                },
            )
        }

        item { TextButton(onClick = onBack) { Text("返回") } }
    }
}

/** 服务端版本比本地新才提示，等于或落后时不打扰。 */
private fun isNewerVersion(remote: String, local: String): Boolean {
    if (remote.isBlank()) return false
    val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
    val l = local.split(".").map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(r.size, l.size)) {
        val a = r.getOrElse(i) { 0 }
        val b = l.getOrElse(i) { 0 }
        if (a != b) return a > b
    }
    return false
}

private const val REPO_URL = "https://github.com/wlt743/NekoMusic-WearOS"
private const val REPO_LABEL = "wlt743/NekoMusic-WearOS"

@Composable
private fun MenuCard(title: String, subtitle: String, onClick: (() -> Unit)?) {
    if (onClick == null) {
        Card(
            onClick = {},
            enabled = true,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            MenuCardContent(title = title, subtitle = subtitle)
        }
    } else {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            MenuCardContent(title = title, subtitle = subtitle)
        }
    }
}

@Composable
private fun MenuCardContent(title: String, subtitle: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = androidx.compose.ui.graphics.Color(0xFFC9C3D3),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SongCard(
    song: Music,
    cached: Boolean,
    downloading: Boolean,
    onClick: () -> Unit,
    onDownload: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val actionWidth = with(density) { 64.dp.toPx() }
    var offsetX by remember { mutableFloatStateOf(0f) }

    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(end = 4.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            if (downloading) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            } else if (cached) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "已缓存",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                IconButton(
                    onClick = {
                        scope.launch {
                            animate(offsetX, 0f, animationSpec = tween(160)) { value, _ -> offsetX = value }
                        }
                        onDownload()
                    },
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = "下载",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        Card(
            onClick = {
                if (offsetX < 0f) {
                    scope.launch {
                        animate(offsetX, 0f, animationSpec = tween(160)) { value, _ -> offsetX = value }
                    }
                } else {
                    onClick()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .pointerInput(actionWidth) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            offsetX = (offsetX + dragAmount).coerceIn(-actionWidth, 0f)
                        },
                        onDragEnd = {
                            scope.launch {
                                animate(
                                    initialValue = offsetX,
                                    targetValue = if (offsetX < -actionWidth / 2f) -actionWidth else 0f,
                                    animationSpec = tween(160),
                                ) { value, _ -> offsetX = value }
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                animate(offsetX, 0f, animationSpec = tween(160)) { value, _ -> offsetX = value }
                            }
                        },
                    )
                },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AsyncImage(
                    model = song.coverUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title.ifBlank { "未知歌曲" },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = song.artist.ifBlank { "未知歌手" },
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (cached) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "已缓存",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
