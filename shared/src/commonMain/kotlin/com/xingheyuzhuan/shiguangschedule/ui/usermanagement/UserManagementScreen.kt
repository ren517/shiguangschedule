package com.xingheyuzhuan.shiguangschedule.ui.usermanagement

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.xingheyuzhuan.shiguangschedule.data.model.SchoolTerm
import com.xingheyuzhuan.shiguangschedule.data.repository.JwxtUser
import com.xingheyuzhuan.shiguangschedule.ui.components.CourseTablePickerDialog
import com.xingheyuzhuan.shiguangschedule.ui.components.SchoolTermPickerDialog
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import org.koin.compose.viewmodel.koinViewModel
import shiguangschedule.shared.generated.resources.Res
import shiguangschedule.shared.generated.resources.a11y_back
import shiguangschedule.shared.generated.resources.a11y_delete
import shiguangschedule.shared.generated.resources.action_cancel
import shiguangschedule.shared.generated.resources.action_confirm
import shiguangschedule.shared.generated.resources.action_query_exam
import shiguangschedule.shared.generated.resources.action_query_score
import shiguangschedule.shared.generated.resources.action_sync_schedule
import shiguangschedule.shared.generated.resources.add_24px
import shiguangschedule.shared.generated.resources.arrow_back_24px
import shiguangschedule.shared.generated.resources.delete_24px
import shiguangschedule.shared.generated.resources.desc_query_exam
import shiguangschedule.shared.generated.resources.desc_query_score
import shiguangschedule.shared.generated.resources.desc_sync_schedule
import shiguangschedule.shared.generated.resources.dialog_text_delete_user
import shiguangschedule.shared.generated.resources.dialog_title_add_user
import shiguangschedule.shared.generated.resources.dialog_title_delete_user
import shiguangschedule.shared.generated.resources.item_school_term
import shiguangschedule.shared.generated.resources.refresh_24px
import shiguangschedule.shared.generated.resources.school_24px
import shiguangschedule.shared.generated.resources.search_24px
import shiguangschedule.shared.generated.resources.section_data_sync
import shiguangschedule.shared.generated.resources.section_jwxt_users
import shiguangschedule.shared.generated.resources.section_school_term
import shiguangschedule.shared.generated.resources.term_school_year_format
import shiguangschedule.shared.generated.resources.title_user_management
import shiguangschedule.shared.generated.resources.user_add
import shiguangschedule.shared.generated.resources.user_adding
import shiguangschedule.shared.generated.resources.user_empty_hint
import shiguangschedule.shared.generated.resources.user_field_name
import shiguangschedule.shared.generated.resources.user_field_password
import shiguangschedule.shared.generated.resources.user_field_student_id
import shiguangschedule.shared.generated.resources.user_not_selected
import shiguangschedule.shared.generated.resources.dialog_title_select_sync_table

/**
 * 用户管理界面。
 *
 * 对应 xust-grade-web 的 /users 页面：管理教务账号、设置学年学期，
 * 并对当前账号执行「同步课表 / 查询成绩 / 查询考试信息」三类操作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserManagementScreen(
    onBack: () -> Unit,
    viewModel: UserManagementViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    var showAddUserDialog by remember { mutableStateOf(false) }
    var showTermDialog by remember { mutableStateOf(false) }
    var showTablePicker by remember { mutableStateOf(false) }
    var pendingDeleteUser by remember { mutableStateOf<JwxtUser?>(null) }

    val termLabel = buildTermLabel(uiState.schoolYear, uiState.schoolTerm)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.title_user_management)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = vectorResource(Res.drawable.arrow_back_24px),
                            contentDescription = stringResource(Res.string.a11y_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.section_jwxt_users),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                UserListCard(
                    users = uiState.users,
                    currentStudentId = uiState.currentStudentId,
                    onSwitch = viewModel::switchUser,
                    onDelete = { pendingDeleteUser = it },
                    onAddClick = { showAddUserDialog = true }
                )
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.section_school_term),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showTermDialog = true }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(Res.string.item_school_term),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = termLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Icon(
                            imageVector = vectorResource(Res.drawable.school_24px),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.section_data_sync),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (uiState.currentStudentId.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.user_not_selected),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        SyncActionRow(
                            icon = Res.drawable.refresh_24px,
                            title = stringResource(Res.string.action_sync_schedule),
                            desc = stringResource(Res.string.desc_sync_schedule),
                            isRunning = uiState.isBusy && uiState.busyTask == JwxtTask.SYNC_SCHEDULE,
                            enabled = !uiState.isBusy,
                            onClick = { showTablePicker = true }
                        )
                        SyncActionRow(
                            icon = Res.drawable.search_24px,
                            title = stringResource(Res.string.action_query_score),
                            desc = stringResource(Res.string.desc_query_score),
                            isRunning = uiState.isBusy && uiState.busyTask == JwxtTask.QUERY_SCORE,
                            enabled = !uiState.isBusy,
                            onClick = viewModel::queryScore
                        )
                        SyncActionRow(
                            icon = Res.drawable.school_24px,
                            title = stringResource(Res.string.action_query_exam),
                            desc = stringResource(Res.string.desc_query_exam),
                            isRunning = uiState.isBusy && uiState.busyTask == JwxtTask.QUERY_EXAM,
                            enabled = !uiState.isBusy,
                            onClick = viewModel::queryExam
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showAddUserDialog) {
        AddUserDialog(
            isBusy = uiState.isBusy && uiState.busyTask == JwxtTask.ADD_USER,
            onDismiss = { showAddUserDialog = false },
            onConfirm = { name, studentId, password ->
                viewModel.addUser(name, studentId, password) { success ->
                    if (success) showAddUserDialog = false
                }
            }
        )
    }

    if (showTermDialog) {
        SchoolTermPickerDialog(
            currentSchoolYear = uiState.schoolYear,
            currentSchoolTerm = SchoolTerm.fromCode(uiState.schoolTerm),
            onDismiss = { showTermDialog = false },
            onConfirm = { year, term ->
                viewModel.setSchoolYear(year)
                viewModel.setSchoolTerm(term)
                showTermDialog = false
            }
        )
    }

    if (showTablePicker) {
        CourseTablePickerDialog(
            title = stringResource(Res.string.dialog_title_select_sync_table),
            onDismissRequest = { showTablePicker = false },
            onTableSelected = { courseTable ->
                showTablePicker = false
                viewModel.syncSchedule(courseTable.id)
            }
        )
    }

    pendingDeleteUser?.let { user ->
        AlertDialog(
            onDismissRequest = { pendingDeleteUser = null },
            title = { Text(stringResource(Res.string.dialog_title_delete_user)) },
            text = { Text(stringResource(Res.string.dialog_text_delete_user, user.studentId)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteUser(user.studentId)
                        pendingDeleteUser = null
                    }
                ) {
                    Text(stringResource(Res.string.action_confirm))
                }
            },
            dismissButton = {
                Button(onClick = { pendingDeleteUser = null }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            }
        )
    }
}

/**
 * 生成学年学期展示文本：2026-2027学年 第一学期
 */
@Composable
private fun buildTermLabel(schoolYear: String, schoolTerm: String): String {
    val startYear = schoolYear.toIntOrNull()
    val yearText = if (startYear != null) {
        stringResource(
            Res.string.term_school_year_format,
            startYear.toString(),
            (startYear + 1).toString()
        )
    } else {
        schoolYear
    }
    return "$yearText ${stringResource(SchoolTerm.fromCode(schoolTerm).labelRes)}"
}

/**
 * 教务账号列表卡片
 */
@Composable
private fun UserListCard(
    users: List<JwxtUser>,
    currentStudentId: String,
    onSwitch: (String) -> Unit,
    onDelete: (JwxtUser) -> Unit,
    onAddClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (users.isEmpty()) {
            Text(
                text = stringResource(Res.string.user_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            )
        } else {
            users.forEach { user ->
                UserRow(
                    user = user,
                    isCurrent = user.studentId == currentStudentId,
                    onSwitch = { onSwitch(user.studentId) },
                    onDelete = { onDelete(user) }
                )
            }
        }

        TextButton(
            onClick = onAddClick,
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            Icon(
                imageVector = vectorResource(Res.drawable.add_24px),
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(Res.string.user_add))
        }
    }
}

/**
 * 单个教务账号行
 */
@Composable
private fun UserRow(
    user: JwxtUser,
    isCurrent: Boolean,
    onSwitch: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSwitch)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = isCurrent, onClick = onSwitch)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = user.name.ifEmpty { user.studentId },
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = user.studentId,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }

        IconButton(onClick = onDelete) {
            Icon(
                imageVector = vectorResource(Res.drawable.delete_24px),
                contentDescription = stringResource(Res.string.a11y_delete),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

/**
 * 数据同步操作行：执行中显示进度圈
 */
@Composable
private fun SyncActionRow(
    icon: DrawableResource,
    title: String,
    desc: String,
    isRunning: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = vectorResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = desc,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
        if (isRunning) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
        }
    }
}

/**
 * 添加教务账号对话框：姓名 / 学号 / 密码，提交时会先登录教务系统验证。
 */
@Composable
private fun AddUserDialog(
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var studentId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.dialog_title_add_user)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.user_field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = studentId,
                    onValueChange = { studentId = it },
                    label = { Text(stringResource(Res.string.user_field_student_id)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(Res.string.user_field_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (isBusy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(Res.string.user_adding),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isBusy && studentId.isNotBlank() && password.isNotBlank(),
                onClick = { onConfirm(name, studentId, password) }
            ) {
                Text(stringResource(Res.string.action_confirm))
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        }
    )
}
