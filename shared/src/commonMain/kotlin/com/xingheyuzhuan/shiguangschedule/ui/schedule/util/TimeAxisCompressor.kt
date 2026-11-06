package com.xingheyuzhuan.shiguangschedule.ui.schedule.util

import com.xingheyuzhuan.shiguangschedule.data.db.main.TimeSlot

/**
 * 时间轴压缩器（节次模式的绝对时间布局专用）。
 *
 * 把相邻节次之间超过 [DEFAULT_MAX_GAP_MINUTES] 的大段空档（如午休 12:10-14:10、
 * 晚休 17:50-19:00）压缩为固定小间隙，小课间保持真实时长不变，
 * 使课表整体紧凑、无大面积空白。
 *
 * 网格（ScheduleGrid）与左侧时间轴（TimeColumn）必须使用同一个实例的映射规则，
 * 保证课程块、节次标签、网格横线三者严格对齐。
 */
class TimeAxisCompressor(
    val sortedSlots: List<TimeSlot>,
    private val maxGapMinutes: Int = DEFAULT_MAX_GAP_MINUTES
) {
    private data class Gap(val realStart: Int, val realEnd: Int, val cut: Int)

    private val gaps: List<Gap>

    /** 时间轴起点（第一节开始，真实分钟）。 */
    val axisStartMin: Int

    /** 时间轴终点（最后一节结束，真实分钟）。 */
    val axisEndMin: Int

    /** 压缩后的总时长（分钟），用于 px/分钟 换算。 */
    val compressedTotalMin: Int

    init {
        axisStartMin = sortedSlots.firstOrNull()?.let { hhmmToMinute(it.startTime) } ?: 0
        axisEndMin = sortedSlots.lastOrNull()?.let { hhmmToMinute(it.endTime) } ?: (24 * 60)

        val list = mutableListOf<Gap>()
        var prevEnd: Int? = null
        for (slot in sortedSlots) {
            val s = hhmmToMinute(slot.startTime)
            val e = hhmmToMinute(slot.endTime)
            if (s != null && e != null && e > s) {
                val pe = prevEnd
                if (pe != null && s - pe > maxGapMinutes) {
                    list += Gap(realStart = pe, realEnd = s, cut = s - pe - maxGapMinutes)
                }
                prevEnd = e
            }
        }
        gaps = list
        compressedTotalMin = (axisEndMin - axisStartMin - gaps.sumOf { it.cut }).coerceAtLeast(1)
    }

    /**
     * 真实分钟（自 00:00 起）→ 压缩后相对分钟（相对 [axisStartMin]）。
     * 在大空档内部按线性比例映射，保证单调连续。
     */
    fun compress(min: Int): Float {
        var cut = 0f
        for (g in gaps) {
            when {
                min >= g.realEnd -> cut += g.cut
                min > g.realStart -> {
                    val frac = (min - g.realStart).toFloat() / (g.realEnd - g.realStart)
                    cut += g.cut * frac
                }
            }
        }
        return min - axisStartMin - cut
    }

    /**
     * 压缩后相对分钟 → 命中的节次号（1 起）。
     * 落在空档（含被压缩的大空档）时归到下一节，末尾之后归最后一节。
     */
    fun sectionAtCompressed(relMin: Float): Int {
        val idx = sortedSlots.indexOfFirst { slot ->
            val e = hhmmToMinute(slot.endTime) ?: return@indexOfFirst false
            relMin < compress(e)
        }
        return (if (idx < 0) sortedSlots.size - 1 else idx) + 1
    }

    companion object {
        /** 超过该时长的相邻节次空档会被压缩到此值。 */
        const val DEFAULT_MAX_GAP_MINUTES = 30
    }
}

/** "HH:mm" → 分钟数（自 00:00 起）。解析失败返回 null。 */
private fun hhmmToMinute(text: String?): Int? {
    if (text.isNullOrBlank()) return null
    val parts = text.split(":")
    if (parts.size != 2) return null
    val h = parts[0].trim().toIntOrNull() ?: return null
    val m = parts[1].trim().toIntOrNull() ?: return null
    return h * 60 + m
}
