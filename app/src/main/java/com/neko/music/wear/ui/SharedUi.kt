package com.neko.music.wear.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CircularProgressIndicator
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** 底部迷你播放条：所有页面（播放页除外）共用。 */
@Composable
fun MiniPlayer(
    title: String,
    playing: Boolean,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onOpen,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(22.dp),
        color = androidx.compose.ui.graphics.Color(0xFF26262E),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevious, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "上一首", modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onTogglePlay, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "暂停" else "播放",
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onNext, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.SkipNext, contentDescription = "下一首", modifier = Modifier.size(16.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 2.dp, end = 6.dp),
            )
        }
    }
}

/**
 * 列表行：左滑露出「下载」，点击下载；已下载显示勾。
 * 首页、我的收藏、歌单内容共用同一套交互。
 */
@Composable
fun SwipeDownloadRow(
    title: String,
    subtitle: String,
    coverUrl: String? = null,
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
            when {
                downloading -> CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                )

                cached -> Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "已缓存",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )

                else -> IconButton(
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
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!coverUrl.isNullOrBlank()) {
                    coil3.compose.AsyncImage(
                        model = coverUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitle,
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

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb < 1.0) "%.0f KB".format(bytes / 1024.0) else "%.1f MB".format(mb)
}
