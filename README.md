# 倪人短剧 · AI 短剧自动化生产平台

> 全栈 AI 短剧自动化生产平台，实现「一句话创意→剧本生成→分镜拆解→AI生图/生视频→配音字幕→自动合成成片」全流程闭环。

## 技术栈

### 前端
| 技术 | 版本 | 用途 |
|------|------|------|
| Vue 3 | 3.4+ | 前端主体框架 |
| TypeScript | 5.0+ | 类型安全 |
| Vite | 5.0+ | 构建工具 |
| Element Plus | 最新 | UI 组件库 |
| Pinia | 最新 | 全局状态管理 |
| Vue Router | 4+ | 路由管理 |
| Axios | 最新 | HTTP 客户端 |
| video.js | 最新 | 视频预览 |
| fabric.js | 最新 | 分镜可视化编辑（进阶） |

### 后端
| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.2+ | 后端主体框架 |
| JDK | 17 | 运行环境 |
| MyBatis-Plus | 3.5+ | ORM 框架 |
| MySQL | 8.0+ | 关系型数据库 |
| Redis | 7.0+ | 缓存与任务状态 |
| Spring Security + JWT | - | 权限认证 |
| Knife4j (Swagger) | 4.4+ | 接口文档 |
| Spring @Async | - | 异步任务处理 |
| Hutool | 5.8+ | 工具类库 |
| Jackson | - | JSON 处理 |

## 全流程 8 个核心节点

```
创意一句话
    ↓
① 创建项目（题材/集数/时长）
    ↓
② AI 生成剧本（一键生成 or 导入）
    ↓
③ AI 分镜拆解（场景/台词/镜头语言）
    ↓
④ 角色与场景素材生成（AI 生成角色肖像 + 场景背景）
    ↓
⑤ 分镜画面生成（AI 逐镜头生图）
    ↓
⑥ 配音生成（TTS 角色配音 + 旁白）
    ↓
⑦ 视频自动合成（FFmpeg 拼接 + 字幕烧录 + BGM）
    ↓
⑧ 成片导出（竖屏 9:16，多格式，可一键发布）
```

## 项目结构

```
niren-drama/
├── backend/                    # Spring Boot 3 后端
│   ├── src/
│   │   └── main/java/com/niren/drama/
│   │       ├── ai/             # AI 提供商抽象层
│   │       │   ├── TextAiProvider.java
│   │       │   ├── ImageAiProvider.java
│   │       │   ├── TtsProvider.java
│   │       │   ├── AiProviderFactory.java
│   │       │   └── impl/       # OpenAI / Mock 实现
│   │       ├── config/         # 配置类（MybatisPlus/Async/Swagger/WebMvc）
│   │       ├── controller/     # REST 控制器
│   │       ├── dto/            # 请求/响应 DTO
│   │       ├── entity/         # 数据库实体
│   │       ├── exception/      # 全局异常处理
│   │       ├── mapper/         # MyBatis-Plus Mapper
│   │       ├── security/       # JWT + Spring Security
│   │       └── service/        # 业务服务层
│   └── src/main/resources/
│       ├── application.yml     # 配置文件
│       └── db/init.sql         # 数据库初始化 SQL
├── frontend/                   # Vue 3 + TypeScript 前端
│   └── src/
│       ├── api/                # Axios 接口层
│       ├── components/         # 公共组件
│       │   └── layout/         # 主布局
│       ├── router/             # Vue Router（带权限拦截）
│       ├── stores/             # Pinia 状态管理
│       └── views/              # 页面视图
│           ├── auth/           # 登录/注册
│           ├── project/        # 项目管理
│           ├── script/         # 剧本生成
│           ├── storyboard/     # 分镜制作
│           ├── character/      # 角色管理
│           ├── scene/          # 场景管理
│           ├── asset/          # 素材库
│           └── settings/       # AI 配置
├── docker-compose.yml          # 容器编排
└── README.md
```

## 快速启动

### 方式一：Docker Compose（推荐）

```bash
# 1. 克隆项目
git clone https://github.com/TwoOxygenH2O/niren-drama.git
cd niren-drama

# 2. 配置环境变量（可选，有默认值）
cp .env.example .env
# 修改 .env 中的 AI API Key 等配置

# 3. 一键启动
docker-compose up -d

# 访问前端：http://localhost
# 访问后端 API 文档：http://localhost:8080/api/doc.html
```

### 方式二：本地开发

**前提：** JDK 17、Maven 3.8+、Node 18+、MySQL 8.0+、Redis 7.0+

**后端：**
```bash
cd backend
# 初始化数据库
mysql -u root -p < src/main/resources/db/init.sql

# 修改 application.yml 中的数据库/Redis/AI 配置，或使用环境变量：
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
# 前端启动于 http://localhost:5173
```

## 环境变量说明

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `DB_HOST` | localhost | MySQL 地址 |
| `DB_PASSWORD` | root | MySQL 密码 |
| `REDIS_HOST` | localhost | Redis 地址 |
| `JWT_SECRET` | *(内置)* | JWT 签名密钥（生产环境必须修改） |
| `AI_TEXT_API_KEY` | *(空)* | 文本 AI API Key |
| `AI_TEXT_MODEL` | gpt-4o | 文本模型名称 |
| `AI_TEXT_BASE_URL` | OpenAI 官方 | 支持任何 OpenAI 兼容接口 |
| `AI_IMAGE_API_KEY` | *(空)* | 图像 AI API Key |
| `UPLOAD_PATH` | ./uploads | 文件上传目录 |

## AI 兼容层

平台实现了统一的 AI 服务接口，支持通过配置切换：

- **文本大模型：** OpenAI GPT 系列 / 火山引擎豆包 / 阿里通义千问 / 百度文心一言（所有 OpenAI 兼容接口均可接入）
- **文生图：** OpenAI DALL-E 3 / Stable Diffusion / 通义万相（通过 `AI_IMAGE_PROVIDER` 切换）
- **TTS 语音：** 火山引擎语音 / 阿里云语音（当前内置多款音色 Mock，接入真实 TTS 需实现 `TtsProvider` 接口）
- **文生视频：** 可灵 AI / 即梦 AI / Runway（通过扩展 `VideoAiProvider` 接口接入）

## 默认账号

| 账号 | 密码 | 角色 |
|------|------|------|
| admin | admin123 | 管理员 |

> ⚠️ 生产环境请立即修改默认密码。

## API 文档

启动后端后访问：`http://localhost:8080/api/doc.html`

主要接口模块：
- `/api/auth` — 登录/注册/用户信息
- `/api/projects` — 项目管理
- `/api/scripts` — 剧本生成与编辑
- `/api/storyboards` — 分镜生成与管理
- `/api/characters` — 角色管理与 AI 生图
- `/api/scenes` — 场景管理与 AI 生图
- `/api/assets` — 素材上传与管理
- `/api/ai-configs` — AI 服务商配置
- `/api/tasks` — 异步任务进度查询

## 开源协议

本项目基于 MIT 协议开源。代码为独立原创开发，参考业界最佳实践，未抄袭任何第三方开源项目源码。
