package com.xingheyuzhuan.shiguangschedule.ui.score

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xingheyuzhuan.shiguangschedule.Destination
import com.xingheyuzhuan.shiguangschedule.data.api.score.ScoreRecord
import com.xingheyuzhuan.shiguangschedule.data.model.SchoolTerm
import com.xingheyuzhuan.shiguangschedule.ui.components.ToastManager
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import org.koin.compose.viewmodel.koinViewModel
import shiguangschedule.shared.generated.resources.Res
import shiguangschedule.shared.generated.resources.action_refresh
import shiguangschedule.shared.generated.resources.label_credit
import shiguangschedule.shared.generated.resources.refresh_24px
import shiguangschedule.shared.generated.resources.chevron_right_24px
import shiguangschedule.shared.generated.resources.score_empty_text
import shiguangschedule.shared.generated.resources.score_tap_to_collapse
import shiguangschedule.shared.generated.resources.score_tap_to_expand
import shiguangschedule.shared.generated.resources.score_label_count
import shiguangschedule.shared.generated.resources.score_label_credits
import shiguangschedule.shared.generated.resources.score_label_gpa
import shiguangschedule.shared.generated.resources.score_label_teacher
import shiguangschedule.shared.generated.resources.score_publish_prefix
import shiguangschedule.shared.generated.resources.score_refresh_failed
import shiguangschedule.shared.generated.resources.score_refresh_success_format
import shiguangschedule.shared.generated.resources.score_synced_at_format
import shiguangschedule.shared.generated.resources.term_school_year_format
import shiguangschedule.shared.generated.resources.title_score

private val SCREEN_HORIZONTAL_PADDING = 16.dp
private val CARD_INNER_PADDING = 14.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreScreen(
    onNavigate: (Destination) -> Unit,
    onBack: () -> Unit,
    viewModel: ScoreViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    val successFormat = stringResource(Res.string.score_refresh_success_format)
    val failedText = stringResource(Res.string.score_refresh_failed)

    LaunchedEffect(Unit) {
        viewModel.refreshResult.collect { result ->
            val message = when (result) {
                is ScoreRefreshResult.Success -> successFormat.replace("%1\$d", result.count.toString())
                is ScoreRefreshResult.Failure -> result.reason.ifBlank { failedText }
            }
            ToastManager.show(message)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.title_score),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold
                    )
                },
                actions = {
                    IconButton(
                        enabled = !uiState.isRefreshing,
                        onClick = { viewModel.refreshScores() }
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
        ScoreContent(
            scores = uiState.scores,
            statsText = buildStatsText(uiState),
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

/**
 * 拼装统计概览：课程门数 / 总学分 / 平均绩点。
 */
@Composable
private fun buildStatsText(uiState: ScoreUiState): String {
    val gpaText = uiState.stats.gpa?.let { formatDecimal(it, 2) } ?: "—"
    return stringResource(Res.string.score_label_count) + " " + uiState.stats.count +
            "   " + stringResource(Res.string.score_label_credits) + " " +
            formatDecimal(uiState.stats.credits, 1) +
            "   " + stringResource(Res.string.score_label_gpa) + " " + gpaText
}

/**
 * 跨平台安全的浮点格式化：按位数四舍五入后直接输出，避免依赖 JVM 的 String.format。
 */
private fun formatDecimal(value: Float, digits: Int): String {
    val factor = if (digits <= 1) 10f else 100f
    return (kotlin.math.round(value * factor) / factor).toString()
}

@Composable
fun ScoreContent(
    scores: List<ScoreRecord>,
    statsText: String,
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
            if (scores.isNotEmpty()) {
                Text(
                    text = statsText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            if (lastSyncText.isNotBlank()) {
                Text(
                    text = stringResource(Res.string.score_synced_at_format, lastSyncText),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (scores.isEmpty()) {
            EmptyStateView()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(
                    bottom = 88.dp + WindowInsets.navigationBars.asPaddingValues()
                        .calculateBottomPadding() + 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(scores) { record ->
                    ScoreCard(record)
                }
                item {
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
fun ScoreCard(record: ScoreRecord) {
    var expanded by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "chevronRotation"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(CARD_INNER_PADDING)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = record.course,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = record.score,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = vectorResource(Res.drawable.chevron_right_24px),
                    contentDescription = if (expanded) {
                        stringResource(Res.string.score_tap_to_collapse)
                    } else {
                        stringResource(Res.string.score_tap_to_expand)
                    },
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .rotate(chevronRotation)
                        .size(20.dp)
                )
            }

            Row(
                modifier = Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ScoreChip(record.nature.ifBlank { record.category })
                Spacer(modifier = Modifier.width(6.dp))
                ScoreChip(stringResource(Res.string.label_credit) + " " + record.credit)
                if (record.gradePoint.isNotBlank()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    ScoreChip("绩点 " + record.gradePoint)
                }
            }

            if (!expanded) {
                Text(
                    text = stringResource(Res.string.score_tap_to_expand),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    if (record.teacher.isNotBlank()) {
                        ScoreDetailRow(stringResource(Res.string.score_label_teacher), record.teacher)
                    }
                    if (record.publishTime.isNotBlank()) {
                        ScoreDetailRow(
                            stringResource(Res.string.score_publish_prefix),
                            record.publishTime
                        )
                    }

                    record.details.forEach { (key, value) ->
                        ScoreDetailRow(key, value)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScoreChip(text: String) {
    if (text.isBlank()) return

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
private fun ScoreDetailRow(label: String, value: String) {
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
            modifier = Modifier.width(96.dp)
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
            text = stringResource(Res.string.score_empty_text),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.outline
        )
    }
}
