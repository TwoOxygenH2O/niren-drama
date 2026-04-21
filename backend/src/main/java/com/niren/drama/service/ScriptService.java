package com.niren.drama.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.niren.drama.ai.AiProviderFactory;
import com.niren.drama.ai.TextAiProvider;
import com.niren.drama.dto.script.BatchScriptPreviewSaveRequest;
import com.niren.drama.dto.script.OutlinePreviewSaveRequest;
import com.niren.drama.dto.script.ScriptGenerateRequest;
import com.niren.drama.dto.script.ScriptSaveRequest;
import com.niren.drama.entity.Project;
import com.niren.drama.entity.Script;
import com.niren.drama.entity.TaskRecord;
import com.niren.drama.exception.BusinessException;
import com.niren.drama.mapper.ScriptMapper;
import com.niren.drama.mapper.TaskRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
    private static final Pattern EPISODE_SCRIPT_PATTERN = Pattern.compile("###EPISODE_START:(\\d+)###\\s*(.*?)\\s*###EPISODE_END###", Pattern.DOTALL);
    private static final Pattern EPISODE_OUTLINE_PATTERN = Pattern.compile("###EPISODE_OUTLINE_START:(\\d+)###\\s*(.*?)\\s*###EPISODE_OUTLINE_END###", Pattern.DOTALL);
    private static final Pattern PROJECT_COMMON_INFO_PATTERN = Pattern.compile("###PROJECT_COMMON_INFO_START###\\s*(.*?)\\s*###PROJECT_COMMON_INFO_END###", Pattern.DOTALL);
    private static final Pattern TITLE_PATTERN = Pattern.compile("^标题[：:]\\s*(.+)$", Pattern.MULTILINE);

    private final ScriptMapper scriptMapper;
    private final TaskRecordMapper taskRecordMapper;
    private final AiProviderFactory aiProviderFactory;
    private final ProjectService projectService;
    private final ObjectProvider<ScriptService> selfProvider;

    public TaskRecord startGenerateOutline(Long userId, ScriptGenerateRequest request) {
        Project project = projectService.getProject(userId, request.getProjectId());
        int totalEpisodes = resolveTotalEpisodes(project, request);
        resolveCreativeSeed(project, request, true);

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

        TextAiProvider textProvider = aiProviderFactory.getTextProvider(userId);
        String systemPrompt = buildOutlineSystemPrompt(resolveGenre(project, request), request.getStyle());

        StringBuilder commonInfoResponse = new StringBuilder();
        textProvider.streamChat(systemPrompt,
                buildProjectCommonInfoUserPrompt(project, request, totalEpisodes, episodeDuration),
                chunk -> {
                    commonInfoResponse.append(chunk);
                    chunkConsumer.accept(chunk);
                });

        String commonInfo = extractProjectCommonInfoForPrompt(commonInfoResponse.toString());
        for (int chunkStart = 1; chunkStart <= totalEpisodes; chunkStart += OUTLINE_CHUNK_SIZE) {
            int chunkEnd = Math.min(totalEpisodes, chunkStart + OUTLINE_CHUNK_SIZE - 1);
            chunkConsumer.accept("\n\n");
            textProvider.streamChat(systemPrompt,
                    buildEpisodeOutlineUserPrompt(project, request, commonInfo, chunkStart, chunkEnd, totalEpisodes, episodeDuration),
                    chunkConsumer);
        }
    }

    public void saveOutlinePreview(Long userId, OutlinePreviewSaveRequest request) {
        Project project = projectService.getProject(userId, request.getProjectId());
        int totalEpisodes = resolveTotalEpisodes(project, new ScriptGenerateRequest());
        String previewContent = StringUtils.trimToEmpty(request.getContent());
        if (previewContent.isBlank()) {
            throw new BusinessException("大纲预览内容不能为空");
        }

        String commonInfo = extractProjectCommonInfo(previewContent);
        Map<Integer, EpisodeOutline> outlineMap = parseEpisodeOutlinesFromPreview(previewContent, totalEpisodes);
        projectService.updateCommonInfo(userId, project.getId(), commonInfo);

        for (int episodeNo = 1; episodeNo <= totalEpisodes; episodeNo++) {
            EpisodeOutline outline = outlineMap.get(episodeNo);
            if (outline == null) {
                throw new BusinessException("第 " + episodeNo + " 集大纲缺失");
            }
            upsertEpisodeOutline(project.getId(), episodeNo, outline, request.getIdea());
        }
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

            updateTask(task, "RUNNING", 10, "正在生成项目通用信息与人物小传...");
            TextAiProvider textProvider = aiProviderFactory.getTextProvider(userId);
            String systemPrompt = buildOutlineSystemPrompt(resolveGenre(project, request), request.getStyle());
            String commonInfoResponse = textProvider.chat(systemPrompt, buildProjectCommonInfoUserPrompt(project, request, totalEpisodes, episodeDuration));
            String commonInfo = extractProjectCommonInfo(commonInfoResponse);
            projectService.updateCommonInfo(userId, project.getId(), commonInfo);

            Map<Integer, EpisodeOutline> outlineMap = new LinkedHashMap<>();
            int generatedCount = 0;
            for (int chunkStart = 1; chunkStart <= totalEpisodes; chunkStart += OUTLINE_CHUNK_SIZE) {
                int chunkEnd = Math.min(totalEpisodes, chunkStart + OUTLINE_CHUNK_SIZE - 1);
                int progress = 20 + Math.min(55, (int) (((chunkEnd * 1.0) / totalEpisodes) * 55));
                updateTask(task, "RUNNING", progress,
                        String.format("正在生成第 %d-%d 集分集大纲...", chunkStart, chunkEnd));

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
                outlineMap.putAll(chunkOutlines);
                generatedCount += chunkOutlines.size();
            }

            if (outlineMap.size() != totalEpisodes) {
                throw new BusinessException(String.format("分集大纲生成不完整，期望 %d 集，实际 %d 集", totalEpisodes, outlineMap.size()));
            }

            updateTask(task, "RUNNING", 85, "正在写入分集大纲...");
            for (Map.Entry<Integer, EpisodeOutline> entry : outlineMap.entrySet()) {
                upsertEpisodeOutline(request.getProjectId(), entry.getKey(), entry.getValue(), request.getIdea());
            }

            task.setStatus("SUCCESS");
            task.setProgress(100);
            task.setMessage(String.format("大纲生成完成，已写入 %d 集分集大纲与项目通用信息", generatedCount));
            task.setResult(String.valueOf(project.getId()));
            taskRecordMapper.updateById(task);
        } catch (Exception e) {
            log.error("Outline generation failed for task {}", taskId, e);
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
            int totalEpisodes = request.getTotalEpisodes() != null ? request.getTotalEpisodes() : endEpisode;
            GenerationMaterials materials = loadGenerationMaterials(project, startEpisode, endEpisode);

            updateTask(task, "RUNNING", 5,
                    String.format("开始批量生成第 %d-%d 集剧本...", startEpisode, endEpisode));
            TextAiProvider textProvider = aiProviderFactory.getTextProvider(userId);
            String systemPrompt = buildScriptSystemPrompt(resolveGenre(project, request), request.getStyle());

            updateTask(task, "RUNNING", 20,
                String.format("AI正在批量生成第 %d-%d 集剧本...", startEpisode, endEpisode));

            String batchPrompt = buildBatchScriptUserPrompt(request, materials, startEpisode, endEpisode, totalEpisodes);
            String batchContent = textProvider.chat(systemPrompt, batchPrompt);

            updateTask(task, "RUNNING", 70,
                String.format("正在拆分并保存第 %d-%d 集...", startEpisode, endEpisode));

            Map<Integer, String> episodeScripts = splitBatchScripts(batchContent, startEpisode, endEpisode);
            for (int ep = startEpisode; ep <= endEpisode; ep++) {
                String content = episodeScripts.get(ep);
                if (StringUtils.isBlank(content)) {
                    throw new BusinessException("第 " + ep + " 集剧本拆分失败");
                }
                upsertGeneratedScript(request.getProjectId(), ep, content, request.getIdea());
            }

            task.setStatus("SUCCESS");
            task.setProgress(100);
            task.setMessage(String.format("批量生成完成，共 %d 集（第 %d-%d 集）", batchCount, startEpisode, endEpisode));
            taskRecordMapper.updateById(task);
        } catch (Exception e) {
            log.error("Batch script generation failed for task {}", taskId, e);
            task.setStatus("FAILED");
            task.setMessage("批量生成失败: " + e.getMessage());
            taskRecordMapper.updateById(task);
        }
    }

    public void streamGenerateScriptPreview(Long userId, ScriptGenerateRequest request, Consumer<String> chunkConsumer) {
        Project project = projectService.getProject(userId, request.getProjectId());
        GenerationPlan plan = resolveGenerationPlan(project, request);
        request.setEpisodeNo(plan.singleEpisode());
        request.setTotalEpisodes(plan.totalEpisodes());
        request.setStartEpisode(plan.startEpisode());
        request.setEndEpisode(plan.endEpisode());

        TextAiProvider textProvider = aiProviderFactory.getTextProvider(userId);
        String systemPrompt = buildScriptSystemPrompt(resolveGenre(project, request), request.getStyle());

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

            updateTask(task, "RUNNING", 10, "开始生成剧本...");

            TextAiProvider textProvider = aiProviderFactory.getTextProvider(userId);
            String systemPrompt = buildScriptSystemPrompt(resolveGenre(project, request), request.getStyle());
            String userPrompt = buildScriptUserPrompt(request, materials, episodeNo);

            updateTask(task, "RUNNING", 30, "AI正在生成剧本内容...");
            String scriptContent = textProvider.chat(systemPrompt, userPrompt);

            updateTask(task, "RUNNING", 80, "保存剧本内容...");
            Script script = upsertGeneratedScript(request.getProjectId(), episodeNo, scriptContent, request.getIdea());

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
        if (content != null) script.setContent(content);
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
        return 1;
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

        return new GenerationMaterials(project, commonInfo, scriptsByEpisode);
    }

    private String buildBatchScriptUserPrompt(ScriptGenerateRequest request,
                                              GenerationMaterials materials,
                                              int startEpisode,
                                              int endEpisode,
                                              int totalEpisodes) {
        SceneBudget sceneBudget = resolveSceneBudget(materials.project());
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
                3) 不要输出任何解释文字，只输出多集剧本内容和标记。
                4) 每一集按约 %d 秒体量，控制在 %d-%d 个场景，保证节奏紧凑、承接自然。
                5) 剧本必须为后续分镜拆解服务，每场都要写清时间、地点、出场角色、动作与情绪。
                6) 场景格式统一：
                   第N场 [场景/时间/地点]
                   角色名：（动作/表情）“台词”
                7) 连续集剧情要有承接，但每一集都要有明确钩子。
                8) 角色语言和行为必须严格符合项目通用信息中的人物小传。
                """,
                startEpisode,
                endEpisode,
                totalEpisodes,
                materials.project().getName(),
                resolveGenre(materials.project(), request),
                resolveEpisodeDuration(materials.project()),
                materials.commonInfo(),
                outlines,
                formatOptionalAdjustment(request.getIdea()),
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

    private String buildOutlineSystemPrompt(String genre, String style) {
        String genreText = genre != null ? genre : "都市言情";
        String styleText = style != null ? style : "";
        return String.format("""
                # 角色定位
                你是一位负责短剧项目统筹策划的总编剧，要先搭建后续剧本、分镜、角色一致性都会复用的稳定设定。

                # 工作目标
                - 先产出可长期复用的项目通用信息：人物小传、关系线、世界规则、可复用场景、伏笔和季级主线。
                - 再产出每一集的大纲，确保节奏、爽点和钩子都服务于后续完整剧本创作。
                - 所有输出必须具体、可执行，不能空泛。

                # 输出原则
                - 角色小传要写清动机、弱点、秘密、关系、语言风格。
                - 分集大纲要能直接交给编剧扩写成完整剧本，不要只写一句话简介。
                - 设定必须前后一致，不允许互相打架。
                - 节奏必须符合短剧商业化表达和竖屏内容习惯。

                # 题材风格
                %s %s
                """, genreText, styleText);
    }

    private String buildScriptSystemPrompt(String genre, String style) {
        String genreText = genre != null ? genre : "都市言情";
        String styleText = style != null ? style : "";
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
                ## 钩子设计（黄金3秒 + 30秒法则）
                - 第1秒：视觉冲击或悬念台词（如"三年前你亲手毁了我，三年后我带着十亿回来了"）
                - 前30秒：必须出现核心冲突事件，让用户停不下来
                - 每集结尾：必须设置强悬念钩子（反转/新危机/身份揭露），驱动付费解锁下一集
                
                ## 爽点节奏模板（每集必须包含）
                - 每2分钟一个小爽点（打脸/逆袭/甜蜜暴击/身份揭露）
                - 每集至少3个大爽点（核心冲突升级/重大反转/高燃台词）
                - 集末反转：必须在最后30秒制造新的强悬念
                
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
                
                # 题材风格
                %s %s
                """, genreText, styleText);
    }

     private String buildProjectCommonInfoUserPrompt(Project project,
                                                                     ScriptGenerateRequest request,
                                                                     int totalEpisodes,
                                                                     int episodeDuration) {
          return String.format("""
                     请为以下短剧项目先生成后续整条生产链路都会用到的项目通用信息。

                     项目名称：%s
                     题材：%s
                     总集数：%d
                     单集时长：约 %d 秒
                     用户提供的故事创意/补充设定：
                     %s

                     输出要求：
                     1) 只输出以下标记包裹的正文，不要解释：
                         ###PROJECT_COMMON_INFO_START###
                         ...正文...
                         ###PROJECT_COMMON_INFO_END###
                     2) 正文必须包含以下模块，并使用中文小标题：
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
                     resolveGenre(project, request),
                     totalEpisodes,
                     episodeDuration,
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
          return String.format("""
                     请基于以下项目通用信息，为第 %d 到第 %d 集生成分集大纲。

                     项目名称：%s
                     总集数：%d
                     单集时长：约 %d 秒

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
                     %s
                     """,
                     startEpisode,
                     endEpisode,
                     project.getName(),
                     totalEpisodes,
                     episodeDuration,
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
        String response = textProvider.chat(
                systemPrompt,
                buildEpisodeOutlineUserPrompt(project, request, commonInfo, startEpisode, endEpisode, totalEpisodes, episodeDuration));

        try {
            return parseEpisodeOutlines(response, startEpisode, endEpisode);
        } catch (BusinessException ex) {
            int responseLength = response != null ? response.length() : 0;
            log.warn("Outline chunk {}-{} parse incomplete, responseLength={}, message={}",
                    startEpisode, endEpisode, responseLength, ex.getMessage());

            if (startEpisode >= endEpisode) {
                EpisodeOutline fallback = tryParseSingleEpisodeOutline(response, startEpisode);
                if (fallback != null) {
                    Map<Integer, EpisodeOutline> single = new LinkedHashMap<>();
                    single.put(startEpisode, fallback);
                    return single;
                }
                throw new BusinessException(String.format("第 %d 集大纲解析失败，请重试；若仍失败请缩短创意描述或减少设定长度", startEpisode));
            }

            int midEpisode = (startEpisode + endEpisode) / 2;
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
            return merged;
        }
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
          Script currentScript = materials.scriptsByEpisode().get(episodeNo);
        return String.format("""
                     请根据以下项目通用信息和分集大纲，生成第 %d 集完整剧本（共 %d 集中的当前集）。
                
                     ## 项目基础信息
                     - 项目名称：%s
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
                
                角色名：（表情+动作）"对白"
                
                旁白：旁白内容
                
                [镜头指示：推/拉/摇/移/特写 等]
                
                要求：
                - 包含 %d-%d 个场景
                - 开场 30 秒内进入冲突
                - 结尾留强钩子
                """,
                episodeNo,
                materials.project().getName(),
                resolveGenre(materials.project(), request),
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

    private String extractProjectCommonInfoForPrompt(String response) {
        try {
            return extractProjectCommonInfo(response);
        } catch (BusinessException ex) {
            return StringUtils.trimToEmpty(response);
        }
    }

    private Map<Integer, EpisodeOutline> parseEpisodeOutlines(String response, int startEpisode, int endEpisode) {
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

        if (result.size() != (endEpisode - startEpisode + 1)) {
            throw new BusinessException(String.format("分集大纲解析失败：第 %d-%d 集标记不完整", startEpisode, endEpisode));
        }
        return result;
    }

    private Map<Integer, EpisodeOutline> parseEpisodeOutlinesFromPreview(String response, int totalEpisodes) {
        Map<Integer, EpisodeOutline> result = new LinkedHashMap<>();
        Matcher matcher = EPISODE_OUTLINE_PATTERN.matcher(response);
        while (matcher.find()) {
            int episodeNo = Integer.parseInt(matcher.group(1));
            if (episodeNo < 1 || episodeNo > totalEpisodes) {
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

        if (result.size() != totalEpisodes) {
            throw new BusinessException(String.format("大纲预览解析失败：应包含 1-%d 集完整标记", totalEpisodes));
        }
        return result;
    }

    private String extractEpisodeTitle(String rawBlock, int episodeNo) {
        Matcher titleMatcher = TITLE_PATTERN.matcher(rawBlock);
        if (titleMatcher.find()) {
            return titleMatcher.group(1).trim();
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
        } else {
            scriptMapper.updateById(script);
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
        if (StringUtils.isBlank(script.getTitle())) {
            script.setTitle("第" + episodeNo + "集");
        }
        if (StringUtils.isNotBlank(aiPrompt)) {
            script.setAiPrompt(aiPrompt.trim());
        }
        script.setStatus("ai_generated");

        if (isNew) {
            scriptMapper.insert(script);
        } else {
            scriptMapper.updateById(script);
        }
        return script;
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
        return 180;
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
        return StringUtils.defaultIfBlank(request.getGenre(), project.getGenre());
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
