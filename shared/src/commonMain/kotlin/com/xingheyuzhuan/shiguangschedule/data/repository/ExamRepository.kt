package com.xingheyuzhuan.shiguangschedule.data.repository

import com.xingheyuzhuan.shiguangschedule.data.api.exam.ExamApiClient
import com.xingheyuzhuan.shiguangschedule.data.api.exam.ExamRecord
import com.xingheyuzhuan.shiguangschedule.data.api.exam.ExamSnapshot
import com.xingheyuzhuan.shiguangschedule.data.api.exam.JwxtExamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

/**
 * 考试安排领域仓库。
 *
 * 职责：
 * 1. 把教务系统原始数据解析成界面模型（等价于 xust-grade-web/core/exam.py 的 normalize()）；
 * 2. 把解析结果以 JSON 静态缓存落盘（等价 data/exam_<xh>.json），避免每次打开都请求教务；
 * 3. 提供「读缓存优先 + 手动强制刷新」的数据合成策略。
 */
@Single
class ExamRepository(
    private val fileSystem: FileSystem,
    @Named("FilesDir") private val filesDir: Path,
    private val appSettingsRepository: AppSettingsRepository,
    private val userRepository: JwxtUserRepository
) {

    private val examApiClient = ExamApiClient()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // 用于触发缓存重新加载的通知流
    private val _reloadSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * 触发缓存重载信号（写入或删除缓存后调用）。
     */
    fun refresh() {
        _reloadSignal.tryEmit(Unit)
    }

    /**
     * 观察本地缓存的考试安排快照。
     */
    val examSnapshotFlow: Flow<ExamSnapshot> = _reloadSignal
        .onStart { emit(Unit) }
        .map { loadSnapshot() }
        .distinctUntilChanged()

    /**
     * 一次性读取本地缓存快照（不存在或损坏时返回空快照）。
     */
    suspend fun loadSnapshot(studentId: String = ""): ExamSnapshot {
        return withContext(Dispatchers.IO) {
            val xh = resolveStudentId(studentId)
            val path = cacheFilePath(xh)
            val text = JwxtCacheStore.readText(fileSystem, path) ?: return@withContext ExamSnapshot()
            runCatching { json.decodeFromString<ExamSnapshot>(text) }.getOrDefault(ExamSnapshot())
        }
    }

    /**
     * 合成考试安排：
     * 缓存存在且非空时直接复用；否则（或强制刷新时）向教务系统请求一次并更新缓存。
     *
     * @param force 是否跳过缓存直接抓取
     */
    suspend fun getExamSchedule(force: Boolean = false): Result<ExamSnapshot> {
        return withContext(Dispatchers.IO) {
            val studentId = resolveStudentId()
            if (studentId.isEmpty()) {
                return@withContext Result.failure(IllegalStateException(NOT_LOGGED_IN_MESSAGE))
            }

            val cached = loadSnapshot(studentId)
            if (!force && cached.exams.isNotEmpty()) {
                return@withContext Result.success(cached)
            }

            val settings = appSettingsRepository.getAppSettingsOnce()
            val cookie = userRepository.ensureSession(studentId)
                ?: return@withContext Result.failure(IllegalStateException(NOT_LOGGED_IN_MESSAGE))

            var items = examApiClient.fetchExamItems(
                cookie = cookie,
                schoolYear = settings.schoolYear,
                schoolTerm = settings.schoolTerm
            )

            // 会话失效时用已保存的账号密码静默重登一次，再取一次数据
            if (items == null) {
                val newCookie = userRepository.refreshSession(studentId)
                items = newCookie?.let {
                    examApiClient.fetchExamItems(
                        cookie = it,
                        schoolYear = settings.schoolYear,
                        schoolTerm = settings.schoolTerm
                    )
                }
            }

            if (items == null) {
                return@withContext Result.failure(IllegalStateException(FETCH_FAILED_MESSAGE))
            }

            val snapshot = ExamSnapshot(
                studentId = studentId,
                schoolYear = settings.schoolYear,
                schoolTerm = settings.schoolTerm,
                fetchedAt = JwxtCacheStore.nowText(),
                exams = normalize(items)
            )

            writeCache(snapshot)
            refresh()

            Result.success(snapshot)
        }
    }

    /**
     * 清除本地缓存（例如切换或删除账号后调用）。
     */
    suspend fun clearCache(studentId: String = "") {
        withContext(Dispatchers.IO) {
            val xh = resolveStudentId(studentId)
            JwxtCacheStore.delete(fileSystem, cacheFilePath(xh))
        }
        refresh()
    }

    private suspend fun resolveStudentId(studentId: String = ""): String {
        val trimmed = studentId.trim()
        return trimmed.ifEmpty { userRepository.getCurrentStudentId() }
    }

    /**
     * 把考试快照写入本地 JSON 缓存。
     */
    private fun writeCache(snapshot: ExamSnapshot) {
        val path = cacheFilePath(snapshot.studentId)
        JwxtCacheStore.writeText(fileSystem, path, json.encodeToString(snapshot))
    }

    /**
     * 缓存文件路径：filesDir/exam/exam_<学号>.json
     */
    private fun cacheFilePath(studentId: String): Path {
        return JwxtCacheStore.filePath(filesDir, CACHE_DIR_NAME, CACHE_FILE_PREFIX, studentId)
    }

    companion object {
        private const val CACHE_DIR_NAME = "exam"
        private const val CACHE_FILE_PREFIX = "exam_"

        private const val NOT_LOGGED_IN_MESSAGE = "尚未添加教务账号"
        private const val FETCH_FAILED_MESSAGE =
            "考试安排获取失败（教务系统可能尚未发布本学期考试安排）"

        /** 缺失字段的统一占位符 */
        private const val PLACEHOLDER = "—"

        private const val FALLBACK_SORT_DATE = "9999-99-99"

        private val TIME_RANGE_REGEX = Regex("(\\d{4}-\\d{2}-\\d{2})\\s*[（(]([^）)]*)[）)]")
        private val TEACHER_SPLIT_REGEX = Regex("[;；]")

        /**
         * 把教务原始考试条目转换成界面模型，并按考试时间升序排序。
         * （对应 core/exam.py 的 normalize / parse_time / format_teachers）
         */
        fun normalize(items: List<JwxtExamItem>?): List<ExamRecord> {
            val result = items.orEmpty().mapNotNull { item ->
                val (day, range) = parseTime(item.timeText)

                ExamRecord(
                    course = item.course?.trim()?.takeIf { it.isNotEmpty() } ?: PLACEHOLDER,
                    timeText = item.timeText?.trim()?.takeIf { it.isNotEmpty() } ?: PLACEHOLDER,
                    date = day,
                    timeRange = range,
                    place = item.place?.trim()?.takeIf { it.isNotEmpty() } ?: PLACEHOLDER,
                    seat = item.seat?.trim()?.takeIf { it.isNotEmpty() } ?: PLACEHOLDER,
                    credit = item.credit?.trim()?.takeIf { it.isNotEmpty() } ?: PLACEHOLDER,
                    college = item.college?.trim()?.takeIf { it.isNotEmpty() } ?: PLACEHOLDER,
                    teachers = formatTeachers(item.teacherInfo).ifEmpty { PLACEHOLDER },
                    method = item.method?.trim()?.takeIf { it.isNotEmpty() } ?: PLACEHOLDER,
                    campus = item.campus?.trim()?.takeIf { it.isNotEmpty() } ?: PLACEHOLDER,
                    examName = item.examName?.trim().orEmpty(),
                    academicYear = item.academicYear?.trim().orEmpty(),
                    term = item.term?.trim().orEmpty()
                )
            }

            return result.sortedWith(
                compareBy<ExamRecord> { it.date.ifEmpty { FALLBACK_SORT_DATE } }
                    .thenBy { it.timeRange }
            )
        }

        /**
         * '2026-06-25(14:00-16:00)' -> ('2026-06-25', '14:00-16:00')
         */
        fun parseTime(raw: String?): Pair<String, String> {
            val text = raw?.trim().orEmpty()
            val match = TIME_RANGE_REGEX.find(text)

            return if (match != null) {
                match.groupValues[1] to match.groupValues[2].trim()
            } else {
                text to ""
            }
        }

        /**
         * '001344/罗清郁;000850/刘文刚' -> '罗清郁、刘文刚'
         */
        fun formatTeachers(raw: String?): String {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return ""

            return TEACHER_SPLIT_REGEX.split(text)
                .map { part ->
                    val trimmed = part.trim()
                    if (trimmed.contains('/')) trimmed.substringAfter('/').trim() else trimmed
                }
                .filter { it.isNotEmpty() }
                .joinToString("、")
        }
    }
}
