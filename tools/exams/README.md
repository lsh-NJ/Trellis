# tools/exams — 题库抓取 / 解析管线

把 [NJUPTFreeExams/NJUPT-General-Free-Exams](https://github.com/NJUPTFreeExams/NJUPT-General-Free-Exams)
里的**考试回忆 / 题库 / 试卷**整理成 Trellis 的种子题库：

```
features/exams/src/jvmMain/resources/exam-seed/exercises.jsonl   ← 唯一提交的产物（<2MB）
```

上游是一个约 **2.6GB、566 份 PDF** 的公开仓库，**不要整克隆**。管线只按需下载两类数据源：

| 数据源 | 内容 | 处理方式 |
|---|---|---|
| `src/content/docs/reserved/*.md` | 考试回忆、教师版题库、复习要点（52 个文件） | 按行/标题解析成题（`build_seed.py`） |
| `public/<课程>/…/试卷*.pdf` | 少量期末试卷（默认 8 份，有体积上限） | PDFKit 抽文本；扫描件渲染成 PNG 用 macOS Vision OCR（`pdf_extract.swift`） |

下载缓存与 OCR 中间产物全部落在 `tools/exams/.cache/`（已 gitignore，可随时删掉重跑）。

## 一键流程

```bash
# 0) 环境：python3 / swift（macOS 自带）/ 可选 GITHUB_TOKEN（提升 API 配额）
python3 tools/exams/fetch.py tree                    # 列树 → .cache/tree.json
python3 tools/exams/fetch.py reserved                # 拉 reserved/*.md → .cache/reserved/
python3 tools/exams/fetch.py papers --count 8 --download
                                                     # 选试卷 PDF → .cache/pdfs/ + papers.json
python3 tools/exams/extract_pdfs.py --max-pages 20 --only-papers
                                                     # PDF → .cache/pdf-text/*.jsonl（逐页文本/OCR）
python3 tools/exams/build_seed.py                    # 生成 exercises.jsonl
python3 tools/exams/build_seed.py --check            # 只校验现有种子能不能解析
```

`fetch.py all` 等价于前三步。`--count` 越大下载越多；默认只挑文件名含「试卷/期末/真题/判分」且体积较小的 PDF。

## 各脚本职责

- **`fetch.py`** — GitHub API + raw 下载：列树、挑文件、下载到 `.cache/`。无 token 限流 60 次/小时（本管线列树只用 1 次）；限流时设 `GITHUB_TOKEN`。
- **`build_seed.py`** — 把 `.cache/reserved/*.md` 与 `.cache/pdf-text/*.jsonl` 解析成 `Exercise` JSON，逐条校验、去重（同学科同题干只留首条）、只收**有答案**的题。
- **`extract_pdfs.py`** — 批量把 `.cache/pdfs/` 交给 `pdf_extract.swift`，逐页输出 JSONL。
- **`pdf_extract.swift`** — 单份 PDF：先读内嵌文本层；去空白后字符数过少视为扫描件 → 用 PDFKit 渲染页面 PNG，再走 `VNRecognizeTextRequest`（`zh-Hans` + `en-US`）。OCR 页 PNG 写 `.cache/media/`，不提交。

## 种子现状（2026-11-07）

- 505 条 / 5 个学科：体育理论 203（111 单选 + 92 判断）、现代邮政英语 MOOC 234（选择 + 填空/术语/翻译，带参考答案）、毛概 41、数字电路与逻辑设计 14、数据结构 13（知识点卡 + 简答）。
- 题型分布：单选 165 · 判断 92 · 简答 205 · 论述 13 · 知识点卡 30。
- ID 规则：`<学科 slug>-<题型>-<序号>`，不依赖上游题号（上游有重复题号）；同文件内唯一、跨文件合并时保留首条。

## 加新学科

1. 在 `build_seed.py` 的解析表里加一个 parser（参照 `parse_pe_reserved`）：输入 md 正文，输出 `Exercise` dict 列表；
2. 跑 `build_seed.py` 重新生成种子；`--check` 应无坏行；
3. `./gradlew :features:exams:jvmTest` 通过（`ExamSeedTest` 会检查零坏行、学科 ≥5、客观题选项与答案自洽）。

## 已知问题 / 边界

- **OCR 分栏串行**：中文试卷常分两栏，Vision 的阅读顺序按 boundingBox 排序仍可能把左右栏混在一起（已在 `pdf_extract.swift` 里按上→下、左→右重排，但不保证）。影响是题干/选项可能错位，因此 PDF 来源的题只在**文本层带答案**时收（如数电判分标准），扫描件 OCR 题先不进种子。
- **图片题**：上游 jpg/png（约 77MB）目前不抓；需要时用 Vision OCR 后人工校对，成本高，暂不做。
- **只收有答案的题**：回忆版没有答案的考点不硬造答案（宁可少收），这类内容后续考虑进知识点卡（RecallCard）人工补全。
- **Linux/Windows 无法复现 OCR 管线**：`pdf_extract.swift` 依赖 macOS PDFKit/Vision；其它平台只能消费已提交的 `exercises.jsonl`。
- **版权**：种子只收「考试回忆 / 复习要点 / 判分标准」这类学生整理内容，源文件路径记在每题 `sourcePath`；上游仓库许可未单独声明，若要商用请先确认授权。

## 运行时如何用

- 应用内「练习」页（`app/ui/.../ExamsScreen.kt`）启动时 `ExamSeed.load()` 加载种子；`<TRELLIS_HOME>/exams/*.jsonl` 里用户自放的题库会合并进来（同 id 保留种子里的第一条）。
- 查询层在 `:features:exams` 的 `ExerciseBank`（按学科/题型/标签/年份过滤、随机抽题、搜索）；数据结构与交互见 `app/ui/.../ExamsScreen.kt` 顶部注释。
