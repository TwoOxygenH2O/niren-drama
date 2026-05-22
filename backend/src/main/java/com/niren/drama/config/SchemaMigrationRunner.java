package com.niren.drama.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaMigrationRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        ensureColumnExists(
                "drama_project",
                "common_info",
                "ALTER TABLE drama_project ADD COLUMN common_info LONGTEXT COMMENT '项目通用信息（人物小传/世界观/长期设定）' AFTER description"
        );
        ensureColumnExists(
            "drama_project",
            "project_type",
            "ALTER TABLE drama_project ADD COLUMN project_type VARCHAR(50) NOT NULL DEFAULT '真人短剧' COMMENT '项目类型：真人短剧/漫画短剧' AFTER common_info"
        );
        ensureColumnExists(
            "drama_storyboard",
            "video_task_id",
            "ALTER TABLE drama_storyboard ADD COLUMN video_task_id VARCHAR(120) COMMENT '外部视频任务ID' AFTER video_url"
        );
        ensureColumnExists(
            "drama_storyboard",
            "video_task_status_url",
            "ALTER TABLE drama_storyboard ADD COLUMN video_task_status_url VARCHAR(1000) COMMENT '外部视频任务状态查询地址' AFTER video_task_id"
        );
        ensureColumnExists(
            "drama_storyboard",
            "video_task_provider",
            "ALTER TABLE drama_storyboard ADD COLUMN video_task_provider VARCHAR(50) COMMENT '外部视频任务供应商' AFTER video_task_status_url"
        );
        ensureColumnExists(
            "drama_storyboard",
            "video_task_status",
            "ALTER TABLE drama_storyboard ADD COLUMN video_task_status VARCHAR(30) COMMENT '外部视频任务状态' AFTER video_task_provider"
        );
        ensureColumnExists(
            "drama_storyboard",
            "video_task_record_id",
            "ALTER TABLE drama_storyboard ADD COLUMN video_task_record_id BIGINT COMMENT '关联动态视频批任务ID' AFTER video_task_status"
        );
        ensureIndexExists(
            "drama_storyboard",
            "idx_video_task_record_id",
            "ALTER TABLE drama_storyboard ADD INDEX idx_video_task_record_id (video_task_record_id)"
        );
        ensureColumnExists(
            "drama_storyboard",
            "subtitle_text",
            "ALTER TABLE drama_storyboard ADD COLUMN subtitle_text LONGTEXT COMMENT '上屏字幕（可覆盖；空则按规则从对白/旁白派生）' AFTER narration"
        );
        ensureColumnExists(
            "drama_storyboard",
            "tts_text",
            "ALTER TABLE drama_storyboard ADD COLUMN tts_text LONGTEXT COMMENT '配音稿（可覆盖；空则由对白+旁白派生）' AFTER subtitle_text"
        );
        ensureColumnExists(
            "drama_storyboard",
            "user_locked_subtitle",
            "ALTER TABLE drama_storyboard ADD COLUMN user_locked_subtitle TINYINT(1) NOT NULL DEFAULT 0 COMMENT '用户是否锁定上屏字幕' AFTER tts_text"
        );
        ensureColumnExists(
            "drama_storyboard",
            "user_locked_tts",
            "ALTER TABLE drama_storyboard ADD COLUMN user_locked_tts TINYINT(1) NOT NULL DEFAULT 0 COMMENT '用户是否锁定配音稿' AFTER user_locked_subtitle"
        );
        ensureColumnExists(
                "drama_storyboard",
                "motion_tier",
                "ALTER TABLE drama_storyboard ADD COLUMN motion_tier VARCHAR(10) NOT NULL DEFAULT 'C' COMMENT '镜头动效分档：A(i2v)/B(增强静帧)/C(基线)'"
        );
        ensureColumnExists(
                "drama_storyboard",
                "motion_tier_reason",
                "ALTER TABLE drama_storyboard ADD COLUMN motion_tier_reason VARCHAR(500) COMMENT '镜头分档原因' AFTER motion_tier"
        );
        ensureColumnExists(
            "drama_character",
            "speech_rate",
            "ALTER TABLE drama_character ADD COLUMN speech_rate INT COMMENT 'TTS语速，100=1.0x，可空' AFTER voice_name"
        );
        ensureColumnExists(
            "drama_character",
            "tts_note",
            "ALTER TABLE drama_character ADD COLUMN tts_note VARCHAR(500) COMMENT 'TTS导演补充（并入 instruction）' AFTER speech_rate"
        );
        ensureColumnExists(
            "drama_character",
            "image_urls",
            "ALTER TABLE drama_character ADD COLUMN image_urls TEXT COMMENT '角色多形象URL列表（JSON数组）' AFTER image_url"
        );
        ensureColumnTypeContains(
                "drama_task_record",
                "result",
                "longtext",
                "ALTER TABLE drama_task_record MODIFY COLUMN result LONGTEXT COMMENT '任务结果（JSON）'"
        );
    }

    private void ensureColumnExists(String tableName, String columnName, String ddl) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class,
                tableName,
                columnName
        );

        if (count != null && count > 0) {
            return;
        }

        log.info("执行数据库迁移: 新增字段 {}.{}", tableName, columnName);
        jdbcTemplate.execute(ddl);
    }

    private void ensureIndexExists(String tableName, String indexName, String ddl) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                Integer.class,
                tableName,
                indexName
        );

        if (count != null && count > 0) {
            return;
        }

        log.info("执行数据库迁移: 在 {} 新增索引 {}", tableName, indexName);
        jdbcTemplate.execute(ddl);
    }

    private void ensureColumnTypeContains(String tableName, String columnName, String expectedTypeKeyword, String ddl) {
        String dataType = jdbcTemplate.queryForObject(
                "SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ? LIMIT 1",
                String.class,
                tableName,
                columnName
        );
        if (dataType != null && dataType.toLowerCase().contains(expectedTypeKeyword.toLowerCase())) {
            return;
        }
        log.info("执行数据库迁移: 修正字段类型 {}.{} -> {}", tableName, columnName, expectedTypeKeyword);
        jdbcTemplate.execute(ddl);
    }
}