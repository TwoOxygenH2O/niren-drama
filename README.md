# 泥人剧场 · AI 短剧自动化生产平台

> 基于 Spring Boot 3 + Vue 3 + TypeScript 的全栈 AI 短剧自动化生产平台，实现「一句话创意 → 剧本生成 → 分镜拆解 → AI生图/生视频 → 角色配音 → 自动合成成片」全流程闭环。

---

## 🎯 项目简介

**泥人剧场（Niren Drama）** 是一个专为短视频创作者设计的 AI 驱动短剧生产平台。平台以剧本为核心，通过 AI 大模型完成剧本撰写、角色/场景设计、分镜画面生成、配音合成等全部步骤，最终输出竖屏（9:16）成片。

本项目独立原创开发，基于行业最佳实践，未抄袭任何第三方开源源码，遵循 MIT 开源协议。

---

## ✨ 功能特性

### 🔐 用户认证
- ✅ 用户注册 / 登录
- ✅ JWT Token 鉴权
- ✅ 权限拦截（前端路由守卫 + 后端 Security Filter）

### 🗂️ 项目管理
- ✅ 创建 / 编辑 / 删除短剧项目
- ✅ 设置题材风格（言情、玄幻、悬疑、职场、古装、喜剧）
- ✅ 配置集数与单集时长
- ✅ 项目状态跟踪（草稿 / 生成中 / 已完成 / 失败）

### ✍️ 剧本生成（S+ 评级提示词引擎）
- ✅ 一句话创意 → AI 自动生成完整短剧项目方案
- ✅ 产出内容包含：剧名、一句话梗概、完整人物小传（含弧光设计）、分集大纲（100字/集）、3张AI封面提示词、完整剧本正文
- ✅ 对标红果短剧/抖音短剧保底S+评级标准
- ✅ 内置爽点节奏模板：黄金3秒钩子 + 每2分钟爽点 + 集末强悬念
- ✅ 平台合规红线内置（无违规内容生成）
- ✅ 异步生成 + 实时进度轮询
- ✅ 剧本在线编辑与保存
- ✅ 多集管理，支持最多120集长篇

### 🎬 分镜制作（S+ 评级分镜引擎）
- ✅ AI 自动拆解剧本为分镜序列（80-100镜头/集）
- ✅ 每个镜头含高质量 imagePrompt（人物+场景+光影+构图，≥50字）
- ✅ 动态镜头智能推荐（追逐/打斗/转场自动标记）
- ✅ 场景复用率优化≥60%，降低AI生图成本
- ✅ 分镜卡片网格展示（含镜头编号 / 时长 / 状态）
- ✅ 分镜画面 AI 生图（异步任务，电影级4K画质）

### 👤 角色管理
- ✅ 角色信息录入（姓名 / 性别 / 年龄 / 外貌 / 性格）
- ✅ AI 一键生成角色定妆照（电影级质感，伦勃朗光影）
- ✅ TTS 音色分配与管理
- ✅ 角色外貌描述跨镜头一致性保障

### 🌄 场景管理
- ✅ 场景信息录入（名称 / 时间 / 室内外）
- ✅ AI 一键生成场景背景图（4K电影级质感，戏剧性光影）
- ✅ 智能光影匹配（根据时段自动调整光照风格）

### 📦 素材库
- ✅ 文件上传（图片 / 视频 / 音频）
- ✅ 按类型筛选
- ✅ 分页展示与删除管理

### ⚙️ AI 配置
- ✅ 多厂商 AI 服务配置（文本 / 图像 / 视频 / TTS）
- ✅ 支持切换 OpenAI、火山引擎豆包、阿里通义、百度文心、MiniMax、可灵AI、即梦AI 等
- ✅ 每类配置支持多个，可设置默认
- ✅ 配置数据库持久化（按用户隔离）

### 🤖 AI 服务层
- ✅ 统一 AI Provider 抽象接口（文本 / 图像 / TTS / 视频）
- ✅ OpenAI 兼容接口实现（支持所有 OpenAI 格式的第三方 API）
- ✅ 异步任务队列（Spring @Async + 任务记录表）
- ✅ 全局任务进度查询接口

---

## 🧠 S+ 评级提示词引擎

本平台内置对标红果短剧/抖音短剧 **保底S+评级** 标准的全链路提示词引擎，覆盖从「一句话创意」到「成片输出」的每一个AI调用环节：

### 剧本生成提示词
| 模块 | 能力 |
|------|------|
| 系统角色 | 10年爆款短剧金牌编剧，专精红果/抖音平台 |
| 合规红线 | 内置平台审核标准，自动规避违规内容 |
| 钩子设计 | 黄金3秒 + 30秒法则 + 集末强悬念模板 |
| 爽点节奏 | 每2分钟小爽点 + 每集≥3个大爽点 + 集末反转 |
| 人物弧光 | 低谷→觉醒→反击→逆袭 完整成长线 |
| 长度约束 | 3000-4000字/集，15-25场景/集 |
| 完整输出 | 剧名 + 梗概 + 人物小传 + 分集大纲(100字/集) + 3张AI封面提示词 + 完整剧本 |

### 分镜拆解提示词
| 模块 | 能力 |
|------|------|
| 镜头密度 | 80-100镜头/集，360-480秒 |
| imagePrompt | 每镜头≥50字，含人物外貌+场景+光影+构图+风格 |
| 爽点镜头 | 每12-15镜头插入快切爽点镜头组 |
| 场景复用 | 复用率≥60%，大幅降低生图成本 |
| 动态推荐 | 智能识别需要动态视频的镜头，占比≤30% |

### AI生图/生视频提示词
| 模块 | 能力 |
|------|------|
| 角色定妆照 | 竖版9:16，伦勃朗光影，电影级4K |
| 场景背景图 | 智能时段光影，戏剧性光影，景深效果 |
| 分镜关键帧 | 详细人物+场景+光影描述，电影级质感 |
| 动态视频 | 基于关键帧的自然运动，避免角色漂移 |

> **核心理念：** 保底级付费短剧剧本，红果短剧、抖音短剧标准，合规无违规，爽点密集，适合平台保底S+评级。

---

## 🛠️ 技术架构

### 后端
| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.2+ | 后端主体框架 |
| JDK | 17 | 运行环境 |
| MyBatis-Plus | 3.5+ | ORM 框架 |
| MySQL | 8.0+ | 主数据库 |
| Redis | 7.0+ | 缓存与任务状态 |
| Spring Security + JWT | — | 权限认证 |
| Knife4j (Swagger) | 4.4+ | 接口文档 |
| Spring @Async | — | 异步 AI 任务 |
| Hutool | 5.8+ | 工具类库 |
| Jackson | — | JSON 处理 |

### 前端
| 技术 | 版本 | 用途 |
|------|------|------|
| Vue 3 | 3.4+ | 前端主体框架 |
| TypeScript | 5.0+ | 类型安全 |
| Vite | 5.0+ | 构建工具 |
| Element Plus | 最新 | UI 组件库 |
| Pinia | 3.0+ | 全局状态管理 |
| Vue Router | 4+ | 路由管理 |
| Axios | 最新 | HTTP 客户端 |

---

## 📂 项目结构

```
niren-drama/
├── backend/                    # Spring Boot 3 后端
│   ├── src/main/java/com/niren/drama/
│   │   ├── ai/                 # AI 提供商抽象层
│   │   │   ├── TextAiProvider.java
│   │   │   ├── ImageAiProvider.java
│   │   │   ├── TtsProvider.java
│   │   │   ├── AiProviderFactory.java
│   │   │   └── impl/           # OpenAI 兼容实现
│   │   ├── config/             # 配置类（MybatisPlus/Async/Swagger/WebMvc）
│   │   ├── controller/         # REST 控制器（9个模块）
│   │   ├── dto/                # 请求/响应 DTO
│   │   ├── entity/             # 数据库实体
│   │   ├── exception/          # 全局异常处理
│   │   ├── mapper/             # MyBatis-Plus Mapper
│   │   ├── security/           # JWT + Spring Security
│   │   └── service/            # 业务服务层（含异步 AI 任务）
│   └── src/main/resources/
│       ├── application.yml     # 配置文件（支持环境变量）
│       └── db/init.sql         # 数据库初始化 SQL
├── frontend/                   # Vue 3 + TypeScript 前端
│   └── src/
│       ├── api/                # Axios 接口封装层
│       ├── components/layout/  # 主布局（侧边栏 + 顶栏）
│       ├── router/             # 路由配置（含权限守卫）
│       ├── stores/             # Pinia 状态管理
│       └── views/              # 页面视图
│           ├── auth/           # 登录 / 注册（沉浸式双栏布局）
│           ├── DashboardView.vue   # 工作台（统计 + 工作流 + 最近项目）
│           ├── project/        # 项目管理（列表 + 详情）
│           ├── script/         # 剧本生成（AI生成 + 在线编辑）
│           ├── storyboard/     # 分镜制作（AI拆解 + 网格预览）
│           ├── character/      # 角色管理（AI生图 + 音色配置）
│           ├── scene/          # 场景管理（AI生图）
│           ├── asset/          # 素材库（上传 / 筛选 / 管理）
│           └── settings/       # AI 服务配置
├── docker-compose.yml          # Docker 容器编排
└── README.md
```

---

## 🚀 快速启动

### 方式一：Docker Compose（推荐）

**前提：** Docker Desktop 或 Docker Engine + Docker Compose

```bash
# 1. 克隆项目
git clone https://github.com/TwoOxygenH2O/niren-drama.git
cd niren-drama

# 2. 按需配置 AI Key（可选，也可在 Web 界面配置）
# 复制环境变量模板
cp .env.example .env
# 编辑 .env，填写 AI_TEXT_API_KEY 等

# 3. 一键启动（首次会自动构建镜像）
docker-compose up -d

# 访问前端：http://localhost
# 访问 API 文档：http://localhost:8080/api/doc.html
```

### 方式二：本地开发

**前提：** JDK 17、Maven 3.8+、Node.js 18+、MySQL 8.0+、Redis 7.0+

**后端：**
```bash
cd backend

# 初始化数据库
mysql -u root -p < src/main/resources/db/init.sql

# 修改 application.yml 或设置环境变量
export DB_PASSWORD=your_mysql_password
export AI_TEXT_API_KEY=sk-xxxxxxxx

mvn spring-boot:run
# 后端启动于 http://localhost:8080
# API 文档：http://localhost:8080/api/doc.html
```

**前端：**
```bash
cd frontend
npm install
npm run dev
# 前端启动于 http://localhost:5173（自动代理 /api 到后端）
```

---

## 🔧 环境变量说明

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `DB_HOST` | localhost | MySQL 地址 |
| `DB_PASSWORD` | root | MySQL 密码 |
| `REDIS_HOST` | localhost | Redis 地址 |
| `JWT_SECRET` | *(内置)* | JWT 签名密钥（**生产环境必须修改**） |
| `AI_TEXT_API_KEY` | *(空)* | 文本 AI API Key |
| `AI_TEXT_MODEL` | gpt-4o | 文本模型名称 |
| `AI_TEXT_BASE_URL` | OpenAI 官方 | 支持任何 OpenAI 兼容接口 |
| `AI_IMAGE_API_KEY` | *(空)* | 图像 AI API Key |
| `AI_IMAGE_MODEL` | dall-e-3 | 图像生成模型 |
| `UPLOAD_PATH` | ./uploads | 文件上传目录 |

> 所有 AI Key 也可在 Web 界面「AI 配置」页面中配置，数据库级别隔离，更安全灵活。

---

## 🤖 AI 兼容层

平台实现了统一 AI Provider 抽象接口，支持通过配置切换服务商：

| 类型 | 支持服务商 |
|------|-----------|
| **文本大模型** | OpenAI GPT 系列 / 火山引擎豆包 / 阿里通义千问 / 百度文心（所有 OpenAI 兼容接口均可接入） |
| **文生图** | OpenAI DALL-E 3 / Stable Diffusion / 通义万相 |
| **文生视频** | 可灵 AI / 即梦 AI / Runway（通过扩展 `VideoAiProvider` 接口接入） |
| **TTS 语音** | 火山引擎 TTS / 阿里云语音（内置多款音色 Mock，接入真实 TTS 只需实现 `TtsProvider` 接口） |

---

## 🔗 API 文档

启动后端后访问：`http://localhost:8080/api/doc.html`

主要接口模块：

| 路径 | 说明 |
|------|------|
| `POST /api/auth/login` | 登录 |
| `POST /api/auth/register` | 注册 |
| `GET/POST/DELETE /api/projects` | 项目管理 |
| `POST /api/scripts/generate` | AI 生成剧本（异步） |
| `POST /api/storyboards/generate` | AI 拆解分镜（异步） |
| `GET/POST /api/characters` | 角色管理 |
| `POST /api/characters/{id}/generate-image` | AI 生成角色图像（异步） |
| `GET/POST /api/scenes` | 场景管理 |
| `POST /api/scenes/{id}/generate-image` | AI 生成场景图（异步） |
| `GET/POST /api/assets` | 素材库 |
| `POST /api/assets/upload` | 文件上传 |
| `GET/POST/DELETE /api/ai-configs` | AI 配置管理 |
| `GET /api/tasks/{id}` | 查询异步任务进度 |

---

## 👤 默认账号

| 账号 | 密码 | 角色 |
|------|------|------|
| admin | admin123 | 管理员 |

> ⚠️ **生产环境请立即修改默认密码！**

---

## 📊 当前开发进度

| 模块 | 后端 | 前端 | 说明 |
|------|------|------|------|
| 用户认证 | ✅ 完成 | ✅ 完成 | JWT 鉴权，登录/注册沉浸式页面 |
| 项目管理 | ✅ 完成 | ✅ 完成 | 列表/详情/创建/删除 |
| 剧本生成 | ✅ 完成 | ✅ 完成 | AI 异步生成 + 在线编辑 |
| 分镜制作 | ✅ 完成 | ✅ 完成 | AI 拆解 + 分镜卡片 |
| 角色管理 | ✅ 完成 | ✅ 完成 | AI 生图 + 音色配置 |
| 场景管理 | ✅ 完成 | ✅ 完成 | AI 生成背景图 |
| 素材库 | ✅ 完成 | ✅ 完成 | 上传/筛选/分页 |
| AI 配置 | ✅ 完成 | ✅ 完成 | 多厂商配置 + 默认配置 |
| 视频合成 | 🔄 规划中 | 🔄 规划中 | FFmpeg 自动合成 |
| 成片导出 | 🔄 规划中 | 🔄 规划中 | 竖屏 9:16 成片下载 |
| Docker 部署 | ✅ 完成 | ✅ 完成 | docker-compose 一键启动 |

> **整体进度：核心 8 个主要功能模块已全部完成，视频合成与成片导出模块正在开发中。**

---

## 🗺️ 开发路线图

- [x] v0.1 — 项目初始化（技术栈选型、数据库设计、目录结构）
- [x] v0.2 — 用户系统（注册/登录/JWT）
- [x] v0.3 — 项目 & 剧本管理（CRUD + AI生成）
- [x] v0.4 — 分镜、角色、场景模块
- [x] v0.5 — 素材库 & AI 配置管理
- [x] v0.6 — UI 全面升级（高级感深色导航 + 沉浸式登录页）
- [ ] v0.7 — 视频合成（FFmpeg 单镜头合成 + 整集拼接）
- [ ] v0.8 — 成片导出 & 宫格图生成
- [ ] v0.9 — 云存储支持（OSS/MinIO）
- [ ] v1.0 — 正式发布（性能优化、文档完善）

---

## 🐳 Docker 部署详情

```yaml
services:
  mysql:   # MySQL 8.0 + 自动初始化 SQL
  redis:   # Redis 7.2
  backend: # Spring Boot 后端（:8080）
  frontend: # Vue 3 前端（Nginx, :80）
```

所有服务通过健康检查依次启动，数据通过 Volume 持久化。

---

## 📝 常见问题

**Q: AI 生成很慢或失败？**
A: 检查「AI 配置」页面中的 API Key 是否正确设置，确保网络可访问对应 AI 服务商。

**Q: 如何接入国内 AI 服务？**
A: 在「AI 配置」页面填写对应厂商的 Base URL 和 API Key 即可，平台支持所有 OpenAI 兼容接口。

**Q: 分镜 AI 生图没有效果？**
A: 需先在「AI 配置」中为"文生图"类型添加有效的 API Key 并设为默认。

---

## 📄 开源协议

本项目基于 **MIT 协议**开源。代码为独立原创开发，参考行业最佳实践，未抄袭任何第三方开源项目源码。

---

**⭐ 如果这个项目对你有帮助，请给一个 Star！**

Made with ❤️ by Niren Team
