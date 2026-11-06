package com.xingheyuzhuan.shiguangschedule.ui.usermanagement

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.data.model.SchoolTerm
import com.xingheyuzhuan.shiguangschedule.data.repository.AppSettingsRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.ExamRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.JwxtUser
import com.xingheyuzhuan.shiguangschedule.data.repository.JwxtUserRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.ScoreRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.ScheduleSyncRepository
import com.xingheyuzhuan.shiguangschedule.ui.components.ToastManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.koin.core.annotation.KoinViewModel
import shiguangschedule.shared.generated.resources.Res
import shiguangschedule.shared.generated.resources.exam_query_failed
import shiguangschedule.shared.generated.resources.exam_query_success_format
import shiguangschedule.shared.generated.resources.score_query_failed
import shiguangschedule.shared.generated.resources.score_query_success_format
import shiguangschedule.shared.generated.resources.sync_schedule_failed
import shiguangschedule.shared.generated.resources.sync_schedule_success_format
import shiguangschedule.shared.generated.resources.user_add_failed
import shiguangschedule.shared.generated.resources.user_add_success_format
import shiguangschedule.shared.generated.resources.user_not_selected

/**
 * 用户管理界面状态包
 *
 * @property users 已保存的教务账号列表
 * @property currentStudentId 当前使用的学号
 * @property schoolYear 当前学年（教务 xnm）
 * @property schoolTerm 当前学期（教务 xqm）
 * @property isBusy 是否正在执行教务操作
 * @property busyTask 正在执行的操作标识（用于在按钮上显示进度）
 */
data class UserManagementUiState(
    val users: List<JwxtUser> = emptyList(),
    val currentStudentId: String = "",
    val schoolYear: String = "",
    val schoolTerm: String = "",
    val isBusy: Boolean = false,
    val busyTask: JwxtTask = JwxtTask.IDLE
)

/**
 * 教务操作标识
 */
enum class JwxtTask {
    IDLE,
    ADD_USER,
    SYNC_SCHEDULE,
    QUERY_SCORE,
    QUERY_EXAM
}

@KoinViewModel
class UserManagementViewModel(
    private val userRepository: JwxtUserRepository,
    private val appSettingsRepository: AppSettingsRepository,
    private val scheduleSyncRepository: ScheduleSyncRepository,
    private val scoreRepository: ScoreRepository,
    private val examRepository: ExamRepository
) : ViewModel() {

    private val busyState = MutableStateFlow(false)
    private val taskState = MutableStateFlow(JwxtTask.IDLE)

    val uiState: StateFlow<UserManagementUiState> = combine(
        userRepository.usersFlow,
        userRepository.currentStudentIdFlow,
        appSettingsRepository.getAppSettings(),
        busyState,
        taskState
    ) { users, currentId, settings, isBusy, task ->
        UserManagementUiState(
            users = users,
            currentStudentId = currentId,
            schoolYear = settings.schoolYear,
            schoolTerm = settings.schoolTerm,
            isBusy = isBusy,
            busyTask = task
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = UserManagementUiState()
    )

    /**
     * 添加教务账号：先登录教务系统验证，通过后才落盘。
     */
    fun addUser(name: String, studentId: String, password: String, onFinished: (Boolean) -> Unit) {
        runTask(JwxtTask.ADD_USER) {
            val result = userRepository.addUser(name, studentId, password)

            val message = if (result.isSuccess) {
                getString(Res.string.user_add_success_format, studentId.trim())
            } else {
                result.exceptionOrNull()?.message
                    ?: getString(Res.string.user_add_failed)
            }
            ToastManager.show(message)

            onFinished(result.isSuccess)
        }
    }

    /**
     * 删除教务账号及其本地缓存。
     */
    fun deleteUser(studentId: String) {
        viewModelScope.launch {
            examRepository.clearCache(studentId)
            scoreRepository.clearCache(studentId)
            userRepository.deleteUser(studentId)
        }
    }

    /**
     * 切换当前使用的教务账号。
     */
    fun switchUser(studentId: String) {
        viewModelScope.launch {
            userRepository.switchUser(studentId)
        }
    }

    /**
     * 设置当前学年（教务 xnm）。
     */
    fun setSchoolYear(schoolYear: String) {
        viewModelScope.launch {
            appSettingsRepository.setSchoolYear(schoolYear)
        }
    }

    /**
     * 设置当前学期（教务 xqm）。
     */
    fun setSchoolTerm(term: SchoolTerm) {
        viewModelScope.launch {
            appSettingsRepository.setSchoolTerm(term.code)
        }
    }

    /**
     * 同步课表：先让用户选择目标课表，再把教务课表整表写入所选课表。
     *
     * @param targetTableId 用户在选择弹窗中选定的课表 ID
     */
    fun syncSchedule(targetTableId: String) {
        runTask(JwxtTask.SYNC_SCHEDULE) {
            val result = scheduleSyncRepository.syncSchedule(targetTableId)
            ToastManager.show(formatResult(result.getOrNull(), Res.string.sync_schedule_success_format, Res.string.sync_schedule_failed, result.exceptionOrNull()))
        }
    }

    /**
     * 查询成绩：抓取教务成绩并落盘缓存。
     */
    fun queryScore() {
        runTask(JwxtTask.QUERY_SCORE) {
            val result = scoreRepository.getScores(force = true)
            val count = result.getOrNull()?.scores?.size
            ToastManager.show(formatResult(count, Res.string.score_query_success_format, Res.string.score_query_failed, result.exceptionOrNull()))
        }
    }

    /**
     * 查询考试信息：抓取教务考试安排并落盘缓存。
     */
    fun queryExam() {
        runTask(JwxtTask.QUERY_EXAM) {
            val result = examRepository.getExamSchedule(force = true)
            val count = result.getOrNull()?.exams?.size
            ToastManager.show(formatResult(count, Res.string.exam_query_success_format, Res.string.exam_query_failed, result.exceptionOrNull()))
        }
    }

    /**
     * 统一的教务任务调度：同一时刻只允许一个任务，避免重复点击。
     */
    private fun runTask(task: JwxtTask, block: suspend () -> Unit): Job {
        return viewModelScope.launch {
            if (busyState.value) return@launch

            busyState.value = true
            taskState.value = task

            try {
                block()
            } catch (e: Exception) {
                ToastManager.show(e.message ?: getString(Res.string.user_not_selected))
            } finally {
                busyState.value = false
                taskState.value = JwxtTask.IDLE
            }
        }
    }

    /**
     * 生成结果提示文案：成功时带条数，失败时展示原因。
     */
    private suspend fun formatResult(
        count: Int?,
        successRes: StringResource,
        failureRes: StringResource,
        error: Throwable?
    ): String {
        return if (count != null) {
            getString(successRes, count)
        } else {
            error?.message?.takeIf { it.isNotBlank() } ?: getString(failureRes)
        }
    }
}
