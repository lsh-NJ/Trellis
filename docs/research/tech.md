# Trellis 技术选型证据简报（记忆·隐私·追踪·视觉·LLM 工程）

查询日期 2026-10-26。✅=已抓原文核实；⚠=官方原文抓取失败（403/超时），列为待验证假设。URL 均为本次实际访问页面。

## 1 记忆与检索

**1.1 记忆框架** 结论：不引 Mem0/Letta/Zep 全家桶，取其设计自建。✅Mem0(arxiv 2504.19413) LOCOMO 上 LLM-judge 相对全上下文 +26%、p95 -91%、token -90%，但图谱记忆仅再 +2%；✅Zep(arxiv 2501.13956) LongMemEval 准确率 +18.5%、延迟 -90%；✅Graphiti 需 Neo4j 5.26/FalkorDB 1.1.2，文档自认小模型做结构化抽取不可靠；✅Cognee v1.6.1(2026-09-24) 可无 LLM 运行，BEAM 0.79@100K；Letta 用 MemFS+后台"做梦"整合；LangMem 分热路径工具与后台管理器。来源 arxiv.org/abs/2504.19413、arxiv.org/abs/2501.13956、github.com/getzep/graphiti、github.com/topoteretes/cognee。建议：借 LangMem 抽象、存储自建；图谱 P2（+2% 不值）。

**1.2 本地向量** 结论：sqlite-vec 起步。✅sqlite-vec 仍是 pre-v1（API 未稳定），纯 C 零依赖，vec0 表支持 float/int8/binary 向量与分区键，配 sqlite-lembed 本地 embedding；✅DuckDB VSS 为实验扩展，向量须定长 ARRAY，HNSW 靠 CREATE INDEX；Qdrant 需常驻服务；LanceDB 依赖 Rust native 库。来源 github.com/asg017/sqlite-vec、duckdb.org/docs/stable/core_extensions/vss。建议：KMP 两端统一 sqlite-vec（JNI）；>50 万块或需多租户过滤再评 LanceDB；Qdrant 不选（违背本地优先）。

**1.3 混合检索与中文 embedding** 结论：FTS5 BM25+向量 RRF 融合，rerank 端侧可选。✅Qwen3-Embedding(arxiv 2506.05176) 含 0.6B/4B/8B 及同规格 reranker，Apache 2.0、MRL 可截维、100+ 语言、MTEB SOTA；✅bge-m3(arxiv 2402.03216) 单模型输出 dense+sparse+多向量，8192 token。来源 arxiv.org/abs/2506.05176、arxiv.org/abs/2402.03216。建议：主用 bge-m3 或 Qwen3-Embedding-0.6B；桌面 GGUF，安卓 ONNX int8（内存⚠待实测）；召回 top-50→RRF→0.6B reranker→top-8。

**1.4 GraphRAG** 结论：不做全量 Microsoft GraphRAG。✅官方索引=LLM 抽实体/关系/claim→社区检测→社区摘要→嵌入，产物 Parquet，成本随语料与层级增长。来源 microsoft.github.io/graphrag/index/overview。建议：个人语料收益低；P3 仅实体链接+主题聚类。

**1.5 评测与写入策略** 结论：append-only 事件+夜间批摘要，不实时写记忆。✅Mem0/Zep 在 LoCoMo、LongMemEval 上评测，可作回归基准（arXiv 2504.19413）；✅OWASP 列"记忆持久化"为注入面（cheatsheetseries.owasp.org/cheatsheets/LLM_Prompt_Injection_Prevention_Cheat_Sheet.html）。建议：原始事件永不覆盖，摘要可重建；遗忘=访问衰减+重要度归档，不物理删；MemBench⚠未核实。

## 2 隐私

**2.1 留存/训练政策** 结论：默认只走"不训练+可审计"渠道，境内模型显式开关。✅Anthropic 消费级：删除后 30 天内从后端清除，开启模型改进则最长留 5 年，安全标记留 2 年/分类分 7 年（privacy.claude.com/en/articles/10023548，更新 2026-07-01）；✅DeepSeek：数据在中国境内处理存储、默认用于训练、可退出（cdn.deepseek.com/policies/en-US/deepseek-privacy-policy.html，更新 2026-02-10）；✅Azure Foundry：不用于训练，触发滥用标记时可留样供人工审核，可申请豁免（learn.microsoft.com/en-us/azure/foundry/responsible-ai/openai/data-privacy，2026-05-19）。⚠OpenAI/Gemini/通义/Moonshot/智谱官方页本次 403 或超时，待确认。建议：企业渠道+ZDR 合同优先；国内模型单独开关+明示。

**2.2 TEE/机密计算** 结论：可验证隐私只有端侧或自托管，云端最好情况是"合同+可验证公证"。✅Apple PCC(security.apple.com/blog/private-cloud-compute，2024-06-10)：无状态计算、响应后不留存、无特权运行时访问、不可定向、公开镜像+透明日志可验证。✅Azure/NVIDIA 有机密 GPU 实例（⚠价格未核实）。⚠Anthropic/OpenAI 是否提供 confidential 推理未核实。建议：只把可验证部分写进对外文案。

**2.3 端侧模型** 结论：端侧只做分类/摘要/脱敏，不做主对话。✅Qwen3(arxiv 2505.09388) 0.6B–235B 稠密/MoE、thinking 与非 thinking 统一、119 语言、Apache 2.0。⚠Gemma 3n、Phi-4-mini、llama.cpp/Ollama/MLX 内存数据未取原文。建议：桌面 4B 量化（约 3GB 内存⚠待实测）做摘要；安卓 0.6B 做意图路由。

**2.4 PII 与本地加密** 结论：上传前 Presidio 脱敏+本地 SQLCipher。✅Presidio 提供 analyzer/anonymizer/image redactor，2026 迁至 data-privacy-stack（presidio.dataprivacystack.org）；✅SQLCipher：AES-256-CBC 逐页加密（默认 4096 字节页）+HMAC-SHA512 完整性，PBKDF2-HMAC-SHA512 25.6 万次迭代，加密 journal/WAL，社区版开源（zetetic.net/sqlcipher/design）。⚠Automerge/CRDT E2EE 同步未核实。建议：密钥放 OS keychain；按元数据/正文/截图分级脱敏；同步 v1 不做，将来 CRDT+E2EE。

**2.5 注入风险** 结论：RAG/截图/网页内容一律不可信。✅OWASP LLM01：输入过滤不是安全边界（引 NCSC），攻击含间接注入、RAG 投毒、多模态隐藏文本、记忆持久化；缓解=结构化分隔+最小权限+高危动作确认；ASR 89%(GPT-4o)/78%(Claude 3.5)。来源 cheatsheetseries.owasp.org/cheatsheets/LLM_Prompt_Injection_Prevention_Cheat_Sheet.html。建议：检索片段置 user 侧并标注"仅数据"；写记忆可撤销；工具白名单。

## 3 追踪

**3.1 ActivityWatch** 结论：复用其 API 协议，采集自建。✅aw-server-rust 默认 REST 127.0.0.1:5600，数据落 ~/.local/share/activitywatch，aw-sync 同步，CORS 白名单（github.com/ActivityWatch/aw-server-rust）。建议：只读其 API 或按同 schema 自采，避免双进程。

**3.2 Windows** 结论：1s 轮询前台窗口足够。✅GetForegroundWindow 返回前台 HWND，窗口失活时可能为 NULL（learn.microsoft.com/windows/win32/api/winuser/nf-winuser-getforegroundwindow）。建议：1s 采样、合并 5s 区间；空闲判定用 GetLastInputInfo（⚠未取原文）。

**3.3 Android/iOS** 结论：安卓只做应用级，iOS 不支持精确追踪。✅Android UsageStatsManager 需用户授予 PACKAGE_USAGE_STATS 特殊权限，queryUsageStats 返回按区间聚合的用量，无内容级明细（⚠developer.android.com 超时）；✅AccessibilityService 可读窗口内容但需用户手动开启、上架用途受限（⚠同上）；✅iOS FamilyControls 需 Apple 特批 entitlement（com.apple.developer.family-controls），family activity picker 不向 App 暴露用户选择（developer.apple.com/documentation/familycontrols）。建议：安卓 UsageStats 应用级+解锁事件；iOS 只做手动打点。

**3.4 合并与边界** 结论：默认只上传应用类别与时长。建议：按 (device_id,ts,app) 去重；窗口标题/URL 仅本地保存；浏览器扩展走 MV3、仅本地上报域名与标题摘要（⚠未取官方文档）；selfspy 等旧开源方案已停维护⚠。

## 4 视觉

**4.1 截图理解** 结论：默认关闭，opt-in 后云端多模态单次结构化。✅DeepSeek deepseek-flash 支持 vision、deepseek-v4-pro 不支持（api-docs.deepseek.com/quick_start/pricing，2026-10-26）。⚠图像 API 单图价格与延迟未核实。建议：本地预筛（帧差/黑名单 App）→云端只回结构化 JSON（app/任务/进度）→只存摘要与关键词。

**4.2 生成插画/贴纸** 结论：v1 不做生成。✅Qwen-Image(arxiv 2508.02324，2025-08-04) 强于中英文字渲染。⚠FLUX/SDXL/OpenAI/Google 图像 API 价格未核实。建议：先用预置插画/emoji；若做，桌面本地 Qwen-Image 量化，不上传用户截图。

**4.3 内容安全与正反馈** 结论：避免生成式奖励泛滥。建议：贴纸只用固定素材；接生成须先过审核 API；奖励文案用确定性规则，防"努力=奖励"通胀（⚠审核细节未核实）。

## 5 LLM 工程

**5.1 prompt caching** 结论：成本主杠杆。✅DeepSeek 缓存命中输入 $0.003/1M（off-peak）vs 未命中 $0.15/1M（约 2%），峰值翻倍，1M 上下文，输出 $0.6/1M，支持 JSON 与 tool calls（api-docs.deepseek.com/quick_start/pricing）。⚠OpenAI/Anthropic 缓存 TTL 细节未核实。建议：system+记忆摘要+工具定义固定前缀；命中率进监控。

**5.2 结构化输出** 结论：schema 驱动+校验重试，小模型抽取不可靠。✅Graphiti 文档明示 json_schema 与 json_object 模式差异且小模型失败率高（github.com/getzep/graphiti）；DeepSeek 双模型均支持 JSON/tool calls（api-docs.deepseek.com/quick_start/pricing）。建议：LLM→DB 写入全部 schema 校验+最多 2 次重试，失败落人工队列。

**5.3 长上下文 vs RAG** 结论：RAG 优先。✅DeepSeek 1M 上下文未命中 $0.15/1M、命中 $0.003/1M，成本可压，真正风险是焦点漂移与延迟（⚠lost-in-middle 未取原文）。建议：常规上下文 ≤8k；长上下文只用于周报/回顾一次性生成。

**5.4 小模型分类** 结论：可行，需量化评测。✅Qwen3 全系（含 0.6B）Apache 2.0。建议：0.6B 端侧做意图/安全/记忆候选分类，不够再升 4B。

## ① 记忆系统推荐架构（组件级）

1. 事件层：SQLite append-only（usage_events/app_sessions/notes/screenshot_refs），全量可审计。
2. 抽取层：夜间批处理 LLM（schema 校验）→memories 表（fact/goal/pattern+confidence+source_event_ids+valid_from/to）。
3. 索引层：SQLite FTS5(BM25)+sqlite-vec（bge-m3 或 Qwen3-Embedding-0.6B）+时间/来源元数据。
4. 检索层：双路 top-50→RRF→0.6B reranker→top-8，叠时间衰减与去重。
5. 上下文包：≤2k token，每条带 provenance 可回原文。
6. 治理：写记忆用户可见/可撤销；遗忘=衰减归档；导出/删除一键。
7. P2 图谱：实体-关系表，服务人物/项目/习惯；P3 再评图 RAG。

## ② 隐私架构（威胁模型→措施→承诺边界）

- T1 云端训练/留存→企业渠道+不训练条款（Azure 等）→边界：合同约束，非技术保证。
- T2 传输泄露→TLS+最小化上传+禁止明文截图外发→边界：上传清单可审计。
- T3 设备丢失/同机他人→SQLCipher+keychain+自动锁定→边界：登录态即明文。
- T4 注入窃取（RAG/截图/网页）→内容不可信+工具白名单+高危确认→边界：只降风险，无法根除。
- T5 供应商内部人员→无状态/机密计算（PCC、Azure CC）→边界：仅少数提供商可验证。
- 承诺：①本地原文不出设备（用户开启的云功能除外，逐条可见）②上传前可预览、可撤销、可删除 ③不写"供应商技术上无法利用数据"。

## ③ 追踪与视觉推荐选型 + 不做什么

选型：桌面 1s 窗口轮询+ActivityWatch 兼容 API；安卓 UsageStats 应用级；iOS 只手动打点（不支持精确）；视觉=预置插画+emoji，截图理解 opt-in、云端多模态、只存结构化摘要。
不做：键鼠内容/剪贴板监听；常驻截图；iOS 精确追踪；GraphRAG 全量索引；实时 LLM 写记忆；生成式奖励推送；上传窗口标题/URL 原文；第三方 SaaS 追踪。
