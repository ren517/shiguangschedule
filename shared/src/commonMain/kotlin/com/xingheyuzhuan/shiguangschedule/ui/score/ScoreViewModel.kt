package com.xingheyuzhuan.shiguangschedule.ui.score

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.data.api.score.ScoreRecord
import com.xingheyuzhuan.shiguangschedule.data.api.score.ScoreSnapshot
import com.xingheyuzhuan.shiguangschedule.data.api.score.ScoreStats
import com.xingheyuzhuan.shiguangschedule.data.repository.AppSettingsRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.JwxtUserRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.ScoreRepository
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
 * 成绩界面状态包
 *
 * @property scores 当前学期的成绩列表
 * @property stats 成绩统计（门数 / 总学分 / 平均绩点）
 * @property schoolYear 当前学年（教务 xnm）
 * @property schoolTerm 当前学期（教务 xqm）
 * @property lastSyncText 缓存同步时间文本
 * @property hasAccount 是否已添加教务账号
 * @property isRefreshing 是否正在向教务系统请求最新成绩
 */
data class ScoreUiState(
    val scores: List<ScoreRecord> = emptyList(),
    val stats: ScoreStats = ScoreStats(),
    val schoolYear: String = "",
    val schoolTerm: String = "",
    val lastSyncText: String = "",
    val hasAccount: Boolean = false,
    val isRefreshing: Boolean = false
)

/**
 * 手动刷新的结果事件
 */
sealed interface ScoreRefreshResult {
    /** 刷新成功，携带成绩条数 */
    data class Success(val count: Int) : ScoreRefreshResult

    /** 刷新失败，携带原因 */
    data class Failure(val reason: String) : ScoreRefreshResult
}

@KoinViewModel
class ScoreViewModel(
    private val scoreRepository: ScoreRepository,
    appSettingsRepository: AppSettingsRepository,
    private val userRepository: JwxtUserRepository
) : ViewModel() {

    private val refreshSignal = MutableStateFlow(false)

    val uiState: StateFlow<ScoreUiState> = combine(
        scoreRepository.scoreSnapshotFlow,
        appSettingsRepository.getAppSettings(),
        userRepository.currentStudentIdFlow,
        refreshSignal
    ) { snapshot: ScoreSnapshot, settings, studentId, isRefreshing ->
        ScoreUiState(
            scores = snapshot.scores,
            stats = snapshot.stats,
            schoolYear = settings.schoolYear,
            schoolTerm = settings.schoolTerm,
            lastSyncText = snapshot.fetchedAt,
            hasAccount = studentId.isNotBlank(),
            isRefreshing = isRefreshing
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = ScoreUiState()
    )

    /** 一次性刷新结果事件 */
    private val _refreshResult = MutableSharedFlow<ScoreRefreshResult>(extraBufferCapacity = 1)
    val refreshResult: SharedFlow<ScoreRefreshResult> = _refreshResult

    /**
     * 手动向教务系统查询最新成绩（覆盖本地缓存）。
     */
    fun refreshScores() {
        viewModelScope.launch {
            if (refreshSignal.value) return@launch
            refreshSignal.value = true

            val result = try {
                scoreRepository.getScores(force = true)
            } catch (e: Exception) {
                Result.failure(e)
            }

            refreshSignal.value = false

            val event = result.fold(
                onSuccess = { snapshot -> ScoreRefreshResult.Success(snapshot.scores.size) },
                onFailure = { error -> ScoreRefreshResult.Failure(error.message.orEmpty()) }
            )
            _refreshResult.tryEmit(event)
        }
    }

    /**
     * 首次进入时若本地没有缓存且已添加账号，则静默查一次。
     */
    private suspend fun syncIfEmpty() {
        if (scoreRepository.loadSnapshot().scores.isNotEmpty()) return
        if (userRepository.getCurrentStudentId().isEmpty()) return

        runCatching { scoreRepository.getScores(force = true) }
    }

    init {
        viewModelScope.launch {
            syncIfEmpty()
        }
    }
}
