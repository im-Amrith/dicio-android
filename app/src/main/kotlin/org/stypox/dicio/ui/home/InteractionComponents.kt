package org.stypox.dicio.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.di.SkillContextImpl
import org.stypox.dicio.io.wake.WakeState
import org.stypox.dicio.ui.theme.AppTheme
import org.stypox.dicio.ui.util.InteractionLogPreviews
import org.stypox.dicio.ui.util.SkillInfoPreviews
import org.stypox.dicio.ui.util.SkillOutputPreviews
import org.stypox.dicio.ui.util.UserInputPreviews
import org.stypox.dicio.ui.util.WakeStatesPreviews
import kotlin.math.absoluteValue

@Composable
fun InteractionList(
    skillContext: SkillContext,
    // will be null when skills have not been initialized yet
    skills: List<SkillInfo>?,
    interactionLog: InteractionLog,
    onConfirmedQuestionClick: (String) -> Unit,
    wakeState: WakeState?,
    onWakeDownload: () -> Unit,
    onWakeDisable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var totalItemCount = 0
    var lastAnchorIndex = 0
    fun LazyListScope.countedItem(
        canBeAnchor: Boolean,
        content: @Composable LazyItemScope.() -> Unit,
    ) {
        if (canBeAnchor) {
            lastAnchorIndex = totalItemCount
        }
        item(content = content)
        totalItemCount += 1
    }

    val interactions = interactionLog.interactions
    val pendingQuestion = interactionLog.pendingQuestion
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = modifier.testTag("interaction_list"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        state = listState,
    ) {
        countedItem(canBeAnchor = false) {
            if (wakeState != null) {
                WakeWordWidget(
                    wakeState = wakeState,
                    onWakeDownload = onWakeDownload,
                    onWakeDisable = onWakeDisable,
                )
            }
        }

        countedItem(canBeAnchor = false) {
            if (skills != null) {
                // avoid showing anything if skills are null, i.e. not ready yet
                WhatICanDo(skills = skills)
            }
        }

        interactions.forEach { interaction ->
            countedItem(canBeAnchor = false) {
                Spacer(modifier = Modifier.height(16.dp))
            }
            interaction.questionsAnswers.forEach {
                if (it.question != null) {
                    // can be null if this QA contains only an error output
                    // (and therefore the error was not caused by the input, but e.g. by the STT)
                    countedItem(canBeAnchor = true) {
                        ConfirmedQuestionCard(
                            userInput = it.question,
                            onClick = onConfirmedQuestionClick,
                        )
                    }
                }
                countedItem(canBeAnchor = it.question == null) {
                    SkillAnswerCard {
                        it.answer.GraphicalOutput(ctx = skillContext)
                    }
                }
            }
        }

        if (pendingQuestion != null) {
            if (interactions.isEmpty() || !pendingQuestion.continuesLastInteraction) {
                countedItem(canBeAnchor = false) {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            if (pendingQuestion.skillBeingEvaluated == null) {
                countedItem(canBeAnchor = true) {
                    PendingQuestionCard(userInput = pendingQuestion.userInput)
                }
                countedItem(canBeAnchor = false) {
                    // still include a spacer in place of the LoadingAnswerCard, so that when the
                    // pending question first appears and the LaunchedEffect below scrolls to it,
                    // there is enough space for the loading card below the question card that will
                    // only appear after the pending question has been confirmed
                    Spacer(modifier = Modifier.height(72.dp))
                }
            } else {
                countedItem(canBeAnchor = true) {
                    ConfirmedQuestionCard(
                        userInput = pendingQuestion.userInput,
                        onClick = onConfirmedQuestionClick,
                    )
                }
                countedItem(canBeAnchor = false) {
                    LoadingAnswerCard(skill = pendingQuestion.skillBeingEvaluated)
                }
            }
        }

        countedItem(canBeAnchor = false) {
            Spacer(modifier = Modifier.height(84.dp))
        }
    }

    // TODO check if this causes performance problems
    var prevHasPending by rememberSaveable { mutableStateOf(false) }
    var prevInteractionCount by rememberSaveable { mutableIntStateOf(0) }
    val newHasPending = pendingQuestion != null
    val newInteractionCount = interactions.size
    LaunchedEffect(key1 = newHasPending, key2 = newInteractionCount) {
        if ((!prevHasPending && newHasPending) || newInteractionCount > prevInteractionCount) {
            scope.launch {
                listState.scrollToItem(lastAnchorIndex)
            }
        }
        prevHasPending = newHasPending
        prevInteractionCount = newInteractionCount
    }
}

@Preview
@Composable
fun InteractionListPreview(
    @PreviewParameter(InteractionLogPreviews::class) interactionLog: InteractionLog,
) {
    val wakeStatesPreviews = WakeStatesPreviews().values.toList()
    AppTheme {
        InteractionList(
            skillContext = SkillContextImpl.newForPreviews(LocalContext.current),
            skills = SkillInfoPreviews().values.toList(),
            interactionLog = interactionLog,
            wakeState = wakeStatesPreviews[interactionLog.hashCode().absoluteValue % wakeStatesPreviews.size],
            onWakeDownload = {},
            onWakeDisable = {},
            onConfirmedQuestionClick = {},
        )
    }
}

/** What the user is saying right now: their words on the right, in the plain interface face. */
@Preview
@Composable
fun PendingQuestionCard(@PreviewParameter(UserInputPreviews::class) userInput: String) {
    UserLine(userInput = userInput, pending = true, onClick = null)
}

/** What the user said; tapping it puts the words back in the text box to edit them. */
@Preview
@Composable
fun ConfirmedQuestionCard(
    @PreviewParameter(UserInputPreviews::class) userInput: String,
    onClick: (String) -> Unit = {},
) {
    UserLine(userInput = userInput, pending = false, onClick = { onClick(userInput) })
}

@Composable
private fun UserLine(userInput: String, pending: Boolean, onClick: (() -> Unit)?) {
    Row(
        horizontalArrangement = Arrangement.End,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 56.dp, end = 16.dp),
    ) {
        val shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
        Box(
            modifier = Modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = userInput,
                style = MaterialTheme.typography.bodyLarge,
                fontStyle = if (pending) FontStyle.Italic else FontStyle.Normal,
                color = if (pending) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Jarvis is working on it: the brass rule is already there, with the skill being used. */
@Preview
@Composable
fun LoadingAnswerCard(@PreviewParameter(SkillInfoPreviews::class) skill: SkillInfo) {
    JarvisLine {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(vertical = 6.dp),
        ) {
            Icon(
                painter = skill.icon(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Jarvis's answer: left aligned, no box, marked by a thin brass rule. */
@Composable
fun SkillAnswerCard(content: @Composable BoxScope.() -> Unit) {
    JarvisLine {
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier.fillMaxWidth(),
            content = content,
        )
    }
}

@Composable
private fun JarvisLine(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(start = 16.dp, end = 32.dp),
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
        )
        Spacer(modifier = Modifier.width(14.dp))
        Box(modifier = Modifier.weight(1f)) { content() }
    }
}

@Preview
@Composable
fun SkillAnswerCardPreview(@PreviewParameter(SkillOutputPreviews::class) skillOutput: SkillOutput) {
    SkillAnswerCard {
        skillOutput.GraphicalOutput(ctx = SkillContextImpl.newForPreviews(LocalContext.current))
    }
}

@Composable
fun MessageCard(
    containerColor: Color,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    val modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)
    if (onClick == null) {
        Card(colors = colors, modifier = modifier, content = content)
    } else {
        Card(onClick = onClick, modifier = modifier, colors = colors, content = content)
    }
}
