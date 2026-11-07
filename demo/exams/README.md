# 演示题库（练习提议用）

`ai-rag-study.jsonl` 是给演示用的 12 道 AI/RAG 练习题（8 单选、2 判断、2 简答），
`subject` 统一为「AI 应用工程」，`course` 为「RAG 与 LLM 应用」/「LLM 应用开发」。

## 为什么需要它

内置种子题库（`features/exams/.../exam-seed/exercises.jsonl`）是 505 道本校通识课真题，
覆盖现代邮政英语/体育/毛概/数字电路/数据结构，**没有 AI 学科**——
而演示主线是「AI 应用工程师」，所以不做这一步，学完节点不会弹出「练习建议」。

## 怎么用

```bash
mkdir -p "$TRELLIS_HOME/exams"
cp demo/exams/*.jsonl "$TRELLIS_HOME/exams/"
```

启动时 `ExerciseFileStore.loadDirectory` 会把 `<TRELLIS_HOME>/exams/*.jsonl` 与内置种子合并
（同 id 只留第一条）。匹配靠 `ExerciseBank.recommendFor`：计划名/单元标题/章节分词后
与题目的 subject/course/tags 做包含匹配，所以 tags 里放了 `api`/`大模型`/`rag`/`提示词`/`json` 等短词。

## 换一套演示题

照 `features/exams/.../Exercise.kt` 的字段写每行一个 JSON 即可；字段：`id`、`subject`、`course`、
`kind`（`single_choice`/`true_false`/`short_answer`/`recall_card`）、`stem`、`options`、`answer`、
`explanation`、`tags`、`sourcePath`。
