# 泥人短剧 · 部署文档

本文档详细说明如何在 **Linux** 和 **Windows** 环境下部署泥人短剧 AI 自动化短剧生产平台。

---

## 目录

- [系统要求](#系统要求)
- [项目结构](#项目结构)
- [环境变量说明](#环境变量说明)
- [Linux 部署](#linux-部署)
  - [方式一：Docker Compose（推荐）](#linux-方式一docker-compose推荐)
  - [方式二：手动部署](#linux-方式二手动部署)
- [Windows 部署](#windows-部署)
  - [方式一：Docker Desktop](#windows-方式一docker-desktop)
  - [方式二：手动部署](#windows-方式二手动部署)
- [AI 服务配置](#ai-服务配置)
- [常见问题](#常见问题)

---

## 系统要求

| 组件 | 最低要求 | 推荐配置 |
|------|----------|----------|
| CPU | 2 核 | 4 核+ |
| 内存 | 4 GB | 8 GB+ |
| 磁盘 | 20 GB | 50 GB+（视频文件需要较大空间） |
| Java | JDK 17+ | JDK 17 |
| Node.js | 18+ | 20 LTS |
| MySQL | 8.0+ | 8.0 |
| Redis | 6.0+ | 7.x |
| FFmpeg | 5.0+ | 6.x+ |
| Docker | 20.10+（Docker 部署时） | 24.x |
| Docker Compose | v2.0+（Docker 部署时） | v2.20+ |

---

## 项目结构

```
niren-drama/
├── backend/                 # Spring Boot 后端
│   ├── src/                 # Java 源码
│   ├── pom.xml             # Maven 配置
│   └── Dockerfile          # 后端 Docker 镜像
├── frontend/               # Vue 3 前端
│   ├── src/                # TypeScript/Vue 源码
│   ├── package.json        # Node.js 依赖
│   ├── nginx.conf          # Nginx 配置
│   └── Dockerfile          # 前端 Docker 镜像
├── docker-compose.yml      # Docker Compose 编排
├── DEPLOYMENT.md           # 本部署文档
└── DEVELOPMENT_PLAN.md     # 开发计划文档
```

---

## 环境变量说明

以下环境变量可在 `.env` 文件或系统环境中设置：

| 变量名 | 说明 | 默认值 |
|--------|------|--------|
| `DB_HOST` | MySQL 主机地址 | `localhost` |
| `DB_PORT` | MySQL 端口 | `3306` |
| `DB_NAME` | 数据库名 | `niren_drama` |
| `DB_USER` | 数据库用户名 | `root` |
| `DB_PASSWORD` | 数据库密码 | `NirenDrama2024!` |
| `REDIS_HOST` | Redis 主机地址 | `localhost` |
| `REDIS_PORT` | Redis 端口 | `6379` |
| `REDIS_PASSWORD` | Redis 密码 | （空） |
| `JWT_SECRET` | JWT 签名密钥 | 内置默认值（**生产环境必须修改**） |
| `UPLOAD_PATH` | 文件上传存储路径 | `./uploads` |
| `BASE_URL` | 后端公网访问地址 | `http://localhost:8080` |
| `FFMPEG_PATH` | FFmpeg 可执行文件路径 | `ffmpeg` |
| `AI_TEXT_API_KEY` | 文本 AI API Key（OpenAI 兼容） | （空） |
| `AI_TEXT_BASE_URL` | 文本 AI API 地址 | `https://api.openai.com/v1` |
| `AI_TEXT_MODEL` | 文本 AI 模型 | `gpt-4o` |
| `AI_IMAGE_API_KEY` | 图像 AI API Key | （空） |
| `AI_IMAGE_BASE_URL` | 图像 AI API 地址 | `https://api.openai.com/v1` |
| `AI_IMAGE_MODEL` | 图像 AI 模型 | `dall-e-3` |
| `AI_TTS_API_KEY` | TTS API Key | （空） |
| `AI_TTS_BASE_URL` | TTS API 地址 | `https://api.openai.com/v1` |

> ⚠️ **安全提醒**：生产环境中，`JWT_SECRET` 和 `DB_PASSWORD` 必须使用强密码，且 API Key 不应提交到版本控制系统。

---

## Linux 部署

### Linux 方式一：Docker Compose（推荐）

这是最简单的部署方式，一条命令即可启动所有服务。

#### 1. 安装 Docker 和 Docker Compose

```bash
# Ubuntu / Debian
sudo apt update
sudo apt install -y docker.io docker-compose-plugin

# CentOS / RHEL
sudo yum install -y docker docker-compose-plugin

# 启动 Docker 服务
sudo systemctl enable docker
sudo systemctl start docker

# 将当前用户加入 docker 组（免 sudo）
sudo usermod -aG docker $USER
# 注意：需要重新登录终端才能生效
```

#### 2. 克隆项目

```bash
git clone https://github.com/TwoOxygenH2O/niren-drama.git
cd niren-drama
```

#### 3. 配置环境变量

```bash
# 创建环境变量文件
cat > .env << 'EOF'
# 数据库配置
DB_PASSWORD=YourStrongPassword123!

# JWT 密钥（请修改为随机长字符串）
JWT_SECRET=your-very-long-and-random-jwt-secret-key-2024

# 服务地址（改为你的服务器IP或域名）
BASE_URL=http://your-server-ip:8080

# AI 配置（填入你的 API Key）
AI_TEXT_API_KEY=sk-your-openai-api-key
AI_TEXT_BASE_URL=https://api.openai.com/v1
AI_TEXT_MODEL=gpt-4o

AI_IMAGE_API_KEY=sk-your-openai-api-key
AI_IMAGE_BASE_URL=https://api.openai.com/v1
AI_IMAGE_MODEL=dall-e-3
EOF
```

#### 4. 启动服务

```bash
# 构建并启动所有服务（后台运行）
docker compose up -d --build

# 查看服务状态
docker compose ps

# 查看后端日志
docker compose logs -f backend

# 查看所有日志
docker compose logs -f
```

#### 5. 访问服务

- **前端页面**：`http://your-server-ip:80`
- **后端 API**：`http://your-server-ip:8080/api`
- **API 文档**：`http://your-server-ip:8080/api/doc.html`
- **默认管理员**：用户名 `admin`，密码 `admin123`

#### 6. 停止/重启服务

```bash
# 停止所有服务
docker compose down

# 重启所有服务
docker compose restart

# 重新构建并启动（代码更新后）
docker compose up -d --build
```

---

### Linux 方式二：手动部署

适用于不使用 Docker 的场景，或需要更精细控制的情况。

#### 1. 安装基础依赖

```bash
# Ubuntu / Debian
sudo apt update
sudo apt install -y openjdk-17-jdk maven nodejs npm mysql-server redis-server ffmpeg git

# 安装 Node.js 20（推荐使用 nvm）
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.7/install.sh | bash
source ~/.bashrc
nvm install 20
nvm use 20

# CentOS / RHEL
sudo yum install -y java-17-openjdk java-17-openjdk-devel maven nodejs npm mysql-server redis ffmpeg git
```

#### 2. 配置 MySQL

```bash
# 启动 MySQL
sudo systemctl enable mysql
sudo systemctl start mysql

# 登录 MySQL 创建数据库
sudo mysql -u root << 'SQL'
ALTER USER 'root'@'localhost' IDENTIFIED WITH mysql_native_password BY 'YourStrongPassword123!';
FLUSH PRIVILEGES;
SQL

# 执行初始化脚本
mysql -u root -p'YourStrongPassword123!' < backend/src/main/resources/db/init.sql
```

#### 3. 配置 Redis

```bash
sudo systemctl enable redis
sudo systemctl start redis
```

#### 4. 构建后端

```bash
cd backend

# 设置环境变量
export DB_HOST=localhost
export DB_PASSWORD='YourStrongPassword123!'
export JWT_SECRET='your-very-long-and-random-jwt-secret-key-2024'
export UPLOAD_PATH=/opt/niren-drama/uploads
export BASE_URL=http://your-server-ip:8080
export AI_TEXT_API_KEY=sk-your-openai-api-key
export FFMPEG_PATH=ffmpeg

# 创建上传目录
sudo mkdir -p /opt/niren-drama/uploads
sudo chown $USER:$USER /opt/niren-drama/uploads

# 构建
mvn clean package -DskipTests

# 启动后端（后台运行）
nohup java -jar target/niren-drama-1.0.0.jar > /opt/niren-drama/backend.log 2>&1 &
echo $! > /opt/niren-drama/backend.pid

cd ..
```

#### 5. 构建前端

```bash
cd frontend

# 安装依赖
npm install

# 构建生产版本
npm run build

cd ..
```

#### 6. 配置 Nginx

```bash
# 安装 Nginx
sudo apt install -y nginx  # Ubuntu/Debian
# sudo yum install -y nginx  # CentOS

# 创建 Nginx 配置
sudo tee /etc/nginx/sites-available/niren-drama << 'EOF'
server {
    listen 80;
    server_name your-domain.com;  # 改为你的域名或IP

    root /opt/niren-drama/frontend;
    index index.html;

    # SPA 路由
    location / {
        try_files $uri $uri/ /index.html;
    }

    # 反向代理后端 API
    location /api {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_read_timeout 180s;
        client_max_body_size 500m;
    }

    # 静态资源缓存
    location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$ {
        expires 30d;
        add_header Cache-Control "public, no-transform";
    }

    gzip on;
    gzip_types text/plain text/css application/json application/javascript text/xml application/xml;
    gzip_min_length 1000;
}
EOF

# 复制前端构建产物
sudo mkdir -p /opt/niren-drama/frontend
sudo cp -r frontend/dist/* /opt/niren-drama/frontend/

# 启用站点
sudo ln -sf /etc/nginx/sites-available/niren-drama /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default

# 测试配置并重载
sudo nginx -t
sudo systemctl reload nginx
```

#### 7. 创建 Systemd 服务（可选）

```bash
sudo tee /etc/systemd/system/niren-drama.service << 'EOF'
[Unit]
Description=Niren Drama Backend
After=mysql.service redis.service
Wants=mysql.service redis.service

[Service]
Type=simple
User=niren
WorkingDirectory=/opt/niren-drama
ExecStart=/usr/bin/java -jar /opt/niren-drama/niren-drama-1.0.0.jar
Restart=on-failure
RestartSec=10

Environment=DB_HOST=localhost
Environment=DB_PASSWORD=YourStrongPassword123!
Environment=JWT_SECRET=your-very-long-and-random-jwt-secret-key-2024
Environment=UPLOAD_PATH=/opt/niren-drama/uploads
Environment=BASE_URL=http://your-server-ip:8080
Environment=FFMPEG_PATH=ffmpeg
Environment=AI_TEXT_API_KEY=sk-your-openai-api-key

[Install]
WantedBy=multi-user.target
EOF

# 复制 jar 包
sudo cp backend/target/niren-drama-1.0.0.jar /opt/niren-drama/

# 创建专用用户
sudo useradd -r -s /sbin/nologin niren
sudo chown -R niren:niren /opt/niren-drama

# 启用并启动服务
sudo systemctl daemon-reload
sudo systemctl enable niren-drama
sudo systemctl start niren-drama

# 查看状态
sudo systemctl status niren-drama
```

---

## Windows 部署

### Windows 方式一：Docker Desktop

#### 1. 安装 Docker Desktop

1. 下载 [Docker Desktop for Windows](https://www.docker.com/products/docker-desktop/)
2. 安装并启动 Docker Desktop
3. 确保 WSL 2 后端已启用（Docker Desktop 安装时会提示）

#### 2. 克隆项目

```powershell
git clone https://github.com/TwoOxygenH2O/niren-drama.git
cd niren-drama
```

#### 3. 配置环境变量

在项目根目录创建 `.env` 文件：

```
DB_PASSWORD=YourStrongPassword123!
JWT_SECRET=your-very-long-and-random-jwt-secret-key-2024
BASE_URL=http://localhost:8080
AI_TEXT_API_KEY=sk-your-openai-api-key
AI_TEXT_BASE_URL=https://api.openai.com/v1
AI_TEXT_MODEL=gpt-4o
AI_IMAGE_API_KEY=sk-your-openai-api-key
AI_IMAGE_BASE_URL=https://api.openai.com/v1
AI_IMAGE_MODEL=dall-e-3
```

#### 4. 启动服务

```powershell
# 构建并启动
docker compose up -d --build

# 查看状态
docker compose ps

# 查看日志
docker compose logs -f backend
```

#### 5. 访问服务

- **前端页面**：`http://localhost`
- **后端 API**：`http://localhost:8080/api`
- **API 文档**：`http://localhost:8080/api/doc.html`
- **默认管理员**：用户名 `admin`，密码 `admin123`

---

### Windows 方式二：手动部署

#### 1. 安装依赖软件

分别下载并安装：

| 软件 | 下载地址 | 说明 |
|------|----------|------|
| JDK 17 | [Eclipse Temurin](https://adoptium.net/) | 安装时勾选添加到 PATH |
| Maven | [Apache Maven](https://maven.apache.org/download.cgi) | 解压后添加 `bin` 目录到 PATH |
| Node.js 20 | [Node.js](https://nodejs.org/) | LTS 版本，安装时自动添加 PATH |
| MySQL 8.0 | [MySQL Community](https://dev.mysql.com/downloads/mysql/) | 安装时设置 root 密码 |
| Redis | [Redis for Windows](https://github.com/tporadowski/redis/releases) | 解压即用，或使用 Memurai |
| FFmpeg | [FFmpeg Builds](https://www.gyan.dev/ffmpeg/builds/) | 下载 release full 版，解压后添加 `bin` 到 PATH |
| Git | [Git for Windows](https://git-scm.com/download/win) | 安装时使用默认选项 |

#### 2. 验证安装

打开 PowerShell 或命令提示符验证：

```powershell
java -version      # 应显示 17.x
mvn -version       # 应显示 Maven 版本
node -v            # 应显示 v20.x
npm -v             # 应显示 npm 版本
ffmpeg -version    # 应显示 FFmpeg 版本
mysql --version    # 应显示 MySQL 版本
git --version      # 应显示 Git 版本
```

#### 3. 配置 MySQL

```powershell
# 登录 MySQL 并执行初始化脚本
mysql -u root -p
```

在 MySQL 命令行中执行：

```sql
source C:/path/to/niren-drama/backend/src/main/resources/db/init.sql;
```

#### 4. 启动 Redis

```powershell
# 如果使用 tporadowski/redis
redis-server
```

#### 5. 构建并启动后端

```powershell
cd backend

# 设置环境变量
$env:DB_HOST = "localhost"
$env:DB_PASSWORD = "YourStrongPassword123!"
$env:JWT_SECRET = "your-very-long-and-random-jwt-secret-key-2024"
$env:UPLOAD_PATH = "C:\niren-drama\uploads"
$env:BASE_URL = "http://localhost:8080"
$env:AI_TEXT_API_KEY = "sk-your-openai-api-key"
$env:FFMPEG_PATH = "ffmpeg"

# 创建上传目录
New-Item -ItemType Directory -Force -Path "C:\niren-drama\uploads"

# 构建
mvn clean package -DskipTests

# 启动后端
java -jar target\niren-drama-1.0.0.jar

cd ..
```

#### 6. 构建并运行前端

打开**新的 PowerShell 窗口**：

```powershell
cd frontend

# 安装依赖
npm install

# 开发模式运行（开发调试时使用）
npm run dev

# 或构建生产版本
npm run build
```

开发模式下前端默认运行在 `http://localhost:5173`。

#### 7. 生产环境前端（使用 Nginx）

1. 下载 [Nginx for Windows](https://nginx.org/en/download.html)
2. 解压到 `C:\nginx`
3. 复制 `frontend/dist` 目录内容到 `C:\nginx\html`
4. 编辑 `C:\nginx\conf\nginx.conf`：

```nginx
server {
    listen       80;
    server_name  localhost;

    root   html;
    index  index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    location /api {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 180s;
        client_max_body_size 500m;
    }

    gzip on;
    gzip_types text/plain text/css application/json application/javascript;
}
```

5. 启动 Nginx：

```powershell
cd C:\nginx
start nginx
```

---

## AI 服务配置

平台支持通过用户界面动态配置 AI 服务，也可以通过环境变量设置默认配置。

### 支持的 AI 服务

| 类型 | 服务 | 说明 |
|------|------|------|
| 文本生成 | OpenAI / 豆包 / 通义千问 / 其他兼容 API | 用于剧本生成和分镜拆解 |
| 图像生成 | OpenAI DALL-E / MidJourney API / 其他 | 用于分镜画面和角色/场景图片 |
| TTS 语音 | OpenAI TTS / 火山引擎 / MiniMax | 用于配音生成 |
| 视频合成 | FFmpeg（本地） | 用于最终视频合成 |

### 通过 Web 界面配置

1. 登录系统
2. 点击左侧菜单「AI 配置」
3. 添加对应类型（text/image/tts）的配置
4. 填入 API 地址、API Key、模型名称
5. 设置为默认配置

### 使用国内 AI 服务

如果使用豆包（火山引擎）、通义千问等国内服务，只需配置对应的 Base URL：

| 服务 | Base URL 示例 |
|------|---------------|
| 豆包 (Doubao) | `https://ark.cn-beijing.volces.com/api/v3` |
| 通义千问 (Qwen) | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| 文心一言 (Wenxin) | 使用百度千帆平台兼容接口 |

---

## 常见问题

### 1. Docker 构建失败，Maven 下载超时

如果在国内网络环境，可能需要配置 Maven 镜像。在 `backend/pom.xml` 中添加阿里云仓库：

```xml
<repositories>
    <repository>
        <id>aliyun</id>
        <url>https://maven.aliyun.com/repository/public</url>
    </repository>
</repositories>
```

### 2. npm install 失败

```bash
# 使用淘宝镜像
npm config set registry https://registry.npmmirror.com
npm install
```

### 3. FFmpeg 未安装或找不到

确保 `ffmpeg` 在系统 PATH 中，或设置环境变量 `FFMPEG_PATH` 为完整路径：

```bash
# Linux
export FFMPEG_PATH=/usr/bin/ffmpeg

# Windows
$env:FFMPEG_PATH = "C:\ffmpeg\bin\ffmpeg.exe"
```

### 4. 上传文件失败或视频生成后无法访问

检查上传目录权限和 `BASE_URL` 配置：

```bash
# Linux
chmod -R 755 /opt/niren-drama/uploads

# 确保 BASE_URL 与实际访问地址一致
export BASE_URL=http://your-actual-ip-or-domain:8080
```

### 5. 视频合成报错 "FFmpeg exited with code"

- 检查 FFmpeg 是否正确安装：`ffmpeg -version`
- 检查磁盘空间是否充足
- 查看后端日志获取详细错误信息：
  ```bash
  docker compose logs backend | grep FFmpeg
  ```

### 6. 数据库连接失败

确保 MySQL 服务已启动，且用户名密码正确：

```bash
# 测试连接
mysql -h localhost -u root -p -e "SHOW DATABASES;"
```

### 7. AI API 调用失败

- 确认 API Key 有效且有足够额度
- 检查 Base URL 是否正确（注意不要多余的斜杠）
- 查看后端日志中的详细错误信息

---

## 更新升级

```bash
# 拉取最新代码
git pull origin main

# Docker 方式：重新构建并启动
docker compose up -d --build

# 手动方式：重新构建
cd backend && mvn clean package -DskipTests && cd ..
cd frontend && npm install && npm run build && cd ..
# 重启后端服务
```

---

## 数据备份

```bash
# 备份数据库
docker compose exec mysql mysqldump -u root -p niren_drama > backup_$(date +%Y%m%d).sql

# 备份上传的素材文件
tar czf uploads_backup_$(date +%Y%m%d).tar.gz uploads/

# 恢复数据库
docker compose exec -i mysql mysql -u root -p niren_drama < backup_20240101.sql
```
