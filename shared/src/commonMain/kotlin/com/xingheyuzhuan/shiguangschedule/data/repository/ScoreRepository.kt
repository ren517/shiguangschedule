package com.xingheyuzhuan.shiguangschedule.data.repository

import com.xingheyuzhuan.shiguangschedule.data.api.score.JwxtScoreItem
import com.xingheyuzhuan.shiguangschedule.data.api.score.ScoreApiClient
import com.xingheyuzhuan.shiguangschedule.data.api.score.ScoreRecord
import com.xingheyuzhuan.shiguangschedule.data.api.score.ScoreSnapshot
import com.xingheyuzhuan.shiguangschedule.data.api.score.ScoreStats
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
 * 成绩领域仓库。
 *
 * 职责：
 * 1. 调教务成绩接口 + 逐门明细解析（等价 xust-grade-web/core/score.py）；
 * 2. 结果落盘为静态 JSON 缓存（等价 data/score_<xh>.json）；
 * 3. 提供「读缓存优先 + 手动强制刷新」的数据合成策略。
 */
@Single
class ScoreRepository(
    private val fileSystem: FileSystem,
    @Named("FilesDir") private val filesDir: Path,
    private val appSettingsRepository: AppSettingsRepository,
    private val userRepository: JwxtUserRepository
) {

    private val scoreApiClient = ScoreApiClient()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _reloadSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 触发缓存重载信号 */
    fun refresh() {
        _reloadSignal.tryEmit(Unit)
    }

    /** 观察本地缓存的成绩快照 */
    val scoreSnapshotFlow: Flow<ScoreSnapshot> = _reloadSignal
        .onStart { emit(Unit) }
        .map { loadSnapshot() }
        .distinctUntilChanged()

    /**
     * 一次性读取本地缓存快照（不存在或损坏时返回空快照）。
     */
    suspend fun loadSnapshot(studentId: String = ""): ScoreSnapshot {
        return withContext(Dispatchers.IO) {
            val xh = resolveStudentId(studentId)
            val path = JwxtCacheStore.filePath(filesDir, CACHE_DIR_NAME, CACHE_FILE_PREFIX, xh)
            val text = JwxtCacheStore.readText(fileSystem, path) ?: return@withContext ScoreSnapshot()
            runCatching { json.decodeFromString<ScoreSnapshot>(text) }.getOrDefault(ScoreSnapshot())
        }
    }

    /**
     * 合成成绩数据：缓存非空且非强制时直接复用；否则向教务系统查询一次。
     *
     * @param force 是否跳过缓存直接抓取
     */
    suspend fun getScores(force: Boolean = false): Result<ScoreSnapshot> {
        return withContext(Dispatchers.IO) {
            val studentId = resolveStudentId()
            if (studentId.isEmpty()) {
                return@withContext Result.failure(IllegalStateException(NOT_LOGGED_IN_MESSAGE))
            }

            val cached = loadSnapshot(studentId)
            if (!force && cached.scores.isNotEmpty()) {
                return@withContext Result.success(cached)
            }

            val settings = appSettingsRepository.getAppSettingsOnce()
            val cookie = userRepository.ensureSession(studentId)
                ?: return@withContext Result.failure(IllegalStateException(NOT_LOGGED_IN_MESSAGE))

            var items = scoreApiClient.fetchScoreItems(
                cookie = cookie,
                schoolYear = settings.schoolYear,
                schoolTerm = settings.schoolTerm
            )

            // 会话失效时用已保存密码静默重登一次再取
            if (items == null) {
                val newCookie = userRepository.refreshSession(studentId)
                items = newCookie?.let {
                    scoreApiClient.fetchScoreItems(
                        cookie = it,
                        schoolYear = settings.schoolYear,
                        schoolTerm = settings.schoolTerm
                    )
                }
            }

            if (items == null) {
                return@withContext Result.failure(IllegalStateException(FETCH_FAILED_MESSAGE))
            }

            val records = normalize(
                items = items,
                cookie = cookie,
                schoolYear = settings.schoolYear,
                schoolTerm = settings.schoolTerm
            )

            val snapshot = ScoreSnapshot(
                studentId = studentId,
                schoolYear = settings.schoolYear,
                schoolTerm = settings.schoolTerm,
                fetchedAt = JwxtCacheStore.nowText(),
                scores = records,
                stats = buildStats(records)
            )

            writeCache(snapshot)
            refresh()

            Result.success(snapshot)
        }
    }

    /**
     * 清除本地成绩缓存。
     */
    suspend fun clearCache(studentId: String = "") {
        withContext(Dispatchers.IO) {
            val xh = resolveStudentId(studentId)
            val path = JwxtCacheStore.filePath(filesDir, CACHE_DIR_NAME, CACHE_FILE_PREFIX, xh)
            JwxtCacheStore.delete(fileSystem, path)
        }
        refresh()
    }

    private suspend fun resolveStudentId(studentId: String = ""): String {
        val trimmed = studentId.trim()
        return trimmed.ifEmpty { userRepository.getCurrentStudentId() }
    }

    private fun writeCache(snapshot: ScoreSnapshot) {
        val path = JwxtCacheStore.filePath(
            filesDir, CACHE_DIR_NAME, CACHE_FILE_PREFIX, snapshot.studentId
        )
        JwxtCacheStore.writeText(fileSystem, path, json.encodeToString(snapshot))
    }

    /**
     * 把教务原始成绩条目转换成界面模型，并逐门补充明细。
     */
    private suspend fun normalize(
        items: List<JwxtScoreItem>,
        cookie: String,
        schoolYear: String,
        schoolTerm: String
    ): List<ScoreRecord> {
        return items.map { item ->
            val detailHtml = scoreApiClient.fetchScoreDetailHtml(
                cookie = cookie,
                classId = item.classId,
                schoolYear = schoolYear,
                schoolTerm = schoolTerm,
                studentIdHash = item.studentIdHash,
                courseName = item.course
            )
            val details = scoreApiClient.parseDetail(detailHtml)

            ScoreRecord(
                course = item.course?.trim().orEmpty().ifEmpty { PLACEHOLDER },
                score = item.score?.trim().orEmpty().ifEmpty { PLACEHOLDER },
                credit = item.credit?.trim().orEmpty(),
                gradePoint = item.gradePoint?.trim().orEmpty(),
                nature = item.nature?.trim().orEmpty(),
                category = item.category?.trim().orEmpty(),
                publishTime = item.publishTime?.trim().orEmpty(),
                teacher = item.teacher?.trim().orEmpty(),
                examType = item.examType?.trim().orEmpty(),
                academicYear = item.academicYear?.trim().orEmpty(),
                term = item.term?.trim().orEmpty(),
                details = details
            )
        }
    }

    /**
     * 统计课程门数、总学分与加权平均绩点。
     */
    private fun buildStats(records: List<ScoreRecord>): ScoreStats {
        var totalCredits = 0f
        var weighted = 0f
        var gpaCredits = 0f

        records.forEach { record ->
            val credit = record.credit.toFloatOrNull() ?: return@forEach
            val point = record.gradePoint.toFloatOrNull()

            totalCredits += credit
            if (point != null) {
                weighted += credit * point
                gpaCredits += credit
            }
        }

        return ScoreStats(
            count = records.size,
            credits = totalCredits,
            gpa = if (gpaCredits > 0f) weighted / gpaCredits else null
        )
    }

    companion object {
        private const val CACHE_DIR_NAME = "score"
        private const val CACHE_FILE_PREFIX = "score_"

        private const val NOT_LOGGED_IN_MESSAGE = "尚未添加教务账号"
        private const val FETCH_FAILED_MESSAGE = "成绩查询失败（教务系统可能尚未发布本学期成绩）"

        private const val PLACEHOLDER = "—"
    }
}
