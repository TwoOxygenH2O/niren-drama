package com.niren.drama.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.niren.drama.ai.AiProviderFactory;
import com.niren.drama.ai.ChatMessage;
import com.niren.drama.ai.TextAiProvider;
import com.niren.drama.common.ProjectStyleSupport;
import com.niren.drama.dto.script.BatchScriptPreviewSaveRequest;
import com.niren.drama.dto.script.OutlinePreviewRepairRequest;
import com.niren.drama.dto.script.OutlinePreviewSaveRequest;
import com.niren.drama.entity.Character;
import com.niren.drama.dto.script.ScriptGenerateRequest;
import com.niren.drama.dto.script.ScriptSaveRequest;
import com.niren.drama.entity.Project;
import com.niren.drama.entity.Script;
import com.niren.drama.entity.TaskRecord;
import com.niren.drama.exception.BusinessException;
import com.niren.drama.mapper.CharacterMapper;
import com.niren.drama.mapper.ScriptMapper;
import com.niren.drama.mapper.TaskRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptService {

    private static final int OUTLINE_CHUNK_SIZE = 4;
    private static final int OUTLINE_CHUNK_MIN = 2;
    private static final int OUTLINE_CHUNK_RETRY_ATTEMPTS = 3;
    private static final int COMMON_INFO_RETRY_ATTEMPTS = 2;
    private static final int CHARACTER_EXTRACTION_RETRY_ATTEMPTS = 3;
    private static final Pattern EPISODE_SCRIPT_PATTERN = Pattern.compile("###EPISODE_START:(\\d+)###\\s*(.*?)\\s*###EPISODE_END###", Pattern.DOTALL);
    private static final Pattern EPISODE_OUTLINE_START_PATTERN = Pattern.compile("###EPISODE_OUTLINE_START:(\\d+)###");
    private static final Pattern EPISODE_OUTLINE_PATTERN = Pattern.compile("###EPISODE_OUTLINE_START:(\\d+)###\\s*(.*?)\\s*###EPISODE_OUTLINE_END###", Pattern.DOTALL);
    private static final Pattern EPISODE_OUTLINE_HEADING_PATTERN = Pattern.compile("(?m)^(?:#{1,6}\\s*)?(?:【|\\[)?\\s*第\\s*(\\d+)\\s*集\\s*(?:】|\\])?\\s*([^\\r\\n]*)$");
    private static final Pattern PROJECT_COMMON_INFO_PATTERN = Pattern.compile("###PROJECT_COMMON_INFO_START###\\s*(.*?)\\s*###PROJECT_COMMON_INFO_END###", Pattern.DOTALL);
    private static final Pattern OFFICIAL_PROJECT_NAME_PATTERN = Pattern.compile("(?m)^\\s*官方项目名称[：:]\\s*(.+?)\\s*$");
    private static final Pattern TITLE_PATTERN = Pattern.compile("^标题[：:]\\s*(.+)$", Pattern.MULTILINE);

    private final ScriptMapper scriptMapper;
    private final CharacterMapper characterMapper;
    private final TaskRecordMapper taskRecordMapper;
    private final AiProviderFactory aiProviderFactory;
    private final ProjectService projectService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<ScriptService> selfProvider;

    public TaskRecord startGenerateOutline(Long userId, ScriptGenerateRequest request) {
        Project project = projectService.getProject(userId, request.getProjectId());
        int totalEpisodes = resolveTotalEpisodes(project, request);
        resolveCreativeSeed(project, request, true);
        log.debug("创建大纲任务: userId={}, projectId={}, totalEpisodes={}",
            userId, request.getProjectId(), totalEpisodes);

        TaskRecord task = new TaskRecord();
        task.setProjectId(request.getProjectId());
        task.setUserId(userId);
        task.setTaskType("SCRIPT_OUTLINE_GEN");
        task.setStatus("PENDING");
        task.setProgress(0);
        task.setMessage(String.format("生成 1-%d 集分集大纲与人物小传的任务已提交...", totalEpisodes));
        taskRecordMapper.insert(task);

        request.setTotalEpisodes(totalEpisodes);
        selfProvider.getObject().generateOutlineAsync(userId, request, task.getId());
        return task;
    }

    public TaskRecord startGenerateScript(Long userId, ScriptGenerateRequest request) {
        Project project = projectService.getProject(userId, request.getProjectId());
        GenerationPlan plan = resolveGenerationPlan(project, request);
        request.setEpisodeNo(plan.singleEpisode());
        request.setTotalEpisodes(plan.totalEpisodes());
        log.debug("创建单集剧本任务: userId={}, projectId={}, episodeNo={}, totalEpisodes={}",
            userId, request.getProjectId(), plan.singleEpisode(), plan.totalEpisodes());

        TaskRecord task = new TaskRecord();
        task.setProjectId(request.getProjectId());
        task.setUserId(userId);
        task.setTaskType("SCRIPT_GEN");
        task.setStatus("PENDING");
        task.setProgress(0);
        task.setMessage(String.format("第 %d 集剧本生成任务已提交...", plan.singleEpisode()));
        taskRecordMapper.insert(task);
        selfProvider.getObject().generateScriptAsync(userId, request, task.getId());
        return task;
    }

    /**
     * Batch generate scripts for multiple episodes in one task.
     */
    public TaskRecord startBatchGenerateScript(Long userId, ScriptGenerateRequest request) {
        Project project = projectService.getProject(userId, request.getProjectId());
        GenerationPlan plan = resolveGenerationPlan(project, request);
        request.setStartEpisode(plan.startEpisode());
        request.setEndEpisode(plan.endEpisode());
        request.setTotalEpisodes(plan.totalEpisodes());
        log.debug("创建批量剧本任务: userId={}, projectId={}, startEpisode={}, endEpisode={}, totalEpisodes={}",
            userId, request.getProjectId(), plan.startEpisode(), plan.endEpisode(), plan.totalEpisodes());

        int batchCount = plan.endEpisode() - plan.startEpisode() + 1;
        TaskRecord task = new TaskRecord();
        task.setProjectId(request.getProjectId());
        task.setUserId(userId);
        task.setTaskType("SCRIPT_BATCH_GEN");
        task.setStatus("PENDING");
        task.setProgress(0);
        task.setMessage(String.format("批量生成第 %d-%d 集剧本，任务已提交...", plan.startEpisode(), plan.endEpisode()));
        taskRecordMapper.insert(task);
        selfProvider.getObject().batchGenerateScriptAsync(userId, request, task.getId(), batchCount);
        return task;
    }

    public void streamGenerateOutline(Long userId, ScriptGenerateRequest request, Consumer<String> chunkConsumer) {
        Project project = projectService.getProject(userId, request.getProjectId());
        int totalEpisodes = resolveTotalEpisodes(project, request);
        int episodeDuration = resolveEpisodeDuration(project);
        log.debug("开始流式生成大纲: userId={}, projectId={}, totalEpisodes={}, episodeDuration={}",
            userId, request.getProjectId(), totalEpisodes, episodeDuration);

        TextAiProvider textProvider = aiProviderFactory.getTextProvider(userId);
        String systemPrompt = buildOutlineSystemPrompt(project, resolveGenre(project, request), request.getStyle());

        String commonInfo = streamProjectCommonInfoWithRetry(
            textProvider,
            systemPrompt,
            project,
            request,
            totalEpisodes,
            episodeDuration,
            chunkConsumer);
        applyAiGeneratedProjectName(userId, project, commonInfo);
        log.debug("大纲流式通用信息已就绪: projectId={}, commonInfoLength={}",
                request.getProjectId(), commonInfo.length());

        int chunkSize = resolveAdaptiveOutlineChunkSize(request, totalEpisodes, episodeDuration);
        for (int chunkStart = 1; chunkStart <= totalEpisodes; chunkStart += chunkSize) {
            int chunkEnd = Math.min(totalEpisodes, chunkStart + chunkSize - 1);
            log.debug("大纲流式分片开始: projectId={}, startEpisode={}, endEpisode={}",
                    request.getProjectId(), chunkStart, chunkEnd);
            chunkConsumer.accept("\n\n");
            streamEpisodeOutlineChunkWithRetry(
                textProvider,
                systemPrompt,
                project,
                request,
                commonInfo,
                chunkStart,
                chunkEnd,
                totalEpisodes,
                episodeDuration,
                chunkConsumer);
            log.debug("大纲流式分片完成: projectId={}, startEpisode={}, endEpisode={}",
                    request.getProjectId(), chunkStart, chunkEnd);
        }
    }

    /**
     * 项目通用信息：优先使用模型 token 流式输出；解析失败时重试（重试段一次性下发）。
     */
    private String streamProjectCommonInfoWithRetry(TextAiProvider textProvider,
                                                    String systemPrompt,
                                                    Project project,
                                                    ScriptGenerateRequest request,
                                                    int totalEpisodes,
                                                    int episodeDuration,
                                                    Consumer<String> chunkConsumer) {
        String prompt = buildProjectCommonInfoUserPrompt(project, request, totalEpisodes, episodeDuration);
        String lastFull = null;
        BusinessException lastException = null;

        for (int attempt = 1; attempt <= COMMON_INFO_RETRY_ATTEMPTS; attempt++) {
            log.debug("流式生成通用信息尝试: projectId={}, attempt={}", project.getId(), attempt);
            if (attempt > 1) {
                chunkConsumer.accept("\n\n—— 正在重试生成项目通用信息… ——\n\n");
            }

            StringBuilder acc = new StringBuilder();
            if (attempt == 1) {
                textProvider.streamChatWithHistory(
                    systemPrompt,
                    Collections.singletonList(new ChatMessage("user", prompt)),
                    chunk -> {
                        acc.append(chunk);
                        chunkConsumer.accept(chunk);
                    });
            } else {
                String response = textProvider.chat(systemPrompt, prompt);
                chunkConsumer.accept(response);
                acc.append(response);
            }

            lastFull = acc.toString();
            try {
                String commonInfo = extractProjectCommonInfo(lastFull);
                log.debug("流式通用信息解析成功: projectId={}, length={}", project.getId(), commonInfo.length());
                return commonInfo;
            } catch (BusinessException ex) {
                lastException = ex;
                log.warn("项目通用信息流式解析失败: attempt={}, responseLength={}, message={}",
                    attempt,
                    lastFull != null ? lastFull.length() : 0,
                    ex.getMessage());
            }
        }

        String fallback = StringUtils.trimToNull(extractProjectCommonInfoForPrompt(lastFull));
        if (fallback != null) {
            return fallback;
        }
        if (lastException != null) {
            throw lastException;
        }
        throw new BusinessException("项目通用信息生成失败：AI 未返回有效内容");
    }

    /**
     * 分集大纲：首遍使用 token 流式输出；解析失败时回退到原有同步生成并追加格式化结果。
     */
    private void streamEpisodeOutlineChunkWithRetry(TextAiProvider textProvider,
                                                    String systemPrompt,
                                                    Project project,
                                                    ScriptGenerateRequest request,
                                                    String commonInfo,
                                                    int startEpisode,
                                                    int endEpisode,
                                                    int totalEpisodes,
                                                    int episodeDuration,
                                                    Consumer<String> chunkConsumer) {
        String prompt = buildEpisodeOutlineUserPrompt(
            project, request, commonInfo, startEpisode, endEpisode, totalEpisodes, episodeDuration);
        StringBuilder acc = new StringBuilder();
        try {
            textProvider.streamChatWithHistory(
                systemPrompt,
                Collections.singletonList(new ChatMessage("user", prompt)),
                chunk -> {
                    acc.append(chunk);
                    chunkConsumer.accept(chunk);
                });
            String response = acc.toString();
            parseEpisodeOutlines(response, startEpisode, endEpisode);
        } catch (Exception ex) {
            log.warn("流式分集大纲解析失败或异常，回退同步生成: projectId={}, range={}-{}, message={}",
                project.getId(), startEpisode, endEpisode, ex.getMessage());
            chunkConsumer.accept("\n\n—— 正在重试本分集大纲… ——\n\n");
            Map<Integer, EpisodeOutline> repaired = generateEpisodeOutlineChunk(
                textProvider,
                systemPrompt,
                project,
                request,
                commonInfo,
                startEpisode,
                endEpisode,
                totalEpisodes,
                episodeDuration);
            chunkConsumer.accept(formatEpisodeOutlineBlocks(repaired, startEpisode, endEpisode));
        }
    }

    public void saveOutlinePreview(Long userId, OutlinePreviewSaveRequest request) {
        Project project = projectService.getProject(userId, request.getProjectId());
        int totalEpisodes = resolveTotalEpisodes(project, new ScriptGenerateRequest());
        String previewContent = StringUtils.trimToEmpty(request.getContent());
        if (previewContent.isBlank()) {
            throw new BusinessException("大纲预览内容不能为空");
        }
        log.debug("保存大纲预览: userId={}, projectId={}, previewLength={}, totalEpisodes={}",
            userId, request.getProjectId(), previewContent.length(), totalEpisodes);

        String commonInfo = extractProjectCommonInfoFromPreview(previewContent);
        Map<Integer, EpisodeOutline> outlineMap = parseEpisodeOutlinesFromPreview(previewContent, totalEpisodes);
        log.debug("大纲预览解析完成: projectId={}, commonInfoLength={}, outlineCount={}",
            request.getProjectId(), StringUtils.length(commonInfo), outlineMap.size());
        if (StringUtils.isNotBlank(commonInfo)) {
            applyAiGeneratedProjectName(userId, project, commonInfo);
            projectService.updateCommonInfo(userId, project.getId(), commonInfo);
        }
        syncCharactersFromCommonInfo(userId, project.getId(), StringUtils.defaultIfBlank(commonInfo, project.getCommonInfo()));

        for (int episodeNo = 1; episodeNo <= totalEpisodes; episodeNo++) {
            EpisodeOutline outline = outlineMap.get(episodeNo);
            if (outline == null) {
                throw new BusinessException("第 " + episodeNo + " 集大纲缺失");
            }
            upsertEpisodeOutline(project.getId(), episodeNo, outline, request.getIdea());
        }
    }

    public Map<String, Object> repairOutlinePreview(Long userId, OutlinePreviewRepairRequest request) {
        Project project = projectService.getProject(userId, request.getProjectId());
        int totalEpisodes = resolveTotalEpisodes(project, new ScriptGenerateRequest());
        String previewContent = StringUtils.trimToEmpty(request.getContent());
        if (previewContent.isBlank()) {
            throw new BusinessException("大纲预览内容不能为空");
        }
        log.debug("修复大纲预览: userId={}, projectId={}, previewLength={}, totalEpisodes={}",
            userId, request.getProjectId(), previewContent.length(), totalEpisodes);

        String commonInfo = resolveOutlinePreviewCommonInfo(project, previewContent);
        Map<Integer, EpisodeOutline> outlineMap = parsePartialEpisodeOutlinesFromPreview(previewContent, totalEpisodes);
        List<Integer> missingEpisodes = findMissingEpisodes(outlineMap, totalEpisodes);
        log.debug("大纲预览修复分析: projectId={}, parsedEpisodes={}, missingEpisodes={}",
            request.getProjectId(), outlineMap.size(), missingEpisodes);
        if (missingEpisodes.isEmpty()) {
            return Map.of(
                    "content", buildOutlinePreviewContent(commonInfo, outlineMap, totalEpisodes),
                    "repairedEpisodes", List.of(),
                    "repairedEpisodeRanges", "",
                    "totalEpisodes", totalEpisodes);
        }

        ScriptGenerateRequest generateRequest = new ScriptGenerateRequest();
        generateRequest.setProjectId(project.getId());
        generateRequest.setIdea(request.getIdea());

        TextAiProvider textProvider = aiProviderFactory.getTextProvider(userId);
        String systemPrompt = buildOutlineSystemPrompt(project, resolveGenre(project, generateRequest), generateRequest.getStyle());
        int episodeDuration = resolveEpisodeDuration(project);
        for (Integer episodeNo : missingEpisodes) {
            if (episodeNo == null || outlineMap.containsKey(episodeNo)) {
                continue;
            }
            outlineMap.putAll(generateEpisodeOutlineChunk(
                    textProvider,
                    systemPrompt,
                    project,
                    generateRequest,
                    commonInfo,
                    episodeNo,
                    episodeNo,
                    totalEpisodes,
                    episodeDuration));
        }

        List<Integer> unresolvedEpisodes = findMissingEpisodes(outlineMap, totalEpisodes);
        log.debug("大纲预览修复结果: projectId={}, repairedEpisodes={}, unresolvedEpisodes={}",
            request.getProjectId(), missingEpisodes, unresolvedEpisodes);
        if (!unresolvedEpisodes.isEmpty()) {
            throw createOutlinePreviewParseException(totalEpisodes, unresolvedEpisodes);
        }

        return Map.of(
                "content", buildOutlinePreviewContent(commonInfo, outlineMap, totalEpisodes),
                "repairedEpisodes", missingEpisodes,
                "repairedEpisodeRanges", formatEpisodeRanges(missingEpisodes),
                "totalEpisodes", totalEpisodes);
    }

    public void saveBatchScriptPreview(Long userId, BatchScriptPreviewSaveRequest request) {
        Project project = projectService.getProject(userId, request.getProjectId());
        int totalEpisodes = resolveTotalEpisodes(project, new ScriptGenerateRequest());
        validateEpisodeIndex(request.getStartEpisode(), totalEpisodes, "起始集");
        validateEpisodeIndex(request.getEndEpisode(), totalEpisodes, "结束集");
        if (request.getStartEpisode() > request.getEndEpisode()) {
            throw new BusinessException("起始集不能大于结束集");
        }

        Map<Integer, String> episodeScripts = splitBatchScripts(
                StringUtils.trimToEmpty(request.getContent()),
                request.getStartEpisode(),
                request.getEndEpisode());
        log.debug("保存批量剧本预览: userId={}, projectId={}, startEpisode={}, endEpisode={}, parsedScripts={}",
            userId, request.getProjectId(), request.getStartEpisode(), request.getEndEpisode(), episodeScripts.size());

        for (int episodeNo = request.getStartEpisode(); episodeNo <= request.getEndEpisode(); episodeNo++) {
            String content = episodeScripts.get(episodeNo);
            if (StringUtils.isBlank(content)) {
                throw new BusinessException("第 " + episodeNo + " 集剧本内容缺失");
            }
            upsertGeneratedScript(project.getId(), episodeNo, content, request.getIdea());
        }
    }

    @Async("aiTaskExecutor")
    public void generateOutlineAsync(Long userId, ScriptGenerateRequest request, Long taskId) {
        TaskRecord task = taskRecordMapper.selectById(taskId);
        if (task == null) {
            return;
        }

        try {
            Project project = projectService.getProject(userId, request.getProjectId());
            int totalEpisodes = request.getTotalEpisodes() != null ? request.getTotalEpisodes() : resolveTotalEpisodes(project, request);
            int episodeDuration = resolveEpisodeDuration(project);
            log.debug("异步大纲生成开始: taskId={}, userId={}, projectId={}, totalEpisodes={}, episodeDuration={}",
                    taskId, userId, request.getProjectId(), totalEpisodes, episodeDuration);

            updateTask(task, "RUNNING", 10, "正在生成项目通用信息与人物小传...");
            TextAiProvider textProvider = aiProviderFactory.getTextProvider(userId);
            String systemPrompt = buildOutlineSystemPrompt(project, resolveGenre(project, request), request.getStyle());
                String commonInfo = generateProjectCommonInfoWithRetry(
                    textProvider,
                    systemPrompt,
                    project,
                    request,
                    totalEpisodes,
                    episodeDuration);
                applyAiGeneratedProjectName(userId, project, commonInfo);
            projectService.updateCommonInfo(userId, project.getId(), commonInfo);
                syncCharactersFromCommonInfo(userId, project.getId(), commonInfo);
                log.debug("异步大纲通用信息已保存: taskId={}, projectId={}, commonInfoLength={}",
                    taskId, request.getProjectId(), commonInfo.length());

            int generatedCount = 0;
            int chunkSize = resolveAdaptiveOutlineChunkSize(request, totalEpisodes, episodeDuration);
            for (int chunkStart = 1; chunkStart <= totalEpisodes; chunkStart += chunkSize) {
                int chunkEnd = Math.min(totalEpisodes, chunkStart + chunkSize - 1);
                int progress = 20 + Math.min(75, (int) (((chunkEnd * 1.0) / totalEpisodes) * 75));
                updateTask(task, "RUNNING", progress,
                        String.format("正在生成并保存第 %d-%d 集分集大纲...", chunkStart, chunkEnd));

                Map<Integer, EpisodeOutline> chunkOutlines = generateEpisodeOutlineChunk(
                    textProvider,
                    systemPrompt,
                    project,
                    request,
                    commonInfo,
                    chunkStart,
                    chunkEnd,
                    totalEpisodes,
                    episodeDuration);
                log.debug("异步大纲分片已生成: taskId={}, projectId={}, startEpisode={}, endEpisode={}, generatedEpisodes={}",
                        taskId, request.getProjectId(), chunkStart, chunkEnd, chunkOutlines.size());
                // Save each chunk to the database immediately after generation so that
                // partial progress is not lost if a later chunk or the overall task fails.
                for (Map.Entry<Integer, EpisodeOutline> entry : chunkOutlines.entrySet()) {
                    upsertEpisodeOutline(request.getProjectId(), entry.getKey(), entry.getValue(), request.getIdea());
                }
                generatedCount += chunkOutlines.size();
            }

            if (generatedCount != totalEpisodes) {
                throw new BusinessException(String.format("分集大纲生成不完整，期望 %d 集，实际 %d 集", totalEpisodes, generatedCount));
            }

            task.setStatus("SUCCESS");
            task.setProgress(100);
            task.setMessage(String.format("大纲生成完成，已写入 %d 集分集大纲与项目通用信息", generatedCount));
            task.setResult(String.valueOf(project.getId()));
            taskRecordMapper.updateById(task);
        } catch (Exception e) {
            log.error("大纲生成失败: taskId={}", taskId, e);
            task.setStatus("FAILED");
            task.setMessage("大纲生成失败: " + e.getMessage());
            taskRecordMapper.updateById(task);
        }
    }

    @Async("aiTaskExecutor")
    public void batchGenerateScriptAsync(Long userId, ScriptGenerateRequest request, Long taskId, int batchCount) {
        TaskRecord task = taskRecordMapper.selectById(taskId);
        if (task == null) return;
        try {
            Project project = projectService.getProject(userId, request.getProjectId());
            int startEpisode = request.getStartEpisode() != null ? request.getStartEpisode() : 1;
            int endEpisode = request.getEndEpisode() != null ? request.getEndEpisode() : startEpisode;
            GenerationMaterials materials = loadGenerationMaterials(project, startEpisode, endEpisode);
                syncCharactersFromCommonInfo(userId, project.getId(), materials.commonInfo());
            log.debug("批量剧本生成开始: taskId={}, userId={}, projectId={}, startEpisode={}, endEpisode={}",
                    taskId, userId, request.getProjectId(), startEpisode, endEpisode);

            updateTask(task, "RUNNING", 5,
                    String.format("开始批量生成第 %d-%d 集剧本...", startEpisode, endEpisode));
            TextAiProvider textProvider = aiProviderFactory.getTextProvider(userId);
            String systemPrompt = buildScriptSystemPrompt(project, resolveGenre(project, request), request.getStyle());

            // Generate and save each episode individually so that completed episodes are
            // persisted immediately. This avoids losing all progress when the AI returns
            // a very long response that gets truncated partway through the batch.
            for (int ep = startEpisode; ep <= endEpisode; ep++) {
                int progress = 10 + (int) (((ep - startEpisode + 1.0) / batchCount) * 80);
                updateTask(task, "RUNNING", progress,
                        String.format("AI正在生成第 %d/%d 集剧本（第 %d 集）...",
                                ep - startEpisode + 1, batchCount, ep));
                log.debug("批量剧本正在生成分集: taskId={}, projectId={}, episodeNo={}, progress={}",
                    taskId, request.getProjectId(), ep, progress);
                String userPrompt = buildScriptUserPrompt(request, materials, ep);
                String scriptContent = textProvider.chat(systemPrompt, userPrompt);
                log.debug("批量剧本分集已生成: taskId={}, projectId={}, episodeNo={}, contentLength={}",
                    taskId, request.getProjectId(), ep, StringUtils.length(scriptContent));
                upsertGeneratedScript(request.getProjectId(), ep, scriptContent, request.getIdea());
            }

            task.setStatus("SUCCESS");
            task.setProgress(100);
            task.setMessage(String.format("批量生成完成，共 %d 集（第 %d-%d 集）", batchCount, startEpisode, endEpisode));
            taskRecordMapper.updateById(task);
        } catch (Exception e) {
            log.error("批量剧本生成失败: taskId={}", taskId, e);
            task.setStatus("FAILED");
            task.setMessage("批量生成失败: " + e.getMessage());
            taskRecordMapper.updateById(task);
        }
    }

    public void streamGenerateScriptPreview(Long userId, ScriptGenerateRequest request, Consumer<String> chunkConsumer) {
        Project project = projectService.getProject(userId, request.getProjectId());
        GenerationPlan plan = resolveGenerationPlan(project, request);
        request.setEpisodeNo(plan.startEpisode() == plan.endEpisode() ? plan.singleEpisode() : null);
        request.setTotalEpisodes(plan.totalEpisodes());
        request.setStartEpisode(plan.startEpisode());
        request.setEndEpisode(plan.endEpisode());
        log.debug("开始流式生成剧本预览: userId={}, projectId={}, startEpisode={}, endEpisode={}, totalEpisodes={}",
            userId, request.getProjectId(), plan.startEpisode(), plan.endEpisode(), plan.totalEpisodes());

        TextAiProvider textProvider = aiProviderFactory.getTextProvider(userId);
        String systemPrompt = buildScriptSystemPrompt(project, resolveGenre(project, request), request.getStyle());

        if (plan.startEpisode() != plan.endEpisode()) {
            GenerationMaterials materials = loadGenerationMaterials(project, plan.startEpisode(), plan.endEpisode());
            String userPrompt = buildBatchScriptUserPrompt(request, materials, plan.startEpisode(), plan.endEpisode(), plan.totalEpisodes());
            textProvider.streamChat(systemPrompt, userPrompt, chunkConsumer);
            return;
        }

        GenerationMaterials materials = loadGenerationMaterials(project, plan.singleEpisode(), plan.singleEpisode());
        String userPrompt = buildScriptUserPrompt(request, materials, plan.singleEpisode());
        textProvider.streamChat(systemPrompt, userPrompt, chunkConsumer);
    }

    @Async("aiTaskExecutor")
    public void generateScriptAsync(Long userId, ScriptGenerateRequest request, Long taskId) {
        TaskRecord task = taskRecordMapper.selectById(taskId);
        if (task == null) return;
        try {
            Project project = projectService.getProject(userId, request.getProjectId());
            int episodeNo = request.getEpisodeNo() != null ? request.getEpisodeNo() : 1;
            GenerationMaterials materials = loadGenerationMaterials(project, episodeNo, episodeNo);
            syncCharactersFromCommonInfo(userId, project.getId(), materials.commonInfo());
            log.debug("异步单集剧本生成开始: taskId={}, userId={}, projectId={}, episodeNo={}",
                    taskId, userId, request.getProjectId(), episodeNo);

            updateTask(task, "RUNNING", 10, "开始生成剧本...");

            TextAiProvider textProvider = aiProviderFactory.getTextProvider(userId);
            String systemPrompt = buildScriptSystemPrompt(project, resolveGenre(project, request), request.getStyle());
            String userPrompt = buildScriptUserPrompt(request, materials, episodeNo);

            updateTask(task, "RUNNING", 30, "AI正在生成剧本内容...");
            String scriptContent = textProvider.chat(systemPrompt, userPrompt);
                log.debug("异步单集剧本已生成: taskId={}, projectId={}, episodeNo={}, contentLength={}",
                    taskId, request.getProjectId(), episodeNo, StringUtils.length(scriptContent));

            updateTask(task, "RUNNING", 80, "保存剧本内容...");
            Script script = upsertGeneratedScript(request.getProjectId(), episodeNo, scriptContent, request.getIdea());

            task.setStatus("SUCCESS");
            task.setProgress(100);
            task.setMessage("剧本生成完成");
            task.setResult(String.valueOf(script.getId()));
            taskRecordMapper.updateById(task);

        } catch (Exception e) {
            log.error("剧本生成失败: taskId={}", taskId, e);
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

    public Script saveScript(Long userId, ScriptSaveRequest request) {
        projectService.getProject(userId, request.getProjectId());

        Script script;
        if (request.getId() != null) {
            script = getScript(request.getId());
        } else {
            script = findScriptByProjectAndEpisode(request.getProjectId(), request.getEpisodeNo());
            if (script == null) {
                script = new Script();
                script.setProjectId(request.getProjectId());
            }
        }

        script.setEpisodeNo(request.getEpisodeNo());
        script.setTitle(request.getTitle());
        script.setContent(request.getContent());
        emitScriptSoftValidation(script.getContent(), "saveScript", request.getProjectId(), request.getEpisodeNo());
        if (request.getSummary() != null) {
            script.setSummary(request.getSummary());
        }
        script.setAiPrompt(request.getAiPrompt());
        script.setStatus("reviewed");

        if (request.getId() != null) {
            scriptMapper.updateById(script);
        } else {
            scriptMapper.insert(script);
        }
        return script;
    }

    public List<Script> listByProject(Long projectId) {
        return scriptMapper.selectList(new LambdaQueryWrapper<Script>()
                .eq(Script::getProjectId, projectId)
                .orderByAsc(Script::getEpisodeNo));
    }

    public Script updateScript(Long id, String content, String title, String summary) {
        Script script = getScript(id);
        if (content != null) {
            script.setContent(content);
            emitScriptSoftValidation(content, "updateScript", script.getProjectId(), script.getEpisodeNo());
        }
        if (title != null) script.setTitle(title);
        if (summary != null) script.setSummary(summary);
        script.setStatus("reviewed");
        scriptMapper.updateById(script);
        return script;
    }

    public void deleteScript(Long id) {
        scriptMapper.deleteById(id);
    }

    private void updateTask(TaskRecord task, String status, int progress, String message) {
        log.debug("任务状态更新: taskId={}, taskType={}, status={}, progress={}, message={}",
                task.getId(), task.getTaskType(), status, progress, message);
        task.setStatus(status);
        task.setProgress(progress);
        task.setMessage(message);
        taskRecordMapper.updateById(task);
    }

    private GenerationPlan resolveGenerationPlan(Project project, ScriptGenerateRequest request) {
        int totalEpisodes = resolveTotalEpisodes(project, request);

        Integer singleEpisode = request.getEpisodeNo();
        if (singleEpisode == null) {
            if (request.getStartEpisode() != null && request.getEndEpisode() == null) {
                singleEpisode = request.getStartEpisode();
            } else if (request.getStartEpisode() == null && request.getEndEpisode() != null) {
                singleEpisode = request.getEndEpisode();
            }
        }

        int startEpisode = request.getStartEpisode() != null
                ? request.getStartEpisode()
                : (singleEpisode != null ? singleEpisode : 1);
        int endEpisode = request.getEndEpisode() != null ? request.getEndEpisode() : startEpisode;

        if (startEpisode > endEpisode) {
            throw new BusinessException("起始集不能大于结束集");
        }

        validateEpisodeIndex(startEpisode, totalEpisodes, "起始集");
        validateEpisodeIndex(endEpisode, totalEpisodes, "结束集");

        int resolvedSingleEpisode = singleEpisode != null ? singleEpisode : startEpisode;
        validateEpisodeIndex(resolvedSingleEpisode, totalEpisodes, "集数");

        return new GenerationPlan(totalEpisodes, startEpisode, endEpisode, resolvedSingleEpisode);
    }

    private int resolveTotalEpisodes(Project project, ScriptGenerateRequest request) {
        if (request.getTotalEpisodes() != null && request.getTotalEpisodes() > 0) {
            return request.getTotalEpisodes();
        }
        if (project.getEpisodes() != null && project.getEpisodes() > 0) {
            return project.getEpisodes();
        }
        return 20;
    }

    private void validateEpisodeIndex(int episodeNo, int totalEpisodes, String label) {
        if (episodeNo < 1) {
            throw new BusinessException(label + "必须大于 0");
        }
        if (episodeNo > totalEpisodes) {
            throw new BusinessException(String.format("%s不能超过项目总集数 %d", label, totalEpisodes));
        }
    }

    private record GenerationPlan(int totalEpisodes, int startEpisode, int endEpisode, int singleEpisode) {}

    private record SceneBudget(int minScenes, int maxScenes, int beatsPerEpisode) {}

    private record EpisodeOutline(String title, String summary) {}

    private record CharacterProfile(String name,
                                    String gender,
                                    String age,
                                    String appearance,
                                    String personality,
                                    String description) {}

    private record GenerationMaterials(Project project, String commonInfo, Map<Integer, Script> scriptsByEpisode) {}

    private GenerationMaterials loadGenerationMaterials(Project project, int startEpisode, int endEpisode) {
        String commonInfo = StringUtils.trimToNull(project.getCommonInfo());
        if (commonInfo == null) {
            throw new BusinessException("请先生成大纲和人物小传，再生成剧本");
        }

        Map<Integer, Script> scriptsByEpisode = new LinkedHashMap<>();
        for (Script script : listByProject(project.getId())) {
            scriptsByEpisode.putIfAbsent(script.getEpisodeNo(), script);
        }

        for (int ep = startEpisode; ep <= endEpisode; ep++) {
            Script script = scriptsByEpisode.get(ep);
            if (script == null || StringUtils.isBlank(script.getSummary())) {
                throw new BusinessException(String.format("第 %d 集还没有分集大纲，请先生成大纲", ep));
            }
        }

        log.debug("生成素材已加载: projectId={}, startEpisode={}, endEpisode={}, commonInfoLength={}, availableScripts={}",
                project.getId(), startEpisode, endEpisode, commonInfo.length(), scriptsByEpisode.size());

        return new GenerationMaterials(project, commonInfo, scriptsByEpisode);
    }

    private String buildBatchScriptUserPrompt(ScriptGenerateRequest request,
                                              GenerationMaterials materials,
                                              int startEpisode,
                                              int endEpisode,
                                              int totalEpisodes) {
        SceneBudget sceneBudget = resolveSceneBudget(materials.project());
                String projectType = resolveProjectType(materials.project());
                String genre = resolveGenre(materials.project(), request);
        StringBuilder outlines = new StringBuilder();
        for (int ep = startEpisode; ep <= endEpisode; ep++) {
            Script script = materials.scriptsByEpisode().get(ep);
            outlines.append("###EPISODE_OUTLINE:").append(ep).append("###\n")
                    .append("标题：").append(StringUtils.defaultIfBlank(script.getTitle(), "第" + ep + "集")).append("\n")
                    .append(StringUtils.defaultString(script.getSummary()).trim()).append("\n\n");
        }

        return String.format("""
                请根据项目通用信息和分集大纲，连续生成第 %d 到第 %d 集剧本（共 %d 集中的区间）。

                项目名称：%s
                项目类型：%s
                题材：%s
                单集时长：约 %d 秒

                项目通用信息（人物小传/世界观/关系线/长期伏笔）：
                %s

                需要生成的分集大纲：
                %s

                %s

                严格要求：
                1) 每一集都必须严格落实对应集数的大纲，不得擅自改主线设定。
                2) 每一集用如下标记包裹，不能缺失、不能改写：
                   ###EPISODE_START:X###
                   ...该集完整剧本正文...
                   ###EPISODE_END###
                   其中 X 必须是对应集数。
                     3) 只能输出第 %d 到第 %d 集，禁止输出该区间之外的任何集数，输出顺序也必须严格从第 %d 集到第 %d 集。
                     4) 不要输出任何解释文字，只输出多集剧本内容和标记。
                     5) 每一集按约 %d 秒体量，控制在 %d-%d 个场景，保证节奏紧凑、承接自然。
                     6) 剧本必须为后续分镜拆解服务，每场都要写清时间、地点、出场角色、动作与情绪。
                     7) 场景格式统一：
                   第N场 [场景/时间/地点]
                   角色：角色名
                   台词：口语短句（不写“角色名：”前缀）
                   可选旁白：仅 VO/OS 且不与台词重复
                     8) 连续集剧情要有承接，但每一集都要有明确钩子。
                     9) 角色语言和行为必须严格符合项目通用信息中的人物小传。
                    10) 禁止小说腔表达（如“他冷冷地看着/心里一沉/开口说道”）。
                """,
                startEpisode,
                endEpisode,
                totalEpisodes,
                materials.project().getName(),
                projectType,
                genre,
                resolveEpisodeDuration(materials.project()),
                materials.commonInfo(),
                outlines,
                formatOptionalAdjustment(request.getIdea()),
                startEpisode,
                endEpisode,
                startEpisode,
                endEpisode,
                resolveEpisodeDuration(materials.project()),
                sceneBudget.minScenes(),
                sceneBudget.maxScenes());
    }

    private Map<Integer, String> splitBatchScripts(String batchContent, int startEpisode, int endEpisode) {
        Map<Integer, String> scripts = new LinkedHashMap<>();

        Matcher markedMatcher = EPISODE_SCRIPT_PATTERN.matcher(batchContent);
        while (markedMatcher.find()) {
            int ep = Integer.parseInt(markedMatcher.group(1));
            if (ep >= startEpisode && ep <= endEpisode) {
                scripts.put(ep, markedMatcher.group(2).trim());
            }
        }
        if (scripts.size() == (endEpisode - startEpisode + 1)) {
            return scripts;
        }

        Pattern heading = Pattern.compile("(?=【第(\\d+)集】)", Pattern.DOTALL);
        Matcher headingMatcher = heading.matcher(batchContent);
        List<Integer> starts = new java.util.ArrayList<>();
        List<Integer> eps = new java.util.ArrayList<>();
        while (headingMatcher.find()) {
            starts.add(headingMatcher.start());
            eps.add(Integer.parseInt(headingMatcher.group(1)));
        }
        for (int i = 0; i < starts.size(); i++) {
            int ep = eps.get(i);
            if (ep < startEpisode || ep > endEpisode) {
                continue;
            }
            int from = starts.get(i);
            int to = (i + 1 < starts.size()) ? starts.get(i + 1) : batchContent.length();
            scripts.put(ep, batchContent.substring(from, to));
        }

        if (scripts.size() != (endEpisode - startEpisode + 1)) {
            throw new BusinessException("批量剧本拆分失败：模型输出未包含完整集数标记");
        }
        return scripts;
    }

    private String buildOutlineSystemPrompt(Project project, String genre, String style) {
        String projectType = resolveProjectType(project);
        String genreText = ProjectStyleSupport.resolveGenre(genre);
        String styleText = style != null ? style : "";
        String styleGuide = ProjectStyleSupport.buildTextCreationRules(projectType, genreText);
        String beatBlock = ProjectStyleSupport.buildShortDramaBeatBlock();
        return String.format("""
                # 角色定位
                你是一位负责短剧项目统筹策划的总编剧，要先搭建后续剧本、分镜、角色一致性都会复用的稳定设定。

                # 工作目标
                - 先产出可长期复用的项目通用信息：人物小传、关系线、世界规则、可复用场景、伏笔和季级主线。
                - 再产出每一集的大纲，确保节奏、爽点和钩子都服务于后续完整剧本创作。
                - 所有输出必须具体、可执行，不能空泛。

                # 输出原则
                - 角色小传要写清动机、弱点、秘密、关系、语言风格。
                - 分集大纲要能直接交给编剧扩写成完整剧本，不要只写一句话简介；每集大纲中必须点明本集「钩子-对抗-反转-悬念收束」四段，避免只有剧情简介。
                - 设定必须前后一致，不允许互相打架。
                - 节奏必须符合短剧商业化表达和竖屏内容习惯。

                # 项目类型与题材
                项目类型：%s
                %s %s

                # 项目风格锚点
                %s

                %s
                """, projectType, genreText, styleText, styleGuide, beatBlock, ProjectStyleSupport.buildScriptSelfCheckBlock());
    }

    private String buildScriptSystemPrompt(Project project, String genre, String style) {
        String projectType = resolveProjectType(project);
        String genreText = ProjectStyleSupport.resolveGenre(genre);
        String styleText = style != null ? style : "";
        String styleGuide = ProjectStyleSupport.buildTextCreationRules(projectType, genreText);
        String beatBlock = ProjectStyleSupport.buildShortDramaBeatBlock();
        return String.format("""
                # 角色定位
                你是一位拥有10年爆款短剧编剧经验的金牌编剧，专精红果短剧、抖音短剧平台。
                你的核心目标：产出保底级付费短剧剧本,红果短剧、抖音短剧标准，合规无违规，爽点密集，适合平台保底S+评级。
                
                # 平台合规红线（必须遵守）
                - 无色情/软色情暗示，无血腥暴力特写，无政治敏感内容
                - 不丑化军人/警察/医生/教师等职业形象
                - 不宣扬封建迷信、赌博、毒品
                - 正向价值观：善有善报、正义终将胜利、真爱至上
                - 不涉及未成年人恋爱/暴力情节
                
                # 爆款方法论
                ## 钩子与冲突节奏（统一口径）
                - 开场约3秒内出现钩子（悬念/冲击台词/危机画面）
                - 约10秒内出现可见冲突，不做慢热铺垫
                - 约每30秒出现一次爽点/反转或信息升级
                - 结尾必须留强钩子（反转/新危机/身份揭露）
                
                ## 人物弧光设计
                - 主角必须有清晰的成长弧线：从低谷 → 觉醒 → 反击 → 逆袭
                - 反派不能脸谱化：需有合理动机，让观众"恨得过瘾"
                - 关键配角需有记忆点：金句/口头禅/标志性动作
                - 人物关系网必须有张力：至少包含2组对立关系 + 1组隐藏关系
                
                # 剧本结构规范
                ## 长度约束
                - 具体字数、场景数、节奏必须服从用户提供的单集时长和分集大纲
                - 必须按该集大纲完成完整戏剧推进
                - 单个场景 100-200 字（含描述+对白+动作指示）
                
                ## 对白要求
                - 短剧可拍可念：口语短句、禁止小说体（大段心理、环境散文、「他开口道/心里一沉」等不要写进台词正文；舞台动作写进场记/景别，不要和口播混成一段）
                - 台词简短有力，单句不超过20字
                - 金句密度：每集至少5句可截图传播的台词
                - 角色语言个性化：不同角色说话风格明显不同
                - 潜台词丰富：表面意思与真实意图之间有反差
                
                ## 视觉化写作
                - 每个场景必须有明确的视觉指示（表情/动作/机位建议）
                - 适合竖屏（9:16）构图
                - 充分复用场景和角色，减少不必要的场景切换
                - 同一场景内多个镜头保持视觉连贯性
                - 对话场景优先使用特写和中景镜头
                
                # 项目类型与题材
                项目类型：%s
                %s %s

                # 项目风格锚点
                %s

                %s

                %s

                %s
                """, projectType, genreText, styleText, styleGuide, beatBlock,
                ProjectStyleSupport.buildNovelToneBlacklistFewShot(),
                ProjectStyleSupport.buildScriptSelfCheckBlock());
    }

    private String generateProjectCommonInfoWithRetry(TextAiProvider textProvider,
                                                      String systemPrompt,
                                                      Project project,
                                                      ScriptGenerateRequest request,
                                                      int totalEpisodes,
                                                      int episodeDuration) {
        String prompt = buildProjectCommonInfoUserPrompt(project, request, totalEpisodes, episodeDuration);
        String lastResponse = null;
        BusinessException lastException = null;
        for (int attempt = 1; attempt <= COMMON_INFO_RETRY_ATTEMPTS; attempt++) {
            log.debug("生成通用信息尝试: projectId={}, attempt={}, totalEpisodes={}, episodeDuration={}",
                    project.getId(), attempt, totalEpisodes, episodeDuration);
            lastResponse = textProvider.chat(systemPrompt, prompt);
            try {
                String commonInfo = extractProjectCommonInfo(lastResponse);
                log.debug("通用信息生成成功: projectId={}, attempt={}, commonInfoLength={}",
                        project.getId(), attempt, commonInfo.length());
                return commonInfo;
            } catch (BusinessException ex) {
                lastException = ex;
                log.warn("项目通用信息解析失败: attempt={}/{}, responseLength={}, message={}",
                        attempt,
                        COMMON_INFO_RETRY_ATTEMPTS,
                        lastResponse != null ? lastResponse.length() : 0,
                        ex.getMessage());
            }
        }

        String fallback = StringUtils.trimToNull(extractProjectCommonInfoForPrompt(lastResponse));
        if (fallback != null) {
            return fallback;
        }
        if (lastException != null) {
            throw lastException;
        }
        throw new BusinessException("项目通用信息生成失败：AI 未返回有效内容");
    }

    private String formatProjectCommonInfoBlock(String commonInfo) {
        String normalized = StringUtils.trimToEmpty(commonInfo);
        if (normalized.isBlank()) {
            return "";
        }
        return "###PROJECT_COMMON_INFO_START###\n"
                + normalized
                + "\n###PROJECT_COMMON_INFO_END###";
    }

    private String formatEpisodeOutlineBlocks(Map<Integer, EpisodeOutline> outlineMap, int startEpisode, int endEpisode) {
        StringBuilder builder = new StringBuilder();
        for (int episodeNo = startEpisode; episodeNo <= endEpisode; episodeNo++) {
            EpisodeOutline outline = outlineMap.get(episodeNo);
            if (outline == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append(formatEpisodeOutlineBlock(episodeNo, outline));
        }
        return builder.toString();
    }

     private String buildProjectCommonInfoUserPrompt(Project project,
                                                                     ScriptGenerateRequest request,
                                                                     int totalEpisodes,
                                                                     int episodeDuration) {
          String projectType = resolveProjectType(project);
          String genre = resolveGenre(project, request);
          String styleGuide = ProjectStyleSupport.buildTextCreationRules(projectType, genre);
          return String.format("""
                     请为以下短剧项目先生成后续整条生产链路都会用到的项目通用信息。

                     项目名称：%s
                     项目类型：%s
                     题材：%s
                     总集数：%d
                     单集时长：约 %d 秒
                     项目风格约束：
                     %s
                     用户提供的故事创意/补充设定：
                     %s

                     输出要求：
                     1) 只输出以下标记包裹的正文，不要解释：
                         ###PROJECT_COMMON_INFO_START###
                         ...正文...
                         ###PROJECT_COMMON_INFO_END###
                     2) 正文开头第一行必须是单独一行（该行不要加 Markdown 标题符号），格式严格为：
                         官方项目名称：《片名》或 官方项目名称：片名
                         其中片名为 4–14 字的短剧宣传名，须贴合故事卖点、有记忆点；可与用户原始灵感表述不同，不得为空。
                         该行之后空一行，再按下列模块与小标题书写：
                         - 故事核心卖点
                         - 世界观/时代背景/规则
                         - 核心人物小传（至少4人，每人都写：身份定位、性格驱动、情感诉求、秘密或弱点、外形记忆点、语言/动作特征）
                         - 人物关系网与对立结构
                         - 可复用的核心场景与道具
                         - 整季主线推进
                         - 写作硬约束（后续每集都必须遵守）
                     3) 内容必须足够具体，能直接给后续剧本生成和分镜生成使用。
                     4) 所有信息要匹配总集数与单集时长，避免过度发散。
                     """,
                     project.getName(),
                     projectType,
                     genre,
                     totalEpisodes,
                     episodeDuration,
                     styleGuide,
                     resolveCreativeSeed(project, request, true));
     }

     private String buildEpisodeOutlineUserPrompt(Project project,
                                                                 ScriptGenerateRequest request,
                                                                 String commonInfo,
                                                                 int startEpisode,
                                                                 int endEpisode,
                                                                 int totalEpisodes,
                                                                 int episodeDuration) {
          SceneBudget sceneBudget = resolveSceneBudget(project);
          String projectType = resolveProjectType(project);
          String genre = resolveGenre(project, request);
          String styleGuide = ProjectStyleSupport.buildTextCreationRules(projectType, genre);
          return String.format("""
                     请基于以下项目通用信息，为第 %d 到第 %d 集生成分集大纲。

                     项目名称：%s
                     项目类型：%s
                     题材：%s
                     总集数：%d
                     单集时长：约 %d 秒

                     项目风格约束：
                     %s

                     项目通用信息：
                     %s

                     输出要求：
                     1) 每一集必须使用如下固定标记，不能缺失、不能改写：
                         ###EPISODE_OUTLINE_START:X###
                         标题：第X集标题
                         大纲：
                         1. 开场钩子：...
                         2. 核心冲突：...
                         3. 中段推进：...
                         4. 爽点/反转：...
                         5. 结尾钩子：...
                         关键人物：...
                         关键场景：...
                         承接说明：...
                         ###EPISODE_OUTLINE_END###
                     2) X 必须是对应真实集数，只输出第 %d-%d 集的标记块，不要输出其他文字。
                     3) 每集大纲必须匹配约 %d 秒的时长，可被扩写为 %d-%d 个场景的完整剧本。
                     4) 大纲里要明确本集要回收什么、埋什么钩子，保证整季递进。
                     5) 每集必须和项目通用信息严格一致，不允许临时新增关键设定。
                     6) 每集大纲请尽量控制在 220-350 字，信息密度高，不要扩写成完整剧本。
                    7) 不要使用 Markdown 代码块，不要输出 ```。
                    8) 若检测到输出长度过大或结构漂移风险，请优先保证标记完整与每集要点，不要写冗余修饰句。
                     %s
                     """,
                     startEpisode,
                     endEpisode,
                     project.getName(),
                     projectType,
                     genre,
                     totalEpisodes,
                     episodeDuration,
                     styleGuide,
                     commonInfo,
                     startEpisode,
                     endEpisode,
                     episodeDuration,
                     sceneBudget.minScenes(),
                     sceneBudget.maxScenes(),
                     formatOptionalAdjustment(request.getIdea()));
     }

    private Map<Integer, EpisodeOutline> generateEpisodeOutlineChunk(TextAiProvider textProvider,
                                                                     String systemPrompt,
                                                                     Project project,
                                                                     ScriptGenerateRequest request,
                                                                     String commonInfo,
                                                                     int startEpisode,
                                                                     int endEpisode,
                                                                     int totalEpisodes,
                                                                     int episodeDuration) {
        String prompt = buildEpisodeOutlineUserPrompt(project, request, commonInfo, startEpisode, endEpisode, totalEpisodes, episodeDuration);
        String lastResponse = null;
        BusinessException lastException = null;
        log.debug("生成大纲分片: projectId={}, startEpisode={}, endEpisode={}, totalEpisodes={}",
            project.getId(), startEpisode, endEpisode, totalEpisodes);

        for (int attempt = 1; attempt <= OUTLINE_CHUNK_RETRY_ATTEMPTS; attempt++) {
            String response = textProvider.chat(systemPrompt, prompt);
            lastResponse = response;
            try {
            Map<Integer, EpisodeOutline> parsed = parseEpisodeOutlines(response, startEpisode, endEpisode);
            log.debug("大纲分片解析成功: projectId={}, startEpisode={}, endEpisode={}, attempt={}, parsedCount={}",
                project.getId(), startEpisode, endEpisode, attempt, parsed.size());
            return parsed;
            } catch (BusinessException ex) {
                int responseLength = response != null ? response.length() : 0;
                log.warn("大纲分片解析不完整: range={}-{}, attempt={}/{}, responseLength={}, message={}",
                        startEpisode,
                        endEpisode,
                        attempt,
                        OUTLINE_CHUNK_RETRY_ATTEMPTS,
                        responseLength,
                        ex.getMessage());
                lastException = ex;

                if (startEpisode >= endEpisode) {
                    EpisodeOutline fallback = tryParseSingleEpisodeOutline(response, startEpisode);
                    if (fallback != null) {
                        Map<Integer, EpisodeOutline> single = new LinkedHashMap<>();
                        single.put(startEpisode, fallback);
                        return single;
                    }
                }
            }
        }

        if (startEpisode >= endEpisode) {
            EpisodeOutline fallback = tryParseSingleEpisodeOutline(lastResponse, startEpisode);
            if (fallback != null) {
                Map<Integer, EpisodeOutline> single = new LinkedHashMap<>();
                single.put(startEpisode, fallback);
                return single;
            }
            throw new BusinessException(String.format("第 %d 集大纲解析失败，请重试；若仍失败请缩短创意描述或减少设定长度", startEpisode));
        }

        Map<Integer, EpisodeOutline> partial = parseMarkedEpisodeOutlines(lastResponse, startEpisode, endEpisode);
        if (!partial.isEmpty()) {
            List<Integer> missing = new ArrayList<>();
            for (int ep = startEpisode; ep <= endEpisode; ep++) {
                if (!partial.containsKey(ep)) {
                    missing.add(ep);
                }
            }
            if (!missing.isEmpty() && missing.size() <= 2) {
                for (Integer miss : missing) {
                    Map<Integer, EpisodeOutline> repaired = generateEpisodeOutlineChunk(
                            textProvider,
                            systemPrompt,
                            project,
                            request,
                            commonInfo,
                            miss,
                            miss,
                            totalEpisodes,
                            episodeDuration);
                    partial.putAll(repaired);
                }
                if (partial.size() == (endEpisode - startEpisode + 1)) {
                    return partial;
                }
            }
        }

        int midEpisode = (startEpisode + endEpisode) / 2;
        log.debug("重试后拆分大纲分片: projectId={}, startEpisode={}, midEpisode={}, endEpisode={}",
            project.getId(), startEpisode, midEpisode, endEpisode);
        Map<Integer, EpisodeOutline> merged = new LinkedHashMap<>();
        merged.putAll(generateEpisodeOutlineChunk(
                textProvider,
                systemPrompt,
                project,
                request,
                commonInfo,
                startEpisode,
                midEpisode,
                totalEpisodes,
                episodeDuration));
        merged.putAll(generateEpisodeOutlineChunk(
                textProvider,
                systemPrompt,
                project,
                request,
                commonInfo,
                midEpisode + 1,
                endEpisode,
                totalEpisodes,
                episodeDuration));
        if (merged.size() == (endEpisode - startEpisode + 1)) {
            return merged;
        }
        throw lastException != null
                ? lastException
                : new BusinessException(String.format("第 %d-%d 集大纲生成失败", startEpisode, endEpisode));
    }

    private EpisodeOutline tryParseSingleEpisodeOutline(String response, int episodeNo) {
        if (StringUtils.isBlank(response)) {
            return null;
        }

        String normalized = response.trim()
                .replace("```markdown", "")
                .replace("```text", "")
                .replace("```", "")
                .trim();

        Pattern startPattern = Pattern.compile("###EPISODE_OUTLINE_START:" + episodeNo + "###\\s*", Pattern.DOTALL);
        Matcher startMatcher = startPattern.matcher(normalized);
        if (startMatcher.find()) {
            normalized = normalized.substring(startMatcher.end()).trim();
        }

        normalized = normalized.replaceAll("\\s*###EPISODE_OUTLINE_END###\\s*$", "").trim();
        if (StringUtils.isBlank(normalized)) {
            return null;
        }

        String title = extractEpisodeTitle(normalized, episodeNo);
        String summary = stripEpisodeTitle(normalized).trim();
        if (StringUtils.isBlank(summary)) {
            return null;
        }
        return new EpisodeOutline(title, summary);
    }

     private String buildScriptUserPrompt(ScriptGenerateRequest request, GenerationMaterials materials, int episodeNo) {
          int totalEpisodes = request.getTotalEpisodes() != null ? request.getTotalEpisodes() : 1;
          SceneBudget sceneBudget = resolveSceneBudget(materials.project());
                    String projectType = resolveProjectType(materials.project());
                    String genre = resolveGenre(materials.project(), request);
          Script currentScript = materials.scriptsByEpisode().get(episodeNo);
        return String.format("""
                     请根据以下项目通用信息和分集大纲，生成第 %d 集完整剧本（共 %d 集中的当前集）。
                
                     ## 项目基础信息
                     - 项目名称：%s
                                         - 项目类型：%s
                     - 题材：%s
                     - 总集数：%d
                     - 单集时长：约 %d 秒
                     ## 项目通用信息
                     %s
                
                     ## 当前集分集大纲
                     标题：%s
                     %s
                
                     ## 相邻剧情承接参考
                     %s
                     %s
                
                     %s
                
                ## 输出要求
                     1. 只输出第 %d 集完整剧本正文，不要输出其他集，不要输出解释。
                     2. 本集必须严格落实当前集大纲，包含开头、冲突、反转、结尾。
                     3. 与上一集有承接，与下一集有钩子，但不能偏离项目通用信息。
                     4. 场景组织稳定：按场景推进剧情，不要跳场景乱切。
                     5. 台词密集但清晰，动作与对白对应。
                     6. 本集按约 %d 秒体量，控制在 %d-%d 个场景。
                     7. 每场都要给出足够的视觉与表演信息，便于下一步拆分镜。

                ## 输出格式（严格遵守）
                
                【第%d集】集标题
                
                第X场 [场景名称/时间/地点]
                （场景描述：环境氛围、光线、人物站位，需包含镜头建议如"特写/中景/全景"）
                角色：角色名
                台词：口语短句（不写“角色名：”前缀）
                可选旁白：仅 VO/OS 且不与台词重复
                镜头提示：推/拉/摇/移/特写（极短）
                
                要求：
                - 包含 %d-%d 个场景
                - 开场 30 秒内进入冲突
                - 结尾留强钩子
                - 禁用小说腔表达
                """,
                episodeNo,
                totalEpisodes,
                materials.project().getName(),
                projectType,
                genre,
                totalEpisodes,
                resolveEpisodeDuration(materials.project()),
                materials.commonInfo(),
                StringUtils.defaultIfBlank(currentScript.getTitle(), "第" + episodeNo + "集"),
                StringUtils.defaultString(currentScript.getSummary()).trim(),
                formatNeighborOutline("上一集大纲", materials.scriptsByEpisode().get(episodeNo - 1)),
                formatNeighborOutline("下一集大纲", materials.scriptsByEpisode().get(episodeNo + 1)),
                formatOptionalAdjustment(request.getIdea()),
                episodeNo,
                resolveEpisodeDuration(materials.project()),
                sceneBudget.minScenes(),
                sceneBudget.maxScenes(),
                episodeNo,
                sceneBudget.minScenes(),
                sceneBudget.maxScenes());
    }

    private String extractProjectCommonInfo(String response) {
        Matcher matcher = PROJECT_COMMON_INFO_PATTERN.matcher(response);
        if (!matcher.find()) {
            throw new BusinessException("项目通用信息解析失败：模型未返回约定标记");
        }
        return matcher.group(1).trim();
    }

    private void applyAiGeneratedProjectName(Long userId, Project project, String commonInfo) {
        String name = extractOfficialProjectName(commonInfo);
        if (StringUtils.isBlank(name)) {
            return;
        }
        projectService.updateProjectName(userId, project.getId(), name);
        project.setName(name);
    }

    /**
     * 从通用信息正文解析模型输出的正式片名（行首：官方项目名称：…）。
     */
    private String extractOfficialProjectName(String commonInfo) {
        if (StringUtils.isBlank(commonInfo)) {
            return null;
        }
        Matcher matcher = OFFICIAL_PROJECT_NAME_PATTERN.matcher(commonInfo);
        if (!matcher.find()) {
            return null;
        }
        return normalizeOfficialProjectName(matcher.group(1));
    }

    private String normalizeOfficialProjectName(String raw) {
        String n = StringUtils.trimToEmpty(raw);
        if (n.isEmpty()) {
            return null;
        }
        while (n.startsWith("《") && n.endsWith("》") && n.length() >= 2) {
            n = n.substring(1, n.length() - 1).trim();
        }
        n = StringUtils.trimToEmpty(n);
        if (n.isEmpty()) {
            return null;
        }
        if (n.length() > 200) {
            n = n.substring(0, 200);
        }
        return n;
    }

    private String extractProjectCommonInfoForPrompt(String response) {
        try {
            return extractProjectCommonInfo(response);
        } catch (BusinessException ex) {
            return StringUtils.trimToEmpty(response);
        }
    }

    private String extractProjectCommonInfoFromPreview(String response) {
        try {
            return extractProjectCommonInfo(response);
        } catch (BusinessException ex) {
            int firstEpisodeIndex = findFirstEpisodeOutlineIndex(response);
            if (firstEpisodeIndex <= 0) {
                return "";
            }
            return response.substring(0, firstEpisodeIndex).trim();
        }
    }

    private String resolveOutlinePreviewCommonInfo(Project project, String previewContent) {
        String commonInfo = StringUtils.trimToNull(extractProjectCommonInfoFromPreview(previewContent));
        if (commonInfo != null) {
            return commonInfo;
        }
        return StringUtils.defaultString(StringUtils.trimToNull(project.getCommonInfo()));
    }

    private Map<Integer, EpisodeOutline> parseMarkedEpisodeOutlines(String response, int startEpisode, int endEpisode) {
        Map<Integer, EpisodeOutline> result = new LinkedHashMap<>();
        Matcher matcher = EPISODE_OUTLINE_PATTERN.matcher(response);
        while (matcher.find()) {
            int episodeNo = Integer.parseInt(matcher.group(1));
            if (episodeNo < startEpisode || episodeNo > endEpisode) {
                continue;
            }
            String rawBlock = matcher.group(2).trim();
            String title = extractEpisodeTitle(rawBlock, episodeNo);
            String summary = stripEpisodeTitle(rawBlock).trim();
            if (StringUtils.isBlank(summary)) {
                throw new BusinessException("第 " + episodeNo + " 集大纲为空");
            }
            result.put(episodeNo, new EpisodeOutline(title, summary));
        }
        return result;
    }

    private Map<Integer, EpisodeOutline> parseEpisodeOutlines(String response, int startEpisode, int endEpisode) {
        Map<Integer, EpisodeOutline> result = parseMarkedEpisodeOutlines(response, startEpisode, endEpisode);

        if (result.size() != (endEpisode - startEpisode + 1)) {
            throw new BusinessException(String.format("分集大纲解析失败：第 %d-%d 集标记不完整", startEpisode, endEpisode));
        }
        return result;
    }

    private Map<Integer, EpisodeOutline> parseEpisodeOutlinesFromPreview(String response, int totalEpisodes) {
        Map<Integer, EpisodeOutline> result = parsePartialEpisodeOutlinesFromPreview(response, totalEpisodes);
        List<Integer> missingEpisodes = findMissingEpisodes(result, totalEpisodes);
        if (!missingEpisodes.isEmpty()) {
            throw createOutlinePreviewParseException(totalEpisodes, missingEpisodes);
        }
        return result;
    }

    private Map<Integer, EpisodeOutline> parsePartialEpisodeOutlinesFromPreview(String response, int totalEpisodes) {
        Map<Integer, EpisodeOutline> result = parseMarkedEpisodeOutlines(response, 1, totalEpisodes);
        if (result.size() < totalEpisodes) {
            Map<Integer, EpisodeOutline> fallbackResult = parseEpisodeOutlinesFromPreviewByHeading(response, totalEpisodes);
            fallbackResult.forEach(result::putIfAbsent);
        }
        return result;
    }

    private BusinessException createOutlinePreviewParseException(int totalEpisodes, List<Integer> missingEpisodes) {
        return new BusinessException(
                422,
                String.format(
                        "大纲预览解析失败：缺少第 %s 集标记；请在预览框中补齐“【第X集】”或“###EPISODE_OUTLINE_START:X###”后再保存",
                        formatEpisodeRanges(missingEpisodes)),
                buildOutlinePreviewParseErrorData(totalEpisodes, missingEpisodes));
    }

    private Map<String, Object> buildOutlinePreviewParseErrorData(int totalEpisodes, List<Integer> missingEpisodes) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "OUTLINE_PREVIEW_PARSE_INCOMPLETE");
        data.put("repairable", !missingEpisodes.isEmpty());
        data.put("totalEpisodes", totalEpisodes);
        data.put("missingEpisodes", missingEpisodes);
        data.put("missingEpisodeRanges", formatEpisodeRanges(missingEpisodes));
        return data;
    }

    private Map<Integer, EpisodeOutline> parseEpisodeOutlinesFromPreviewByHeading(String response, int totalEpisodes) {
        Map<Integer, EpisodeOutline> result = new LinkedHashMap<>();
        Matcher headingMatcher = EPISODE_OUTLINE_HEADING_PATTERN.matcher(response);
        List<Integer> starts = new ArrayList<>();
        List<Integer> episodeNos = new ArrayList<>();
        List<String> headingSuffixes = new ArrayList<>();

        while (headingMatcher.find()) {
            starts.add(headingMatcher.start());
            episodeNos.add(Integer.parseInt(headingMatcher.group(1)));
            headingSuffixes.add(StringUtils.trimToEmpty(headingMatcher.group(2)));
        }

        for (int i = 0; i < starts.size(); i++) {
            int episodeNo = episodeNos.get(i);
            if (episodeNo < 1 || episodeNo > totalEpisodes) {
                continue;
            }

            int from = starts.get(i);
            int to = (i + 1 < starts.size()) ? starts.get(i + 1) : response.length();
            EpisodeOutline outline = buildEpisodeOutlineFromHeadingBlock(
                    response.substring(from, to).trim(),
                    episodeNo,
                    headingSuffixes.get(i));
            if (outline != null) {
                result.put(episodeNo, outline);
            }
        }
        return result;
    }

    private EpisodeOutline buildEpisodeOutlineFromHeadingBlock(String rawBlock, int episodeNo, String headingSuffix) {
        if (StringUtils.isBlank(rawBlock)) {
            return null;
        }

        int firstLineBreak = findFirstLineBreak(rawBlock);
        String body = firstLineBreak >= 0 ? rawBlock.substring(firstLineBreak).trim() : "";
        String explicitTitle = findEpisodeTitle(body);
        String summary = stripEpisodeTitle(body).trim();
        String inlineTitle = normalizeHeadingSuffix(headingSuffix);

        if (StringUtils.isBlank(summary)) {
            summary = inlineTitle;
            inlineTitle = "";
        }
        if (StringUtils.isBlank(summary)) {
            return null;
        }

        String title = StringUtils.defaultIfBlank(explicitTitle, inlineTitle);
        if (StringUtils.isBlank(title)) {
            title = "第" + episodeNo + "集";
        }
        return new EpisodeOutline(title, summary);
    }

    private int findFirstEpisodeOutlineIndex(String response) {
        int firstIndex = -1;

        Matcher markedMatcher = EPISODE_OUTLINE_START_PATTERN.matcher(response);
        if (markedMatcher.find()) {
            firstIndex = markedMatcher.start();
        }

        Matcher headingMatcher = EPISODE_OUTLINE_HEADING_PATTERN.matcher(response);
        if (headingMatcher.find()) {
            firstIndex = firstIndex < 0 ? headingMatcher.start() : Math.min(firstIndex, headingMatcher.start());
        }
        return firstIndex;
    }

    private String findEpisodeTitle(String rawBlock) {
        Matcher titleMatcher = TITLE_PATTERN.matcher(rawBlock);
        if (!titleMatcher.find()) {
            return "";
        }
        return titleMatcher.group(1).trim();
    }

    private String normalizeHeadingSuffix(String headingSuffix) {
        String normalized = StringUtils.trimToEmpty(headingSuffix);
        normalized = normalized.replaceFirst("^[：:\\-—\\s]+", "").trim();
        return normalized;
    }

    private int findFirstLineBreak(String text) {
        int lineFeed = text.indexOf('\n');
        int carriageReturn = text.indexOf('\r');
        if (lineFeed < 0) {
            return carriageReturn;
        }
        if (carriageReturn < 0) {
            return lineFeed;
        }
        return Math.min(lineFeed, carriageReturn);
    }

    private List<Integer> findMissingEpisodes(Map<Integer, EpisodeOutline> result, int totalEpisodes) {
        List<Integer> missingEpisodes = new ArrayList<>();
        for (int episodeNo = 1; episodeNo <= totalEpisodes; episodeNo++) {
            if (!result.containsKey(episodeNo)) {
                missingEpisodes.add(episodeNo);
            }
        }
        return missingEpisodes;
    }

    private String buildOutlinePreviewContent(String commonInfo, Map<Integer, EpisodeOutline> outlineMap, int totalEpisodes) {
        StringBuilder builder = new StringBuilder();
        if (StringUtils.isNotBlank(commonInfo)) {
            builder.append("###PROJECT_COMMON_INFO_START###\n")
                    .append(commonInfo.trim())
                    .append("\n###PROJECT_COMMON_INFO_END###");
        }

        for (int episodeNo = 1; episodeNo <= totalEpisodes; episodeNo++) {
            EpisodeOutline outline = outlineMap.get(episodeNo);
            if (outline == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append(formatEpisodeOutlineBlock(episodeNo, outline));
        }
        return builder.toString().trim();
    }

    private String formatEpisodeOutlineBlock(int episodeNo, EpisodeOutline outline) {
        return new StringBuilder()
                .append("###EPISODE_OUTLINE_START:").append(episodeNo).append("###\n")
                .append("标题：")
                .append(StringUtils.defaultIfBlank(outline.title(), "第" + episodeNo + "集"))
                .append("\n")
                .append(StringUtils.trimToEmpty(outline.summary()))
                .append("\n###EPISODE_OUTLINE_END###")
                .toString();
    }

    private String formatEpisodeRanges(List<Integer> episodeNos) {
        if (episodeNos.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        int rangeStart = episodeNos.get(0);
        int rangeEnd = rangeStart;
        for (int i = 1; i < episodeNos.size(); i++) {
            int episodeNo = episodeNos.get(i);
            if (episodeNo == rangeEnd + 1) {
                rangeEnd = episodeNo;
                continue;
            }
            appendEpisodeRange(builder, rangeStart, rangeEnd);
            builder.append('、');
            rangeStart = episodeNo;
            rangeEnd = episodeNo;
        }
        appendEpisodeRange(builder, rangeStart, rangeEnd);
        return builder.toString();
    }

    private void appendEpisodeRange(StringBuilder builder, int rangeStart, int rangeEnd) {
        if (rangeStart == rangeEnd) {
            builder.append(rangeStart);
            return;
        }
        builder.append(rangeStart).append('-').append(rangeEnd);
    }

    private String extractEpisodeTitle(String rawBlock, int episodeNo) {
        String title = findEpisodeTitle(rawBlock);
        if (StringUtils.isNotBlank(title)) {
            return title;
        }
        return "第" + episodeNo + "集";
    }

    private String stripEpisodeTitle(String rawBlock) {
        Matcher titleMatcher = TITLE_PATTERN.matcher(rawBlock);
        if (!titleMatcher.find()) {
            return rawBlock;
        }
        return titleMatcher.replaceFirst("").trim();
    }

    public void syncCharactersFromCommonInfo(Long userId, Long projectId, String commonInfo) {
        String normalizedCommonInfo = StringUtils.trimToNull(commonInfo);
        if (projectId == null || normalizedCommonInfo == null) {
            log.debug("通用信息为空，跳过角色同步: userId={}, projectId={}", userId, projectId);
            return;
        }

        log.debug("开始角色同步: userId={}, projectId={}, commonInfoLength={}",
                userId, projectId, normalizedCommonInfo.length());

        List<CharacterProfile> profiles = extractCharacterProfilesWithRetry(userId, normalizedCommonInfo);
        if (profiles.isEmpty()) {
            log.warn("角色同步已跳过，未提取到角色档案: projectId={}", projectId);
            return;
        }

        Map<String, Character> existingByKey = new LinkedHashMap<>();
        for (Character character : characterMapper.selectList(new LambdaQueryWrapper<Character>()
                .eq(Character::getProjectId, projectId)
                .orderByAsc(Character::getSortOrder)
                .orderByAsc(Character::getCreateTime))) {
            existingByKey.putIfAbsent(normalizeCharacterKey(character.getName()), character);
        }
        log.debug("角色同步源数据: projectId={}, extractedProfiles={}, existingCharacters={}",
                projectId, profiles.size(), existingByKey.size());

        int sortOrder = 1;
        int insertedCount = 0;
        int updatedCount = 0;
        for (CharacterProfile profile : profiles) {
            String normalizedKey = normalizeCharacterKey(profile.name());
            if (normalizedKey.isBlank()) {
                continue;
            }

            Character character = existingByKey.get(normalizedKey);
            boolean isNew = character == null;
            if (character == null) {
                character = new Character();
                character.setProjectId(projectId);
                character.setName(profile.name().trim());
                existingByKey.put(normalizedKey, character);
            }

            if (StringUtils.isNotBlank(profile.description())) {
                character.setDescription(profile.description().trim());
            }
            if (StringUtils.isNotBlank(profile.personality())) {
                character.setPersonality(profile.personality().trim());
            }
            if (StringUtils.isNotBlank(profile.appearance())) {
                character.setAppearance(profile.appearance().trim());
            }
            if (StringUtils.isNotBlank(profile.gender())) {
                character.setGender(profile.gender());
            }
            if (StringUtils.isNotBlank(profile.age())) {
                character.setAge(profile.age().trim());
            }
            if (character.getSortOrder() == null) {
                character.setSortOrder(sortOrder);
            }

            if (isNew) {
                characterMapper.insert(character);
                insertedCount++;
                log.debug("角色已新增: projectId={}, name={}, gender={}, age={}",
                        projectId, character.getName(), character.getGender(), character.getAge());
            } else {
                characterMapper.updateById(character);
                updatedCount++;
                log.debug("角色已更新: projectId={}, name={}, gender={}, age={}",
                        projectId, character.getName(), character.getGender(), character.getAge());
            }
            sortOrder += 1;
        }
        log.debug("角色同步完成: projectId={}, inserted={}, updated={}, totalProfiles={}",
                projectId, insertedCount, updatedCount, profiles.size());
    }

    private List<CharacterProfile> extractCharacterProfilesWithRetry(Long userId, String commonInfo) {
        if (StringUtils.isBlank(commonInfo)) {
            return List.of();
        }

        TextAiProvider textProvider = aiProviderFactory.getTextProvider(userId);
        String systemPrompt = """
                你是短剧角色资料整理助手。
                你的任务是从项目通用信息中提取核心人物，并输出严格 JSON。
                只允许输出 JSON，不要输出解释、Markdown、代码块。
                输出格式：
                {"characters":[{"name":"","gender":"female|male|other","age":"","appearance":"","personality":"","description":""}]}
                要求：
                1. 至少提取主要角色，name 不能为空。
                2. gender 只能是 female、male、other 之一。
                3. description 汇总身份定位、关系、动机、秘密或弱点。
                4. 不确定的字段用空字符串，不要编造新的角色名。
                """;
        String userPrompt = "请从以下项目通用信息中提取核心人物列表并输出 JSON：\n\n" + commonInfo;

        Exception lastException = null;
        String lastResponse = null;
        for (int attempt = 1; attempt <= CHARACTER_EXTRACTION_RETRY_ATTEMPTS; attempt++) {
            log.debug("角色提取尝试: userId={}, attempt={}, commonInfoLength={}",
                    userId, attempt, commonInfo.length());
            lastResponse = textProvider.chat(systemPrompt, userPrompt);
            try {
                List<CharacterProfile> profiles = parseCharacterProfilesResponse(lastResponse);
                if (!profiles.isEmpty()) {
                    log.debug("角色提取成功: userId={}, attempt={}, extractedProfiles={}",
                            userId, attempt, profiles.size());
                    return profiles;
                }
                log.debug("角色提取返回空档案: userId={}, attempt={}, responseLength={}",
                        userId, attempt, StringUtils.length(lastResponse));
            } catch (Exception ex) {
                lastException = ex;
                log.warn("角色提取解析失败: attempt={}/{}, responseLength={}, message={}",
                        attempt,
                        CHARACTER_EXTRACTION_RETRY_ATTEMPTS,
                        lastResponse != null ? lastResponse.length() : 0,
                        ex.getMessage());
            }
        }

        if (lastException != null) {
            log.warn("角色提取重试后仍失败: {}", lastException.getMessage());
        } else {
            log.warn("角色提取重试后无可用档案: lastResponseLength={}",
                    lastResponse != null ? lastResponse.length() : 0);
        }
        return List.of();
    }

    private List<CharacterProfile> parseCharacterProfilesResponse(String response) throws Exception {
        String jsonPayload = extractJsonPayload(response);
        if (StringUtils.isBlank(jsonPayload)) {
            return List.of();
        }

        JsonNode root = objectMapper.readTree(jsonPayload);
        JsonNode charactersNode = root.isArray() ? root : root.path("characters");
        if (!charactersNode.isArray()) {
            return List.of();
        }

        Map<String, CharacterProfile> profilesByKey = new LinkedHashMap<>();
        for (JsonNode node : charactersNode) {
            String name = textOrEmpty(node, "name").trim();
            String normalizedKey = normalizeCharacterKey(name);
            if (normalizedKey.isBlank()) {
                continue;
            }
            CharacterProfile profile = new CharacterProfile(
                    name,
                    normalizeCharacterGender(textOrEmpty(node, "gender")),
                    textOrEmpty(node, "age"),
                    textOrEmpty(node, "appearance"),
                    textOrEmpty(node, "personality"),
                    textOrEmpty(node, "description"));
            profilesByKey.putIfAbsent(normalizedKey, profile);
        }
        log.debug("已解析 AI 返回角色档案: profileCount={}", profilesByKey.size());
        return new ArrayList<>(profilesByKey.values());
    }

    private String extractJsonPayload(String response) {
        String normalized = StringUtils.trimToEmpty(response)
                .replace("```json", "")
                .replace("```", "")
                .trim();
        if (normalized.startsWith("{")) {
            int end = normalized.lastIndexOf('}');
            return end >= 0 ? normalized.substring(0, end + 1) : normalized;
        }
        if (normalized.startsWith("[")) {
            int end = normalized.lastIndexOf(']');
            return end >= 0 ? normalized.substring(0, end + 1) : normalized;
        }

        int objectStart = normalized.indexOf('{');
        int objectEnd = normalized.lastIndexOf('}');
        if (objectStart >= 0 && objectEnd > objectStart) {
            return normalized.substring(objectStart, objectEnd + 1);
        }

        int arrayStart = normalized.indexOf('[');
        int arrayEnd = normalized.lastIndexOf(']');
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            return normalized.substring(arrayStart, arrayEnd + 1);
        }
        return "";
    }

    private String textOrEmpty(JsonNode node, String fieldName) {
        if (node == null) {
            return "";
        }
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return "";
        }
        return StringUtils.trimToEmpty(value.asText());
    }

    private String normalizeCharacterGender(String gender) {
        String normalized = StringUtils.trimToEmpty(gender).toLowerCase();
        if (normalized.isBlank()) {
            return "other";
        }
        if (normalized.contains("female") || normalized.contains("女")) {
            return "female";
        }
        if (normalized.contains("male") || normalized.contains("男")) {
            return "male";
        }
        return "other";
    }

    private String normalizeCharacterKey(String name) {
        if (StringUtils.isBlank(name)) {
            return "";
        }
        return name.replaceAll("[\\s【】\\[\\]（）()：:·・•\\-—]", "").trim().toLowerCase();
    }

    private void upsertEpisodeOutline(Long projectId, int episodeNo, EpisodeOutline outline, String aiPrompt) {
        Script script = findScriptByProjectAndEpisode(projectId, episodeNo);
        boolean isNew = script == null;
        if (script == null) {
            script = new Script();
            script.setProjectId(projectId);
            script.setEpisodeNo(episodeNo);
            script.setStatus("draft");
        }

        script.setTitle(StringUtils.defaultIfBlank(outline.title(), "第" + episodeNo + "集"));
        script.setSummary(outline.summary());
        if (StringUtils.isNotBlank(aiPrompt)) {
            script.setAiPrompt(aiPrompt.trim());
        }

        if (isNew) {
            scriptMapper.insert(script);
            log.debug("分集大纲已新增: projectId={}, episodeNo={}, title={}",
                    projectId, episodeNo, script.getTitle());
        } else {
            scriptMapper.updateById(script);
            log.debug("分集大纲已更新: projectId={}, episodeNo={}, title={}",
                    projectId, episodeNo, script.getTitle());
        }
    }

    private Script upsertGeneratedScript(Long projectId, int episodeNo, String content, String aiPrompt) {
        Script script = findScriptByProjectAndEpisode(projectId, episodeNo);
        boolean isNew = script == null;
        if (script == null) {
            script = new Script();
            script.setProjectId(projectId);
            script.setEpisodeNo(episodeNo);
            script.setTitle("第" + episodeNo + "集");
        }

        script.setContent(content.trim());
        emitScriptSoftValidation(script.getContent(), "upsertGeneratedScript", projectId, episodeNo);
        if (StringUtils.isBlank(script.getTitle())) {
            script.setTitle("第" + episodeNo + "集");
        }
        if (StringUtils.isNotBlank(aiPrompt)) {
            script.setAiPrompt(aiPrompt.trim());
        }
        script.setStatus("ai_generated");

        if (isNew) {
            scriptMapper.insert(script);
            log.debug("生成剧本已新增: projectId={}, episodeNo={}, contentLength={}",
                    projectId, episodeNo, StringUtils.length(script.getContent()));
        } else {
            scriptMapper.updateById(script);
            log.debug("生成剧本已更新: projectId={}, episodeNo={}, contentLength={}",
                    projectId, episodeNo, StringUtils.length(script.getContent()));
        }
        return script;
    }

    private void emitScriptSoftValidation(String content, String source, Long projectId, Integer episodeNo) {
        String text = StringUtils.trimToEmpty(content);
        if (text.isBlank()) {
            return;
        }
        int novelToneHits = 0;
        for (String keyword : new String[]{"冷冷地看着", "心里一沉", "开口说道", "在心里", "空气仿佛"} ) {
            if (text.contains(keyword)) {
                novelToneHits++;
            }
        }
        int longLineCount = 0;
        int dialogueLineCount = 0;
        for (String line : text.split("\\R")) {
            String one = StringUtils.trimToEmpty(line);
            if (one.startsWith("台词：")) {
                dialogueLineCount++;
                if (one.length() > 28) {
                    longLineCount++;
                }
            }
        }
        if (novelToneHits > 0 || longLineCount > 0) {
            log.warn("剧本软校验: source={}, projectId={}, episodeNo={}, novelToneHits={}, longDialogueLines={}, dialogueLines={}",
                    source, projectId, episodeNo, novelToneHits, longLineCount, dialogueLineCount);
        } else {
            log.debug("剧本软校验通过: source={}, projectId={}, episodeNo={}, dialogueLines={}",
                    source, projectId, episodeNo, dialogueLineCount);
        }
    }

    private Script findScriptByProjectAndEpisode(Long projectId, Integer episodeNo) {
        if (projectId == null || episodeNo == null) {
            return null;
        }
        List<Script> scripts = scriptMapper.selectList(new LambdaQueryWrapper<Script>()
                .eq(Script::getProjectId, projectId)
                .eq(Script::getEpisodeNo, episodeNo)
                .orderByAsc(Script::getId)
                .last("limit 1"));
        return scripts.isEmpty() ? null : scripts.get(0);
    }

    private SceneBudget resolveSceneBudget(Project project) {
        int duration = resolveEpisodeDuration(project);
        int minScenes = Math.max(6, Math.min(18, duration / 35));
        int maxScenes = Math.max(minScenes + 2, Math.min(28, duration / 20));
        int beatsPerEpisode = Math.max(5, Math.min(10, duration / 30));
        return new SceneBudget(minScenes, maxScenes, beatsPerEpisode);
    }

    private int resolveEpisodeDuration(Project project) {
        if (project.getEpisodeDuration() != null && project.getEpisodeDuration() > 0) {
            return project.getEpisodeDuration();
        }
        return 60;
    }

    private int resolveAdaptiveOutlineChunkSize(ScriptGenerateRequest request, int totalEpisodes, int episodeDuration) {
        int chunkSize = OUTLINE_CHUNK_SIZE;
        String idea = request != null ? StringUtils.trimToEmpty(request.getIdea()) : "";
        // 单次请求生成的集数越多、单集越长，单次输出越容易触达 max_tokens，适当减小分批以降低截断风险
        if (totalEpisodes >= 16 || episodeDuration >= 120 || idea.length() >= 900) {
            chunkSize = 3;
        }
        if (totalEpisodes >= 20 || idea.length() >= 1800) {
            chunkSize = OUTLINE_CHUNK_MIN;
        }
        return Math.max(OUTLINE_CHUNK_MIN, Math.min(OUTLINE_CHUNK_SIZE, chunkSize));
    }

    private String resolveCreativeSeed(Project project, ScriptGenerateRequest request, boolean required) {
        String creativeSeed = StringUtils.trimToNull(request.getIdea());
        if (creativeSeed == null) {
            creativeSeed = StringUtils.trimToNull(project.getDescription());
        }
        if (required && creativeSeed == null) {
            throw new BusinessException("请先填写创意描述，或在项目描述中补充故事概念");
        }
        return creativeSeed;
    }

    private String resolveGenre(Project project, ScriptGenerateRequest request) {
        return ProjectStyleSupport.resolveGenre(StringUtils.defaultIfBlank(request.getGenre(), project.getGenre()));
    }

    private String resolveProjectType(Project project) {
        return ProjectStyleSupport.resolveProjectType(project != null ? project.getProjectType() : null);
    }

    private String formatNeighborOutline(String label, Script script) {
        if (script == null || StringUtils.isBlank(script.getSummary())) {
            return label + "：无\n";
        }
        return label + "：\n标题：" + StringUtils.defaultIfBlank(script.getTitle(), "未命名") + "\n" + script.getSummary().trim() + "\n";
    }

    private String formatOptionalAdjustment(String idea) {
        if (StringUtils.isBlank(idea)) {
            return "## 本次额外补充要求\n无\n";
        }
        return "## 本次额外补充要求\n" + idea.trim() + "\n";
    }
}
