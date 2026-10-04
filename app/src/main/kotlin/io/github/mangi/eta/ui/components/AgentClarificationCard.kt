package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runtime.AgentUserInputAnswer
import io.github.mangi.eta.agent.runtime.AgentUserInputRequest
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 聊天与浮层共用；每道题均可选择建议或输入任意答案。 */
@Composable
internal fun AgentClarificationCard(
    request: AgentUserInputRequest,
    submitting: Boolean,
    onSubmit: (AgentUserInputAnswer) -> Unit,
    modifier: Modifier = Modifier,
) {
    var answers by rememberSaveable(request.id, stateSaver = listSaver<Map<String, String>, String>(
        save = { map -> map.entries.flatMap { listOf(it.key, it.value) } },
        restore = { items -> items.chunked(2).associate { it[0] to it[1] } },
    )) { mutableStateOf(emptyMap()) }
    Card(modifier = modifier.fillMaxWidth(), insideMargin = PaddingValues(12.dp)) {
        Column(
            modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.clarify_waiting))
            request.questions.forEach { question ->
                Text(question.question)
                question.options.forEach { option ->
                    TextButton(
                        text = option,
                        enabled = !submitting,
                        onClick = { answers = answers + (question.id to option) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val value = answers[question.id].orEmpty()
                BasicTextField(
                    value = value,
                    onValueChange = { if (it.length <= AgentUserInputRequest.MAX_ANSWER_CHARS) answers = answers + (question.id to it) },
                    enabled = !submitting,
                    textStyle = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                        .background(MiuixTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                        .border(1.dp, MiuixTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    decorationBox = { field ->
                        if (value.isEmpty()) Text(stringResource(R.string.clarify_free_text), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        field()
                    },
                )
            }
            TextButton(
                text = stringResource(R.string.clarify_submit),
                enabled = !submitting && request.questions.all { !answers[it.id].isNullOrBlank() },
                onClick = { onSubmit(AgentUserInputAnswer(request.id, answers.mapValues { it.value.trim() })) },
            )
        }
    }
}
