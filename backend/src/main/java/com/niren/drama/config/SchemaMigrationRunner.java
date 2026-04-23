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

        log.info("Applying schema migration: add {}.{}", tableName, columnName);
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

        log.info("Applying schema migration: add index {} on {}", indexName, tableName);
        jdbcTemplate.execute(ddl);
    }
}