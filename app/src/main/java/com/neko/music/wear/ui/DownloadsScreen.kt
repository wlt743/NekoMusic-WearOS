package com.neko.music.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.ListHeader
import com.neko.music.wear.MainViewModel
import com.neko.music.wear.data.Music

/**
 * 「已下载」页：多选删除 + 一键全部删除（带确认）。
 */
@Composable
fun DownloadsScreen(
    state: MainViewModel.UiState,
    onBack: () -> Unit,
    onDelete: (Set<Int>) -> Unit,
    onDeleteAll: () -> Unit,
) {
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var confirmAll by remember { mutableStateOf(false) }

    val songs = state.cachedSongs

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
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
                Text(
                    text = "已下载",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (songs.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            selecting = !selecting
                            if (!selecting) selected = emptySet()
                        },
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Checklist,
                            contentDescription = "多选",
                            modifier = Modifier.size(18.dp),
                            tint = if (selecting) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }

        item {
            Text(
                text = if (songs.isEmpty()) {
                    "还没有下载的歌曲"
                } else {
                    "${songs.size} 首 · ${formatSize(state.cacheSizeBytes)}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = androidx.compose.ui.graphics.Color(0xFFC9C3D3),
            )
        }

        items(songs) { song ->
            DownloadRow(
                song = song,
                selecting = selecting,
                checked = song.id in selected,
                onClick = {
                    if (selecting) {
                        selected = if (song.id in selected) selected - song.id else selected + song.id
                    }
                },
            )
        }

        if (songs.isNotEmpty()) {
            item {
                if (selecting) {
                    TextButton(
                        onClick = {
                            onDelete(selected)
                            selected = emptySet()
                            selecting = false
                        },
                        enabled = selected.isNotEmpty(),
                    ) {
                        Text(if (selected.isEmpty()) "选择要删除的歌" else "删除所选 ${selected.size} 首")
                    }
                } else {
                    TextButton(onClick = { confirmAll = true }) {
                        Text("全部删除")
                    }
                }
            }
        }
    }

    if (confirmAll) {
        // 表和手机的配色不同，这里给对话框单独套一套深色配色
        MaterialTheme(
            colorScheme = darkColorScheme(
                surface = androidx.compose.ui.graphics.Color(0xFF1C1B1F),
                onSurface = androidx.compose.ui.graphics.Color(0xFFE6E1E5),
                primary = androidx.compose.ui.graphics.Color(0xFF9EC6FF),
                onPrimary = androidx.compose.ui.graphics.Color(0xFF06121F),
                surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFF2A282E),
            ),
        ) {
            AlertDialog(
                onDismissRequest = { confirmAll = false },
                title = { Text("删除全部下载") },
                text = { Text("是否确认删除喵") },
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(32.dp),
                containerColor = androidx.compose.ui.graphics.Color(0xFF1C1B1F),
                titleContentColor = androidx.compose.ui.graphics.Color(0xFFE6E1E5),
                textContentColor = androidx.compose.ui.graphics.Color(0xFFCAC4D0),
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmAll = false
                            onDeleteAll()
                        },
                    ) { Text("确认") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmAll = false }) { Text("取消") }
                },
            )
        }
    }
}

@Composable
private fun DownloadRow(
    song: Music,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
            if (selecting) {
                Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                    if (checked) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "已选中",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
