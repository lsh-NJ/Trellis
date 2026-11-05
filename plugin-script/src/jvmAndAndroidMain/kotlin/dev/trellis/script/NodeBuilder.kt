package dev.trellis.script

import dev.trellis.kernel.rule.ActionCall
import dev.trellis.kernel.slot.UiAlign
import dev.trellis.kernel.slot.UiBar
import dev.trellis.kernel.slot.UiFont
import dev.trellis.kernel.slot.UiNode
import dev.trellis.kernel.slot.UiNoticeTone
import dev.trellis.kernel.slot.UiTap
import dev.trellis.kernel.slot.UiTextSize
import dev.trellis.kernel.slot.UiTextStyle
import dev.trellis.kernel.slot.UiTextWeight
import dev.trellis.kernel.slot.UiTone
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/**
 * 把插件交回来的 JS 对象翻译成 [UiNode]。
 *
 * 这一段是**唯一的信任边界**：从这里出去的东西，宿主一律当数据画。
 * 所以这里认不出的东西一律变成 [UiNode.Notice]，绝不「尽量猜一猜」。
 */
internal object NodeBuilder {

    private const val MAX_DEPTH = 8
    private const val MAX_NODES = 200
    private const val MAX_TEXT = 400
    private const val MAX_LABEL = 12
    private const val MAX_UNIT = 6
    private const val MAX_BADGE = 16
    private const val MAX_COLUMNS = 12
    private const val MAX_BARS = 12
    private const val MAX_POINTS = 64
    private const val MAX_GLYPH = 4
    private const val MAX_GLYPHS = 6
    private const val MAX_REF = 200
    private const val MAX_IMAGE_HEIGHT = 240
    private const val MAX_TABLE_COLS = 6
    private const val MAX_TABLE_ROWS = 20
    private const val MAX_TABLE_CELL = 80
    private const val MAX_HEAT_CELLS = 28

    fun build(value: Any?): UiNode = convert(value, 0, intArrayOf(MAX_NODES))

    private fun convert(value: Any?, depth: Int, budget: IntArray): UiNode {
        if (budget[0] <= 0) return UiNode.Notice("这张卡片画的东西太多了（超过 " + MAX_NODES + " 个）", UiNoticeTone.Problem)
        budget[0] -= 1

        if (depth > MAX_DEPTH) return UiNode.Notice("卡片嵌套太深（超过 " + MAX_DEPTH + " 层）", UiNoticeTone.Problem)
        if (value == null || value is Undefined) return UiNode.Notice("插件这一步什么都没返回", UiNoticeTone.Problem)
        if (value is String) return UiNode.Text(clip(value))
        if (value is Number || value is Boolean) return UiNode.Text(value.toString())
        if (value !is Scriptable) return UiNode.Notice("插件返回了宿主看不懂的东西", UiNoticeTone.Problem)

        val type = string(value, "type") ?: return UiNode.Notice("卡片里有一个节点没写 type", UiNoticeTone.Problem)
        return when (type) {
            "column" -> UiNode.Column(children(value, depth, budget), gap(value), align(value))
            "row" -> UiNode.Row(children(value, depth, budget), gap(value), align(value))
            "section" -> UiNode.Section(
                title = clip(string(value, "title") ?: "", MAX_TEXT),
                children = children(value, depth, budget),
            )
            "grid" -> {
                val columns = int(value, "columns", 2).coerceIn(1, MAX_COLUMNS)
                UiNode.Grid(columns = columns, children = gridChildren(value, columns, depth, budget))
            }
            "text" -> UiNode.Text(
                text = clip(string(value, "text") ?: string(value, "content") ?: ""),
                style = style(value),
                tone = tone(value),
                size = textSize(value),
                weight = textWeight(value),
                font = font(value),
            )
            "bars" -> bars(value)
            "sparkline" -> sparkline(value)
            "columns" -> columns(value)
            "area" -> area(value)
            "ring" -> ring(value)
            "dotmatrix" -> dotMatrix(value)
            "stat" -> stat(value)
            "badge" -> UiNode.Badge(
                text = clip(string(value, "text") ?: string(value, "content") ?: "", MAX_BADGE),
                tone = tone(value),
            )
            "progress" -> progress(value)
            "icon" -> icon(value)
            "image" -> image(value)
            "table" -> table(value)
            "heat" -> heat(value)
            "spacer" -> UiNode.Spacer(int(value, "height", 8).coerceIn(0, 64))
            "notice" -> UiNode.Notice(clip(string(value, "text") ?: string(value, "content") ?: ""))
            "divider" -> UiNode.Divider
            "button" -> button(value)
            "input" -> input(value)
            "stepper" -> stepper(value)
            else -> UiNode.Notice("插件用了宿主不认识的界面元素：" + type, UiNoticeTone.Problem)
        }
    }

    private fun children(value: Scriptable, depth: Int, budget: IntArray): List<UiNode> {
        val raw = ScriptableObject.getProperty(value, "children")
        if (raw !is NativeArray) return emptyList()
        val out = mutableListOf<UiNode>()
        for (i in 0 until raw.length.toInt()) out += convert(raw.get(i, raw), depth + 1, budget)
        return out
    }

    /**
     * 网格的子节点可以带 `span`（占几栏）。`span` 超过总栏数就夹到总栏数，
     * 小于 1 按 1 —— 写错了也不会把整张卡片打成报错，最多排得难看。
     */
    private fun gridChildren(value: Scriptable, columns: Int, depth: Int, budget: IntArray): List<UiNode> {
        val raw = ScriptableObject.getProperty(value, "children")
        if (raw !is NativeArray) return emptyList()
        val out = mutableListOf<UiNode>()
        for (i in 0 until raw.length.toInt()) {
            val item = raw.get(i, raw)
            val node = convert(item, depth + 1, budget)
            val span = if (item is Scriptable) {
                int(item, "span", 1).coerceIn(1, columns)
            } else {
                1
            }
            out += if (span > 1) UiNode.Cell(span, node) else node
        }
        return out
    }

    private fun stat(value: Scriptable): UiNode {
        val label = string(value, "label") ?: string(value, "title")
        val number = string(value, "value") ?: string(value, "text")
        if (label == null && number == null) {
            return UiNode.Notice("stat 至少要写 label 或 value", UiNoticeTone.Problem)
        }
        return UiNode.Stat(clip(label ?: "", MAX_LABEL), clip(number ?: "", MAX_LABEL))
    }

    private fun progress(value: Scriptable): UiNode {
        val max = int(value, "max", 100).coerceAtLeast(1)
        val raw = int(value, "value", 0)
        return UiNode.Progress(
            value = raw.coerceIn(0, max),
            max = max,
            label = clip(string(value, "label") ?: "", MAX_TEXT),
        )
    }

    private fun bars(value: Scriptable): UiNode {
        val raw = ScriptableObject.getProperty(value, "items")
        if (raw !is NativeArray) return UiNode.Notice("bars 需要一个 items 数组", UiNoticeTone.Problem)
        val out = mutableListOf<UiBar>()
        for (i in 0 until minOf(raw.length.toInt(), MAX_BARS)) {
            val item = raw.get(i, raw)
            if (item !is Scriptable) continue
            out += UiBar(
                label = clip(string(item, "label") ?: "", MAX_LABEL),
                value = int(item, "value", 0),
            )
        }
        if (out.isEmpty()) return UiNode.Notice("bars 里没有可画的数据", UiNoticeTone.Problem)
        return UiNode.Bars(
            items = out,
            max = (ScriptableObject.getProperty(value, "max") as? Number)?.toInt(),
            label = clip(string(value, "label") ?: "", MAX_TEXT),
        )
    }

    private fun sparkline(value: Scriptable): UiNode {
        val raw = ScriptableObject.getProperty(value, "values")
        if (raw !is NativeArray) return UiNode.Notice("sparkline 需要一个 values 数字数组", UiNoticeTone.Problem)
        val points = mutableListOf<Int>()
        for (i in 0 until minOf(raw.length.toInt(), MAX_POINTS)) {
            val number = raw.get(i, raw) as? Number ?: continue
            points += number.toInt()
        }
        if (points.isEmpty()) return UiNode.Notice("sparkline 里没有可画的点", UiNoticeTone.Problem)
        val min = (ScriptableObject.getProperty(value, "min") as? Number)?.toInt() ?: 0
        val max = (ScriptableObject.getProperty(value, "max") as? Number)?.toInt()
            ?: maxOf(points.max(), 1)
        return UiNode.Sparkline(points, min, max.coerceAtLeast(min + 1), clip(string(value, "label") ?: "", MAX_TEXT))
    }

    private fun columns(value: Scriptable): UiNode {
        val raw = ScriptableObject.getProperty(value, "values")
        if (raw !is NativeArray) return UiNode.Notice("columns 需要一个 values 数字数组", UiNoticeTone.Problem)
        val points = mutableListOf<Int>()
        for (i in 0 until minOf(raw.length.toInt(), MAX_POINTS)) {
            val number = raw.get(i, raw) as? Number ?: continue
            points += number.toInt()
        }
        if (points.isEmpty()) return UiNode.Notice("columns 里没有可画的柱子", UiNoticeTone.Problem)
        val labels = mutableListOf<String>()
        val rawLabels = ScriptableObject.getProperty(value, "labels")
        if (rawLabels is NativeArray) {
            for (i in 0 until minOf(rawLabels.length.toInt(), MAX_POINTS)) {
                labels += clip((rawLabels.get(i, rawLabels) as? String) ?: "", MAX_LABEL)
            }
        }
        return UiNode.Columns(
            values = points,
            labels = labels,
            max = (ScriptableObject.getProperty(value, "max") as? Number)?.toInt(),
            label = clip(string(value, "label") ?: "", MAX_TEXT),
        )
    }

    private fun area(value: Scriptable): UiNode {
        val raw = ScriptableObject.getProperty(value, "values")
        if (raw !is NativeArray) return UiNode.Notice("area 需要一个 values 数字数组", UiNoticeTone.Problem)
        val points = mutableListOf<Int>()
        for (i in 0 until minOf(raw.length.toInt(), MAX_POINTS)) {
            val number = raw.get(i, raw) as? Number ?: continue
            points += number.toInt()
        }
        if (points.isEmpty()) return UiNode.Notice("area 里没有可画的点", UiNoticeTone.Problem)
        val min = (ScriptableObject.getProperty(value, "min") as? Number)?.toInt() ?: 0
        val max = (ScriptableObject.getProperty(value, "max") as? Number)?.toInt()
            ?: maxOf(points.max(), 1)
        return UiNode.Area(points, min, max.coerceAtLeast(min + 1), clip(string(value, "label") ?: "", MAX_TEXT))
    }

    private fun dotMatrix(value: Scriptable): UiNode {
        val raw = string(value, "value") ?: string(value, "text") ?: ""
        val glyphs = raw.filter { it.isDigit() || it == ':' || it == '-' || it == ' ' }
        if (glyphs.isBlank()) return UiNode.Notice("dotmatrix 需要 value（0-9 的数字，例如 \"20\"）", UiNoticeTone.Problem)
        return UiNode.DotMatrix(
            value = clip(glyphs, MAX_GLYPHS),
            label = clip(string(value, "label") ?: "", MAX_TEXT),
        )
    }

    private fun ring(value: Scriptable): UiNode {
        val max = int(value, "max", 100).coerceAtLeast(1)
        return UiNode.Ring(
            value = int(value, "value", 0).coerceIn(0, max),
            max = max,
            label = clip(string(value, "label") ?: "", MAX_TEXT),
        )
    }

    private fun textSize(value: Scriptable): UiTextSize = when (string(value, "size")) {
        "small" -> UiTextSize.Small
        "large" -> UiTextSize.Large
        "huge" -> UiTextSize.Huge
        else -> UiTextSize.Normal
    }

    private fun textWeight(value: Scriptable): UiTextWeight = when (string(value, "weight")) {
        "medium" -> UiTextWeight.Medium
        "bold" -> UiTextWeight.Bold
        else -> UiTextWeight.Normal
    }

    private fun font(value: Scriptable): UiFont = when (string(value, "font")) {
        "mono" -> UiFont.Mono
        "serif" -> UiFont.Serif
        "condensed" -> UiFont.Condensed
        else -> UiFont.Default
    }

    private fun icon(value: Scriptable): UiNode {
        val glyph = string(value, "glyph") ?: string(value, "text") ?: string(value, "emoji")
        if (glyph.isNullOrBlank()) return UiNode.Notice("icon 里没有写 glyph", UiNoticeTone.Problem)
        return UiNode.Icon(
            glyph = clip(glyph, MAX_GLYPH),
            size = int(value, "size", 24).coerceIn(16, 64),
        )
    }

    private fun image(value: Scriptable): UiNode {
        val ref = string(value, "src") ?: string(value, "ref") ?: string(value, "path")
        if (ref.isNullOrBlank()) {
            return UiNode.Notice("image 里没有写 src（插件目录里的相对路径）", UiNoticeTone.Problem)
        }
        return UiNode.Image(
            ref = clip(ref, MAX_REF),
            alt = clip(string(value, "alt") ?: "", MAX_TEXT),
            height = int(value, "height", 96).coerceIn(24, MAX_IMAGE_HEIGHT),
        )
    }

    private fun heat(value: Scriptable): UiNode {
        val raw = ScriptableObject.getProperty(value, "values")
        if (raw !is NativeArray) {
            return UiNode.Notice("heat 需要一个 values 数组（每格强度，0 = 没记录）", UiNoticeTone.Problem)
        }
        val values = (0 until minOf(raw.length.toInt(), MAX_HEAT_CELLS)).map { index ->
            (plain(raw.get(index, raw)).toIntOrNull() ?: 0).coerceAtLeast(0)
        }
        val labelsRaw = ScriptableObject.getProperty(value, "labels")
        val labels = if (labelsRaw is NativeArray) {
            (0 until minOf(labelsRaw.length.toInt(), MAX_HEAT_CELLS)).map { index ->
                plain(labelsRaw.get(index, labelsRaw))
            }
        } else {
            emptyList()
        }
        return UiNode.HeatStrip(values = values, max = int(value, "max", 5).coerceIn(1, 100), labels = labels)
    }

    private fun table(value: Scriptable): UiNode {
        val rawHeaders = ScriptableObject.getProperty(value, "headers")
        val headers = if (rawHeaders is NativeArray) {
            (0 until minOf(rawHeaders.length.toInt(), MAX_TABLE_COLS)).map { i ->
                clip(plain(rawHeaders.get(i, rawHeaders)), MAX_TABLE_CELL)
            }
        } else {
            emptyList()
        }
        val rawRows = ScriptableObject.getProperty(value, "rows")
        if (rawRows !is NativeArray) {
            return UiNode.Notice("table 需要一个 rows 数组（每一行也是一个数组）", UiNoticeTone.Problem)
        }
        val rows = mutableListOf<List<String>>()
        for (i in 0 until minOf(rawRows.length.toInt(), MAX_TABLE_ROWS)) {
            val rawRow = rawRows.get(i, rawRows)
            if (rawRow !is NativeArray) continue
            rows += (0 until minOf(rawRow.length.toInt(), MAX_TABLE_COLS)).map { cell ->
                clip(plain(rawRow.get(cell, rawRow)), MAX_TABLE_CELL)
            }
        }
        if (rows.isEmpty()) return UiNode.Notice("table 里没有可画的行", UiNoticeTone.Problem)
        return UiNode.Table(
            headers = headers,
            rows = rows,
            caption = clip(string(value, "caption") ?: "", MAX_TEXT),
        )
    }

    private fun button(value: Scriptable): UiNode {
        val label = clip(string(value, "label") ?: "按钮", MAX_LABEL)
        val on = string(value, "on")
        if (on != null) return UiNode.Button(label, UiTap.Plugin(on, args(value)))
        val action = string(value, "action")
        if (action != null) return UiNode.Button(label, UiTap.Host(ActionCall(action, args(value))))
        return UiNode.Notice("按钮「" + label + "」没说要干什么", UiNoticeTone.Problem)
    }

    private fun input(value: Scriptable): UiNode {
        val on = string(value, "on") ?: return UiNode.Notice("输入框没写 on（提交之后交给谁）", UiNoticeTone.Problem)
        return UiNode.Input(
            hint = clip(string(value, "hint") ?: "", MAX_TEXT),
            submitLabel = clip(string(value, "submitLabel") ?: "记下", MAX_LABEL),
            onSubmit = UiTap.Plugin(on),
        )
    }

    private fun stepper(value: Scriptable): UiNode {
        val on = string(value, "on")
            ?: return UiNode.Notice("计步器没写 on（加分/减分交给谁）", UiNoticeTone.Problem)
        val min = int(value, "min", 0)
        val max = int(value, "max", 999).coerceAtLeast(min)
        val step = int(value, "step", 1).coerceIn(1, 100)
        val raw = int(value, "value", min)
        return UiNode.Stepper(
            label = clip(string(value, "label") ?: string(value, "title") ?: "", MAX_TEXT),
            value = raw.coerceIn(min, max),
            unit = clip(string(value, "unit") ?: "", MAX_UNIT),
            min = min,
            max = max,
            step = step,
            onStep = UiTap.Plugin(on, args(value)),
        )
    }

    private fun args(value: Scriptable): Map<String, String> {
        val raw = ScriptableObject.getProperty(value, "args")
        if (raw !is Scriptable) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (key in raw.ids) {
            val name = key.toString()
            out[name] = plain(ScriptableObject.getProperty(raw, name))
        }
        return out
    }

    private fun plain(value: Any?): String = when (value) {
        null, is Undefined -> ""
        is Number -> {
            val d = value.toDouble()
            if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()
        }
        else -> value.toString()
    }

    private fun style(value: Scriptable): UiTextStyle = when (string(value, "style")) {
        "title" -> UiTextStyle.Title
        "caption" -> UiTextStyle.Caption
        else -> UiTextStyle.Body
    }

    private fun tone(value: Scriptable): UiTone = when (string(value, "tone")) {
        "muted" -> UiTone.Muted
        "accent" -> UiTone.Accent
        "danger" -> UiTone.Danger
        "success" -> UiTone.Success
        else -> UiTone.Default
    }

    private fun align(value: Scriptable): UiAlign = when (string(value, "align")) {
        "center" -> UiAlign.Center
        "end" -> UiAlign.End
        else -> UiAlign.Start
    }

    private fun gap(value: Scriptable): Int =
        ((ScriptableObject.getProperty(value, "gap") as? Number)?.toInt() ?: 8).coerceIn(0, 32)

    private fun int(value: Scriptable, name: String, fallback: Int): Int =
        ((ScriptableObject.getProperty(value, name) as? Number)?.toInt()) ?: fallback

    private fun string(value: Scriptable, name: String): String? {
        val raw = ScriptableObject.getProperty(value, name)
        if (raw == null || raw is Undefined || raw === Scriptable.NOT_FOUND) return null
        if (raw is CharSequence) return raw.toString()
        if (raw is Number) return raw.toString()
        return null
    }

    private fun clip(text: String, limit: Int = MAX_TEXT): String =
        if (text.length <= limit) text else text.substring(0, limit) + "…"
}
