package com.niren.drama.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.niren.drama.ai.AiProviderFactory;
import com.niren.drama.ai.TextAiProvider;
import com.niren.drama.common.PageQuery;
import com.niren.drama.dto.script.ScriptGenerateRequest;
import com.niren.drama.entity.Script;
import com.niren.drama.entity.TaskRecord;
import com.niren.drama.exception.BusinessException;
import com.niren.drama.mapper.ScriptMapper;
import com.niren.drama.mapper.TaskRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptService {

    private final ScriptMapper scriptMapper;
    private final TaskRecordMapper taskRecordMapper;
    private final AiProviderFactory aiProviderFactory;

    public TaskRecord startGenerateScript(Long userId, ScriptGenerateRequest request) {
        TaskRecord task = new TaskRecord();
        task.setProjectId(request.getProjectId());
        task.setUserId(userId);
        task.setTaskType("SCRIPT_GEN");
        task.setStatus("PENDING");
        task.setProgress(0);
        task.setMessage("任务已提交，等待执行...");
        taskRecordMapper.insert(task);
        generateScriptAsync(userId, request, task.getId());
        return task;
    }

    @Async("aiTaskExecutor")
    public void generateScriptAsync(Long userId, ScriptGenerateRequest request, Long taskId) {
        TaskRecord task = taskRecordMapper.selectById(taskId);
        if (task == null) return;
        try {
            updateTask(task, "RUNNING", 10, "开始生成剧本...");

            TextAiProvider textProvider = aiProviderFactory.getTextProvider(userId);
            String systemPrompt = buildScriptSystemPrompt(request.getGenre(), request.getStyle());
            String userPrompt = buildScriptUserPrompt(request);

            updateTask(task, "RUNNING", 30, "AI正在生成剧本内容...");
            String scriptContent = textProvider.chat(systemPrompt, userPrompt);

            updateTask(task, "RUNNING", 80, "保存剧本内容...");
            Script script = new Script();
            script.setProjectId(request.getProjectId());
            script.setEpisodeNo(request.getEpisodeNo() != null ? request.getEpisodeNo() : 1);
            script.setContent(scriptContent);
            script.setAiPrompt(request.getIdea());
            script.setStatus("ai_generated");
            script.setTitle("第" + script.getEpisodeNo() + "集");
            scriptMapper.insert(script);

            task.setStatus("SUCCESS");
            task.setProgress(100);
            task.setMessage("剧本生成完成");
            task.setResult(String.valueOf(script.getId()));
            taskRecordMapper.updateById(task);

        } catch (Exception e) {
            log.error("Script generation failed for task {}", taskId, e);
            task.setStatus("FAILED");
            task.setMessage("剧本生成失败: " + e.getMessage());
            taskRecordMapper.updateById(task);
        }
    }

    public Script getScript(Long id) {
        Script script = scriptMapper.selectById(id);
        if (script == null) throw new BusinessException("剧本不存在");
        return script;
    }

    public List<Script> listByProject(Long projectId) {
        return scriptMapper.selectList(new LambdaQueryWrapper<Script>()
                .eq(Script::getProjectId, projectId)
                .orderByAsc(Script::getEpisodeNo));
    }

    public Script updateScript(Long id, String content, String title) {
        Script script = getScript(id);
        if (content != null) script.setContent(content);
        if (title != null) script.setTitle(title);
        script.setStatus("reviewed");
        scriptMapper.updateById(script);
        return script;
    }

    public void deleteScript(Long id) {
        scriptMapper.deleteById(id);
    }

    private void updateTask(TaskRecord task, String status, int progress, String message) {
        task.setStatus(status);
        task.setProgress(progress);
        task.setMessage(message);
        taskRecordMapper.updateById(task);
    }

    private String buildScriptSystemPrompt(String genre, String style) {
        return String.format("""
                你是一位专业的短剧编剧，擅长创作适合短视频平台的竖屏短剧。
                你的剧本需要满足以下要求：
                1. 节奏紧凑，开头30秒内必须有强烈冲突或悬念
                2. 对话简洁有力，符合年轻观众口味
                3. 每集包含15-20个分镜场景，时长约3-5分钟
                4. 格式规范：场景编号、角色名、对话/旁白分明
                5. 适合竖屏（9:16）拍摄构图
                题材风格：%s %s
                """, genre != null ? genre : "都市言情", style != null ? style : "");
    }

    private String buildScriptUserPrompt(ScriptGenerateRequest request) {
        return String.format("""
                请基于以下创意，创作一集完整的短剧剧本：
                
                创意描述：%s
                
                剧本格式要求：
                【第X集】标题
                
                第X场 [场景名称/时间/地点]
                （场景描述）
                
                角色名：（动作）"对白"
                
                旁白：旁白内容
                
                请生成完整剧本，包含至少15个分镜场景。
                """, request.getIdea());
    }
}
