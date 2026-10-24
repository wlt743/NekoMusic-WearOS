package com.neko.music.wear.ui

import androidx.compose.runtime.Composable
import androidx.wear.compose.material3.MaterialTheme

/**
 * Wear Material 3 默认就是深色表盘配色，这里直接复用，保证组件与系统表盘风格一致。
 */
@Composable
fun NekoTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}
