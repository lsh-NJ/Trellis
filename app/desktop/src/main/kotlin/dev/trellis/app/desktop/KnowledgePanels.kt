package dev.trellis.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 知识库左右两栏里的零件：笔记列表、概念 chips、概念列表、概念详情、搜索结果。
 *
 * 这些都只是「把数据画出来 + 把点击回调出去」，不读文件、不调模型——
 * 页面（[KnowledgeScreen]）负责状态与落盘，这一层只保证放得下、点得到。
 */

internal fun knowledgeTimeLabel(ms: Long, zone: ZoneId): String =
    DateTimeFormatter.ofPattern("MM-dd HH:mm").format(Instant.ofEpochMilli(ms).atZone(zone))

/** 左栏顶部的「笔记 / 概念」切换：两个小按钮，选中态用主色浅底。 */
@Composable
internal fun KnowledgePaneTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(ControlShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** 笔记列表；query 过滤在页面里做（概念搜索走 [KnowledgeSearchResults]）。 */
@Composable
internal fun NoteListPanel(
    notes: List<NoteSummary>,
    selectedId: String?,
    zone: ZoneId,
    emptyHint: String,
    onSelect: (String) -> Unit,
    onDelete: (NoteSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (notes.isEmpty()) {
        Text(
            emptyHint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(8.dp),
        )
        return
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(notes, key = { it.id }) { note ->
            NoteListRow(
                note = note,
                selected = note.id == selectedId,
                zone = zone,
                onClick = { onSelect(note.id) },
                onDelete = { onDelete(note) },
            )
        }
    }
}

@Composable
private fun NoteListRow(
    note: NoteSummary,
    selected: Boolean,
    zone: ZoneId,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ControlShape)
            .background(background)
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                displayTitle(note.title),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                knowledgeTimeLabel(note.updatedAt, zone) + " · " + note.chars + " 字符",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "×",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(ControlShape)
                .clickable(onClick = onDelete)
                .padding(6.dp),
        )
    }
}

/**
 * 笔记底部的「本笔记涉及的概念」：点 chip 看概念详情，点 × 熄灭（删引用），
 * 末尾一个输入框手动补概念（AI 抽不到时用户自己标，是 §4「手动兜底」的入口）。
 */
@Composable
internal fun NoteConceptChips(
    concepts: List<String>,
    onOpenConcept: (String) -> Unit,
    onRemoveConcept: (String) -> Unit,
    onAddConcept: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }

    fun submit() {
        val name = normalizeConceptName(input)
        input = ""
        if (name.isNotEmpty()) onAddConcept(name)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "概念",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (name in concepts) {
            ConceptChip(
                name = name,
                onOpen = { onOpenConcept(name) },
                onRemove = { onRemoveConcept(name) },
            )
        }
        if (concepts.isEmpty()) {
            Text(
                "还没有概念",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        BasicTextField(
            value = input,
            onValueChange = { input = it },
            singleLine = true,
            modifier = Modifier
                .width(150.dp)
                .padding(horizontal = 6.dp, vertical = 4.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Enter || event.key == Key.NumPadEnter)
                    ) {
                        submit()
                        true
                    } else {
                        false
                    }
                },
            textStyle = MaterialTheme.typography.labelMedium.merge(
                TextStyle(color = MaterialTheme.colorScheme.onSurface),
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner ->
                Box {
                    if (input.isEmpty()) {
                        Text(
                            "添加概念，回车确认",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                    inner()
                }
            },
        )
    }
}

/**
 * 右栏的「本篇概念」：当前这篇笔记点亮的词，竖着放。
 *
 * 和左栏的「概念」tab 分工：左栏是全局概念索引（所有笔记）；这里是这篇笔记自己
 * 涉及的概念，点开看详情、× 熄灭（只从这篇笔记删引用）、下面输入框手动补。
 * 用 [FlowRow] 自动换行，窄侧栏也放得下。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NoteConceptPanel(
    concepts: List<String>,
    onOpenConcept: (String) -> Unit,
    onRemoveConcept: (String) -> Unit,
    onAddConcept: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }

    fun submit() {
        val name = normalizeConceptName(input)
        input = ""
        if (name.isNotEmpty()) onAddConcept(name)
    }

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "本篇概念",
                style = MaterialTheme.typography.labelLarge,
                fontFamily = AppFonts.mono,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                concepts.size.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (concepts.isEmpty()) {
            Text(
                "这篇还没有概念。保存后 AI 会整理，也可以在下面加。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (name in concepts) {
                    ConceptChip(
                        name = name,
                        onOpen = { onOpenConcept(name) },
                        onRemove = { onRemoveConcept(name) },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = input,
            onValueChange = { input = it },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Enter || event.key == Key.NumPadEnter)
                    ) {
                        submit()
                        true
                    } else {
                        false
                    }
                },
            textStyle = MaterialTheme.typography.labelMedium.merge(
                TextStyle(color = MaterialTheme.colorScheme.onSurface),
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner ->
                Box {
                    if (input.isEmpty()) {
                        Text(
                            "添加概念，回车确认",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
private fun ConceptChip(
    name: String,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(ControlShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .padding(start = 9.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .clickable(onClick = onOpen)
                .padding(vertical = 4.dp),
        )
        Text(
            "×",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(ControlShape)
                .clickable(onClick = onRemove)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

/** 概念列表：一行 = 概念名 + 篇数/次数 + 最近更新。 */
@Composable
internal fun ConceptListPanel(
    concepts: List<ConceptSummary>,
    selectedName: String?,
    zone: ZoneId,
    emptyHint: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (concepts.isEmpty()) {
        Text(
            emptyHint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(8.dp),
        )
        return
    }
    val selectedKey = selectedName?.let(::conceptKey)
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(concepts, key = { conceptKey(it.name) }) { concept ->
            val selected = conceptKey(concept.name) == selectedKey
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ControlShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    )
                    .clickable { onSelect(concept.name) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        concept.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        concept.noteCount.toString() + " 篇 · " + concept.mentionCount + " 次 · " +
                            knowledgeTimeLabel(concept.updatedAt, zone),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 概念详情：一句定义（可改）+ 来源笔记（点「定位」跳回正文命中处）+ 相关概念。
 *
 * 定义存在 `<home>/knowledge/concepts.json`（[ConceptMetaStore]），不进笔记正文；
 * 来源片段每次从笔记文件现算，所以删掉笔记里的引用，这里也自动变少。
 */
@Composable
internal fun ConceptDetailPanel(
    detail: ConceptDetail?,
    definition: String,
    definitionStatus: String,
    zone: ZoneId,
    onDefinitionChange: (String) -> Unit,
    onLocate: (ConceptMention) -> Unit,
    onOpenRelated: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (detail == null) {
        Box(modifier.fillMaxWidth().padding(24.dp)) {
            Text(
                "选一个概念看它出现在哪些笔记里。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    Column(modifier.fillMaxWidth()) {
        Text(detail.summary.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            detail.summary.noteCount.toString() + " 篇笔记提到 · 共 " + detail.summary.mentionCount + " 次",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        Text(
            "定义",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(ControlShape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, ControlShape)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            BasicTextField(
                value = definition,
                onValueChange = onDefinitionChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.merge(
                    TextStyle(color = MaterialTheme.colorScheme.onSurface),
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    Box {
                        if (definition.isEmpty()) {
                            Text(
                                "给这个概念写一句定义；AI 会自动补，可以改。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                        inner()
                    }
                },
            )
        }
        if (definitionStatus.isNotEmpty()) {
            Text(
                definitionStatus,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(12.dp))

        Text(
            "来源（点一下跳回正文）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(detail.mentions, key = { it.noteId }) { mention ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(ControlShape)
                        .clickable { onLocate(mention) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            displayTitle(mention.noteTitle),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            knowledgeTimeLabel(mention.noteUpdatedAt, zone),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        mention.snippet,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (detail.related.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "常一起出现",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (related in detail.related) {
                    Box(
                        modifier = Modifier
                            .clip(ControlShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                            .clickable { onOpenRelated(related.name) }
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                    ) {
                        Text(related.name, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/** 搜索命中：概念一组、笔记一组；点哪个进哪个。 */
@Composable
internal fun KnowledgeSearchResults(
    hits: SearchHits,
    selectedNoteId: String?,
    selectedConceptName: String?,
    zone: ZoneId,
    onOpenNote: (String) -> Unit,
    onOpenConcept: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (hits.concepts.isEmpty() && hits.notes.isEmpty()) {
        Text(
            "没有匹配的概念或笔记",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(8.dp),
        )
        return
    }
    val selectedKey = selectedConceptName?.let(::conceptKey)
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (hits.concepts.isNotEmpty()) {
            item { SectionLabel("概念") }
            items(hits.concepts, key = { "concept:" + conceptKey(it.name) }) { concept ->
                val selected = conceptKey(concept.name) == selectedKey
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(ControlShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        )
                        .clickable { onOpenConcept(concept.name) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        concept.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        concept.noteCount.toString() + " 篇",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (hits.notes.isNotEmpty()) {
            item { SectionLabel("笔记") }
            items(hits.notes, key = { "note:" + it.id }) { note ->
                NoteListRow(
                    note = note,
                    selected = note.id == selectedNoteId,
                    zone = zone,
                    onClick = { onOpenNote(note.id) },
                    onDelete = {},
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
    )
}

/** 左下角偶尔用到的空态提示（现在只有概念栏在用，留着统一风格）。 */
@Composable
internal fun KnowledgeHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(8.dp),
    )
}
