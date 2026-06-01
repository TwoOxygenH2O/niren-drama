# 泥人剧场 Niren Drama

泥人剧场是一个面向竖屏短剧生产的全栈 AI 应用。系统覆盖从一句话创意、剧本生成、分镜拆解、角色与场景管理、首帧生成、图生视频、配音合成、视频合成到发布前质检的完整流程，目标输出适合抖音短剧、红果短剧等平台的 9:16 竖屏内容。

本项目同时包含一个研究型算法 Demo：CASR（Continuity-Aware Self-Repair，连续性感知自修复）。CASR 用于对短剧生产线中的生成后失败进行诊断、归因、策略搜索和可解释展示，适合作为生成式视频生产可靠性研究的系统案例。

## 核心能力

- 用户认证：注册、登录、验证码、JWT 鉴权和前端路由守卫。
- 项目管理：项目创建、编辑、删除、题材设置、集数和单集时长配置。
- 剧本生成：基于项目题材和生产目标生成短剧方案、分集大纲和正文。
- 分镜制作：将剧本拆解为镜头序列，生成图像提示词和动态镜头建议。
- 角色与场景管理：维护角色设定、场景设定、定妆照、背景图和一致性信息。
- AI 服务配置：支持文本、图像、视频和 TTS 多类 OpenAI 兼容服务配置。
- 生产线工作台：集中展示镜头状态、任务进度、质量问题、资产快照和发布包检查。
- 视频合成：基于 FFmpeg 完成镜头合成、字幕、音频对齐和竖屏成片导出。
- CASR 自修复实验室：展示连续性诊断、失败归因、成本敏感策略搜索和修复路径解释。

## 核心算法：CASR 连续性感知自修复

CASR 的定位不是重复实现“角色一致性生成”或“脚本转分镜”，而是在生成完成后的生产线阶段解决通用问题：生成失败如何被发现、如何归因、如何在成本和质量之间选择修复路径。

CASR 输入包括项目、分镜、角色和场景一致性条目、任务记录、资产状态以及已有质检问题。算法输出包括：

- `qualityScore`：结构质量分，覆盖素材缺失、9:16 发布约束、时长异常、黑屏、冻结、任务失败和陈旧任务。
- `continuityScore`：连续性分，覆盖角色锚点、服装锚点、场景锚点、首帧和视频提示词继承风险。
- `failureTypes`：可执行的失败类型，例如缺首帧、身份漂移风险、场景漂移风险、黑屏、时长异常、视频任务失败等。
- `repairPlan`：候选修复路径，例如保存快照、切换 Wan2.2、重跑指定镜头、再次质检。
- `explanation`：解释为什么推荐该路径，并展示预计成本、耗时、成功率、风险和收益。

第一版 CASR 采用可运行的 cost-aware policy search：

```text
reward = scoreGain - costPenalty - timePenalty - riskPenalty
```

后续可以接入视觉评估模型，例如 CLIP、视频帧相似度、人脸一致性检测和光流分析，用于进一步增强状态评估与策略搜索。

### casr-core 独立算法仓库

CASR 的核心领域模型、诊断器、奖励模型和策略搜索器已经抽取到独立算法仓库 `TwoOxygenH2O/casr-core`。当前项目通过 Maven 依赖 `com.twooxygen.casr:casr-engine:0.1.0-SNAPSHOT` 引用算法核心，`NirenCasrInputAdapter` 只负责把生产线实体转换为算法输入。这样可以同时满足两个目标：`casr-core` 作为可投稿、可压缩交付的算法 Demo；本项目作为真实短剧生产系统中的落地集成案例。

本地开发时需要先在同级目录 `../casr-core` 执行：

```bash
mvn clean install
```

安装完成后，当前项目后端即可解析 `casr-engine` 依赖。

## CASR Demo 与论文

项目提供一键创建 CASR 研究 Demo 的入口。登录后进入 Dashboard，点击“创建 CASR 研究 Demo”，系统会生成一个内置 7 个镜头、典型失败样例和一致性锚点的演示项目。进入生产线工作台后，可以查看 CASR 如何发现问题、生成策略树，并解释推荐修复路径。

论文初稿位于：

```text
docs/papers/casr-short-drama-self-repair.md
```

论文包含英文标题和英文摘要，正文使用中文，覆盖摘要、引言、相关工作、方法、系统实现、实验设计、案例展示、局限性和结论。

远程优先的投稿与研究材料位于：

```text
docs/submissions/remote-first/
```

该目录用于准备 arXiv 预印本、远程演示视频、技术报告说明和研究展示材料。当前路线不依赖现场参会、线下展位或必须到场的 Demo 环节。

## 技术架构

### 后端

| 技术 | 用途 |
| --- | --- |
| Spring Boot 3.2 | 后端主框架 |
| Java 17 | 运行环境 |
| Maven | 构建与测试 |
| MyBatis-Plus | ORM 与数据库访问 |
| MySQL 8.0 | 主数据库 |
| Spring Security + JWT | 认证与鉴权 |
| Spring @Async | 长任务异步执行 |
| Knife4j / Swagger | API 文档 |
| FFmpeg | 视频合成与本地质检 |
| Jackson | JSON 序列化 |

说明：Redis 不是当前运行必需组件。当前后端运行、登录验证码、CASR 和生产线质检均不依赖 Redis。

### 前端

| 技术 | 用途 |
| --- | --- |
| Vue 3 | 前端主框架 |
| TypeScript | 类型安全 |
| Vite | 构建工具 |
| Element Plus | UI 组件 |
| Pinia | 状态管理 |
| Vue Router | 路由管理 |
| Axios | HTTP 客户端 |

## 目录结构

```text
niren-drama/
├── backend/
│   ├── src/main/java/com/niren/drama/
│   │   ├── ai/                 # AI Provider 抽象与实现
│   │   ├── config/             # Spring、MyBatis、Swagger、迁移配置
│   │   ├── controller/         # REST API
│   │   ├── dto/                # 请求和响应 DTO
│   │   ├── entity/             # 数据库实体
│   │   ├── mapper/             # MyBatis-Plus Mapper
│   │   ├── security/           # JWT 与安全过滤器
│   │   └── service/            # 业务服务、AI 任务、CASR 算法
│   └── src/main/resources/
│       ├── application.yml
│       └── db/init.sql
├── frontend/
│   └── src/
│       ├── api/
│       ├── components/
│       ├── router/
│       ├── stores/
│       └── views/
├── docs/
│   └── papers/
│       └── casr-short-drama-self-repair.md
├── docker-compose.yml
└── README.md
```

## 启动方式

### 后端本地启动

要求：JDK 17、Maven 3.8+、MySQL 8.0+。

```bash
cd backend
mysql -u root -p < src/main/resources/db/init.sql
mvn spring-boot:run
```

后端默认地址：

```text
http://localhost:8080
```

API 文档：

```text
http://localhost:8080/api/doc.html
```

### 前端本地启动

要求：Node.js 18+。

```bash
cd frontend
npm install
npm run dev
```

前端默认地址：

```text
http://localhost:5173
```

前端开发服务器会将 `/api` 请求代理到后端。

### Docker Compose

```bash
docker-compose up -d
```

Docker Compose 用于本地一键体验。MySQL、后端、前端等组件会按编排启动；当前编排不需要 Redis。

## 关键 API

所有接口统一返回：

```json
{
  "code": 200,
  "message": "success",
  "data": {}
}
```

CASR 相关接口：

```text
POST /api/production/{projectId}/casr/analyze
POST /api/production/{projectId}/casr/plan
POST /api/production/{projectId}/casr/execute
POST /api/production/demo/casr
```

生产线相关接口：

```text
GET  /api/production/{projectId}/workspace
POST /api/production/{projectId}/repair
POST /api/production/{projectId}/quality-check
POST /api/production/{projectId}/snapshots
POST /api/production/{projectId}/export-package
```

## 配置说明

常用环境变量：

| 变量 | 说明 |
| --- | --- |
| `DB_HOST` | MySQL 地址 |
| `DB_PORT` | MySQL 端口 |
| `DB_NAME` | 数据库名 |
| `DB_USER` | 数据库用户名 |
| `DB_PASSWORD` | 数据库密码 |
| `JWT_SECRET` | JWT 签名密钥，生产环境必须替换 |
| `NIREN_CONFIG_ENCRYPTION_KEY` | AI 服务 API Key 的数据库加密密钥；为空时回退到 `JWT_SECRET` |
| `AI_TEXT_API_KEY` | 文本模型 API Key |
| `AI_TEXT_BASE_URL` | OpenAI 兼容文本接口地址 |
| `AI_IMAGE_API_KEY` | 图像模型 API Key |
| `UPLOAD_PATH` | 本地上传目录 |
| `FFMPEG_PATH` | FFmpeg 可执行文件路径 |

AI Key 也可以在 Web 端的 AI 配置页面中维护，并按用户隔离保存。

## 测试与构建

后端测试：

```bash
cd backend
mvn test
```

前端构建：

```bash
cd frontend
npm run build
```

CASR 相关测试覆盖：

- 缺首帧、视频失败、黑屏、时长异常和连续性风险诊断。
- 策略搜索在相同输入下稳定选择成本收益最高的路径。
- Demo 服务创建项目、分镜、问题、一致性条目和 CASR run。
- 策略生成不执行修复；执行接口只运行用户确认的动作。

## 研究展示重点

本项目可以作为生成式视频生产可靠性研究案例展示：

- 完整产品闭环：从创意到生产线工作台，再到成片导出。
- 后端工程能力：Spring Boot 分层架构、MyBatis-Plus、JWT、异步任务、FFmpeg 集成。
- 前端工程能力：Vue 3、TypeScript、Element Plus、状态管理和复杂工作台 UI。
- 算法工程能力：CASR 的质量评估、失败归因、成本敏感策略搜索和可解释展示。
- 文档表达能力：README、论文初稿、Demo 流程和工程验证记录。

## 许可

本项目遵循 MIT License。
