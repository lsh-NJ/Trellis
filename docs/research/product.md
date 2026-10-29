# Trellis 产品 / 主动性 HCI / 伦理 证据简报

核对 2026-10-26。格式：判断｜证据｜来源｜对 Trellis 的决定。⚠＝未核实。

## 1 产品拆解

1. **连续天数是习惯 App 第一留存引擎，分层极陡。** Duolingo FY2025 10-K：DAU 52.7M／MAU 133.1M（≈40%），43M DAU（≈82%）带 ≥7 天 streak、15M 带 ≥365 天；MAU 约 9% 付费；自述"feels more like a mobile game than an education product"。｜https://origin.rootz.global/static/filing/0001628280-26-012494.html ｜决定：有连续天数，但默认不惩罚、可暂停；不卖「修复 streak」——那是把焦虑变现。
2. **它的推送是「进度＋提醒＋一句正向话」，执行却越界成监控感。** 10-K 原文"progress reports, lesson reminders, and sometimes a simple message of positivity"；App Store 最新 1 星："I feel like I am being watched"、"learning on your bed… I can't hide from it"。｜https://itunes.apple.com/us/rss/customerreviews/page=1/id=570060128/sortby=mostrecent/json ｜决定：主动消息只说「事实＋具体下一步」，禁止全知语气；拟人在语气，不在监视能力。
3. **通知疲劳是默认状态。** Reuters Institute DNR 2025：跨市场 79% 一周内收不到任何新闻推送；这批人中 43% 主动关闭，理由「太多／无关」。｜https://reutersinstitute.politics.ox.ac.uk/digital-news-report/2025/walking-notification-tightrope-how-engage-audiences-while-avoiding ｜决定：推送设每日预算＋每周安静日，关闭开关放设置第一屏。
4. **Guilt-trip 文案拉得回人，也在制造敌意。** El País 2024-12-22 专文分析 Duolingo 的 passive-aggressive 召回话术。｜https://english.elpais.com/lifestyle/2024-12-22/we-havent-seen-you-in-a-while-duolingos-passive-aggressive-strategy-for-keeping-users-hooked.html ｜决定：拟禁用词表（「你又失败」）；缺席后第一句是「欢迎回来＋今天最小一步」，不翻旧账。
5. **国内卷成打卡＋番茄钟；AI 日程类在移动端「小而贵」。** 中国区：番茄ToDo 4.91（94.2 万评分）、滴答清单 4.88（14.9 万）、Forest 4.84（25.9 万）；美国区：Motion 4.09（1,856，必须订阅且需先有电脑）、Sunsama 4.51（515）；对照 Quizlet 4.78（113.6 万）、Finch 4.95（76.8 万）、Anki 4.04（一次性 $24.99，仅 2,369）。｜https://itunes.apple.com/search?term=motion&entity=software&country=us ｜决定：不比功能清单，差异必须是「它记得我这个人」；桌面做计划/复盘，Android 只做记录与接收。
6. **付费结构决定产品性格，转化窗口在第 0 天。** RevenueCat SOA 2026：D35 下载→付费中位 2.6%（北美）；硬付费墙 10.7% vs 免费增值 2.1%，一年后留存几乎相同；55% 的三天试用取消在 Day 0；首年 LTV 中位 $23。｜https://www.revenuecat.com/state-of-subscription-apps/ ｜决定：免费可用，把「第一次被理解」放进首会话 60 秒（导入真实目标→给出第一周）；付费卖更深的记忆与主动性，不卖解锁。
7. **苏格拉底式追问是教育 AI 最易翻车处。** Khanmigo 官方定位"without giving you direct answers"；Hechinger 报道：学生不想要追问，干脆不用。｜https://hechingerreport.org/proof-points-khanmigo-math-ai-tutor/ ｜决定：默认「先给答案＋一步追问」，每条回答旁给「直接告诉我」开关；引导是选项不是收费站。

## 2 AI 陪伴

8. **监管风险落在数据合法性与年龄门槛，不在「太像人」。** 意大利 Garante 2025-04-10 罚 Replika 母公司 Luka €5,000,000：无合法依据、告知不足、未做年龄验证与儿童保护。｜https://www.enforcementtracker.com/ETid-2611 ｜决定：默认不收集位置/通讯录；情感对话本地优先、可导出删除；年龄声明前置，未成年人不开放开放式陪伴。
9. **未成年人＋开放式角色扮演是红线。** Character.AI 2025-10-29 禁止 18 岁以下与开放式 bot 聊天，背景是诉讼指控其关联青少年死亡/自杀未遂。｜https://blog.character.ai/u18-chat-announcement/ ｜决定：不做无边界角色扮演；「朋友」限定在学习陪跑者角色内，边界写进系统提示并对用户公开。
10. **关系型产品的死法：关停时只剩一个导出按钮。** Dot（前 Apple 设计师参与创立，定位 friend and companion）2025-09-05 宣布关停，运营至 10-05。｜https://techcrunch.com/2025/09/05/personalized-ai-companion-app-dot-is-shutting-down/ ｜决定：首版就有「导出全部记忆＋离线可读」；退订/关停有告别与归档流程，不静默 404。
11. **「像朋友」还是「表演」取决于记忆与一致性，不是文风。** ⚠ Pentina 2023（CHB 140:107600）指社交吸引力预测对 Replika 的情感依赖（未取全文）；MIT×OpenAI 981 人 4 周研究指重度使用与孤独/依赖相关（二手，数字未核）。｜https://dl.acm.org/doi/10.1016/J.CHB.2022.107600 ｜决定：指标＝跨 7 天能引用用户自己说过的话；禁用「我一直在等你」；用户说放弃时先问原因。

## 3 主动式 agent HCI

12. **主动性的核心是「不确定性 × 打扰成本」的期望效用。** Horvitz 1999 CHI《Principles of mixed-initiative user interfaces》给出人机共同发起权的经典框架。｜https://dblp.org/rec/conf/chi/Horvitz99.html ｜决定：每个主动提议内部带置信度、打断代价、猜错回退成本；低置信时先问一句或静默取证，不直接执行。
13. **时机由状态触发而非定时器；多数 JITAI 缺证据。** Nahum-Shani 2018（Ann Behav Med 52(6):446-462）：JITAI 是正确时间、正确类型/剂量并随内部与情境状态调整；同时批评许多 JITAI 极少使用证据/理论，须关注依从与留存。｜https://pubmed.ncbi.nlm.nih.gov/27663578/ ｜决定：触发条件是用户状态（拖延迹象、完成度、情绪低点、日历空档），不是每天 9:00 的 cron。
14. **主动建议有效但会新鲜感衰减，形式比频率重要。** HeartSteps 6 周微随机试验（44 人，每天≤5 次用户自选时刻）：有建议时 30 分钟步数 +14%（p=.06），walking 类 +24%（p=.02）；效应初期 +66%/+107% 后衰减；antisedentary 无可检测效应。｜https://pubmed.ncbi.nlm.nih.gov/30192907/ ｜决定：同一形式不超过 2 周轮换；上线即埋「第 4 周衰减」监测，衰减就降频不加量。
15. **被打断的代价是压力，不只是慢。** Mark/Gudith/Klocke CHI 2008：被打断后任务更快，但压力、挫折、时间压力与心智努力显著更高。｜https://doi.org/10.1145/1357054.1357072 ｜决定：专注或免打扰时完全静默，消息进「稍后」托盘；「稍后」是一等公民，不是取消。
16. **隐私默认值踩线，产品要重做一次。** Recall：2024-05 作为 Copilot+ PC 卖点发布，6 月因 ICO 审查与反弹推迟，9 月宣布 11 月重发并加 opt-in、Purview、加密。｜https://www.silicon.co.uk/security/microsoft-recall-relaunch-581786 ｜决定：读屏/读文件默认关闭、显式授权、运行时有常驻指示、可按应用排除；主动内容进「今天」页同列表，不做独立 feed。

## 4 教育 AI

17. **UNESCO 已把年龄与机构政策写成全球基准。** 2023-09-07《生成式 AI 教育与研究指南》：7 个监管步骤、课堂工具最低 13 岁、教师培训；450+ 学校调查中不到 10% 有正式政策。｜https://www.unesco.org/en/articles/guidance-generative-ai-education-and-research ｜决定：未成年场景把「AI 管理员」降级为老师/家长可审阅的计划助手；公开「AI 能做什么/不能做什么」说明书。
18. **AI 辅导的效果来自教学法＋人的在场，不是模型。** Tutor CoPilot RCT：700+ 教师、1,000 名弱势学生，学生掌握数学主题概率高 4 p.p.（p<0.01），弱评分教师的学生 +9 p.p.。｜https://edworkingpapers.com/ai24-1054 ｜决定：给老师/同伴「一键补上下文」入口；效果指标看用户自己的计划完成，不看聊天时长。
19. **「AI 家教赢课堂」是有条件的。** 哈佛 PS2 Pal：194 名本科生交叉设计，AI 组后测中位 4.5 vs 3.5、49 vs 60 分钟；但只测两个物理主题，AI 由教师自建、内置引导式提问且不给答案。｜https://techjuice.pk/harvard-ai-tutoring-study-physics-learning-outcomes-debate/ ｜决定：agent 指令写死教学法（诊断→示范→复述→才给答案），保留可切换、可解释的教学模式。
20. **「AI 让学生变懒/认知债务」是强信号但非定论。** ⚠ MIT《Your Brain on ChatGPT》（54 人、EEG）与 Bastani「Generative AI Can Harm Learning」指向无约束使用降低脱离 AI 后表现；原始数字未核。｜https://www.media.mit.edu/publications/your-brain-on-chatgpt/ ｜决定：每周一次「无 AI 复述/自测」，正反馈来自「你自己完成的证据」。

## 5 必须避开的 8 个坑

1. 羞耻式召回（El País / Duolingo）。
2. 全知语气"can't hide from it"。
3. 把推送当增长杠杆（79% 收不到、43% 主动关）。
4. 只追问不给答案（Khanmigo）。
5. 无年龄边界的开放式角色扮演（Character.AI）。
6. 情感数据无合法依据＋无年龄验证（Replika €5M）。
7. 关系型产品不设计退出与导出（Dot）。
8. 隐私承诺当营销、默认值踩线（Recall）。

## 6 值得抄的 8 个机制

1. 连续天数＋可暂停（不抄修复付费）——Duolingo。
2. 进度报告式主动消息：本周做成了什么＋下一步一句——10-K。
3. 教学法写进系统提示（诊断→示范→复述→答案）——PS2 Pal。
4. 人机协作：老师/同伴一键补上下文——Tutor CoPilot。
5. 状态触发替代定时触发——JITAI/HeartSteps。
6. 形式轮换＋上线即测新鲜感衰减——HeartSteps。
7. 敏感能力 opt-in＋常驻指示＋按应用排除——Recall。
8. 退出即体面：导出全部记忆＋告别/归档——Dot。

> App Store 数字为 2026-10-26 经 Apple 公开 Search/Lookup API 读取，代表规模与价格结构，不代表留存。
