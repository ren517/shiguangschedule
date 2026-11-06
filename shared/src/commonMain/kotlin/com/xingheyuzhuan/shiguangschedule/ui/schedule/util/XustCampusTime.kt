package com.xingheyuzhuan.shiguangschedule.ui.schedule.util

import com.xingheyuzhuan.shiguangschedule.data.db.main.TimeSlot

/**
 * 西安科技大学（XUST）多校区作息工具。
 *
 * 背景：临潼校区分为骊山校园东区、骊山校园西区、秦汉校区。
 * 上午第 3、4 节课东西区（含秦汉）时间不同，下午及晚上各区一致。
 * 课程地点形如「校区-楼号-房号」或「楼号-房号」（如 `2-16-508` 楼16=西区、
 * `2-3-505` 楼3=东区）。本工具按楼号/校区关键词自动判定课程所属校区，
 * 并据此返回正确的节次起止时间，使卡片按楼号显示对应时间。
 *
 * 作息来源（校方作息表）：
 *  - 西区（骊山校园西区，9~17 号教学楼为主校区）：第3节 10:25-11:15，第4节 11:20-12:10
 *  - 东区/秦汉（骊山校园东区 1/2/3 号教学楼、实验楼；秦汉校区）：第3节 10:05-10:55，第4节 11:00-11:50
 *  - 第1、2、5~10 节各区完全一致。
 */
object XustCampusTime {

    /** 校区枚举：西区（默认），东区/秦汉（上午 3、4 节不同）。 */
    enum class XustCampus {
        WEST,        // 骊山校园西区
        EAST_QINHAN  // 骊山校园东区 / 秦汉校区
    }

    /** 西区作息（与 CourseTableRepository.defaultTimeSlots 一致）。 */
    private val WEST_SLOTS = listOf(
        "08:05" to "08:55",
        "09:00" to "09:50",
        "10:25" to "11:15",
        "11:20" to "12:10",
        "14:10" to "15:00",
        "15:05" to "15:55",
        "16:05" to "16:55",
        "17:00" to "17:50",
        "19:00" to "19:50",
        "20:00" to "20:50"
    )

    /** 东区 / 秦汉校区作息（仅上午第 3、4 节与西区不同）。 */
    private val EAST_QINHAN_SLOTS = listOf(
        "08:05" to "08:55",
        "09:00" to "09:50",
        "10:05" to "10:55",
        "11:00" to "11:50",
        "14:10" to "15:00",
        "15:05" to "15:55",
        "16:05" to "16:55",
        "17:00" to "17:50",
        "19:00" to "19:50",
        "20:00" to "20:50"
    )

    /**
     * 根据课程地点判定校区。
     * @return 命中则返回对应校区；无法判定返回 null（调用方沿用默认西区作息）。
     */
    fun detectCampus(position: String?): XustCampus? {
        if (position.isNullOrBlank()) return null
        val p = position.trim()

        // 1. 明确的校区 / 楼宇关键词
        if (p.contains("秦汉")) return XustCampus.EAST_QINHAN
        if (p.contains("东区") || p.contains("骊山东") || p.contains("临潼东")) return XustCampus.EAST_QINHAN
        if (p.contains("西区") || p.contains("骊山西") || p.contains("临潼西")) return XustCampus.WEST
        if (p.contains("实验")) return XustCampus.EAST_QINHAN   // 临潼校区东区实验楼
        if (p.contains("工程训练") || p.contains("体育馆")) return XustCampus.WEST

        // 2. 按「校区-楼号-房号」/「楼号-房号」取楼号
        val segs = p.split("-").map { it.trim() }.filter { it.isNotEmpty() }
        val buildingCandidate: Int? = when (segs.size) {
            3 -> segs[1].toIntOrNull()   // 校区-楼号-房号
            2 -> segs[0].toIntOrNull()   // 楼号-房号
            1 -> segs[0].toIntOrNull()
            else -> null
        }
        if (buildingCandidate != null) {
            campusFromBuilding(buildingCandidate)?.let { return it }
        }

        // 3. 兜底：扫描所有 1~2 位数字组，优先 2 位楼号
        val digitGroups = Regex("""\d{1,2}""").findAll(p).mapNotNull { it.value.toIntOrNull() }.toList()
        digitGroups.firstOrNull { it in 9..17 }?.let { return XustCampus.WEST }
        digitGroups.firstOrNull { it in 1..3 }?.let { return XustCampus.EAST_QINHAN }

        return null
    }

    /**
     * 返回某校区的 10 个节次起止时间表（索引 0 对应第 1 节）。
     */
    fun campusSlots(campus: XustCampus): List<Pair<String, String>> {
        return if (campus == XustCampus.EAST_QINHAN) EAST_QINHAN_SLOTS else WEST_SLOTS
    }

    /**
     * 在基础节次时间表上套用校区作息覆盖（仅覆盖判定校区的第 3、4 节等差异项）。
     * 无法判定校区时原样返回。
     */
    fun effectiveTimeSlots(base: List<TimeSlot>, position: String?): List<TimeSlot> {
        val campus = detectCampus(position) ?: return base
        val override = campusSlots(campus)
        if (override.isEmpty()) return base
        return base.map { slot ->
            val idx = slot.number - 1
            if (idx in override.indices) {
                val (s, e) = override[idx]
                slot.copy(startTime = s, endTime = e)
            } else {
                slot
            }
        }
    }

    /**
     * 楼号 → 校区映射：
     * 1/2/3 号教学楼、实验楼等属东区；9~17 号教学楼、工程训练中心、体育馆属西区；
     * 4~8 等未知楼号返回 null（沿用默认西区作息）。
     */
    private fun campusFromBuilding(n: Int): XustCampus? = when (n) {
        in 1..3 -> XustCampus.EAST_QINHAN
        in 9..17 -> XustCampus.WEST
        else -> null
    }
}
