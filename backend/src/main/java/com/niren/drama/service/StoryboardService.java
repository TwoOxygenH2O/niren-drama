package com.niren.drama.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.niren.drama.ai.AiProviderFactory;
import com.niren.drama.ai.TextAiProvider;
import com.niren.drama.dto.storyboard.StoryboardGenerateRequest;
import com.niren.drama.entity.Script;
import com.niren.drama.entity.Storyboard;
import com.niren.drama.entity.TaskRecord;
import com.niren.drama.exception.BusinessException;
import com.niren.drama.mapper.ScriptMapper;
import com.niren.drama.mapper.StoryboardMapper;
import com.niren.drama.mapper.TaskRecordMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StoryboardService {

    private final StoryboardMapper storyboardMapper;
    private final ScriptMapper scriptMapper;
    private final TaskRecordMapper taskRecordMapper;
    private final AiProviderFactory aiProviderFactory;
    private final ObjectMapper objectMapper;

    public TaskRecord startGenerateStoryboard(Long userId, StoryboardGenerateRequest request) {
        TaskRecord task = new TaskRecord();
        task.setProjectId(request.getProjectId());
        task.setUserId(userId);
        task.setTaskType("STORYBOARD_GEN");
        task.setStatus("PENDING");
        task.setProgress(0);
        task.setMessage("任务已提交，等待执行...");
        task.setRefId(request.getScriptId());
        taskRecordMapper.insert(task);
        generateStoryboardAsync(userId, request, task.getId());
        return task;
    }

    @Async("aiTaskExecutor")
    public void generateStoryboardAsync(Long userId, StoryboardGenerateRequest request, Long taskId) {
        TaskRecord task = taskRecordMapper.selectById(taskId);
        if (task == null) return;
        try {
            updateTask(task, "RUNNING", 10, "读取剧本内容...");
            Script script = scriptMapper.selectById(request.getScriptId());
            if (script == null) throw new BusinessException("剧本不存在");

            updateTask(task, "RUNNING", 30, "AI正在拆解分镜脚本...");
            TextAiProvider textProvider = aiProviderFactory.getTextProvider(userId);
            String systemPrompt = buildStoryboardSystemPrompt();
            String userPrompt = buildStoryboardUserPrompt(script.getContent());
            String storyboardJson = textProvider.chat(systemPrompt, userPrompt);

            updateTask(task, "RUNNING", 70, "保存分镜数据...");
            List<Storyboard> shots = parseStoryboardJson(storyboardJson, request);
            for (Storyboard shot : shots) {
                storyboardMapper.insert(shot);
            }

            task.setStatus("SUCCESS");
            task.setProgress(100);
            task.setMessage(String.format("分镜生成完成，共%d个镜头", shots.size()));
            taskRecordMapper.updateById(task);

        } catch (Exception e) {
            log.error("Storyboard generation failed for task {}", taskId, e);
            task.setStatus("FAILED");
            task.setMessage("分镜生成失败: " + e.getMessage());
            taskRecordMapper.updateById(task);
        }
    }

    public List<Storyboard> listByProject(Long projectId) {
        return storyboardMapper.selectList(new LambdaQueryWrapper<Storyboard>()
                .eq(Storyboard::getProjectId, projectId)
                .orderByAsc(Storyboard::getEpisodeNo)
                .orderByAsc(Storyboard::getShotNo));
    }

    public List<Storyboard> listByScript(Long scriptId) {
        return storyboardMapper.selectList(new LambdaQueryWrapper<Storyboard>()
                .eq(Storyboard::getScriptId, scriptId)
                .orderByAsc(Storyboard::getShotNo));
    }

    public Storyboard getStoryboard(Long id) {
        Storyboard s = storyboardMapper.selectById(id);
        if (s == null) throw new BusinessException("分镜不存在");
        return s;
    }

    public Storyboard updateStoryboard(Long id, Storyboard update) {
        Storyboard storyboard = getStoryboard(id);
        if (update.getDescription() != null) storyboard.setDescription(update.getDescription());
        if (update.getDialogue() != null) storyboard.setDialogue(update.getDialogue());
        if (update.getNarration() != null) storyboard.setNarration(update.getNarration());
        if (update.getCameraAngle() != null) storyboard.setCameraAngle(update.getCameraAngle());
        if (update.getDuration() != null) storyboard.setDuration(update.getDuration());
        if (update.getImagePrompt() != null) storyboard.setImagePrompt(update.getImagePrompt());
        storyboardMapper.updateById(storyboard);
        return storyboard;
    }

    private void updateTask(TaskRecord task, String status, int progress, String message) {
        task.setStatus(status);
        task.setProgress(progress);
        task.setMessage(message);
        taskRecordMapper.updateById(task);
    }

    private String buildStoryboardSystemPrompt() {
        return """
                你是一位专业的分镜导演，擅长将剧本拆解为精确的分镜脚本。
                请将剧本拆解为JSON格式的分镜列表，每个镜头包含以下字段：
                - shotNo: 镜头序号（从1开始）
                - description: 画面描述（用于AI生图的详细描述）
                - cameraAngle: 镜头语言（close-up/medium/wide/overhead/pov）
                - dialogue: 角色台词（如有）
                - narration: 旁白（如有）
                - duration: 镜头时长（秒，3-8秒）
                - characterName: 主要角色名（如有）
                - sceneName: 场景名称
                
                返回格式：{"shots": [...]}
                """;
    }

    private String buildStoryboardUserPrompt(String scriptContent) {
        return String.format("""
                请将以下剧本拆解为详细分镜脚本，以JSON格式返回：
                
                %s
                
                注意：画面描述需要足够详细，便于AI生成图片。
                """, scriptContent);
    }

    private List<Storyboard> parseStoryboardJson(String json, StoryboardGenerateRequest request) {
        List<Storyboard> shots = new ArrayList<>();
        try {
            // Extract JSON block from response
            String cleanJson = json;
            int start = json.indexOf("{");
            int end = json.lastIndexOf("}");
            if (start >= 0 && end > start) {
                cleanJson = json.substring(start, end + 1);
            }
            JsonNode root = objectMapper.readTree(cleanJson);
            JsonNode shotsNode = root.path("shots");
            int shotNo = 1;
            for (JsonNode shotNode : shotsNode) {
                Storyboard shot = new Storyboard();
                shot.setProjectId(request.getProjectId());
                shot.setScriptId(request.getScriptId());
                shot.setEpisodeNo(1);
                shot.setShotNo(shotNo++);
                shot.setDescription(shotNode.path("description").asText());
                shot.setCameraAngle(shotNode.path("cameraAngle").asText("medium"));
                shot.setDialogue(shotNode.path("dialogue").asText(null));
                shot.setNarration(shotNode.path("narration").asText(null));
                shot.setDuration(shotNode.path("duration").asInt(5));
                shot.setStatus("draft");
                // Build image prompt from description
                shot.setImagePrompt(buildImagePrompt(shot));
                shots.add(shot);
            }
        } catch (Exception e) {
            log.warn("Failed to parse storyboard JSON, creating placeholder shots. Error: {}", e.getMessage());
            // Create a placeholder shot if parsing fails
            Storyboard placeholder = new Storyboard();
            placeholder.setProjectId(request.getProjectId());
            placeholder.setScriptId(request.getScriptId());
            placeholder.setEpisodeNo(1);
            placeholder.setShotNo(1);
            placeholder.setDescription("AI生成的分镜脚本（解析失败，请手动编辑）");
            placeholder.setDuration(5);
            placeholder.setStatus("draft");
            shots.add(placeholder);
        }
        return shots;
    }

    private String buildImagePrompt(Storyboard shot) {
        return String.format("垂直构图9:16，%s，镜头：%s，%s风格，电影质感，高清4K",
                shot.getDescription(),
                shot.getCameraAngle() != null ? shot.getCameraAngle() : "medium shot",
                "现代都市");
    }
}
