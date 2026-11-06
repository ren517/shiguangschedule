package com.xingheyuzhuan.shiguangschedule.data.repository

import com.xingheyuzhuan.shiguangschedule.data.api.schedule.JwxtScheduleItem
import com.xingheyuzhuan.shiguangschedule.data.api.schedule.ScheduleApiClient
import com.xingheyuzhuan.shiguangschedule.data.db.main.Course
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseTableConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 课表同步仓库：把教务系统的个人课表直接写进本机课表数据库。
 *
 * 爬取与解析规则对齐 xust-grade-web/core/schedule.py：
 * - 接口 kbcx/xskbcx_cxXsgrkb.html，表单 xnm / xqm / kzlx=ck
 * - zcd 解析为周次集合（支持 1-16周、2-16周(双)、1-8周,10-16周 等写法）
 * - jc 解析为起止节次
 * 同步策略为「整表替换」：清空当前课表已有课程后重新写入。
 */
@Single
class ScheduleSyncRepository(
    private val appSettingsRepository: AppSettingsRepository,
    private val courseTableRepository: CourseTableRepository,
    private val userRepository: JwxtUserRepository
) {

    private val scheduleApiClient = ScheduleApiClient()

    /**
     * 同步当前账号在所选学年学期的课表到当前课表。
     *
     * @return 成功时返回写入的课程条数，失败时返回携带原因的异常
     */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun syncSchedule(targetTableId: String? = null): Result<Int> {
        return withContext(Dispatchers.IO) {
            val studentId = userRepository.getCurrentStudentId()
            if (studentId.isEmpty()) {
                return@withContext Result.failure(IllegalStateException(NOT_LOGGED_IN_MESSAGE))
            }

            val settings = appSettingsRepository.getAppSettingsOnce()

            // 优先使用调用方选定的课表，否则回退到当前课表 / 数据库首个课表
            val tableId = if (!targetTableId.isNullOrEmpty()) {
                targetTableId
            } else {
                settings.currentCourseTableId.ifEmpty {
                    courseTableRepository.getAllCourseTables().first().firstOrNull()?.id.orEmpty()
                }
            }

            if (tableId.isEmpty()) {
                return@withContext Result.failure(IllegalStateException(NO_COURSE_TABLE_MESSAGE))
            }

            val cookie = userRepository.ensureSession(studentId)
                ?: return@withContext Result.failure(IllegalStateException(NOT_LOGGED_IN_MESSAGE))

            var items = scheduleApiClient.fetchScheduleItems(
                cookie = cookie,
                schoolYear = settings.schoolYear,
                schoolTerm = settings.schoolTerm
            )

            // 会话失效时静默重登一次再取
            if (items == null) {
                val newCookie = userRepository.refreshSession(studentId)
                items = newCookie?.let {
                    scheduleApiClient.fetchScheduleItems(
                        cookie = it,
                        schoolYear = settings.schoolYear,
                        schoolTerm = settings.schoolTerm
                    )
                }
            }

            if (items == null) {
                return@withContext Result.failure(IllegalStateException(FETCH_FAILED_MESSAGE))
            }

            // 整表替换：先清空当前课表已有课程
            courseTableRepository.clearCourses(tableId)

            var writtenCount = 0
            items.forEach { item ->
                val course = item.toCourse(tableId) ?: return@forEach
                courseTableRepository.upsertCourse(course, parseWeeks(item.weekText))
                writtenCount++
            }

            updateTotalWeeks(tableId, items)

            // 同步成功后把目标课表设为当前显示课表，主页直接展示刚同步的内容
            appSettingsRepository.insertOrUpdateAppSettings(
                settings.copy(currentCourseTableId = tableId)
            )

            Result.success(writtenCount)
        }
    }

    /**
     * 用课表中出现过的最大周次刷新学期总周数，避免周次切换条范围过短。
     */
    private suspend fun updateTotalWeeks(tableId: String, items: List<JwxtScheduleItem>) {
        val maxWeek = items.maxOfOrNull { parseWeeks(it.weekText).maxOrNull() ?: 0 } ?: return
        if (maxWeek <= 0) return

        val config = appSettingsRepository.getCourseConfigOnce(tableId)
            ?: CourseTableConfig(courseTableId = tableId)

        appSettingsRepository.insertOrUpdateCourseConfig(
            config.copy(semesterTotalWeeks = maxWeek.coerceAtLeast(config.semesterTotalWeeks))
        )
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun JwxtScheduleItem.toCourse(tableId: String): Course? {
        val day = parseDay(dayOfWeek, dayOfWeekName) ?: return null
        val (startSection, endSection) = parseSectionSpan(sectionText)

        val courseName = course?.trim().orEmpty()
        if (courseName.isEmpty()) return null

        return Course(
            id = Uuid.random().toString(),
            courseTableId = tableId,
            name = courseName,
            teacher = teacher?.trim().orEmpty(),
            position = place?.trim().orEmpty(),
            day = day,
            startSection = startSection,
            endSection = endSection,
            isCustomTime = false,
            customStartTime = null,
            customEndTime = null,
            colorInt = stableColorIndex(courseName),
            remark = buildRemark(this),
            credit = credit?.trim()?.toFloatOrNull()
        )
    }

    /**
     * 备注：保留课程性质 / 考核方式 / 班级 / 校区等教务原始信息。
     */
    private fun buildRemark(item: JwxtScheduleItem): String? {
        val parts = listOfNotNull(
            item.nature?.trim()?.takeIf { it.isNotEmpty() },
            item.examMethod?.trim()?.takeIf { it.isNotEmpty() },
            item.classGroup?.trim()?.takeIf { it.isNotEmpty() },
            item.campus?.trim()?.takeIf { it.isNotEmpty() }
        )
        return parts.joinToString(" · ").takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val NOT_LOGGED_IN_MESSAGE = "尚未添加教务账号"
        private const val NO_COURSE_TABLE_MESSAGE = "当前没有可写入的课表"
        private const val FETCH_FAILED_MESSAGE = "课表同步失败（教务系统可能未发布本学期课表）"

        /** 课程配色数量，与课表样式表的默认色板一致 */
        private const val COLOR_COUNT = 8

        private val WEEKDAY_CN = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")

        private val WEEK_SPLIT_REGEX = Regex("[,，、]")
        private val NUMBER_REGEX = Regex("\\d+")

        /**
         * 星期几：优先 xqj 数字字段，其次 xqjmc 中文（对应 core/schedule.py 的 _weekday_of）
         */
        fun parseDay(dayOfWeek: String?, dayOfWeekName: String?): Int? {
            val raw = dayOfWeek?.trim().orEmpty()
            if (raw.isNotEmpty() && raw.all { it.isDigit() }) {
                val value = raw.toIntOrNull()
                if (value != null && value in 1..7) return value
            }

            val name = dayOfWeekName?.trim().orEmpty()
            val index = WEEKDAY_CN.indexOf(name)
            return if (index >= 0) index + 1 else null
        }

        /**
         * '1-2节' → (1, 2)；'第3节' → (3, 3)；解析失败 → (99, 99)
         */
        fun parseSectionSpan(sectionText: String?): Pair<Int, Int> {
            val numbers = NUMBER_REGEX.findAll(sectionText.orEmpty())
                .map { it.value.toIntOrNull() }
                .filterNotNull()
                .toList()

            if (numbers.isEmpty()) return 99 to 99

            val first = numbers.first()
            val second = numbers.getOrElse(1) { first }

            return if (second < first) second to first else first to second
        }

        /**
         * 解析周次字段为周数集合（对应 core/schedule.py 的 parse_weeks）。
         *
         * 支持：'1-16周'、'2-16周(双)'、'1-8周,10-16周'、'3周' 等写法。
         */
        fun parseWeeks(weekText: String?): List<Int> {
            val weeks = mutableSetOf<Int>()
            if (weekText.isNullOrBlank()) return emptyList()

            WEEK_SPLIT_REGEX.split(weekText).forEach { part ->
                val text = part.trim()
                if (text.isEmpty()) return@forEach

                val odd = text.contains("单")
                val even = text.contains("双")

                val numbers = NUMBER_REGEX.findAll(text)
                    .map { it.value.toIntOrNull() }
                    .filterNotNull()
                    .toList()

                if (numbers.isEmpty()) return@forEach

                val range = if (numbers.size >= 2) {
                    val from = minOf(numbers[0], numbers[1])
                    val to = maxOf(numbers[0], numbers[1])
                    from..to
                } else {
                    numbers[0]..numbers[0]
                }

                range.forEach { week ->
                    if (odd && week % 2 == 0) return@forEach
                    if (even && week % 2 == 1) return@forEach
                    weeks.add(week)
                }
            }

            return weeks.sorted()
        }

        /**
         * 同名课程永远映射到同一个配色索引。
         */
        private fun stableColorIndex(courseName: String): Int {
            val hash = courseName.fold(0) { acc, char -> acc * 31 + char.code }
            return (hash and Int.MAX_VALUE) % COLOR_COUNT
        }
    }
}
