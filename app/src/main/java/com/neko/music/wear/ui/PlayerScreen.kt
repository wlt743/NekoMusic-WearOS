package com.neko.music.wear.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.CircularProgressIndicator
import coil3.compose.AsyncImage
import com.neko.music.wear.MainViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun PlayerScreen(
    state: MainViewModel.UiState,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onBack: () -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val threshold = with(density) { 72.dp.toPx() }

    var offsetX by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(offsetX.roundToInt(), 0) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    // 记录手势开始时的页，避免从歌词页右滑回控制页时被误判为返回
                    val startPage = pagerState.currentPage
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dx = 0f
                    var following = false
                    var swipedRight = false
                    var childConsumed = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        // 进度条自己消费了这次手势（拖进度），就不要当成返回
                        if (change.isConsumed) childConsumed = true

                        if (!following &&
                            kotlin.math.abs(dx) > viewConfiguration.touchSlop &&
                            kotlin.math.abs(dx) > kotlin.math.abs(dy)
                        ) {
                            following = true
                        }

                        // 第一页右滑：整页跟手右移，做出「退出」的物理反馈
                        if (following && startPage == 0 && dx > 0f && !childConsumed) {
                            swipedRight = true
                            offsetX = dx
                        }

                        if (!change.pressed) break
                    }

                    if (startPage == 0 && swipedRight && !childConsumed) {
                        val shouldBack = dx > threshold
                        scope.launch {
                            animate(
                                initialValue = offsetX,
                                targetValue = if (shouldBack) size.width.toFloat() else 0f,
                                animationSpec = tween(durationMillis = 180),
                            ) { value, _ -> offsetX = value }

                            if (shouldBack) {
                                onBack()
                            } else {
                                offsetX = 0f
                            }
                        }
                    }
                }
            },
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            if (page == 0) {
                ControlsPage(
                    state = state,
                    onTogglePlay = onTogglePlay,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onSeek = onSeek,
                )
            } else {
                LyricsPage(state = state)
            }
        }
    }
}

@Composable
private fun ControlsPage(
    state: MainViewModel.UiState,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    val song = state.current ?: return
    val progress = if (state.durationMs > 0) {
        state.positionMs.toFloat() / state.durationMs
    } else {
        0f
    }
    val lyricLine = state.lyrics.getOrNull(state.lyricIndex)?.text.orEmpty()
    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            AsyncImage(
                model = song.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape),
            )
        }

        item {
            Text(
                text = song.title.ifBlank { "未知歌曲" },
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item {
            Text(
                text = song.artist.ifBlank { "未知歌手" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item {
            Text(
                text = lyricLine.ifBlank { " " },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item {
            SeekBar(
                progress = progress,
                onSeek = onSeek,
                modifier = Modifier.fillMaxWidth(0.86f),
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(0.7f),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatTime(state.positionMs), style = MaterialTheme.typography.labelSmall)
                Text(formatTime(state.durationMs), style = MaterialTheme.typography.labelSmall)
            }
        }

        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPrevious, modifier = Modifier.size(46.dp)) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "上一首")
                }
                IconButton(onClick = onTogglePlay, modifier = Modifier.size(52.dp)) {
                    if (state.preparing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    } else {
                        Icon(
                            imageVector = if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (state.playing) "暂停" else "播放",
                        )
                    }
                }
                IconButton(onClick = onNext, modifier = Modifier.size(46.dp)) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "下一首")
                }
            }
        }
    }
}

@Composable
private fun LyricsPage(state: MainViewModel.UiState) {
    if (state.lyrics.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "暂无歌词",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val listState = rememberScalingLazyListState()

    LaunchedEffect(state.lyricIndex) {
        if (state.lyricIndex >= 0) {
            runCatching {
                listState.animateScrollToItem((state.lyricIndex - 2).coerceAtLeast(0))
            }
        }
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(state.lyrics) { index, line ->
            val active = index == state.lyricIndex
            Text(
                text = line.text,
                style = if (active) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.bodySmall
                },
                color = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 按 Material 3 滑块规范绘制：轨道 4dp、拇指 20dp、拇指两侧各留 4dp 间隙、末端停止点 4dp。
 * 触摸区域 40dp 高，按下即定位，拖动实时跟随，抬手提交。
 */
@Composable
private fun SeekBar(
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val shown = (if (dragging) dragValue else progress).coerceIn(0f, 1f)

    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.surfaceVariant
    val stopColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    Box(
        modifier = modifier
            .height(30.dp)
            .pointerInput(Unit) {
                val inset = 14.dp.toPx()
                val usable = (size.width - inset * 2).coerceAtLeast(1f)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var claimed = false
                    var released = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y

                        if (!claimed) {
                            // 竖向滑动交给外层列表滚动，别抢手势
                            if (kotlin.math.abs(dy) > viewConfiguration.touchSlop &&
                                kotlin.math.abs(dy) > kotlin.math.abs(dx)
                            ) {
                                break
                            }
                            if (kotlin.math.abs(dx) > viewConfiguration.touchSlop) {
                                claimed = true
                                dragging = true
                            }
                        }

                        if (claimed) {
                            dragValue = ((change.position.x - inset) / usable).coerceIn(0f, 1f)
                            change.consume()
                        }

                        if (!change.pressed) {
                            released = true
                            break
                        }
                    }

                    if (claimed && released) {
                        dragging = false
                        onSeek(dragValue)
                    } else if (!claimed && released) {
                        // 单击定位
                        val tapped = ((down.position.x - inset) / usable).coerceIn(0f, 1f)
                        onSeek(tapped)
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val inset = 14.dp.toPx()
            val startX = inset
            val endX = size.width - inset
            val trackWidth = 4.dp.toPx()
            val thumbRadius = 10.dp.toPx()
            val gap = 4.dp.toPx()
            val thumbX = startX + (endX - startX) * shown

            val activeEnd = thumbX - thumbRadius - gap
            val inactiveStart = thumbX + thumbRadius + gap

            if (inactiveStart < endX) {
                drawLine(
                    color = inactiveColor,
                    start = Offset(inactiveStart, cy),
                    end = Offset(endX, cy),
                    strokeWidth = trackWidth,
                    cap = StrokeCap.Round,
                )
            }

            if (activeEnd > startX) {
                drawLine(
                    color = activeColor,
                    start = Offset(startX, cy),
                    end = Offset(activeEnd, cy),
                    strokeWidth = trackWidth,
                    cap = StrokeCap.Round,
                )
            }

            // 末端停止点指示器
            drawCircle(color = stopColor, radius = 2.dp.toPx(), center = Offset(endX, cy))

            // 拇指
            drawCircle(color = activeColor, radius = thumbRadius, center = Offset(thumbX, cy))
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
