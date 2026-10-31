package com.neko.music.wear.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.abs

// 贪吃蛇，照 GitHub 上 iower/snake-js-1kb（990 字节）移植：14×14，150ms 一步。
private const val N = 14

@Composable
fun SnakeGame(onExit: () -> Unit) {
    var s by remember { mutableStateOf(listOf(7 to 7)) }
    var d by remember { mutableIntStateOf(3) }   // 1 左 2 下 3 右 4 上
    var t by remember { mutableIntStateOf(0) }
    var f by remember { mutableStateOf(7 to 2) }
    var over by remember { mutableStateOf(false) }
    var score by remember { mutableIntStateOf(0) }

    fun reset() { s = listOf(7 to 7); d = 3; t = 0; f = 2 to 4; over = false; score = 0 }
    fun step() {
        if (t != 0) { d = t; t = 0 }
        val h = s.first()
        val n = when (d) {
            1 -> (h.first + N - 1) % N to h.second
            2 -> h.first to (h.second + 1) % N
            4 -> h.first to (h.second + N - 1) % N
            else -> (h.first + 1) % N to h.second
        }
        if (n in s) { over = true; return }
        s = listOf(n) + s
        if (n == f) {
            score++
            f = (0 until N).flatMap { x -> (0 until N).map { y -> x to y } }.filter { it !in s }.randomOrNull() ?: f
        } else s = s.dropLast(1)
    }

    LaunchedEffect(over) { while (!over) { delay(150); step() } }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(28.dp).pointerInput(over, d) {
            detectTapGestures(
                onTap = { o ->
                    if (over) reset() else {
                        val dx = o.x - size.width / 2f; val dy = o.y - size.height / 2f
                        val w = if (abs(dx) > abs(dy)) (if (dx > 0) 3 else 1) else (if (dy > 0) 2 else 4)
                        if (abs(w - d) != 2) t = w
                    }
                },
                onLongPress = { onExit() },
            )
        }) {
            val c = size.minDimension / N
            drawRect(Color(0x22000000))
            s.forEachIndexed { i, p ->
                drawRect(if (i == 0) Color(0xFF7C5CFF) else Color(0xFFB9A6FF),
                    Offset(p.first * c + c * .1f, p.second * c + c * .1f), Size(c * .8f, c * .8f))
            }
            drawCircle(Color(0xFFFF5C8A), c * .3f, Offset(f.first * c + c / 2, f.second * c + c / 2))
        }
        if (over) Text("得分 $score\n点一下重来\n长按退出", style = MaterialTheme.typography.bodyMedium,
            color = Color.White)
    }
}
