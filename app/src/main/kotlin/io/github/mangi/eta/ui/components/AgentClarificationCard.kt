package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runtime.AgentUserInputAnswer
import io.github.mangi.eta.agent.runtime.AgentUserInputRequest
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 聊天与浮层共用；每道题均可选择建议或输入任意答案，支持单题与多题逐题分页展示。 */
@Composable
internal fun AgentClarificationCard(
    request: AgentUserInputRequest,
    submitting: Boolean,
    onSubmit: (AgentUserInputAnswer) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    controlledState: AgentClarificationPagerState? = null,
    onStateChange: ((AgentClarificationPagerState) -> Unit)? = null,
) {
    var internalAnswers by rememberSaveable(request.id, stateSaver = listSaver<Map<String, String>, String>(
        save = { map -> map.entries.flatMap { listOf(it.key, it.value) } },
        restore = { items -> items.chunked(2).associate { it[0] to it[1] } },
    )) { mutableStateOf(emptyMap()) }

    var internalSelectedOptions by rememberSaveable(request.id, stateSaver = listSaver<Map<String, Set<String>>, String>(
        save = { map ->
            val result = mutableListOf<String>()
            for ((key, set) in map) {
                result.add(key)
                result.add(set.size.toString())
                result.addAll(set)
            }
            result
        },
        restore = { items ->
            val result = mutableMapOf<String, Set<String>>()
            var i = 0
            while (i < items.size) {
                val key = items[i]
                val size = items.getOrNull(i + 1)?.toIntOrNull() ?: 0
                val set = mutableSetOf<String>()
                for (j in 0 until size) {
                    items.getOrNull(i + 2 + j)?.let { set.add(it) }
                }
                result[key] = set
                i += 2 + size
            }
            result
        },
    )) { mutableStateOf(emptyMap()) }

    var internalCurrentPageIndex by rememberSaveable(request.id) { mutableIntStateOf(0) }

    val isControlled = controlledState != null && controlledState.request.id == request.id
    val pagerState = if (isControlled) {
        controlledState
    } else {
        remember(request, internalCurrentPageIndex, internalAnswers, internalSelectedOptions) {
            AgentClarificationPagerState(
                request = request,
                currentPageIndex = internalCurrentPageIndex,
                answers = internalAnswers,
                selectedOptions = internalSelectedOptions,
            )
        }
    }

    fun updateState(next: AgentClarificationPagerState) {
        if (isControlled) {
            onStateChange?.invoke(next)
        } else {
            internalCurrentPageIndex = next.currentPageIndex
            internalAnswers = next.answers
            internalSelectedOptions = next.selectedOptions
        }
    }

    val currentQuestion = pagerState.currentQuestion ?: return

    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = if (compact) PaddingValues(8.dp) else PaddingValues(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
        ) {
            // 顶部标题与页码指示
            if (compact) {
                if (!pagerState.isSingleQuestion) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(
                                R.string.clarify_question_progress,
                                pagerState.currentPageIndex + 1,
                                pagerState.totalPages,
                            ),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.clarify_waiting),
                        style = MiuixTheme.textStyles.title4,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    if (!pagerState.isSingleQuestion) {
                        Text(
                            text = stringResource(
                                R.string.clarify_question_progress,
                                pagerState.currentPageIndex + 1,
                                pagerState.totalPages,
                            ),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            // 可滚动的问题与选项区域
            val scrollState = rememberScrollState()
            LaunchedEffect(pagerState.currentPageIndex) {
                scrollState.scrollTo(0)
            }

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = currentQuestion.question,
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (currentQuestion.multiSelect) {
                        Text(
                            text = stringResource(R.string.clarify_multi_select),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }
                }
                if (currentQuestion.multiSelect) {
                    val currentSet = pagerState.currentSelectedOptions
                    currentQuestion.options.forEach { option ->
                        val isSelected = option in currentSet
                        val borderColor = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.outline
                        val backgroundColor = if (isSelected) MiuixTheme.colorScheme.primary.copy(alpha = 0.08f) else MiuixTheme.colorScheme.surface
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(backgroundColor, RoundedCornerShape(8.dp))
                                .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                                .clickable(enabled = !submitting) {
                                    updateState(pagerState.toggleOption(currentQuestion.id, option))
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(
                                state = if (isSelected) ToggleableState.On else ToggleableState.Off,
                                onClick = null,
                                enabled = !submitting,
                            )
                            Text(
                                text = option,
                                style = MiuixTheme.textStyles.body1,
                                color = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                } else {
                    currentQuestion.options.forEach { option ->
                        TextButton(
                            text = option,
                            enabled = !submitting,
                            onClick = { updateState(pagerState.withAnswer(currentQuestion.id, option)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                val currentValue = pagerState.currentAnswer
                BasicTextField(
                    value = currentValue,
                    onValueChange = {
                        if (it.length <= AgentUserInputRequest.MAX_ANSWER_CHARS) {
                            updateState(pagerState.withAnswer(currentQuestion.id, it))
                        }
                    },
                    enabled = !submitting,
                    textStyle = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MiuixTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                        .border(1.dp, MiuixTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .padding(if (compact) 8.dp else 10.dp),
                    decorationBox = { field ->
                        if (currentValue.isEmpty()) {
                            Text(
                                stringResource(R.string.clarify_free_text),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        field()
                    },
                )
            }

            // 底部导航固定区域
            if (pagerState.isSingleQuestion) {
                TextButton(
                    text = stringResource(R.string.clarify_submit),
                    enabled = !submitting && pagerState.canSubmit,
                    onClick = {
                        pagerState.buildFinalAnswer()?.let(onSubmit)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minWidth = if (compact) 0.dp else ButtonDefaults.MinWidth,
                    minHeight = if (compact) 36.dp else ButtonDefaults.MinHeight,
                    insideMargin = if (compact) PaddingValues(horizontal = 8.dp, vertical = 6.dp) else ButtonDefaults.InsideMargin,
                    textStyle = if (compact) MiuixTheme.textStyles.body2 else null,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (pagerState.canGoPrevious) {
                        TextButton(
                            text = stringResource(R.string.clarify_previous),
                            enabled = !submitting,
                            onClick = {
                                updateState(pagerState.previousPage())
                            },
                            modifier = Modifier.weight(1f),
                            minWidth = if (compact) 0.dp else ButtonDefaults.MinWidth,
                            minHeight = if (compact) 36.dp else ButtonDefaults.MinHeight,
                            insideMargin = if (compact) PaddingValues(horizontal = 8.dp, vertical = 6.dp) else ButtonDefaults.InsideMargin,
                            textStyle = if (compact) MiuixTheme.textStyles.body2 else null,
                        )
                    }
                    if (pagerState.isLastPage) {
                        TextButton(
                            text = stringResource(R.string.clarify_submit),
                            enabled = !submitting && pagerState.canSubmit,
                            onClick = {
                                pagerState.buildFinalAnswer()?.let(onSubmit)
                            },
                            modifier = Modifier.weight(if (pagerState.canGoPrevious) 1f else 2f),
                            minWidth = if (compact) 0.dp else ButtonDefaults.MinWidth,
                            minHeight = if (compact) 36.dp else ButtonDefaults.MinHeight,
                            insideMargin = if (compact) PaddingValues(horizontal = 8.dp, vertical = 6.dp) else ButtonDefaults.InsideMargin,
                            textStyle = if (compact) MiuixTheme.textStyles.body2 else null,
                        )
                    } else {
                        TextButton(
                            text = stringResource(R.string.clarify_next),
                            enabled = !submitting && pagerState.canGoNext,
                            onClick = {
                                updateState(pagerState.nextPage())
                            },
                            modifier = Modifier.weight(if (pagerState.canGoPrevious) 1f else 2f),
                            minWidth = if (compact) 0.dp else ButtonDefaults.MinWidth,
                            minHeight = if (compact) 36.dp else ButtonDefaults.MinHeight,
                            insideMargin = if (compact) PaddingValues(horizontal = 8.dp, vertical = 6.dp) else ButtonDefaults.InsideMargin,
                            textStyle = if (compact) MiuixTheme.textStyles.body2 else null,
                        )
                    }
                }
            }
        }
    }
}
