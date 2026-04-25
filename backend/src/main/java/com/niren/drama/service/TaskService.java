package com.niren.drama.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.niren.drama.entity.TaskRecord;
import com.niren.drama.exception.BusinessException;
import com.niren.drama.mapper.TaskRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskRecordMapper taskRecordMapper;
    private final ObjectMapper objectMapper;

    public TaskRecord getTask(Long id) {
        TaskRecord task = taskRecordMapper.selectById(id);
        if (task == null) throw new BusinessException("任务不存在");
        enrichTaskDiagnostics(task);
        return task;
    }

    public List<TaskRecord> listByProject(Long projectId) {
        List<TaskRecord> tasks = taskRecordMapper.selectList(new LambdaQueryWrapper<TaskRecord>()
                .eq(TaskRecord::getProjectId, projectId)
                .orderByDesc(TaskRecord::getCreateTime));
        tasks.forEach(this::enrichTaskDiagnosticsLight);
        return tasks;
    }

    public List<TaskRecord> listByUser(Long userId) {
        List<TaskRecord> tasks = taskRecordMapper.selectList(new LambdaQueryWrapper<TaskRecord>()
                .eq(TaskRecord::getUserId, userId)
                .orderByDesc(TaskRecord::getCreateTime)
                .last("LIMIT 50"));
        tasks.forEach(this::enrichTaskDiagnosticsLight);
        return tasks;
    }

    private void enrichTaskDiagnosticsLight(TaskRecord task) {
        task.setTotalElapsedMs(resolveElapsedMs(task));
    }

    private void enrichTaskDiagnostics(TaskRecord task) {
        task.setTotalElapsedMs(resolveElapsedMs(task));
        if (task.getTotalElapsedMs() == null) {
            return;
        }
        Map<String, Long> stepDurationMs = new LinkedHashMap<>();
        Map<String, Integer> failureDist = new LinkedHashMap<>();
        int externalCalls = 0;
        int externalErrors = 0;
        int localCalls = 0;
        try {
            JsonNode root = objectMapper.readTree(task.getResult());
            JsonNode calls = root.path("calls");
            if (calls.isArray()) {
                Map<String, Integer> actionCounter = new LinkedHashMap<>();
                for (JsonNode call : calls) {
                    String url = call.path("url").asText("");
                    String action = call.path("action").asText("unknown");
                    actionCounter.merge(action, 1, Integer::sum);
                    boolean external = url.startsWith("http://") || url.startsWith("https://");
                    if (external) {
                        externalCalls++;
                    } else {
                        localCalls++;
                    }
                    boolean failed = isFailedCall(call);
                    if (failed) {
                        String type = classifyFailure(call);
                        failureDist.merge(type, 1, Integer::sum);
                        if (external) {
                            externalErrors++;
                        }
                    }
                }
                int totalCount = Math.max(1, externalCalls + localCalls);
                for (Map.Entry<String, Integer> entry : actionCounter.entrySet()) {
                    long estimated = Math.round(task.getTotalElapsedMs() * (entry.getValue() * 1.0d / totalCount));
                    stepDurationMs.put(entry.getKey(), Math.max(1L, estimated));
                }
            }
        } catch (Exception ignored) {
            // task.result may be plain URL or non-JSON
        }
        if (stepDurationMs.isEmpty()) {
            stepDurationMs.put(task.getTaskType() != null ? task.getTaskType() : "task", task.getTotalElapsedMs());
        }
        task.setStepDurationMs(stepDurationMs);
        task.setFailureTypeDistribution(failureDist);
        task.setExternalApiCallCount(externalCalls);
        task.setExternalApiErrorRatio(externalCalls <= 0 ? 0d : externalErrors * 1.0d / externalCalls);
    }

    private Long resolveElapsedMs(TaskRecord task) {
        LocalDateTime start = task.getCreateTime();
        if (start == null) {
            return null;
        }
        LocalDateTime end = task.getUpdateTime() != null ? task.getUpdateTime() : LocalDateTime.now();
        return Math.max(0L, Duration.between(start, end).toMillis());
    }

    private boolean isFailedCall(JsonNode call) {
        if (call.path("success").isBoolean()) {
            return !call.path("success").asBoolean();
        }
        int status = call.path("statusCode").asInt(0);
        if (status >= 400) {
            return true;
        }
        return call.path("error").isTextual() && !call.path("error").asText("").isBlank();
    }

    private String classifyFailure(JsonNode call) {
        String text = (call.path("error").asText("") + " " + call.path("responseBody").asText(""))
                .toLowerCase(Locale.ROOT);
        int status = call.path("statusCode").asInt(0);
        if (status >= 500 || text.contains("internal_server_error")) return "provider_5xx";
        if (status >= 400) return "provider_4xx";
        if (text.contains("timeout") || text.contains("timed out")) return "timeout";
        if (text.contains("connection reset") || text.contains("disconnected") || text.contains("network")) return "network";
        if (text.contains("parse") || text.contains("json")) return "parse";
        if (text.contains("data too long") || text.contains("sql")) return "db";
        return "unknown";
    }
}
