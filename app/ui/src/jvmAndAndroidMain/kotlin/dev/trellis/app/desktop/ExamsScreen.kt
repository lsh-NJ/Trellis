package dev.trellis.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.trellis.feature.exams.Exercise
import dev.trellis.feature.exams.ExerciseKind

/**
 * 练习页：左栏按学科选题，右栏一道一道做。
 *
 * 题库来自 `:features:exams`（种子 JSONL + `<home>/exams/` 里用户自己的题目）。
 * 这一版只做「做题 + 看答案」：不记错题本、不攒掌握度 —— 先把「能练」这条链路打通，
 * 之后的推荐/掌握度才有数据可依。
 */
@Composable
internal fun ExamsScreen(runtime: AppRuntime, modifier: Modifier = Modifier) {
    val tokens = LocalTrellisTokens.current
    Row(modifier.fillMaxSize().background(tokens.colors.bg)) {
        ExamSidebar(runtime, Modifier.width(264.dp).fillMaxHeight())
        ListSidebarSplitter()
        ExamPractice(runtime, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun ExamSidebar(runtime: AppRuntime, modifier: Modifier = Modifier) {
    val tokens = LocalTrellisTokens.current
    val bank = runtime.examBank
    ListSidebar(
        title = "练习",
        count = bank.size,
        actionLabel = "换一组",
        onAction = { runtime.restartExamPractice() },
        modifier = modifier,
    ) {
        ChromeTextField(
            value = runtime.examQuery,
            onValueChange = { runtime.setExamQuery(it) },
            placeholder = "搜索题干 / 答案 / 标签",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        ExamSubjectRow(
            label = "全部",
            count = bank.size,
            selected = runtime.examSubject == null,
            onClick = { runtime.selectExamSubject(null) },
        )
        bank.subjects.forEach { subject ->
            ExamSubjectRow(
                label = subject,
                count = bank.bySubject(subject).size,
                selected = runtime.examSubject == subject,
                onClick = { runtime.selectExamSubject(subject) },
            )
        }
        if (bank.isEmpty) {
            Spacer(Modifier.height(8.dp))
            Text(
                "题库是空的。把题库 .jsonl 放进数据目录的 exams/ 下，或跑一次 tools/exams/ 里的抓取脚本生成种子。",
                style = tokens.type.caption,
                color = tokens.colors.ink3,
            )
        }
        runtime.examLoadNotes.take(3).forEach { note ->
            Spacer(Modifier.height(6.dp))
            Text(note, style = tokens.type.caption, color = tokens.colors.warn)
        }
    }
}

@Composable
private fun ExamSubjectRow(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = LocalTrellisTokens.current
    val shape = RoundedCornerShape(tokens.radius.control)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) tokens.colors.accentSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = tokens.type.body,
            color = if (selected) tokens.colors.onAccentSoft else tokens.colors.ink,
            modifier = Modifier.weight(1f),
        )
        Text(count.toString(), style = tokens.type.caption, color = tokens.colors.ink3)
    }
}

@Composable
private fun ExamPractice(runtime: AppRuntime, modifier: Modifier = Modifier) {
    val tokens = LocalTrellisTokens.current
    val current = runtime.examCurrent
    Column(modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
        Text("练习", style = tokens.type.page, color = tokens.colors.ink)
        Spacer(Modifier.height(6.dp))
        Text(
            buildString {
                append("题库 ").append(runtime.examBank.size).append(" 题")
                runtime.examSubject?.let { append(" · ").append(it) }
                if (runtime.examQuery.isNotBlank()) append(" · 搜索「").append(runtime.examQuery).append("」")
                if (runtime.examQueue.isNotEmpty()) {
                    append(" · 第 ").append(runtime.examIndex + 1).append(" / ").append(runtime.examQueue.size)
                }
            },
            style = tokens.type.meta,
            color = tokens.colors.ink3,
        )
        Spacer(Modifier.height(16.dp))
        when {
            runtime.examBank.isEmpty -> ExamEmptyCard(
                title = "还没有题可做",
                detail = "题库是空的。把 .jsonl 放进数据目录的 exams/ 下，或者按 tools/exams/README.md 跑一次抓取脚本生成种子题库。",
                runtime = runtime,
            )
            current == null -> ExamEmptyCard(
                title = "这一组筛选下没有题",
                detail = "换个学科或清空搜索词试试；「换一组」会按当前筛选重新抽一轮。",
                runtime = runtime,
            )
            else -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                ExamQuestionCard(runtime, current)
            }
        }
    }
}

@Composable
private fun ExamEmptyCard(title: String, detail: String, runtime: AppRuntime) {
    val tokens = LocalTrellisTokens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(tokens.radius.card))
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.rule, RoundedCornerShape(tokens.radius.card))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = tokens.type.title, color = tokens.colors.ink)
        Text(detail, style = tokens.type.body, color = tokens.colors.ink2)
        TextButton(onClick = { runtime.restartExamPractice() }) { Text("换一组") }
    }
}

@Composable
private fun ExamQuestionCard(runtime: AppRuntime, exercise: Exercise) {
    val tokens = LocalTrellisTokens.current
    val revealed = runtime.examAnswerVisible
    val selected = runtime.examSelected
    val correctLabels = exercise.normalizedAnswerLabels()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(tokens.radius.card))
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.ruleStrong, RoundedCornerShape(tokens.radius.card))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                exercise.kind.displayName,
                style = tokens.type.caption,
                color = tokens.colors.onAccentSoft,
                modifier = Modifier
                    .clip(RoundedCornerShape(tokens.radius.control))
                    .background(tokens.colors.accentSoft)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                exercise.subjectLabel,
                style = tokens.type.meta,
                color = tokens.colors.ink3,
                modifier = Modifier.weight(1f),
            )
            exercise.year?.let { Text(it, style = tokens.type.meta, color = tokens.colors.ink3) }
        }
        Text(exercise.stem, style = tokens.type.title, color = tokens.colors.ink)

        if (exercise.options.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                exercise.options.forEach { option ->
                    ExamOptionRow(
                        label = option.label,
                        text = option.text,
                        selected = option.label in selected,
                        correct = option.label in correctLabels,
                        revealed = revealed,
                        onClick = { runtime.toggleExamOption(option.label) },
                    )
                }
            }
        }

        if (revealed) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(tokens.radius.control))
                    .background(tokens.colors.sunken)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("答案", style = tokens.type.caption, color = tokens.colors.ink3)
                Text(exercise.answerText(), style = tokens.type.body, color = tokens.colors.ink)
                if (exercise.explanation.isNotBlank()) {
                    Text(exercise.explanation, style = tokens.type.body, color = tokens.colors.ink2)
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildString {
                    append("第 ").append(runtime.examIndex + 1).append(" / ").append(runtime.examQueue.size).append(" 题")
                    if (exercise.sourcePath.isNotBlank()) {
                        append(" · 来源 ").append(exercise.sourcePath.substringAfterLast('/'))
                    }
                    exercise.sourcePage?.let { append(" p.").append(it) }
                },
                style = tokens.type.caption,
                color = tokens.colors.ink3,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { runtime.prevExam() }, enabled = runtime.examIndex > 0) { Text("上一题") }
            if (revealed) {
                TextButton(
                    onClick = { runtime.nextExam() },
                    enabled = runtime.examIndex < runtime.examQueue.size - 1,
                ) { Text("下一题") }
            } else {
                TextButton(onClick = { runtime.revealExamAnswer() }) { Text("看答案") }
            }
        }
    }
}

@Composable
private fun ExamOptionRow(
    label: String,
    text: String,
    selected: Boolean,
    correct: Boolean,
    revealed: Boolean,
    onClick: () -> Unit,
) {
    val tokens = LocalTrellisTokens.current
    val background = when {
        revealed && correct -> tokens.colors.accentSoft
        revealed && selected && !correct -> tokens.colors.danger.copy(alpha = 0.10f)
        selected -> tokens.colors.accentSoft
        else -> Color.Transparent
    }
    val border = when {
        revealed && correct -> tokens.colors.accent
        selected -> tokens.colors.ruleStrong
        else -> tokens.colors.rule
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(tokens.radius.control))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(tokens.radius.control))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(tokens.radius.control))
                .background(if (selected || (revealed && correct)) tokens.colors.accent else tokens.colors.sunken),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = tokens.type.caption,
                fontWeight = FontWeight.SemiBold,
                color = if (selected || (revealed && correct)) tokens.colors.onAccent else tokens.colors.ink2,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = tokens.type.body, color = tokens.colors.ink)
    }
}
