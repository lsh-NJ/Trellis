package dev.trellis.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.trellis.feature.exams.Exercise
import dev.trellis.feature.exams.ExerciseOption

/**
 * 练习二级页：只显示「刚接受的那一道题」。
 *
 * 不常驻导航、不列题库、没有上一题/下一题：这个页面是「学完一个单元 → 顺手练一道」
 * 的收尾动作，不是刷题工具。关掉就回会话页，下一道题等下一次提议。
 */
@Composable
internal fun PracticeScreen(runtime: AppRuntime, modifier: Modifier = Modifier) {
    val exercise = runtime.practiceExercise
    val tokens = LocalTrellisTokens.current
    val colors = tokens.colors

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ChromeSecondaryHeader(
            title = "练习",
            subtitle = exercise?.let { it.kind.displayName + " · " + it.subjectLabel }
                ?: "只显示刚接受的那一道题",
            onBack = { runtime.closePractice() },
        )
        Spacer(Modifier.height(tokens.space.l))

        if (exercise == null) {
            Text(
                "现在没有在练的题。学完一个单元后，管家会在会话里提议一道。",
                style = tokens.type.body,
                color = colors.ink2,
            )
            return@Column
        }

        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 760.dp)
                .align(Alignment.CenterHorizontally),
        ) {
            ExerciseCard(runtime, exercise)
            runtime.practiceMastery?.let { mastery ->
                Spacer(Modifier.height(tokens.space.s))
                Text(
                    "${mastery.subject}：${mastery.label()}",
                    style = tokens.type.caption,
                    color = colors.ink2,
                )
            }
            Spacer(Modifier.height(tokens.space.m))
            Text(
                "这道题来自题库，只在这一次练习里显示。",
                style = tokens.type.caption,
                color = colors.ink3,
            )
        }
    }
}

@Composable
private fun ExerciseCard(runtime: AppRuntime, exercise: Exercise) {
    val tokens = LocalTrellisTokens.current
    val colors = tokens.colors
    val revealed = runtime.practiceAnswerVisible
    val selected = runtime.practiceSelected
    val answerLabels = exercise.normalizedAnswerLabels().map { it.uppercase() }.toSet()

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(tokens.radius.card))
            .background(colors.surface)
            .border(1.dp, colors.rule, RoundedCornerShape(tokens.radius.card))
            .padding(tokens.space.l),
        verticalArrangement = Arrangement.spacedBy(tokens.space.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KindChip(exercise.kind.displayName)
            Spacer(Modifier.width(tokens.space.s))
            Text(exercise.subjectLabel, style = tokens.type.meta, color = colors.ink2)
            exercise.year?.let { year ->
                Spacer(Modifier.width(tokens.space.s))
                Text(year, style = tokens.type.caption, color = colors.ink3)
            }
        }

        Text(exercise.stem, style = tokens.type.read, color = colors.ink)

        if (exercise.options.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(tokens.space.s)) {
                exercise.options.forEach { option ->
                    ExerciseOptionRow(
                        option = option,
                        kind = exercise.kind,
                        selected = option.label.uppercase() in selected,
                        revealed = revealed,
                        correct = option.label.uppercase() in answerLabels,
                        onClick = { runtime.togglePracticeOption(option.label) },
                    )
                }
            }
        }

        if (!revealed) {
            Row(horizontalArrangement = Arrangement.spacedBy(tokens.space.m)) {
                ChromeButton(
                    label = "看答案",
                    onClick = { runtime.revealPracticeAnswer() },
                    enabled = exercise.options.isEmpty() || selected.isNotEmpty(),
                )
                if (exercise.options.isNotEmpty()) {
                    Text(
                        "答完再看答案",
                        style = tokens.type.caption,
                        color = colors.ink3,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        } else {
            AnswerPanel(runtime, exercise)
            if (!exercise.kind.isObjective) {
                SelfReportRow(runtime)
            }
            ChromeButton(label = "回到会话", onClick = { runtime.closePractice() })
        }
    }
}

/**
 * 知识点/简答题没有被判分的选项，用一句话自评给掌握度提供数据。
 *
 * 只影响演示用的最小掌握度，不承诺「掌握度=会了」；一次练习只记一条。
 */
@Composable
private fun SelfReportRow(runtime: AppRuntime) {
    val tokens = LocalTrellisTokens.current
    val colors = tokens.colors
    if (runtime.practiceAnswered) {
        Text("这次的自评已经记下了。", style = tokens.type.caption, color = colors.ink3)
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tokens.space.m),
        ) {
            Text("记住了吗？", style = tokens.type.body, color = colors.ink2)
            ChromeTextLink("记住了", onClick = { runtime.ratePracticeAnswer(remembered = true) })
            ChromeTextLink("没记住", onClick = { runtime.ratePracticeAnswer(remembered = false) })
        }
    }
}

@Composable
private fun AnswerPanel(runtime: AppRuntime, exercise: Exercise) {
    val tokens = LocalTrellisTokens.current
    val colors = tokens.colors
    val selected = runtime.practiceSelected
    val correct = exercise.normalizedAnswerLabels().map { it.uppercase() }.toSet()
    val chosen = selected.map { it.uppercase() }.toSet()
    val objective = exercise.options.isNotEmpty()

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(tokens.radius.control))
            .background(colors.sunken)
            .padding(tokens.space.m),
        verticalArrangement = Arrangement.spacedBy(tokens.space.xs),
    ) {
        if (objective) {
            val (label, color) = if (chosen == correct) "答对了" to colors.ok else "答错了" to colors.danger
            Text(label, style = tokens.type.title, color = color)
        }
        Text("答案", style = tokens.type.caption, color = colors.ink3)
        Text(exercise.answerText(), style = tokens.type.body, color = colors.ink)
        if (exercise.explanation.isNotBlank()) {
            Spacer(Modifier.height(tokens.space.xs))
            Text("解析", style = tokens.type.caption, color = colors.ink3)
            Text(exercise.explanation, style = tokens.type.body, color = colors.ink2)
        }
        Spacer(Modifier.height(tokens.space.xs))
        Text(
            "来源：${exercise.sourcePath}" + (exercise.sourcePage?.let { " · 第 $it 页" } ?: ""),
            style = tokens.type.caption,
            color = colors.ink3,
        )
    }
}

@Composable
private fun ExerciseOptionRow(
    option: ExerciseOption,
    kind: dev.trellis.feature.exams.ExerciseKind,
    selected: Boolean,
    revealed: Boolean,
    correct: Boolean,
    onClick: () -> Unit,
) {
    val tokens = LocalTrellisTokens.current
    val colors = tokens.colors
    val shape = RoundedCornerShape(tokens.radius.control)
    val background = when {
        revealed && correct -> colors.ok.copy(alpha = 0.12f)
        revealed && selected && !correct -> colors.danger.copy(alpha = 0.10f)
        selected -> colors.accentSoft
        else -> colors.surface
    }
    val borderColor = when {
        revealed && correct -> colors.ok
        revealed && selected && !correct -> colors.danger
        selected -> colors.accent
        else -> colors.rule
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape)
            .clickable(enabled = !revealed, onClick = onClick)
            .padding(horizontal = tokens.space.m, vertical = tokens.space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            option.label.uppercase(),
            style = tokens.type.control,
            color = if (selected || (revealed && correct)) colors.accent else colors.ink2,
            modifier = Modifier.width(24.dp),
        )
        Text(option.text, style = tokens.type.body, color = colors.ink)
        if (kind == dev.trellis.feature.exams.ExerciseKind.MultipleChoice) {
            Spacer(Modifier.weight(1f))
            Text(
                if (selected) "已选" else "",
                style = tokens.type.caption,
                color = colors.ink3,
            )
        }
    }
}

@Composable
private fun KindChip(label: String) {
    val tokens = LocalTrellisTokens.current
    Text(
        label,
        style = tokens.type.caption,
        color = tokens.colors.onAccentSoft,
        modifier = Modifier
            .clip(RoundedCornerShape(tokens.radius.control))
            .background(tokens.colors.accentSoft)
            .padding(horizontal = tokens.space.s, vertical = 2.dp),
    )
}
