package com.neko.music.wear.ui

data class LyricLine(val timeMs: Long, val text: String)

private val TIME_TAG = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")

/** 解析 LRC。支持一行多个时间标签，按时间升序返回。 */
fun parseLrc(raw: String): List<LyricLine> {
    if (raw.isBlank()) return emptyList()
    val lines = mutableListOf<LyricLine>()

    raw.lineSequence().forEach { line ->
        val tags = TIME_TAG.findAll(line).toList()
        if (tags.isEmpty()) return@forEach
        val text = line.substring(tags.last().range.last + 1).trim()
        if (text.isEmpty()) return@forEach

        tags.forEach { tag ->
            val minute = tag.groupValues[1].toLongOrNull() ?: return@forEach
            val second = tag.groupValues[2].toLongOrNull() ?: return@forEach
            val fraction = tag.groupValues[3]
            val millis = when (fraction.length) {
                0 -> 0L
                1 -> fraction.toLong() * 100
                2 -> fraction.toLong() * 10
                else -> fraction.take(3).toLong()
            }
            lines += LyricLine(minute * 60_000 + second * 1_000 + millis, text)
        }
    }

    return lines.sortedBy { it.timeMs }
}

/** 当前时间对应的歌词行下标；没有歌词时返回 -1。 */
fun currentLyricIndex(lines: List<LyricLine>, positionMs: Long): Int {
    if (lines.isEmpty()) return -1
    for (index in lines.indices.reversed()) {
        if (lines[index].timeMs <= positionMs + 200) return index
    }
    return 0
}
