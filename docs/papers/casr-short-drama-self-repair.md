# CASR: Continuity-Aware Self-Repair for AI Short-Drama Production Pipelines

## Abstract

AI short-drama production systems increasingly combine script generation, storyboard decomposition, image generation, image-to-video synthesis, speech synthesis, and video composition. Existing tools focus primarily on generation quality and end-to-end authoring, while production teams still need a practical mechanism to diagnose generated failures, explain their causes, and choose repair actions under limited cost and time budgets. This paper introduces CASR, a Continuity-Aware Self-Repair algorithm for short-drama production pipelines. CASR builds a shot graph from storyboards, assets, consistency anchors, task records, and quality issues; scores structural quality and narrative continuity; attributes failures into actionable categories; and performs cost-aware policy search to recommend repair paths. The first implementation avoids heavy visual model dependencies and provides a stable research prototype that can be connected to Wan2.2, LTX, or other image-to-video workflows. A demo case shows that CASR can expose missing first frames, identity drift risk, scene drift risk, motion failures, black frames, stale tasks, and duration anomalies, then recommend a repair sequence with explicit cost, time, risk, and expected gain.

## 摘要

生成式短剧生产系统已经能够覆盖从创意、剧本、分镜、首帧、视频、配音到成片合成的多阶段流程，但在真实生产中，失败往往不是单点错误，而是由角色连续性、场景连续性、任务状态、素材质量和成本约束共同造成。本文提出 CASR，即 Continuity-Aware Self-Repair，连续性感知自修复算法。CASR 的目标不是替代视频生成模型，而是在生成后阶段提供诊断、失败归因、成本敏感策略搜索与可解释展示。系统首先构建镜头图，提取结构质量、连续性锚点、任务状态和已有质检问题；随后输出质量分、连续性分和失败类型；最后通过策略搜索比较不同修复路径，推荐兼顾质量收益、成本、耗时和风险的动作序列。该算法适合作为 AI 短剧平台的生产线增强模块，也适合作为全栈工程与应用型算法研究的研究展示案例。

## 1. 引言

AI 短剧生产线的主流程通常由多个生成与合成环节串联而成：剧本生成、分镜拆解、角色和场景设定、首帧生成、图生视频、TTS 配音、字幕与视频合成。单个环节的生成结果即使看起来可用，也可能在后续环节放大为连续性问题。例如，某个镜头缺少首帧会降低图生视频的身份稳定性；某个镜头视频任务失败会影响成片节奏；场景提示词没有继承一致性锚点，会导致同一空间在相邻镜头中发生漂移。

现有产品和研究更关注“生成出更好的内容”或“评测视频生成模型”，但短剧生产还需要一个面向工程流水线的后处理算法：它能够回答哪些镜头出了问题、问题属于哪类、应该先修哪里、为什么这条修复路径更划算。CASR 正是针对这一空白设计的研究原型。

## 2. 相关工作

Runway Gen-4 References 代表了基于参考图像提高角色、物体和世界一致性的产品方向。LTX Studio 代表了从脚本到视频的端到端创作工作流。SkyReels-V1 代表了面向短剧生产的人物中心视频基础模型方向。VBench-2.0 则代表了视频生成质量评测基准方向。

CASR 与上述方向的差异在于：它不重复实现角色一致性生成、脚本转分镜或通用视频评测，而是聚焦生成后生产线状态的诊断和修复策略。它把短剧项目视为一个有成本、有任务状态、有一致性锚点、有资产版本的工程系统，并在这个系统上执行可解释的策略搜索。

## 3. 方法

### 3.1 输入表示

CASR 的输入包括：

- 项目元信息：项目类型、题材、集数、生产状态。
- 分镜列表：镜头编号、描述、首帧、视频、音频、时长、视频任务状态、图像提示词和视频提示词。
- 一致性条目：角色、场景、风格等锁定属性。
- 任务记录：运行中、失败或长期卡住的 AI 任务。
- 质检问题：黑屏、冻结、比例异常、素材缺失、时长异常等已有问题。

这些输入被组织为 shot graph。每个镜头节点携带素材状态、任务状态、连续性风险、已有问题和可执行动作。

### 3.2 诊断与评分

CASR 输出两个核心分数：

- `qualityScore`：结构质量分，关注素材缺失、视频任务失败、黑屏、冻结、比例异常、时长异常和陈旧任务。
- `continuityScore`：连续性分，关注角色身份、服装、场景、首帧约束和视频提示词继承情况。

当前原型支持的失败类型包括：

- `missing_first_frame`
- `missing_media`
- `identity_drift_risk`
- `wardrobe_drift_risk`
- `scene_drift_risk`
- `motion_failure`
- `black_frame`
- `frozen_frame`
- `duration_out_of_range`
- `wrong_aspect_ratio`
- `video_task_failed`
- `stale_task`

### 3.3 策略搜索

CASR 的修复策略不直接等同于“全部重跑”。算法把每个修复方案建模为动作序列，并计算：

```text
reward = scoreGain - costPenalty - timePenalty - riskPenalty
```

动作空间包括：

- `snapshot`：保存当前资产快照。
- `regenerateFirstFrame`：重生成首帧。
- `retryVideo`：重跑问题镜头视频。
- `switchLtx`：切换到快速预览工作流。
- `switchWan`：切换到高连续性工作流。
- `useFirstFrameOnly`：使用首帧兜底合成。
- `composePreview`：生成预览合成。
- `qualityCheck`：再次运行质检。

第一版实现采用稳定、可解释的 cost-aware policy search。后续可以把候选动作扩展为蒙特卡洛树搜索，将视觉相似度模型或 CLIP 特征接入状态转移评估。

### 3.4 可解释输出

CASR 不只输出一个动作，而是输出候选策略树。每条路径包含预计质量收益、成本、耗时、风险、成功率和推荐原因。这样研究评审者或生产人员可以看到算法如何在“快速预览”“高质量发布”和“保底交付”之间权衡。

## 4. 系统实现

本项目将 CASR 实现为后端算法服务和前端可视化实验室。

后端模块包括：

- `CasrAnalysisService`：构建诊断结果，输出镜头级失败类型、质量分、连续性分和解释。
- `CasrPolicySearchService`：生成候选修复路径，并按奖励函数排序。
- `CasrWorkflowService`：加载生产线上下文，持久化 CASR run，并保证策略生成阶段不执行修复。
- `CasrDemoService`：创建包含典型失败样例的研究 Demo 项目。
- `drama_casr_run`：保存运行摘要、分数、失败类型、策略树、推荐动作和成本估计。

前端模块包括：

- Dashboard 的“创建 CASR 研究 Demo”入口。
- 生产线工作台中的“CASR 自修复实验室”。
- 镜头风险图、失败类型标签、质量分、连续性分、策略路径、收益和成本展示。
- 用户确认后的单动作执行入口，避免自动触发昂贵生成调用。

## 5. 实验设计

论文初版使用内置 Demo 数据进行实验展示。Demo 包含 7 个镜头，覆盖以下典型问题：

- 首帧缺失导致视频生成不稳定。
- 视频任务失败导致成片缺段。
- 黑屏和时长异常影响发布质量。
- 角色身份和场景连续性存在漂移风险。
- 陈旧任务占用生产线状态。

对比基线是普通重试策略，即不区分失败原因，直接重跑所有失败或缺失镜头。CASR 的评估维度包括：

- 问题定位：是否能指出具体镜头和失败类型。
- 修复成本：是否减少不必要的重跑。
- 可解释性：是否说明推荐路径的收益、成本、耗时和风险。
- 工程可用性：是否能在没有额外视觉模型依赖的情况下稳定运行。

## 6. 案例展示

在 CASR Demo 中，系统会先生成一个短剧项目，内置角色锚点、场景锚点、七个镜头和五类生产问题。运行 CASR 后，工作台展示结构质量分、连续性分和镜头风险图。策略搜索通常推荐“保存快照、切换 Wan2.2、重跑问题镜头、再次质检”的路径。

该推荐的含义是：先保留当前资产以降低回滚风险；再切换到更强调连续性的工作流；随后只重跑高风险镜头，而不是全量重跑；最后再次执行质检闭环。这比普通重试更适合作为发布前修复策略。

## 7. 局限性

第一版 CASR 是研究原型，暂不接入额外视觉模型。因此它对黑屏、冻结、身份漂移和场景漂移的判断主要来自已有资产状态、任务状态、质检问题和提示词继承规则。后续可以接入以下能力：

- CLIP 或人脸特征相似度，用于角色连续性量化。
- 视频帧采样与光流分析，用于动作失败和冻结检测。
- 成本模型校准，用真实调用价格和耗时数据更新 reward。
- 更完整的 MCTS 状态转移模拟。

## 8. 结论

CASR 将 AI 短剧生产中的“生成失败后怎么办”转化为一个可计算、可解释、可展示的算法问题。它补强了生成式视频流水线的工程闭环：诊断问题、归因失败、搜索修复路径、展示决策依据，并把执行权交给用户确认。对研究 Demo 来说，CASR 同时体现了后端算法服务、前端可视化、生产系统建模、工程验证和论文表达能力。

## 参考定位

- Runway Gen-4 References: https://help.runwayml.com/hc/en-us/articles/40042718905875-Creating-with-Gen-4-Image-References
- LTX Studio: https://ltx.studio/
- SkyReels-V1: https://www.globenewswire.com/news-release/2025/02/19/3029103/0/en/skyreels-open-sources-the-world-s-first-human-centric-video-foundation-model-for-ai-short-drama-creation-skyreels-v1-reshaping-the-ai-short-drama-landscape.html
- VBench-2.0: https://huggingface.co/papers/2503.21755
