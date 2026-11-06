package com.xingheyuzhuan.shiguangschedule.ui.exam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xingheyuzhuan.shiguangschedule.Destination
import com.xingheyuzhuan.shiguangschedule.data.api.exam.ExamRecord
import com.xingheyuzhuan.shiguangschedule.data.model.SchoolTerm
import com.xingheyuzhuan.shiguangschedule.ui.components.ToastManager
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import org.koin.compose.viewmodel.koinViewModel
import shiguangschedule.shared.generated.resources.Res
import shiguangschedule.shared.generated.resources.action_refresh
import shiguangschedule.shared.generated.resources.exam_empty_text
import shiguangschedule.shared.generated.resources.exam_label_campus
import shiguangschedule.shared.generated.resources.exam_label_college
import shiguangschedule.shared.generated.resources.exam_label_method
import shiguangschedule.shared.generated.resources.exam_label_place
import shiguangschedule.shared.generated.resources.exam_label_seat
import shiguangschedule.shared.generated.resources.exam_label_teachers
import shiguangschedule.shared.generated.resources.exam_label_time
import shiguangschedule.shared.generated.resources.exam_refresh_failed
import shiguangschedule.shared.generated.resources.exam_refresh_success_format
import shiguangschedule.shared.generated.resources.exam_synced_at_format
import shiguangschedule.shared.generated.resources.label_credit
import shiguangschedule.shared.generated.resources.refresh_24px
import shiguangschedule.shared.generated.resources.term_school_year_format
import shiguangschedule.shared.generated.resources.title_exam_schedule

private val SCREEN_HORIZONTAL_PADDING = 16.dp
private val CARD_INNER_PADDING = 14.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamScheduleScreen(
    onNavigate: (Destination) -> Unit,
    onBack: () -> Unit,
    viewModel: ExamScheduleViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    val successFormat = stringResource(Res.string.exam_refresh_success_format)
    val failedText = stringResource(Res.string.exam_refresh_failed)

    LaunchedEffect(Unit) {
        viewModel.refreshResult.collect { result ->
            val message = when (result) {
                is ExamRefreshResult.Success -> successFormat.replace("%1\$d", result.count.toString())
                is ExamRefreshResult.Failure -> result.reason.ifBlank { failedText }
            }
            ToastManager.show(message)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.title_exam_schedule),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold
                    )
                },
                actions = {
                    IconButton(
                        enabled = !uiState.isRefreshing,
                        onClick = { viewModel.refreshExamSchedule() }
                    ) {
                        if (uiState.isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.width(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = vectorResource(Res.drawable.refresh_24px),
                                contentDescription = stringResource(Res.string.action_refresh)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors()
            )
        }
    ) { innerPadding ->
        ExamContent(
            exams = uiState.exams,
            termLabel = buildTermLabel(uiState.schoolYear, uiState.schoolTerm),
            lastSyncText = uiState.lastSyncText,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    }
}

/**
 * 拼装「2026-2027学年 第一学期」形式的学年学期文本。
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

    return yearText + " " + stringResource(SchoolTerm.fromCode(schoolTerm).labelRes)
}

@Composable
fun ExamContent(
    exams: List<ExamRecord>,
    termLabel: String,
    lastSyncText: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = SCREEN_HORIZONTAL_PADDING)
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                text = termLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (lastSyncText.isNotBlank()) {
                Text(
                    text = stringResource(Res.string.exam_synced_at_format, lastSyncText),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (exams.isEmpty()) {
            EmptyStateView()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(
                    bottom = 88.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(exams) { exam ->
                    ExamCard(exam)
                }
                item {
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
fun ExamCard(record: ExamRecord) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(CARD_INNER_PADDING)) {
            Text(
                text = record.course,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(
                modifier = Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ExamChip(record.timeText)
                Spacer(modifier = Modifier.width(6.dp))
                ExamChip(record.place)
            }

            Spacer(modifier = Modifier.height(10.dp))

            ExamDetailRow(stringResource(Res.string.exam_label_time), record.timeText)
            ExamDetailRow(stringResource(Res.string.exam_label_place), record.place)
            ExamDetailRow(stringResource(Res.string.exam_label_seat), record.seat)
            ExamDetailRow(stringResource(Res.string.exam_label_method), record.method)
            ExamDetailRow(stringResource(Res.string.label_credit), record.credit)
            ExamDetailRow(stringResource(Res.string.exam_label_college), record.college)
            ExamDetailRow(stringResource(Res.string.exam_label_teachers), record.teachers)
            ExamDetailRow(stringResource(Res.string.exam_label_campus), record.campus)
        }
    }
}

@Composable
private fun ExamChip(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun ExamDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            fontSize = 12.sp,
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun EmptyStateView() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(Res.string.exam_empty_text),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.outline
        )
    }
}
