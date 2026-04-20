-- 倪人短剧 AI 自动化生产平台 - 数据库初始化脚本
-- Database: niren_drama
-- MySQL 8.0+

CREATE DATABASE IF NOT EXISTS niren_drama DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE niren_drama;

-- ===========================
-- 系统用户表
-- ===========================
CREATE TABLE IF NOT EXISTS sys_user (
    id           BIGINT       NOT NULL COMMENT '主键ID',
    username     VARCHAR(50)  NOT NULL COMMENT '用户名',
    password     VARCHAR(255) NOT NULL COMMENT '密码（BCrypt加密）',
    nickname     VARCHAR(100) COMMENT '昵称',
    email        VARCHAR(100) COMMENT '邮箱',
    avatar       VARCHAR(500) COMMENT '头像URL',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0-禁用，1-启用',
    roles        VARCHAR(200) NOT NULL DEFAULT 'USER' COMMENT '角色，逗号分隔：ADMIN,USER',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-正常，1-删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统用户表';

-- ===========================
-- AI 配置表
-- ===========================
CREATE TABLE IF NOT EXISTS sys_ai_config (
    id           BIGINT       NOT NULL COMMENT '主键ID',
    user_id      BIGINT       NOT NULL COMMENT '用户ID',
    config_type  VARCHAR(20)  NOT NULL COMMENT '配置类型：text/image/video/tts',
    provider     VARCHAR(50)  NOT NULL COMMENT '服务商',
    base_url     VARCHAR(500) COMMENT 'API Base URL',
    api_key      VARCHAR(500) COMMENT 'API Key',
    model        VARCHAR(100) COMMENT '模型名称',
    extra        TEXT         COMMENT '扩展配置（JSON）',
    is_default   TINYINT      NOT NULL DEFAULT 0 COMMENT '是否为默认配置',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_user_type (user_id, config_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI服务配置表';

-- ===========================
-- 短剧项目表
-- ===========================
CREATE TABLE IF NOT EXISTS drama_project (
    id               BIGINT       NOT NULL COMMENT '主键ID',
    user_id          BIGINT       NOT NULL COMMENT '创建用户ID',
    name             VARCHAR(200) NOT NULL COMMENT '项目名称',
    description      TEXT         COMMENT '项目描述',
    genre            VARCHAR(50)  COMMENT '题材风格',
    episodes         INT          NOT NULL DEFAULT 1 COMMENT '剧集数量',
    episode_duration INT          NOT NULL DEFAULT 180 COMMENT '单集时长（秒）',
    status           VARCHAR(20)  NOT NULL DEFAULT 'draft' COMMENT '项目状态',
    cover_image      VARCHAR(500) COMMENT '封面图片URL',
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted          TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='短剧项目表';

-- ===========================
-- 剧本表
-- ===========================
CREATE TABLE IF NOT EXISTS drama_script (
    id          BIGINT       NOT NULL COMMENT '主键ID',
    project_id  BIGINT       NOT NULL COMMENT '项目ID',
    episode_no  INT          NOT NULL DEFAULT 1 COMMENT '集数编号',
    title       VARCHAR(200) COMMENT '集标题',
    content     LONGTEXT     COMMENT '剧本全文内容',
    summary     TEXT         COMMENT '剧情摘要',
    status      VARCHAR(20)  NOT NULL DEFAULT 'draft' COMMENT '剧本状态',
    ai_prompt   TEXT         COMMENT 'AI生成所用的提示词',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_project_id (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='剧本表';

-- ===========================
-- 角色表
-- ===========================
CREATE TABLE IF NOT EXISTS drama_character (
    id          BIGINT       NOT NULL COMMENT '主键ID',
    project_id  BIGINT       NOT NULL COMMENT '项目ID',
    name        VARCHAR(100) NOT NULL COMMENT '角色名',
    description TEXT         COMMENT '角色简介',
    personality TEXT         COMMENT '性格描述',
    appearance  TEXT         COMMENT '外貌特征',
    gender      VARCHAR(20)  COMMENT '性别',
    age         VARCHAR(20)  COMMENT '年龄',
    image_url   VARCHAR(500) COMMENT 'AI生成角色图像URL',
    voice_id    VARCHAR(100) COMMENT 'TTS音色ID',
    voice_name  VARCHAR(100) COMMENT 'TTS音色名称',
    sort_order  INT          NOT NULL DEFAULT 0,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_project_id (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色表';

-- ===========================
-- 场景表
-- ===========================
CREATE TABLE IF NOT EXISTS drama_scene (
    id          BIGINT       NOT NULL COMMENT '主键ID',
    project_id  BIGINT       NOT NULL COMMENT '项目ID',
    name        VARCHAR(200) NOT NULL COMMENT '场景名称',
    description TEXT         COMMENT '场景描述',
    time_of_day VARCHAR(20)  COMMENT '时间',
    location    VARCHAR(20)  COMMENT '地点类型',
    image_url   VARCHAR(500) COMMENT '场景背景图URL',
    sort_order  INT          NOT NULL DEFAULT 0,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_project_id (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='场景表';

-- ===========================
-- 分镜脚本表
-- ===========================
CREATE TABLE IF NOT EXISTS drama_storyboard (
    id           BIGINT       NOT NULL COMMENT '主键ID',
    project_id   BIGINT       NOT NULL COMMENT '项目ID',
    script_id    BIGINT       COMMENT '所属剧本ID',
    episode_no   INT          NOT NULL DEFAULT 1 COMMENT '集数编号',
    shot_no      INT          NOT NULL COMMENT '镜头序号',
    description  TEXT         COMMENT '画面描述',
    camera_angle VARCHAR(50)  COMMENT '镜头语言',
    dialogue     TEXT         COMMENT '角色台词',
    narration    TEXT         COMMENT '旁白文字',
    character_id BIGINT       COMMENT '主角色ID',
    scene_id     BIGINT       COMMENT '场景ID',
    duration     INT          NOT NULL DEFAULT 5 COMMENT '镜头时长（秒）',
    image_url    VARCHAR(500) COMMENT '分镜图片URL',
    video_url    VARCHAR(500) COMMENT '分镜视频URL',
    audio_url    VARCHAR(500) COMMENT '配音音频URL',
    image_prompt TEXT         COMMENT 'AI生图提示词',
    video_prompt TEXT         COMMENT '动态镜头视频提示词',
    motion_level VARCHAR(20)  NOT NULL DEFAULT 'low' COMMENT '动态等级：low/medium/high',
    dynamic_recommended TINYINT NOT NULL DEFAULT 0 COMMENT '是否推荐为动态镜头',
    dynamic_selected TINYINT  NOT NULL DEFAULT 0 COMMENT '是否最终选择为动态镜头',
    dynamic_score INT         NOT NULL DEFAULT 0 COMMENT '动态推荐分数 0-100',
    dynamic_reason VARCHAR(500) COMMENT '动态推荐原因',
    render_mode  VARCHAR(20)  NOT NULL DEFAULT 'image' COMMENT '最终渲染模式：image/video',
    status       VARCHAR(30)  NOT NULL DEFAULT 'draft' COMMENT '分镜状态',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_project_id (project_id),
    KEY idx_script_id (script_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分镜脚本表';

ALTER TABLE drama_storyboard
    ADD COLUMN IF NOT EXISTS video_prompt TEXT COMMENT '动态镜头视频提示词' AFTER image_prompt,
    ADD COLUMN IF NOT EXISTS motion_level VARCHAR(20) NOT NULL DEFAULT 'low' COMMENT '动态等级：low/medium/high' AFTER video_prompt,
    ADD COLUMN IF NOT EXISTS dynamic_recommended TINYINT NOT NULL DEFAULT 0 COMMENT '是否推荐为动态镜头' AFTER motion_level,
    ADD COLUMN IF NOT EXISTS dynamic_selected TINYINT NOT NULL DEFAULT 0 COMMENT '是否最终选择为动态镜头' AFTER dynamic_recommended,
    ADD COLUMN IF NOT EXISTS dynamic_score INT NOT NULL DEFAULT 0 COMMENT '动态推荐分数 0-100' AFTER dynamic_selected,
    ADD COLUMN IF NOT EXISTS dynamic_reason VARCHAR(500) COMMENT '动态推荐原因' AFTER dynamic_score,
    ADD COLUMN IF NOT EXISTS render_mode VARCHAR(20) NOT NULL DEFAULT 'image' COMMENT '最终渲染模式：image/video' AFTER dynamic_reason;

-- ===========================
-- 素材资产表
-- ===========================
CREATE TABLE IF NOT EXISTS drama_asset (
    id          BIGINT       NOT NULL COMMENT '主键ID',
    project_id  BIGINT       COMMENT '项目ID',
    user_id     BIGINT       NOT NULL COMMENT '上传用户ID',
    name        VARCHAR(500) COMMENT '文件原始名',
    type        VARCHAR(20)  COMMENT '类型：image/video/audio/other',
    url         VARCHAR(1000) COMMENT '访问URL',
    file_path   VARCHAR(1000) COMMENT '服务器物理路径',
    file_size   BIGINT       COMMENT '文件大小（字节）',
    mime_type   VARCHAR(100) COMMENT 'MIME类型',
    ref_type    VARCHAR(50)  COMMENT '关联类型',
    ref_id      BIGINT       COMMENT '关联ID',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_project_id (project_id),
    KEY idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='素材资产表';

-- ===========================
-- 异步任务记录表
-- ===========================
CREATE TABLE IF NOT EXISTS drama_task_record (
    id          BIGINT       NOT NULL COMMENT '主键ID',
    project_id  BIGINT       COMMENT '项目ID',
    user_id     BIGINT       NOT NULL COMMENT '用户ID',
    task_type   VARCHAR(50)  NOT NULL COMMENT '任务类型',
    status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '任务状态',
    progress    INT          NOT NULL DEFAULT 0 COMMENT '进度 0-100',
    message     VARCHAR(1000) COMMENT '进度消息',
    result      TEXT         COMMENT '任务结果（JSON）',
    ref_id      BIGINT       COMMENT '关联对象ID',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_project_id (project_id),
    KEY idx_user_id (user_id),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='异步任务记录表';

-- ===========================
-- 初始管理员账号（密码: admin123）
-- ===========================
INSERT IGNORE INTO sys_user (id, username, password, nickname, roles, status, deleted)
VALUES (1000000000000000001, 'admin',
        '$2a$10$7JEoNP8gqGBvN8EeJ5b6gO7ZGxMmCKLNVIPz3fvKFjMMfE6LV9bHO',
        '系统管理员', 'ADMIN,USER', 1, 0);
