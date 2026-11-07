#!/usr/bin/env python3
"""build_seed.py — 把上游 Free-Exams 的题目资料整理成 Trellis 题库种子。

数据来源（全部由 fetch.py / extract_pdfs.py 下载到 tools/exams/.cache/，不提交）：

1. `.cache/reserved/*.md`（NJUPT-General-Free-Exams 的“考试回忆/题库/复习要点”）
   - 体育_23-24-1_理论考试教师版题库.md   → 203 题（111 单选 + 92 判断，带答案）
   - 现代邮政英语-MOOC-2024.md            → 234 题（40 选择 + 194 填空/术语/翻译，带参考答案）
   - 毛概_24-25-1_复习.md                 → 17 名词解释（RecallCard）+ 主观题（ShortAnswer/Essay）
   - 数据结构（混合式）_*-复习笔记.md ×3  → 问句标题/术语定义 → RecallCard
2. `.cache/pdf-text/*.jsonl`（pdf_extract.swift 的逐页输出）
   - 数字电路与逻辑设计A 2016-2017-02 判分标准 → PDF 文本层里带红色答案的 19 道选择题

输出：`features/exams/src/jvmMain/resources/exam-seed/exercises.jsonl`
（JSONL，一行一个 Exercise；只提交这个小文件，PDF/图片/OCR 中间产物都不提交。）

用法：
    python3 tools/exams/fetch.py reserved        # 拉 reserved md
    python3 tools/exams/fetch.py papers --count 8 --download
    python3 tools/exams/extract_pdfs.py --max-pages 20   # 数电判分标准 PDF → pdf-text/
    python3 tools/exams/build_seed.py            # 生成种子
    python3 tools/exams/build_seed.py --check    # 校验已有种子文件能解析

设计约定：
- 一个题目一个 dict，字段与 Kotlin `dev.trellis.feature.exams.Exercise` 对齐；
- `id` 全文件唯一（用“学科 slug + 题型 + 序号”，不依赖上游题号——上游题号有重复）；
- 同一学科内题干完全相同的题目去重（保留第一次出现，后面的记为 skipped）；
- 只收“有答案”的题：没有答案的回忆题/考点不硬造答案，宁可少收。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

CACHE_DIR = Path(__file__).resolve().parent / ".cache"
DEFAULT_OUT = (
    Path(__file__).resolve().parent.parent.parent
    / "features"
    / "exams"
    / "src"
    / "jvmMain"
    / "resources"
    / "exam-seed"
    / "exercises.jsonl"
)

# 学科顺序决定种子文件里的分组顺序（也方便人工检查）。
SUBJECT_ORDER = ["体育", "现代邮政英语", "毛概", "数据结构", "数字电路与逻辑设计"]
SUBJECT_SLUG = {
    "体育": "tiyu",
    "现代邮政英语": "english",
    "毛概": "maogai",
    "数据结构": "data-structure",
    "数字电路与逻辑设计": "digital-circuit",
}
KIND_ORDER = [
    "single_choice",
    "multiple_choice",
    "true_false",
    "short_answer",
    "essay",
    "recall_card",
]
KIND_CODE = {
    "single_choice": "mcq",
    "multiple_choice": "mmcq",
    "true_false": "tf",
    "short_answer": "sa",
    "essay": "essay",
    "recall_card": "rc",
}
OBJECTIVE_KINDS = {"single_choice", "multiple_choice", "true_false"}

CJK_RE = re.compile(r"[\u4e00-\u9fff]")
TAG_SPLIT_RE = re.compile(r"[,，、;；/|]+")


class SeedError(RuntimeError):
    """解析/校验失败：信息里必须带来源，方便定位是哪份资料写错了。"""


# ---------------------------------------------------------------------------
# 文本清洗
# ---------------------------------------------------------------------------


def normalize_raw(text: str) -> str:
    """去 BOM/CRLF/NBSP 等上游 Markdown 里常见的“看不见的字符”。"""
    return (
        text.replace("\ufeff", "")
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .replace("\xa0", " ")
        .replace("\u200b", "")
        .replace("\u200c", "")
        .replace("\u200d", "")
        .replace("\u2060", "")
    )


def strip_markdown(text: str) -> str:
    """把一行 Markdown 压成纯文本（保留文字，去掉标记/链接/图片语法）。"""
    t = text
    t = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", t)          # 图片
    t = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", t)      # 链接，保 label
    t = re.sub(r"<(https?://[^>]+)>", r"\1", t)         # 自动链接
    t = t.replace("**", "").replace("__", "").replace("~~", "").replace("`", "")
    t = t.replace("\\'", "'")
    t = re.sub(r"\\([\\`*_{}\[\]()#+\-.!])", r"\1", t)  # 转义字符还原
    t = re.sub(r"[ \t]+", " ", t)
    return t.strip()


def join_prose(parts: list[str]) -> str:
    """把多行正文合成一段：中文之间不留空格，中英之间/英文单词之间保留空格。"""
    cleaned = [strip_markdown(p) for p in parts if p and strip_markdown(p).strip()]
    text = " ".join(cleaned)
    # 中文（含中文标点）之间的软换行不应变空格。
    text = re.sub(r"(?<=[\u4e00-\u9fff\u3000-\u303f])\s+(?=[\u4e00-\u9fff\u3000-\u303f])", "", text)
    text = re.sub(r"\s{2,}", " ", text)
    return text.strip()


def split_tags(raw: str) -> list[str]:
    out: list[str] = []
    for part in TAG_SPLIT_RE.split(raw or ""):
        part = part.strip()
        if part and part not in out:
            out.append(part)
    return out


def year_from_filename(name: str) -> str | None:
    """体育_23-24-1_... / 毛概_24-25-1_... → '2023-2024-1'；否则退回文件名里的 20xx。"""
    m = re.search(r"(\d{2})-(\d{2})-(\d)", name)
    if m:
        first, second, term = m.groups()
        return f"20{first}-20{second}-{term}"
    m = re.search(r"(20\d{2})", name)
    return m.group(1) if m else None


def dedupe_stem(text: str) -> str:
    """题干去重用的归一化：去掉显式空括号、空白、标点后比较。"""
    t = text.replace("（  ）", "").replace("(  )", "")
    t = re.sub(r"[\s\W_]+", "", t, flags=re.UNICODE)
    return t.lower()


# ---------------------------------------------------------------------------
# 选项 / 答案
# ---------------------------------------------------------------------------


@dataclass
class Option:
    label: str
    text: str


def letters_from_answer(answer: str, labels: list[str]) -> list[str] | None:
    """把“C”“AC”“A,C”“（B）”“C. xxx”解析成选项标签列表；解析不出返回 None。"""
    if not answer:
        return None
    t = answer.strip().replace("（", "(").replace("）", ")")
    t = re.sub(r"[\s,，、;；.。]+", "", t)
    m = re.match(r"^\(?([A-H]+)\)?$", t)
    if m:
        letters = list(m.group(1))
        if all(letter in labels for letter in letters):
            return letters
    return None


def labels_match_text(answer: str, options: list[Option]) -> list[str] | None:
    body = strip_markdown(answer).strip()
    if not body:
        return None
    for opt in options:
        if strip_markdown(opt.text) == body:
            return [opt.label]
    # 判断题：答案是“正确/对/√”时映射到含“正确”的选项。
    if body in {"正确", "对", "√", "T", "True", "true"}:
        for opt in options:
            if "正确" in opt.text:
                return [opt.label]
    if body in {"错误", "错", "×", "F", "False", "false"}:
        for opt in options:
            if "错误" in opt.text:
                return [opt.label]
    return None


def resolve_answer(answer: str, options: list[Option]) -> tuple[str, list[str]]:
    """返回 (规范答案文本, 标签列表)。选择题答案统一成标签（如 'C' / 'AC'）。"""
    labels = [opt.label for opt in options]
    letters = letters_from_answer(answer, labels)
    if letters:
        return "".join(letters), letters
    by_text = labels_match_text(answer, options)
    if by_text:
        return "".join(by_text), by_text
    return strip_markdown(answer), []


# ---------------------------------------------------------------------------
# 种子构建器：负责 id、去重、统计、校验
# ---------------------------------------------------------------------------


@dataclass
class SeedBuilder:
    rows: list[dict] = field(default_factory=list)
    ids: set[str] = field(default_factory=set)
    seen_stems: set[tuple[str, str]] = field(default_factory=set)
    counters: Counter = field(default_factory=Counter)
    skipped: Counter = field(default_factory=Counter)
    issues: list[str] = field(default_factory=list)

    def add(
        self,
        *,
        subject: str,
        kind: str,
        stem: str,
        answer: str,
        course: str = "",
        options: list[Option] | None = None,
        explanation: str = "",
        tags: list[str] | None = None,
        source_path: str,
        source_page: int | None = None,
        year: str | None = None,
    ) -> dict | None:
        stem = join_prose([stem]) if "\n" in stem else strip_markdown(stem)
        answer = join_prose([answer]) if "\n" in answer else strip_markdown(answer)
        options = options or []
        tags = tags or []

        if not stem or not answer:
            self.skipped["空题干/空答案"] += 1
            return None
        key = (subject, dedupe_stem(stem))
        if key in self.seen_stems:
            self.skipped["同学科题干重复"] += 1
            return None

        resolved_answer, _letters = resolve_answer(answer, options)
        if kind in OBJECTIVE_KINDS:
            if not options:
                self.skipped["客观题缺选项"] += 1
                return None
            resolved_answer = resolved_answer or answer
        row = {
            "id": "",
            "subject": subject,
            "course": course,
            "kind": kind,
            "stem": stem,
            "options": [{"label": o.label, "text": o.text} for o in options],
            "answer": resolved_answer,
            "explanation": explanation,
            "tags": tags,
            "sourcePath": source_path,
        }
        if source_page is not None:
            row["sourcePage"] = int(source_page)
        if year:
            row["year"] = year

        self.counters[(subject, kind)] += 1
        seq = self.counters[(subject, kind)]
        row["id"] = f"{SUBJECT_SLUG[subject]}-{KIND_CODE[kind]}-{seq:03d}"
        if row["id"] in self.ids:  # 理论上不会，防撞
            raise SeedError(f"id 撞车：{row['id']}")

        errors = validate_row(row)
        if errors:
            raise SeedError(f"{source_path}: 题目不合法（{stem[:30]!r}）：" + "；".join(errors))

        self.ids.add(row["id"])
        self.seen_stems.add(key)
        self.rows.append(row)
        return row

    def summary(self) -> str:
        lines = [f"种子题总数：{len(self.rows)}"]
        per_subject = Counter(row["subject"] for row in self.rows)
        per_kind = Counter(row["kind"] for row in self.rows)
        lines.append("按学科：" + "，".join(f"{s} {per_subject[s]}" for s in SUBJECT_ORDER if per_subject[s]))
        lines.append("按题型：" + "，".join(f"{k} {per_kind[k]}" for k in KIND_ORDER if per_kind[k]))
        if self.skipped:
            lines.append("跳过：" + "，".join(f"{k} {v}" for k, v in self.skipped.items()))
        if self.issues:
            lines.append("问题：\n  - " + "\n  - ".join(self.issues))
        return "\n".join(lines)


# ---------------------------------------------------------------------------
# 校验：与 Kotlin ExerciseValidator 保持同一套规则（Python 侧先兜住解析 bug）
# ---------------------------------------------------------------------------


def validate_row(row: dict) -> list[str]:
    errors: list[str] = []
    kind = row["kind"]
    for key in ("id", "subject", "kind", "stem", "sourcePath"):
        if not str(row.get(key, "")).strip():
            errors.append(f"{key} 不能为空")
    if not row.get("answer", "").strip():
        errors.append("answer 不能为空")
    options = row.get("options") or []
    labels = [opt["label"] for opt in options]

    if kind in ("single_choice", "multiple_choice"):
        if len(options) < 2:
            errors.append("选择题至少要有 2 个选项")
        if len(set(labels)) != len(labels):
            errors.append("选项标签不能重复")
        for opt in options:
            if not opt["label"].strip() or not opt["text"].strip():
                errors.append("选项标签和正文都不能为空")
        answer = row["answer"].strip()
        if not answer:
            errors.append("选择题答案不能为空")
        elif not re.fullmatch(r"[A-H]+", answer):
            errors.append(f"选择题答案应是选项标签（如 C / AC），实际是 {answer!r}")
        else:
            unknown = [ch for ch in answer if ch not in labels]
            if unknown:
                errors.append(f"答案标签 {''.join(unknown)!r} 不在选项 {labels} 里")
            if kind == "single_choice" and len(answer) != 1:
                errors.append("单选题答案只能有一个标签")
    elif kind == "true_false":
        if options and len(options) != 2:
            errors.append("判断题选项为空或正好 2 个")
        if options:
            if row["answer"] not in labels:
                errors.append("判断题答案应是选项标签（A/B）")
        else:
            errors.append("判断题至少要有 A/B 两个选项")
    elif kind in ("short_answer", "essay", "recall_card"):
        if kind == "recall_card" and options:
            errors.append("知识点卡片不应该有选项")
    else:
        errors.append(f"未知题型 {kind!r}")
    return errors


# ---------------------------------------------------------------------------
# 解析器 1：体育教师版题库（203 题：111 单选 + 92 判断）
# ---------------------------------------------------------------------------

TIYU_QUESTION_RE = re.compile(r"^(\d+)\s*[.、]\s*(.*)$")
TIYU_OPTION_RE = re.compile(r"^([A-H])\s*[.、]\s*(.*)$")
TIYU_ANSWER_RE = re.compile(r"^答案\s*[：:]\s*(.*)$")


def parse_tiyu(text: str, source_path: str, year: str | None = None) -> list[dict]:
    lines = normalize_raw(text).split("\n")
    # 只看 `## 选择题` 之后的内容（文件头有 frontmatter / 说明）。
    start = 0
    for i, line in enumerate(lines):
        if re.match(r"^##\s*选择题", line.strip()):
            start = i + 1
            break
    blocks: list[dict] = []
    cur: dict | None = None
    for raw in lines[start:]:
        s = raw.strip()
        if not s:
            continue
        m = TIYU_QUESTION_RE.match(s)
        if m:
            if cur is not None:
                blocks.append(cur)
            cur = {"num": int(m.group(1)), "stem": [m.group(2)], "options": [], "answer": ""}
            continue
        if cur is None:
            continue
        mo = TIYU_OPTION_RE.match(s)
        if mo:
            cur["options"].append(Option(mo.group(1), mo.group(2).strip()))
            continue
        ma = TIYU_ANSWER_RE.match(s)
        if ma:
            cur["answer"] = re.sub(r"^答案\s*[：:]\s*", "", ma.group(1).strip())
            continue
        # 题干折行（少见）：只有还没出现选项/答案时才算题干。
        if not cur["options"] and not cur["answer"]:
            cur["stem"].append(s)
    if cur is not None:
        blocks.append(cur)

    builder = SeedBuilder()
    for block in blocks:
        stem = join_prose(block["stem"])
        answer = strip_markdown(block["answer"])
        options = block["options"]
        labels = [o.label for o in options]
        texts = [strip_markdown(o.text) for o in options]
        if len(options) == 2 and any("正确" in t for t in texts) and any("错误" in t for t in texts):
            kind = "true_false"
        elif len(options) >= 2:
            letters = letters_from_answer(answer, labels)
            kind = "multiple_choice" if letters and len(letters) > 1 else "single_choice"
        else:
            builder.skipped["体育：不是选择题"] += 1
            continue
        tags = ["体育理论", "教师版题库", "判断题" if kind == "true_false" else "选择题"]
        builder.add(
            subject="体育",
            course="体育理论考试",
            kind=kind,
            stem=stem,
            options=options,
            answer=answer,
            explanation="题目与答案来自 NJUPT Free-Exams 的体育理论考试教师版题库。",
            tags=tags,
            source_path=source_path,
            year=year,
        )
    return builder.rows


# ---------------------------------------------------------------------------
# 解析器 2：现代邮政英语 MOOC（234 题：40 选择 + 194 填空/术语/翻译）
# ---------------------------------------------------------------------------

ENGLISH_QUESTION_RE = re.compile(r"^(\d+)、\s*(.*)$")
ENGLISH_OPTION_RE = re.compile(r"^\s+([A-D])、\s*(.*)$")
ENGLISH_ANSWER_RE = re.compile(r"^\s*参考答案[：:]\s*(.*)$")
ENGLISH_SECTION_RE = re.compile(r"^\*\*(.+?)\*\*$")
ENGLISH_RULE_RE = re.compile(r"^-{3,}$")
ENGLISH_UNIT_RE = re.compile(r"^Unit\s+(\d+)", re.IGNORECASE)


def parse_english(text: str, source_path: str, year: str | None = None) -> list[dict]:
    lines = normalize_raw(text).split("\n")
    blocks: list[dict] = []
    cur: dict | None = None
    section = ""
    unit = ""

    def flush() -> None:
        nonlocal cur
        if cur is not None:
            blocks.append(cur)
            cur = None

    for raw in lines:
        stripped = raw.strip()
        if not stripped:
            continue
        hm = ENGLISH_SECTION_RE.match(stripped)
        if hm:
            # `**Test 1**` / `**主观题**` 这类是章节标题；英文正文加粗很少见。
            title = hm.group(1).strip()
            if re.search(r"(Test|Assignment|Sheet|Exam|主观题|Unit)", title, re.IGNORECASE):
                flush()
                section = title
                continue
        um = ENGLISH_UNIT_RE.match(stripped.replace("**", ""))
        if um and len(stripped) < 40:
            flush()
            unit = stripped.replace("**", "").strip()
            continue
        if ENGLISH_RULE_RE.match(stripped):
            flush()
            continue
        qm = ENGLISH_QUESTION_RE.match(stripped)
        if qm:
            flush()
            # 上游是 Markdown 有序列表：`11、1. WANTED: ...`，11 是列表序号、1 才是题号。
            inner = re.sub(r"^\d{1,2}\s*[.、]\s+", "", qm.group(2)).strip()
            cur = {
                "section": section,
                "unit": unit,
                "stem": [inner],
                "options": [],
                "answer_lines": [],
                "mode": "stem",
            }
            continue
        if cur is None:
            continue
        om = ENGLISH_OPTION_RE.match(raw)
        if om and cur["mode"] in ("stem", "options"):
            cur["mode"] = "options"
            cur["options"].append(Option(om.group(1), om.group(2).strip()))
            continue
        am = ENGLISH_ANSWER_RE.match(raw)
        if am:
            cur["mode"] = "answer"
            cur["answer_lines"] = [am.group(1).strip()]
            continue
        if cur["mode"] == "answer":
            cur["answer_lines"].append(stripped)
        elif cur["mode"] == "stem":
            cur["stem"].append(stripped)
        # mode == options 时出现的非选项行：多数是选项换行，拼回最后一个选项。
        elif cur["options"]:
            last = cur["options"][-1]
            if last.text:
                cur["options"][-1] = Option(last.label, last.text + " " + stripped)
    flush()

    builder = SeedBuilder()
    for block in blocks:
        # 填空题的横线是一长串下划线，join_prose→strip_markdown 会把成对的 "__" 当强调删掉；
        # 先换成全角下划线，保留“这里是空”的视觉提示。
        stem = join_prose(
            [
                re.sub(r"_{2,}", lambda m: "＿" * min(len(m.group(0)), 12), part)
                for part in block["stem"]
            ]
        )
        options = block["options"]
        raw_answer = " ".join(block["answer_lines"])
        answer = strip_markdown(raw_answer)
        # 上游部分答案自带列表序号（“参考答案：2. We are…”），入库时去掉。
        answer = re.sub(r"^\d{1,2}\s*[.、)]\s+", "", answer).strip()
        if not answer:
            builder.skipped["英语：缺参考答案"] += 1
            continue
        # 上游用 `##%_YZPRLFH_%##` 分隔多个可接受答案；种子只留第一个。
        if "##%_YZPRLFH_%##" in answer:
            parts = [p.strip() for p in answer.split("##%_YZPRLFH_%##") if p.strip()]
            answer = " / ".join(parts)
        tags = ["现代邮政英语", "MOOC"]
        for tag in split_tags(block["section"]):
            if tag and tag not in tags:
                tags.append(tag)
        if block["unit"]:
            tags.append(block["unit"])
        if options:
            letters = letters_from_answer(answer, [o.label for o in options])
            if letters:
                kind = "multiple_choice" if len(letters) > 1 else "single_choice"
            else:
                # 标成选择题但答案不是标签：当填空题收（答案原文仍可用）。
                kind = "short_answer"
        else:
            no_fixed = "不设固定答案" in answer or "无固定答案" in answer
            essay_hint = any(k in stem for k in ("创新思维", "论述", "说明你")) or any(
                k in block["section"] for k in ("主观题", "Exam")
            )
            kind = "essay" if (no_fixed or essay_hint) else "short_answer"
        builder.add(
            subject="现代邮政英语",
            course="现代邮政英语（MOOC）",
            kind=kind,
            stem=stem,
            options=options if kind in OBJECTIVE_KINDS else [],
            answer=answer if kind in OBJECTIVE_KINDS else answer,
            explanation="题目与参考答案来自 NJUPT Free-Exams 的《现代邮政英语》MOOC 题库。",
            tags=tags,
            source_path=source_path,
            year=year,
        )
    return builder.rows


# ---------------------------------------------------------------------------
# 解析器 3：毛概复习（17 名词解释 + 主观题）
# ---------------------------------------------------------------------------

MAOGAI_NOUN_RE = re.compile(r"^\d+\.\s*\*\*(.+?)\*\*[：:]\s*(.*)$")
MAOGAI_ITEM_RE = re.compile(r"^(\d+)\.\s*(.+)$")
MAOGAI_PAGE_REF_RE = re.compile(r"^[（(]\s*P[P\d\s\-–—、,，.]*[）)]$")
MAOGAI_HEADING_RE = re.compile(r"^##\s*(.+)$")


def parse_maogai(text: str, source_path: str, year: str | None = None) -> list[dict]:
    lines = normalize_raw(text).split("\n")
    section = ""
    nouns: list[dict] = []
    subjects: list[dict] = []
    cur_noun: dict | None = None
    cur_subject: dict | None = None

    for raw in lines:
        s = raw.strip()
        hm = MAOGAI_HEADING_RE.match(s)
        if hm:
            section = hm.group(1).strip()
            cur_noun = None
            cur_subject = None
            continue
        if not s:
            continue
        if section == "名词解释":
            m = MAOGAI_NOUN_RE.match(s)
            if m:
                cur_subject = None
                cur_noun = {"term": m.group(1).strip(), "def_lines": [m.group(2)]}
                nouns.append(cur_noun)
                continue
            if cur_noun is not None:
                cur_noun["def_lines"].append(s)
        elif section == "主观题":
            m = MAOGAI_ITEM_RE.match(s)
            if m:
                cur_noun = None
                cur_subject = {"num": int(m.group(1)), "stem": m.group(2).strip(), "lines": []}
                subjects.append(cur_subject)
                continue
            if cur_subject is not None:
                cur_subject["lines"].append(s)

    builder = SeedBuilder()
    for noun in nouns:
        term = strip_markdown(noun["term"])
        definition = join_prose(noun["def_lines"])
        if not term or not definition:
            builder.skipped["毛概：名词解释缺内容"] += 1
            continue
        builder.add(
            subject="毛概",
            course="毛泽东思想和中国特色社会主义理论体系概论",
            kind="recall_card",
            stem=f"名词解释：{term}",
            answer=definition,
            explanation="",
            tags=["名词解释"],
            source_path=source_path,
            year=year,
        )
    for item in subjects:
        stem = strip_markdown(item["stem"])
        body_lines: list[str] = []
        page_ref = ""
        for line in item["lines"]:
            stripped = re.sub(r"^[-*]\s*", "", line.strip())
            # 页码引用可能独占一行，也可能写在答案第一行开头：`（P10 - P12）在马克思…`
            ref = re.match(r"^[（(]\s*(P[P\d\s\-–—、,，.]+)[）)]\s*", stripped)
            if ref:
                page_ref = page_ref or strip_markdown(f"（{ref.group(1).strip()}）")
                stripped = stripped[ref.end() :]
                if not stripped:
                    continue
            if MAOGAI_PAGE_REF_RE.match(stripped):
                page_ref = page_ref or strip_markdown(stripped)
                continue
            body_lines.append(stripped)
        answer = join_prose(body_lines)
        if not answer:
            builder.skipped["毛概：主观题无答案"] += 1
            continue
        kind = "essay" if any(k in stem for k in ("论述", "材料", "结合", "如何理解")) else "short_answer"
        builder.add(
            subject="毛概",
            course="毛泽东思想和中国特色社会主义理论体系概论",
            kind=kind,
            stem=stem,
            answer=answer,
            explanation=(f"原文引用页码：{page_ref}" if page_ref else ""),
            tags=["主观题"],
            source_path=source_path,
            year=year,
        )
    return builder.rows


# ---------------------------------------------------------------------------
# 解析器 4：数据结构复习笔记（问句标题 + 术语定义 → RecallCard）
# ---------------------------------------------------------------------------

STRUCTURE_HEADING_RE = re.compile(r"^(#{1,4})\s+(.+?)\s*$")
STRUCTURE_DEF_BULLET_RE = re.compile(r"^[-*]\s*([^：:]{2,30})[：:]\s*(.+)$")
QUESTION_WORDS = ("什么", "为什么", "如何", "怎样", "哪些", "？", "?")
FENCE_RE = re.compile(r"^\s*(```|~~~)")
# “注意/说明/示例”这类 bullet 不是知识点定义，“$/_/{”说明是公式或 Markdown 残留，都不收卡片。
STRUCTURE_META_TERMS = {"注意", "说明", "提示", "注", "例如", "示例", "例子", "结论", "补充", "要点"}


def parse_structure_notes(text: str, source_path: str, note_title: str, year: str | None = None) -> list[dict]:
    lines = normalize_raw(text).split("\n")
    builder = SeedBuilder()
    # 1) 问句标题 + 紧随段落 → RecallCard。
    heading: str | None = None
    body: list[str] = []
    in_fence = False

    def flush_heading() -> None:
        nonlocal heading, body
        if heading and body:
            answer = join_prose(body)
            if len(answer) >= 12:
                builder.add(
                    subject="数据结构",
                    course="数据结构（混合式）",
                    kind="recall_card",
                    stem=heading,
                    answer=answer,
                    explanation="",
                    tags=["复习笔记", note_title],
                    source_path=source_path,
                    year=year,
                )
        heading = None
        body = []

    for raw in lines:
        s = raw.strip()
        if FENCE_RE.match(raw):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        hm = STRUCTURE_HEADING_RE.match(s)
        if hm:
            flush_heading()
            title = strip_markdown(hm.group(2))
            if any(word in title for word in QUESTION_WORDS) and len(title) <= 60:
                heading = title
            continue
        if heading is not None:
            if not s or s.startswith("- ") or s.startswith("* ") or re.match(r"^\d+[.、]", s):
                body.append(re.sub(r"^[-*]\s*", "", s) if s else "")
            else:
                body.append(s)

    flush_heading()

    # 2) `术语：定义` bullet → RecallCard（上限控制，避免复习笔记把题库撑成几百条）。
    in_fence = False
    for raw in lines:
        s = raw.strip()
        if FENCE_RE.match(raw):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        m = STRUCTURE_DEF_BULLET_RE.match(s)
        if not m:
            continue
        term = strip_markdown(m.group(1)).strip()
        definition = join_prose([m.group(2)])
        if len(term) < 2 or len(definition) < 4 or len(term) > 20:
            continue
        if term in STRUCTURE_META_TERMS:
            continue
        # “最大堆：...最大...”这种笔记里没写完、只有占位符的定义不要。
        if "..." in definition or "…" in definition:
            continue
        # Markdown 残留 / 公式残片 / 代码变量（`boolean sptSet[v]`）都不适合当卡片正面。
        if any(ch in term for ch in ("$", "_", "\\", "{", "}", "[", "]")):
            continue
        # 纯公式定义（如“$h\\leq…$”）在 UI 里只会显示原始 LaTeX，不收。
        if definition.lstrip().startswith("$"):
            continue
        if term.startswith(("http", "https")) or "：" in term:
            continue
        builder.add(
            subject="数据结构",
            course="数据结构（混合式）",
            kind="recall_card",
            stem=f"「{term}」是什么？",
            answer=definition,
            explanation="",
            tags=["复习笔记", "术语", note_title],
            source_path=source_path,
            year=year,
        )
    return builder.rows


# ---------------------------------------------------------------------------
# 解析器 5：数电判分标准 PDF 文本层（19 道带红色答案的单选题）
# ---------------------------------------------------------------------------

DC_QUESTION_RE = re.compile(r"^\s*(\d{1,2})\s*[.、]\s*(.*)$")
DC_ANSWER_RUN_RE = re.compile(r"(?<!\w)([A-D])(?:\s+\1\b)+")
DC_OPTION_SPLIT_RE = re.compile(r"(?:^|\s)([A-D])\s*[、.]\s*")
# PDF 文本层把红色加粗的字重复了三遍，标题会变成“一 一 一、 、 、填空 填空填空…”，
# 所以章节判定要容忍重复字符，不能只写 ^一、。
DC_SECTION_START_RE = re.compile(r"^\s*一(?:\s*一)*\s*[、.，,]")
DC_SECTION_END_RE = re.compile(r"^\s*二(?:\s*二)*\s*[、.，,]")
# 页脚/页眉、以及红色答案重复渲染留下的一行单字母，都是噪声。
DC_FOOTER_RE = re.compile(r"试卷答案|第\s*\d+\s*页\s*共\s*\d+\s*页")
DC_STRAY_LETTER_RE = re.compile(r"^[A-D]$")
# 公式被文本层压平后会出现“= )B”这种残句，这种题收进来也没法做。
DC_GARBLED_STEM_RE = re.compile(r"[=+⊕−-]\s*\)|\(\s*[=+⊕−-]")


def _dc_options_from_text(text: str) -> list[Option]:
    """从“A、n B、2n C、n D、2n”里拆选项；允许选项正文被换行拆开。"""
    text = re.sub(r"\s{2,}", " ", text)
    matches = list(DC_OPTION_SPLIT_RE.finditer(text))
    if not matches:
        return []
    options: list[Option] = []
    for i, m in enumerate(matches):
        start = m.end()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        body = text[start:end].strip()
        if body:
            options.append(Option(m.group(1), body))
    return options


def parse_digital_circuit(pdf_rows: list[dict], source_path: str, year: str | None = None) -> list[dict]:
    builder = SeedBuilder()
    # 只处理“一、填空和选择题”这一节；题目行跨页时把页拼起来。
    section_lines: list[tuple[int, str]] = []
    in_section = False
    done = False
    for row in pdf_rows:
        page = int(row.get("page", 0))
        for raw in normalize_raw(row.get("text", "")).split("\n"):
            s = raw.strip()
            if not s:
                continue
            if not in_section:
                if DC_SECTION_START_RE.match(s) or "填空和选择" in s:
                    in_section = True
                continue
            if DC_SECTION_END_RE.match(s):
                done = True
                break
            if DC_FOOTER_RE.search(s) or DC_STRAY_LETTER_RE.match(s):
                continue
            section_lines.append((page, s))
        if done:
            break

    # 按题号切块。
    blocks: list[dict] = []
    cur: dict | None = None
    for page, s in section_lines:
        m = DC_QUESTION_RE.match(s)
        # “2 D、2n”这种续行不算新题：题号后必须紧跟题干文字，且不能以选项标签开头。
        if m and (cur is None or not re.match(r"^[A-D]\s*[、.]", m.group(2))):
            if cur is not None:
                blocks.append(cur)
            cur = {"num": int(m.group(1)), "page": page, "lines": [m.group(2)]}
            continue
        if cur is not None:
            cur["lines"].append(s)
    if cur is not None:
        blocks.append(cur)

    for block in blocks:
        text = " ".join(block["lines"])
        answer_match = DC_ANSWER_RUN_RE.search(text)
        answer = ""
        if answer_match:
            answer = answer_match.group(1).upper()
            # 把答案标记换成空括号，题干读起来像正常填空。
            text = DC_ANSWER_RUN_RE.sub("（  ）", text, count=1)
        # 找选项起点：题干与 A、B、C、D 一般挤在同一个块里。
        first_opt = DC_OPTION_SPLIT_RE.search(text)
        if not first_opt:
            builder.skipped["数电：无法拆选项"] += 1
            continue
        stem = strip_markdown(text[: first_opt.start()])
        options = _dc_options_from_text(text[first_opt.start() :])
        labels = [o.label for o in options]
        if not answer or len(options) < 4 or sorted(labels) != ["A", "B", "C", "D"]:
            builder.skipped["数电：题干/选项不完整或公式乱码"] += 1
            continue
        if DC_GARBLED_STEM_RE.search(stem) or len(stem) < 8:
            builder.skipped["数电：题干公式被压平"] += 1
            continue
        explanation = (
            "题干与选项从 PDF 文本层直接抽取（判分标准页，数学公式可能被压平）；"
            "答案字母来自原文红色标注。"
        )
        builder.add(
            subject="数字电路与逻辑设计",
            course="数字电路与逻辑设计A",
            kind="single_choice",
            stem=stem,
            options=options,
            answer=answer,
            explanation=explanation,
            tags=["判分标准", "选择题"],
            source_path=source_path,
            source_page=block["page"],
            year=year,
        )
    return builder.rows


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------


def read_jsonl(path: Path) -> list[dict]:
    rows: list[dict] = []
    with path.open("r", encoding="utf-8") as fh:
        for line_no, line in enumerate(fh, 1):
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError as exc:
                raise SeedError(f"{path}:{line_no} 不是合法 JSON：{exc}") from exc
    return rows


def build_seed(cache: Path = CACHE_DIR) -> tuple[list[dict], str]:
    reserved = cache / "reserved"
    pdf_text = cache / "pdf-text"
    if not reserved.is_dir():
        raise SeedError(f"缺少 {reserved}，先跑：python3 tools/exams/fetch.py reserved")

    def reserved_md(prefix: str) -> Path:
        hits = sorted(p for p in reserved.glob(prefix + "*.md"))
        if not hits:
            raise SeedError(f"{reserved} 里找不到 {prefix}*.md（先跑 fetch.py reserved）")
        return hits[0]

    builder = SeedBuilder()
    collected: list[list[dict]] = []

    def collect(name: str, fn) -> None:
        try:
            part = fn()
        except SeedError as exc:
            builder.issues.append(f"{name}: {exc}")
            return
        collected.append(part)

    # 1) 体育
    pe_path = reserved_md("体育_")
    collect("体育", lambda: parse_tiyu(pe_path.read_text("utf-8"), pe_path.name, year_from_filename(pe_path.name)))

    # 2) 现代邮政英语
    english_path = reserved_md("现代邮政英语")
    collect(
        "现代邮政英语",
        lambda: parse_english(english_path.read_text("utf-8"), english_path.name, year_from_filename(english_path.name)),
    )

    # 3) 毛概
    maogai_path = reserved_md("毛概_")
    collect(
        "毛概",
        lambda: parse_maogai(maogai_path.read_text("utf-8"), maogai_path.name, year_from_filename(maogai_path.name)),
    )

    # 4) 数据结构三份复习笔记
    structure_files = sorted(reserved.glob("数据结构（混合式）_*_复习笔记.md"))
    if not structure_files:
        builder.issues.append("数据结构：找不到 数据结构（混合式）_*_复习笔记.md")
    for path in structure_files:
        note_title = path.stem.split("_")[-2] if "_" in path.stem else path.stem
        collect(
            f"数据结构/{note_title}",
            lambda p=path, t=note_title: parse_structure_notes(
                p.read_text("utf-8"), p.name, t, year_from_filename(p.name)
            ),
        )

    # 5) 数电判分标准 PDF
    dc_files = sorted(pdf_text.glob("数字电路与逻辑设计A__*.jsonl")) if pdf_text.is_dir() else []
    if dc_files:
        dc_file = dc_files[0]
        pdf_rows = read_jsonl(dc_file)
        source_path = next((r.get("sourcePath", "") for r in pdf_rows if r.get("sourcePath")), "")
        collect(
            "数字电路与逻辑设计",
            lambda: parse_digital_circuit(pdf_rows, source_path or dc_file.name, year="2016-2017-2"),
        )
    else:
        builder.issues.append(
            f"数字电路与逻辑设计：{pdf_text} 里没有数电判分标准 jsonl（先跑 fetch.py papers --count 8 --download 与 extract_pdfs.py）"
        )

    # 汇总 + 重新编号：各解析器各自从 001 起编号，这里按学科/题型全局重排，保证全文件唯一。
    for part in collected:
        builder.rows.extend(part)
    ordered = sorted(
        builder.rows,
        key=lambda r: (
            SUBJECT_ORDER.index(r["subject"]) if r["subject"] in SUBJECT_ORDER else len(SUBJECT_ORDER),
            KIND_ORDER.index(r["kind"]) if r["kind"] in KIND_ORDER else len(KIND_ORDER),
            r["sourcePath"],
            r.get("sourcePage") or 0,
        ),
    )
    global_counters: Counter = Counter()
    global_ids: set[str] = set()
    for row in ordered:
        key = (row["subject"], row["kind"])
        global_counters[key] += 1
        row["id"] = f"{SUBJECT_SLUG[row['subject']]}-{KIND_CODE[row['kind']]}-{global_counters[key]:03d}"
        if row["id"] in global_ids:
            raise SeedError(f"跨来源 id 撞车：{row['id']}")
        global_ids.add(row["id"])
    return ordered, builder.summary()


def write_jsonl(rows: list[dict], out: Path) -> int:
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8", newline="\n") as fh:
        for row in rows:
            fh.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")
    return out.stat().st_size


def check_seed(path: Path) -> list[dict]:
    rows = read_jsonl(path)
    seen_ids: set[str] = set()
    errors: list[str] = []
    for row in rows:
        if row.get("id") in seen_ids:
            errors.append(f"重复 id：{row.get('id')}")
        seen_ids.add(row.get("id"))
        errors.extend(f"{row.get('id')}: {msg}" for msg in validate_row(row))
    print(f"校验 {path}：{len(rows)} 题，{len(errors)} 个问题")
    for err in errors:
        print("  ✗", err)
    if errors:
        raise SeedError("种子文件校验失败")
    return rows


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="把上游 Free-Exams 资料整理成 exercises.jsonl 种子")
    parser.add_argument("--cache", type=Path, default=CACHE_DIR, help="缓存目录（默认 tools/exams/.cache）")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT, help="输出 JSONL 路径")
    parser.add_argument("--check", action="store_true", help="只校验 --out 指向的已有种子文件，不重新生成")
    args = parser.parse_args(argv)

    if args.check:
        check_seed(args.out)
        return 0

    rows, summary = build_seed(args.cache)
    if not rows:
        raise SeedError("一条题都没解析出来，检查 .cache 是否完整（fetch.py reserved / extract_pdfs.py）")
    size = write_jsonl(rows, args.out)
    print(summary)
    print(f"已写入 {args.out}（{len(rows)} 题，{size / 1024:.1f} KB）")
    check_seed(args.out)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except SeedError as exc:
        print(f"错误：{exc}", file=sys.stderr)
        sys.exit(1)
