package com.niren.drama.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.niren.drama.ai.AiProviderFactory;
import com.niren.drama.ai.TextAiProvider;
import com.niren.drama.dto.immersive.ImmersiveDirectorChatRequest;
import com.niren.drama.dto.immersive.ImmersiveDirectorChatResponse;
import com.niren.drama.dto.script.OutlinePreviewRepairRequest;
import com.niren.drama.dto.script.ScriptGenerateRequest;
import com.niren.drama.entity.Project;
import com.niren.drama.entity.Script;
import com.niren.drama.entity.TaskRecord;
import com.niren.drama.exception.BusinessException;
import com.niren.drama.mapper.TaskRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImmersiveDirectorService {

    private final ProjectService projectService;
    private final ScriptService scriptService;
    private final StoryboardService storyboardService;
    private final AiProviderFactory aiProviderFactory;
    private final ObjectMapper objectMapper;
    private final CharacterService characterService;
    private final TaskRecordMapper taskRecordMapper;

    public ImmersiveDirectorChatResponse chat(Long userId, ImmersiveDirectorChatRequest request) {
        Long projectId = request.getProjectId();
        Project project = projectService.getProject(userId, projectId);
        int episodeNo = request.getEpisodeNo() != null && request.getEpisodeNo() > 0 ? request.getEpisodeNo() : 1;
        String phase = StringUtils.defaultIfBlank(request.getWorkflowPhase(), "plan_ready").trim().toLowerCase(Locale.ROOT);
        String userMessage = StringUtils.trimToEmpty(request.getMessage());
        if (userMessage.isBlank()) {
            throw new BusinessException("消息不能为空");
        }

        List<Script> scripts = scriptService.listByProject(projectId);
        Script currentScript = scripts.stream()
                .filter(s -> s.getEpisodeNo() != null && s.getEpisodeNo().equals(episodeNo))
                .findFirst()
                .orElse(null);

        StringBuilder ctx = new StringBuilder();
        ctx.append("项目名称：").append(StringUtils.defaultString(project.getName())).append('\n');
        ctx.append("项目类型：").append(StringUtils.defaultString(project.getProjectType())).append('\n');
        ctx.append("题材风格：").append(StringUtils.defaultString(project.getGenre())).append('\n');
        String ci = StringUtils.trimToEmpty(project.getCommonInfo());
        if (ci.length() > 4000) {
            ci = ci.substring(0, 4000) + "\n…（已截断）";
        }
        ctx.append("项目通用信息（节选）：\n").append(ci).append("\n\n");

        if (currentScript != null) {
            ctx.append("当前第 ").append(episodeNo).append(" 集剧本标题：")
                    .append(StringUtils.defaultString(currentScript.getTitle())).append('\n');
            String content = StringUtils.trimToEmpty(currentScript.getContent());
            if (content.length() > 6000) {
                content = content.substring(0, 6000) + "\n…（已截断）";
            }
            ctx.append("本集剧本正文（节选）：\n").append(content).append("\n\n");
        } else {
            ctx.append("当前第 ").append(episodeNo).append(" 集尚无已入库的剧本正文。\n\n");
        }

        if ("outline".equals(phase) && StringUtils.isNotBlank(request.getOutlineContent())) {
            String oc = request.getOutlineContent();
            if (oc.length() > 12000) {
                oc = oc.substring(0, 12000) + "\n…（已截断）";
            }
            ctx.append("当前全剧大纲草稿：\n").append(oc).append("\n\n");
        }

        String systemPrompt = buildDirectorSystemPrompt(phase);
        String userPrompt = ctx + "\n当前创作阶段 workflowPhase=" + phase
                + "\n\n用户指令：\n" + userMessage;

        TextAiProvider provider = aiProviderFactory.getTextProvider(userId);
        String raw = provider.chat(systemPrompt, userPrompt);
        DirectorDecision decision = parseDirectorDecision(raw);

        ImmersiveDirectorChatResponse resp = new ImmersiveDirectorChatResponse();
        resp.setReply(decision.reply());
        resp.setAction(decision.action());

        switch (decision.action()) {
            case "REGENERATE_STORYBOARD" -> handleRegenerateStoryboard(userId, projectId, currentScript, decision, resp);
            case "REGENERATE_SCRIPT" -> handleRegenerateScript(userId, projectId, episodeNo, userMessage, decision, resp);
            case "REPAIR_OUTLINE" -> handleRepairOutline(userId, request, phase, decision, resp);
            case "REGENERATE_CHARACTER" -> handleRegenerateCharacter(userId, projectId, decision, resp);
            default -> { /* NONE */ }
        }
        return resp;
    }

    private void handleRegenerateStoryboard(Long userId,
                                            Long projectId,
                                            Script currentScript,
                                            DirectorDecision decision,
                                            ImmersiveDirectorChatResponse resp) {
        if (currentScript == null || StringUtils.isBlank(currentScript.getContent())) {
            resp.setReply("当前集暂无剧本正文，无法拆解分镜。请先完成本集剧本或使用「重写本集剧本」类指令。");
            resp.setAction("NONE");
            return;
        }
        TaskRecord task = storyboardService.startRegenerateStoryboard(userId, projectId, currentScript.getId());
        resp.setTaskId(task.getId());
        resp.setTaskType(task.getTaskType());
        resp.setReply(decision.reply() + "\n\n已提交重新拆解本集分镜任务，完成后将更新数据库中的镜头表。");
    }

    private void handleRegenerateScript(Long userId,
                                        Long projectId,
                                        int episodeNo,
                                        String userMessage,
                                        DirectorDecision decision,
                                        ImmersiveDirectorChatResponse resp) {
        ScriptGenerateRequest gen = new ScriptGenerateRequest();
        gen.setProjectId(projectId);
        gen.setEpisodeNo(episodeNo);
        gen.setIdea(userMessage);
        TaskRecord task = scriptService.startGenerateScript(userId, gen);
        resp.setTaskId(task.getId());
        resp.setTaskType(task.getTaskType());
        resp.setReply(decision.reply() + "\n\n已提交重写第 " + episodeNo + " 集剧本任务。");
    }

    private void handleRepairOutline(Long userId,
                                     ImmersiveDirectorChatRequest request,
                                     String phase,
                                     DirectorDecision decision,
                                     ImmersiveDirectorChatResponse resp) {
        if (!"outline".equals(phase) || StringUtils.isBlank(request.getOutlineContent())) {
            resp.setReply("当前不在大纲编辑阶段或未携带大纲正文，无法执行大纲修复。请在有大纲预览时使用本功能。");
            resp.setAction("NONE");
            return;
        }
        OutlinePreviewRepairRequest rr = new OutlinePreviewRepairRequest();
        rr.setProjectId(request.getProjectId());
        rr.setContent(request.getOutlineContent());
        rr.setIdea(request.getMessage());
        Map<String, Object> repaired = scriptService.repairOutlinePreview(userId, rr);
        Object content = repaired.get("content");
        if (content != null) {
            resp.setOutlineContent(String.valueOf(content));
        }
        resp.setReply(decision.reply() + "\n\n已根据你的说明尝试调整大纲预览。");
    }

    private String buildDirectorSystemPrompt(String phase) {
        return """
                你是「泥人剧场」沉浸式创作助手，负责理解创作者的自然语言指令。
                你必须结合上下文中给出的项目基本信息、剧本节选与用户输入，判断下一步最合适的「动作」。

                仅允许输出一段 JSON（不要 Markdown、不要代码围栏），格式严格如下：
                {"reply":"给用户的中文回复，简短清晰","action":"NONE"}

                action 只能是以下之一（大写英文）：
                - NONE：闲聊、解释、建议、无法执行、或与当前阶段不匹配的操作；仅回复文字。
                - REGENERATE_STORYBOARD：用户明确要求重新生成、拆解、刷新、重做「本集分镜」「镜头表」「分镜脚本」等（针对当前集剧本）。
                - REGENERATE_SCRIPT：用户明确要求重写、改写、重新生成「本集剧本正文」「剧本内容」等（不是大纲）。
                - REPAIR_OUTLINE：用户希望在大纲阶段根据说明「修改、补充、调整全剧大纲预览」；仅当 workflowPhase 为 outline 且上下文中包含大纲正文时可选。
                - REGENERATE_CHARACTER：用户明确要求重新生成、刷新、重做「角色」「人物」「主体列表」等（适用于 plan_ready 或后续阶段，针对全项目角色库，将从项目通用信息中重新提取角色档案）。

                规则：
                1) 若用户明确要求重新生成角色（如输入"重新生成角色"等关键词），且 workflowPhase 为 plan_ready 或后续阶段，必须选择 REGENERATE_CHARACTER。不要因当前阶段而拒绝。
                2) 若当前阶段为 outline，且用户只是在讨论剧情走向而未要求改大纲，action 用 NONE。
                3) 若 workflowPhase 不是 outline，不要选择 REPAIR_OUTLINE。
                4) 若本集没有剧本正文却要求分镜，选择 NONE，并在 reply 中说明需先有剧本。
                5) reply 与 action 必须一致，不要承诺无法在系统中自动完成的操作。

                当前前端阶段代号："""
                + phase
                + """
                """;
    }

    private DirectorDecision parseDirectorDecision(String raw) {
        String s = StringUtils.trimToEmpty(raw);
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) {
                s = s.substring(nl + 1);
            }
            int end = s.lastIndexOf("```");
            if (end > 0) {
                s = s.substring(0, end).trim();
            }
        }
        try {
            JsonNode n = objectMapper.readTree(s);
            String reply = n.path("reply").asText("好的。");
            String action = n.path("action").asText("NONE").trim().toUpperCase(Locale.ROOT);
            if (!isAllowedAction(action)) {
                action = "NONE";
            }
            return new DirectorDecision(reply, action);
        } catch (Exception e) {
            log.warn("导演助手输出非 JSON，按纯文本处理: {}", e.getMessage());
            return new DirectorDecision(StringUtils.isNotBlank(s) ? s : "模型未返回可解析结果。", "NONE");
        }
    }

    private boolean isAllowedAction(String action) {
        return "NONE".equals(action)
                || "REGENERATE_STORYBOARD".equals(action)
                || "REGENERATE_SCRIPT".equals(action)
                || "REPAIR_OUTLINE".equals(action)
                || "REGENERATE_CHARACTER".equals(action);
    }

    private void handleRegenerateCharacter(Long userId,
                                           Long projectId,
                                           DirectorDecision decision,
                                           ImmersiveDirectorChatResponse resp) {
        Project project = projectService.getProject(userId, projectId);
        String commonInfo = StringUtils.trimToNull(project.getCommonInfo());
        if (commonInfo == null) {
            resp.setReply("项目暂无通用信息（commonInfo），无法提取角色。请先完成大纲确认。");
            resp.setAction("NONE");
            return;
        }

        // 先创建 PENDING 任务
        TaskRecord task = new TaskRecord();
        task.setProjectId(projectId);
        task.setUserId(userId);
        task.setTaskType("CHARACTER_REGENERATE");
        task.setStatus("PENDING");
        task.setProgress(0);
        task.setMessage("正在重新生成角色…");
        taskRecordMapper.insert(task);

        resp.setTaskId(task.getId());
        resp.setTaskType(task.getTaskType());

        try {
            characterService.deleteByProject(projectId);
            scriptService.syncCharactersFromCommonInfo(userId, projectId, commonInfo);

            task.setStatus("SUCCESS");
            task.setProgress(100);
            task.setMessage("角色已重新生成完毕");
            taskRecordMapper.updateById(task);

            resp.setReply(decision.reply() + "\n\n已重新生成角色列表，请在右侧策划栏查看。系统将自动生成角色形象图片。");
        } catch (Exception e) {
            log.error("角色重新生成失败: projectId={}", projectId, e);
            task.setStatus("FAILED");
            task.setMessage("角色重新生成失败: " + e.getMessage());
            taskRecordMapper.updateById(task);

            resp.setReply("角色重新生成失败：" + e.getMessage());
            resp.setAction("NONE");
        }
    }

    private record DirectorDecision(String reply, String action) {}
}
