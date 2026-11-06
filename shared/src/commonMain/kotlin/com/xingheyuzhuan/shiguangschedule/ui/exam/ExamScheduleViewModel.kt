package com.xingheyuzhuan.shiguangschedule.ui.exam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.data.api.exam.ExamRecord
import com.xingheyuzhuan.shiguangschedule.data.api.exam.ExamSnapshot
import com.xingheyuzhuan.shiguangschedule.data.model.AppSettingsModel
import com.xingheyuzhuan.shiguangschedule.data.repository.AppSettingsRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.ExamRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.JwxtUserRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

/**
 * 考试安排界面状态包
 *
 * @property exams 当前学期考试列表（已按考试时间升序）
 * @property schoolYear 当前学年起始年
 * @property schoolTerm 当前学期代号
 * @property lastSyncText 缓存同步时间文本
 * @property isLoggedIn 教务账号是否已登录
 * @property isRefreshing 是否正在向教务系统请求最新考试安排
 */
data class ExamUiState(
    val exams: List<ExamRecord> = emptyList(),
    val schoolYear: String = "",
    val schoolTerm: String = "",
    val lastSyncText: String = "",
    val isLoggedIn: Boolean = false,
    val isRefreshing: Boolean = false
)

/**
 * 手动刷新的结果事件
 */
sealed interface ExamRefreshResult {
    /** 刷新成功，携带考试条数 */
    data class Success(val count: Int) : ExamRefreshResult

    /** 刷新失败，携带原因 */
    data class Failure(val reason: String) : ExamRefreshResult
}

@KoinViewModel
class ExamScheduleViewModel(
    private val examRepository: ExamRepository,
    appSettingsRepository: AppSettingsRepository,
    private val userRepository: JwxtUserRepository
) : ViewModel() {

    private val refreshSignal = MutableStateFlow(false)

    /**
     * 聚合「考试缓存 + 学年学期设置 + 教务登录态 + 刷新中」四路数据源。
     */
    val uiState: StateFlow<ExamUiState> = combine(
        examRepository.examSnapshotFlow,
        appSettingsRepository.getAppSettings(),
        userRepository.currentStudentIdFlow,
        refreshSignal
    ) { snapshot, settings, studentId, isRefreshing ->
        ExamUiState(
            exams = snapshot.exams,
            schoolYear = settings.schoolYear,
            schoolTerm = settings.schoolTerm,
            lastSyncText = snapshot.fetchedAt,
            isLoggedIn = studentId.isNotBlank(),
            isRefreshing = isRefreshing
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = ExamUiState()
    )

    /** 一次性刷新结果事件 */
    private val _refreshResult = MutableSharedFlow<ExamRefreshResult>(extraBufferCapacity = 1)
    val refreshResult: SharedFlow<ExamRefreshResult> = _refreshResult

    /**
     * 手动向教务系统拉取最新考试安排（覆盖本地缓存）。
     */
    fun refreshExamSchedule() {
        viewModelScope.launch {
            if (refreshSignal.value) return@launch
            refreshSignal.value = true

            val result: Result<ExamSnapshot> = try {
                examRepository.getExamSchedule(force = true)
            } catch (e: Exception) {
                Result.failure(e)
            }

            refreshSignal.value = false

            val event = result.fold(
                onSuccess = { snapshot -> ExamRefreshResult.Success(snapshot.exams.size) },
                onFailure = { error -> ExamRefreshResult.Failure(error.message.orEmpty()) }
            )
            _refreshResult.tryEmit(event)
        }
    }

    /**
     * 首次进入时若本地没有缓存且已登录，则静默拉一次，
     * 等价于 xust-grade-web/core/exam.py 中 get_exam 的缓存兜底策略。
     */
    private suspend fun syncIfEmpty() {
        if (examRepository.loadSnapshot().exams.isNotEmpty()) return
        if (userRepository.getCurrentStudentId().isEmpty()) return

        runCatching { examRepository.getExamSchedule(force = true) }
    }

    init {
        viewModelScope.launch {
            syncIfEmpty()
        }
    }
}

/**
 * 学年学期展示文本的拼装规则：2026-2027学年 第一学期
 */
fun AppSettingsModel.termYearText(): String {
    val startYear = schoolYear.toIntOrNull()
    return if (startYear != null) "$startYear-${startYear + 1}" else schoolYear
}
