package com.dshremote.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dshremote.app.events.ApprovalPrompt
import com.dshremote.app.events.QuestionDraft
import com.dshremote.app.events.QuestionPrompt

@Composable
fun ApprovalDialog(prompt: ApprovalPrompt, onAllow: () -> Unit, onReject: () -> Unit) {
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text("需要授权") },
        text = {
            Column {
                Text(prompt.toolName, style = MaterialTheme.typography.titleMedium)
                if (prompt.reason.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(prompt.reason, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onAllow) { Text("允许一次") } },
        dismissButton = { TextButton(onClick = onReject) { Text("拒绝") } },
    )
}

@Composable
fun QuestionDialog(
    prompt: QuestionPrompt,
    onSubmit: (Map<String, QuestionDraft>) -> Unit,
    onCancel: () -> Unit,
) {
    var drafts by remember(prompt.eventId) {
        mutableStateOf(prompt.questions.associate { it.id to QuestionDraft() })
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("需要你的选择") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
            ) {
                for (question in prompt.questions) {
                    val draft = drafts[question.id] ?: QuestionDraft()
                    if (!question.header.isNullOrBlank()) {
                        Text(question.header, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(question.question, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                    question.detail?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 3.dp))
                    }
                    for (option in question.options) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val next = if (question.multiSelect) {
                                        if (option.label in draft.selected) draft.selected - option.label
                                        else draft.selected + option.label
                                    } else {
                                        setOf(option.label)
                                    }
                                    drafts = drafts + (question.id to draft.copy(selected = next))
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = option.label in draft.selected,
                                onCheckedChange = {
                                    val next = if (question.multiSelect) {
                                        if (option.label in draft.selected) draft.selected - option.label
                                        else draft.selected + option.label
                                    } else {
                                        setOf(option.label)
                                    }
                                    drafts = drafts + (question.id to draft.copy(selected = next))
                                },
                            )
                            Column {
                                Text(option.label, style = MaterialTheme.typography.bodySmall)
                                option.description?.let {
                                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = draft.custom,
                        onValueChange = { drafts = drafts + (question.id to draft.copy(custom = it)) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        placeholder = { Text("其他…", style = MaterialTheme.typography.labelSmall) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(drafts) }) { Text("提交") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
    )
}
