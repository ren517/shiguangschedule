package com.xingheyuzhuan.shiguangschedule.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xingheyuzhuan.shiguangschedule.data.model.SchoolTerm
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import shiguangschedule.shared.generated.resources.Res
import shiguangschedule.shared.generated.resources.action_cancel
import shiguangschedule.shared.generated.resources.action_confirm
import shiguangschedule.shared.generated.resources.dialog_title_school_term
import shiguangschedule.shared.generated.resources.term_school_year
import shiguangschedule.shared.generated.resources.term_semester
import kotlin.time.Clock

/**
 * 学年与学期选择器对话框。
 *
 * 学年按起始年倒序给出最近 6 个学年，学期只有第一 / 第二学期两个选项，
 * 与 xust-grade-web 的 _term_options() / SEMESTER_OPTIONS 保持一致。
 *
 * 滚轮内只显示「2026-2027」这样的年份区间（列头已标注「学年」），
 * 避免过长的文本在窄列中被截断。
 */
@Composable
fun SchoolTermPickerDialog(
    currentSchoolYear: String,
    currentSchoolTerm: SchoolTerm,
    onDismiss: () -> Unit,
    onConfirm: (String, SchoolTerm) -> Unit
) {
    val todayYear = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.year

    val yearEntries = (todayYear + 1 downTo todayYear - 4).map { startYear ->
        startYear to "$startYear-${startYear + 1}"
    }
    val termEntries = SchoolTerm.entries.map { schoolTerm ->
        schoolTerm to stringResource(schoolTerm.labelRes)
    }

    val initialYearLabel = yearEntries
        .firstOrNull { it.first.toString() == currentSchoolYear }
        ?.second
        ?: yearEntries.firstOrNull { it.first == todayYear }?.second
        ?: yearEntries.first().second
    val initialTermLabel = termEntries
        .firstOrNull { it.first == currentSchoolTerm }?.second
        ?: termEntries.first().second

    var selectedYearLabel by remember { mutableStateOf(initialYearLabel) }
    var selectedTermLabel by remember { mutableStateOf(initialTermLabel) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(Res.string.dialog_title_school_term),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1.6f)) {
                        Text(
                            text = stringResource(Res.string.term_school_year),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        NativeNumberPicker(
                            values = yearEntries.map { it.second },
                            selectedValue = selectedYearLabel,
                            onValueChange = { selectedYearLabel = it },
                            modifier = Modifier.fillMaxWidth(),
                            itemHeight = 46.dp,
                            selectedItemTextSize = 22.sp,
                            unselectedItemTextSize = 17.sp
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(Res.string.term_semester),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        NativeNumberPicker(
                            values = termEntries.map { it.second },
                            selectedValue = selectedTermLabel,
                            onValueChange = { selectedTermLabel = it },
                            modifier = Modifier.fillMaxWidth(),
                            itemHeight = 46.dp,
                            selectedItemTextSize = 22.sp,
                            unselectedItemTextSize = 17.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                    Button(
                        onClick = {
                            val startYear = yearEntries.firstOrNull { it.second == selectedYearLabel }?.first
                            val term = termEntries.firstOrNull { it.second == selectedTermLabel }?.first
                                ?: SchoolTerm.FIRST
                            onConfirm(startYear?.toString() ?: currentSchoolYear, term)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = stringResource(Res.string.action_confirm),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}
