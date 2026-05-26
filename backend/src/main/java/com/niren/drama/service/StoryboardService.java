package com.niren.drama.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.niren.drama.ai.AiProviderFactory;
import com.niren.drama.ai.ImageAiProvider;
import com.niren.drama.ai.TextAiProvider;
import com.niren.drama.ai.TtsProvider;
import com.niren.drama.ai.VoiceInfo;
import com.niren.drama.ai.trace.AiCallTrace;
import com.niren.drama.ai.trace.AiTraceContext;
import com.niren.drama.common.DramaTextSanitizer;
import com.niren.drama.common.ProjectStyleSupport;
import com.niren.drama.dto.storyboard.StoryboardGenerateRequest;
import com.niren.drama.dto.storyboard.StoryboardPreviewSaveRequest;
import com.niren.drama.entity.Project;
import com.niren.drama.entity.Script;
import com.niren.drama.entity.Character;
import com.niren.drama.entity.Scene;
import com.niren.drama.entity.Storyboard;
import com.niren.drama.entity.TaskRecord;
import com.niren.drama.exception.BusinessException;
import com.niren.drama.mapper.CharacterMapper;
import com.niren.drama.mapper.SceneMapper;
import com.niren.drama.mapper.ScriptMapper;
import com.niren.drama.mapper.StoryboardMapper;
import com.niren.drama.mapper.TaskRecordMapper;
import com.niren.drama.ai.AiOutputTruncatedException;
import com.niren.drama.ai.ChatMessage;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.niren.drama.service.storage.StoredAsset;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class StoryboardService {

    private static final int MAX_TASK_TRACE_CALLS = 20;

    private final StoryboardMapper storyboardMapper;
    private final ScriptMapper scriptMapper;
    private final TaskRecordMapper taskRecordMapper;
    private final CharacterMapper characterMapper;
    private final SceneMapper sceneMapper;
    private final AiProviderFactory aiProviderFactory;
    private final ProjectService projectService;
    private final CostEstimationService costEstimationService;
    private final PublicAssetStorageService publicAssetStorageService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<StoryboardService> selfProvider;

    /** Image generation style */
    private static final String PORTRAIT_IMAGE_STYLE = "vivid";
        private static final String DEFAULT_IMAGE_NEGATIVE_PROMPT = String.join("，",
            "中年感",
            "老年感",
            "显老",
            "成熟老态",
            "面部模糊",
            "脸部被遮挡",
            "五官崩坏",
            "脸部畸形",
            "路人脸",
            "男女特征混乱",
            "低清晰度",
            "低分辨率",
            "人物漂移",
            "多余肢体",
            "手部畸形");

    private static final int STORYBOARD_CONTINUATION_MAX_ATTEMPTS = 4;
    private static final int STORYBOARD_CONTINUATION_TAIL_LENGTH = 600;
    private static final int STORYBOARD_CONTINUATION_OVERLAP_LENGTH = 200;
    private static final int STORYBOARD_SCENE_BATCH_TARGET_CHARS = 200;
    private static final int STORYBOARD_SCENE_BATCH_MIN_CHARS = 50;
    private static final int STORYBOARD_SCENE_BATCH_MAX_LINES = 4;
    private static final int IMAGE_PROMPT_MAX_CHARS = 760;
    private static final int IMAGE_NEGATIVE_MAX_CHARS = 220;
    private static final int VIDEO_PROMPT_MAX_CHARS = 420;
    private static final int TTS_INSTRUCTION_MAX_CHARS = 280;
    private static final int TASK_RESULT_MAX_CHARS = 15000;
    private static final int TASK_RESULT_MAX_CALLS = 8;

    @Value("${niren.drama.line-doctor.enabled:false}")
    private boolean lineDoctorEnabled;
    @Value("${niren.drama.dialogue.strip-speaker-prefix:true}")
    private boolean stripDialogueSpeakerPrefix;
    @Value("${niren.drama.dialogue.strip-narration-speaker-prefix:false}")
    private boolean stripNarrationSpeakerPrefix;
    @Value("${niren.drama.dialogue.dedupe-narration:true}")
    private boolean dedupeDialogueNarration;
    @Value("${niren.compose.subtitle.include-narration:false}")
    private boolean enrichSubtitleIncludeNarration;
    @Value("${niren.compose.subtitle.strip-speaker-prefix:true}")
    private boolean enrichSubtitleStripSpeakerPrefix;

    /** Enable image reuse for same scene+character+angle combinations */
    @Value("${niren.cost.image-reuse-enabled:true}")
    private boolean imageReuseEnabled;
    @Value("${niren.cost.video-ai-enabled:false}")
    private boolean costVideoAiEnabled;
    @Value("${niren.cost.video-ai-shot-ratio:0.18}")
    private double costVideoAiShotRatio;
    @Value("${niren.recommend.dynamic.enabled:true}")
    private boolean dynamicRecommendEnabled;
    @Value("${niren.recommend.dynamic.max-ratio-per-episode:0.2}")
    private double dynamicRecommendMaxRatioPerEpisode;
    @Value("${niren.recommend.dynamic.max-count-per-episode:10}")
    private int dynamicRecommendMaxCountPerEpisode;
    @Value("${niren.recommend.dynamic.min-score-to-recommend:60}")
    private int dynamicRecommendMinScoreToRecommend;
    @Value("${niren.dynamic-priority.target-dynamic-ratio:0.99}")
    private double targetDynamicRatio;
    @Value("${niren.dynamic-priority.force-dynamic-by-default:true}")
    private boolean forceDynamicByDefault;
    @Value("${niren.motion-tier.enabled:true}")
    private boolean motionTierEnabled;
    @Value("${niren.image.retry.max-attempts:3}")
    private int imageRetryMaxAttempts;
    @Value("${niren.image.retry.backoff-ms:900}")
    private long imageRetryBackoffMs;
    @Value("${niren.image.retry.concurrency-throttle-ms:120}")
    private long imageRetryThrottleMs;

    public TaskRecord startGenerateStoryboard(Long userId, StoryboardGenerateRequest request) {
        log.debug("创建分镜任务: userId={}, projectId={}, scriptId={}",
            userId, request.getProjectId(), request.getScriptId());
        TaskRecord task = new TaskRecord();
        task.setProjectId(request.getProjectId());
        task.setUserId(userId);
        task.setTaskType("STORYBOARD_GEN");
        task.setStatus("PENDING");
        task.setProgress(0);
        task.setMessage("任务已提交，等待执行...");
        task.setRefId(request.getScriptId());
        taskRecordMapper.insert(task);
        selfProvider.getObject().generateStoryboardAsync(userId, request, task.getId());
        return task;
    }

    /**
     * 删除该剧本下已有分镜（含预览与已保存），并重新提交异步拆解任务。
     */
    public TaskRecord startRegenerateStoryboard(Long userId, Long projectId, Long scriptId) {
        requireScript(scriptId, projectId);
        storyboardMapper.delete(new LambdaQueryWrapper<Storyboard>()
                .eq(Storyboard::getProjectId, projectId)
                .eq(Storyboard::getScriptId, scriptId));
        StoryboardGenerateRequest req = new StoryboardGenerateRequest();
        req.setProjectId(projectId);
        req.setScriptId(scriptId);
        return startGenerateStoryboard(userId, req);
    }

    public void streamGenerateStoryboard(Long userId, StoryboardGenerateRequest request, java.util.function.Consumer<String> chunkConsumer, java.util.function.Consumer<String> progressConsumer) {
        log.debug("开始流式生成分镜预览: userId={}, projectId={}, scriptId={}",
            userId, request.getProjectId(), request.getScriptId());
        Project project = projectService.getProject(userId, request.getProjectId());
        Script script = requireScript(request.getScriptId(), request.getProjectId());
        TextAiProvider textProvider = aiProviderFactory.getTextProvider(userId);
        String systemPrompt = buildStoryboardSystemPrompt(project);
        generateStoryboardPreviewByScenes(textProvider, systemPrompt, script, request, project, chunkConsumer, progressConsumer);
    }

    private void generateStoryboardPreviewByScenes(TextAiProvider textProvider,
                                                   String systemPrompt,
                                                   Script script,
                                                   StoryboardGenerateRequest request,
                                                   Project project,
                                                   java.util.function.Consumer<String> chunkConsumer,
                                                   java.util.function.Consumer<String> progressConsumer) {
        List<ScriptScene> scenes = splitScriptScenes(script.getContent());
        if (scenes.isEmpty()) {
            throw new BusinessException("剧本内容为空，无法拆分场景");
        }

        boolean isStream = chunkConsumer != null;
        log.debug("分镜预览场景拆分完成: projectId={}, scriptId={}, sceneCount={}, streaming={}",
            request.getProjectId(), request.getScriptId(), scenes.size(), isStream);
        if (isStream) {
            chunkConsumer.accept("{\n  \"shots\": [\n");
        }

        int nextShotNo = 1;
        boolean firstShotEmitted = false;
        
        List<Storyboard> savedShots = storyboardMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Storyboard>()
                .eq(Storyboard::getProjectId, request.getProjectId())
                .eq(Storyboard::getScriptId, request.getScriptId())
                .eq(Storyboard::getStatus, "preview_draft")
                .orderByAsc(Storyboard::getShotNo));

        int startSceneIndex = 0;

        if (!savedShots.isEmpty()) {
            java.time.LocalDateTime scriptUpdate = script.getUpdateTime();
            java.time.LocalDateTime previewTime = savedShots.get(0).getCreateTime();
            if (scriptUpdate != null && scriptUpdate.isAfter(previewTime)) {
                log.debug("剧本已变更，分镜预览草稿失效: projectId={}, scriptId={}, savedShots={}",
                        request.getProjectId(), request.getScriptId(), savedShots.size());
                storyboardMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Storyboard>()
                        .eq(Storyboard::getProjectId, request.getProjectId())
                        .eq(Storyboard::getScriptId, request.getScriptId())
                        .eq(Storyboard::getStatus, "preview_draft"));
            } else {
                long maxSceneId = -1;
                for (Storyboard s : savedShots) {
                    if (s.getSceneId() != null && s.getSceneId() > maxSceneId) {
                        maxSceneId = s.getSceneId();
                    }
                    if (s.getShotNo() >= nextShotNo) {
                        nextShotNo = s.getShotNo() + 1;
                    }
                    // re-emit
                    if (isStream) {
                        try {
                            String shotJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(s);
                            String chunk = (firstShotEmitted ? ",\n" : "") + "    " + shotJson.replace("\n", "\n    ");
                            chunkConsumer.accept(chunk);
                            firstShotEmitted = true;
                        } catch (Exception ignored) {}
                    }
                }
                log.debug("从已保存草稿恢复分镜预览: projectId={}, scriptId={}, resumedShots={}, nextShotNo={}, startSceneIndex={}",
                        request.getProjectId(), request.getScriptId(), savedShots.size(), nextShotNo, startSceneIndex);
                startSceneIndex = (int) maxSceneId + 1;
            }
        }

        for (int sceneIndex = startSceneIndex; sceneIndex < scenes.size(); sceneIndex++) {
            log.debug("生成分镜场景: projectId={}, scriptId={}, sceneIndex={}, sceneLabel={}",
                    request.getProjectId(), request.getScriptId(), sceneIndex, scenes.get(sceneIndex).displayName());
            ArrayNode sceneShots = generateSceneShotsWithFallback(
                    textProvider,
                    systemPrompt,
                    scenes,
                    sceneIndex,
                    request,
                    project,
                    progressConsumer,
                    STORYBOARD_SCENE_BATCH_TARGET_CHARS);

            for (JsonNode shotNode : sceneShots) {
                ObjectNode shotObject = shotNode.isObject()
                        ? ((ObjectNode) shotNode).deepCopy()
                        : objectMapper.convertValue(shotNode, ObjectNode.class);
                shotObject.put("shotNo", nextShotNo++);

                // parse into Storyboard locally to save
                Storyboard draftShot = objectMapper.convertValue(shotObject, Storyboard.class);
                draftShot.setProjectId(request.getProjectId());
                draftShot.setScriptId(request.getScriptId());
                draftShot.setSceneId((long) sceneIndex); // hack to remember sceneIndex
                draftShot.setStatus("preview_draft");
                if (script.getEpisodeNo() != null) {
                    draftShot.setEpisodeNo(script.getEpisodeNo());
                } else {
                    draftShot.setEpisodeNo(1);
                }
                applyDramaTextProcessing(
                        draftShot,
                        project.getUserId(),
                        project,
                        textOrNull(shotObject, "characterName"));
                storyboardMapper.insert(draftShot);
if (isStream) {
                    try {
                        String shotJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(shotObject);
                        String chunk = (firstShotEmitted ? ",\n" : "") + "    " + shotJson.replace("\n", "\n    ");
                        chunkConsumer.accept(chunk);
                        firstShotEmitted = true;
                    } catch (Exception e) {
                        log.warn("分镜对象序列化失败", e);
                    }
                }
            }
        }

        if (isStream) {
            chunkConsumer.accept("\n  ]\n}");
        }
    }

    private ArrayNode generateSceneShotsWithFallback(TextAiProvider textProvider,
                                                     String systemPrompt,
                                                     List<ScriptScene> scenes,
                                                     int sceneIndex,
                                                     StoryboardGenerateRequest request,
                                                     Project project,
                                                     java.util.function.Consumer<String> progressConsumer,
                                                     int batchMaxChars) {
        ScriptScene scene = scenes.get(sceneIndex);
        List<SceneBatch> batches = splitSceneBatches(scene, batchMaxChars);
        ArrayNode sceneShots = objectMapper.createArrayNode();

        try {
            for (SceneBatch batch : batches) {
                String progressLabel = buildSceneBatchProgressLabel(scenes.size(), sceneIndex, batch);
                if (progressConsumer != null) {
                    progressConsumer.accept(progressLabel);
                }
                String batchPrompt = buildStoryboardSceneBatchUserPrompt(scenes, sceneIndex, batch, project);
                String batchStoryboardJson = generateStoryboardPreviewContent(
                        textProvider,
                        systemPrompt,
                        batchPrompt,
                        request,
                        null,
                        progressConsumer,
                        progressLabel);
                ArrayNode batchShots = parseGeneratedSceneShots(batchStoryboardJson, scene, batch);
                batchShots.forEach(sceneShots::add);
            }
        } catch (BusinessException ex) {
            if (shouldRetrySceneWithSmallerBatches(ex, batchMaxChars)) {
                int smallerBatchChars = Math.max(STORYBOARD_SCENE_BATCH_MIN_CHARS, batchMaxChars / 2);
                if (smallerBatchChars < batchMaxChars) {
                    log.warn("分镜场景生成失败，准备缩小批次重试: scene={}, batchSize={}, reason={}",
                            scene.sceneNo(),
                            batchMaxChars,
                            ex.getMessage());
                    if (progressConsumer != null) {
                        progressConsumer.accept(String.format("第 %d/%d 场较长，正在拆成更小片段重试", sceneIndex + 1, scenes.size()));
                    }
                    return generateSceneShotsWithFallback(
                            textProvider,
                            systemPrompt,
                            scenes,
                            sceneIndex,
                            request,
                            project,
                            progressConsumer,
                            smallerBatchChars);
                }
            }
            throw ex;
        }

        if (sceneShots.isEmpty()) {
            throw new BusinessException(String.format("第 %d 场分镜生成结果为空，请重试", scene.sceneNo()));
        }
        return sceneShots;
    }

    private ArrayNode parseGeneratedSceneShots(String storyboardJson, ScriptScene scene, SceneBatch batch) {
        JsonNode sceneRoot;
        JsonNode shotsNode;
        try {
            sceneRoot = extractStoryboardRoot(storyboardJson);
            shotsNode = resolveShotsNode(sceneRoot);
        } catch (IOException e) {
            throw new BusinessException(String.format(
                    "第 %d 场第 %d/%d 段分镜生成结果解析失败，请重试",
                    scene.sceneNo(),
                    batch.batchIndex(),
                    batch.totalBatches()));
        }
        if (!shotsNode.isArray() || shotsNode.isEmpty()) {
            throw new BusinessException(String.format(
                    "第 %d 场第 %d/%d 段分镜生成结果为空，请重试",
                    scene.sceneNo(),
                    batch.batchIndex(),
                    batch.totalBatches()));
        }

        ArrayNode normalizedShots = objectMapper.createArrayNode();
        for (JsonNode shotNode : shotsNode) {
            ObjectNode shotObject = shotNode.isObject()
                    ? ((ObjectNode) shotNode).deepCopy()
                    : objectMapper.convertValue(shotNode, ObjectNode.class);
            if (!hasText(textOrNull(shotObject, "sceneName")) && hasText(scene.sceneLabel())) {
                shotObject.put("sceneName", scene.sceneLabel());
            }
            normalizedShots.add(shotObject);
        }
        return normalizedShots;
    }

    private boolean shouldRetrySceneWithSmallerBatches(BusinessException ex, int batchMaxChars) {
        if (batchMaxChars <= STORYBOARD_SCENE_BATCH_MIN_CHARS) {
            return false;
        }
        String message = ex.getMessage();
        if (!hasText(message)) {
            return false;
        }
        return message.contains("长度上限")
                || message.contains("未完成")
                || message.contains("解析失败")
                || message.contains("结果为空");
    }

    private String generateStoryboardPreviewContent(TextAiProvider textProvider,
                                                    String systemPrompt,
                                                    String userPrompt,
                                                    StoryboardGenerateRequest request,
                                                    java.util.function.Consumer<String> chunkConsumer,
                                                    java.util.function.Consumer<String> progressConsumer,
                                                    String phaseText) {
        StringBuilder accumulatedContent = new StringBuilder();
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("user", userPrompt));

        for (int attempt = 0; attempt < STORYBOARD_CONTINUATION_MAX_ATTEMPTS; attempt++) {
            boolean streamDirectly = chunkConsumer != null && attempt == 0;
            StringBuilder attemptContent = new StringBuilder();
            try {
                if (progressConsumer != null && attempt > 0) {
                    progressConsumer.accept(String.format("%s，正在继续补全输出 (第 %d 次，可能耗时较长)...", phaseText, attempt));
                }
                textProvider.streamChatWithHistory(systemPrompt, messages, chunk -> {
                    if (streamDirectly) {
                        accumulatedContent.append(chunk);
                        chunkConsumer.accept(chunk);
                        return;
                    }
                    attemptContent.append(chunk);
                });
            } catch (AiOutputTruncatedException ex) {
                if (!streamDirectly) {
                    flushContinuationChunk(accumulatedContent, attemptContent, chunkConsumer);
                }
                if (isCompleteStoryboardPreview(accumulatedContent.toString(), request)) {
                    return normalizeStoryboardPreviewContent(accumulatedContent.toString());
                }
                if (attempt + 1 >= STORYBOARD_CONTINUATION_MAX_ATTEMPTS) {
                    throw new BusinessException("分镜预览生成达到长度上限，多次续写后仍未完成，请缩小当前剧本范围或减少镜头复杂度后重试");
                }
                log.info("分镜预览在第{}次尝试被截断，继续生成", attempt + 1);
                messages = buildStoryboardContinuationMessages(userPrompt, accumulatedContent.toString(), true);
                continue;
            }

            if (!streamDirectly) {
                flushContinuationChunk(accumulatedContent, attemptContent, chunkConsumer);
            }
            if (isCompleteStoryboardPreview(accumulatedContent.toString(), request)) {
                return normalizeStoryboardPreviewContent(accumulatedContent.toString());
            }
            if (attempt + 1 >= STORYBOARD_CONTINUATION_MAX_ATTEMPTS) {
                break;
            }
            log.info("分镜预览在第{}次尝试后仍不完整，继续请求续写", attempt + 1);
            messages = buildStoryboardContinuationMessages(userPrompt, accumulatedContent.toString(), false);
        }

        throw new BusinessException("分镜预览生成未完成，请缩小当前剧本范围或减少镜头复杂度后重试");
    }

    private void flushContinuationChunk(StringBuilder accumulatedContent,
                                        StringBuilder attemptContent,
                                        java.util.function.Consumer<String> chunkConsumer) {
        String appendedContent = mergeContinuationContent(accumulatedContent, attemptContent.toString());
        if (hasText(appendedContent) && chunkConsumer != null) {
            chunkConsumer.accept(appendedContent);
        }
    }

    private String mergeContinuationContent(StringBuilder accumulatedContent, String continuationContent) {
        if (!hasText(continuationContent)) {
            return "";
        }
        int existingLen = accumulatedContent.length();
        int searchTailLen = Math.min(existingLen, STORYBOARD_CONTINUATION_TAIL_LENGTH);
        if (searchTailLen == 0) {
            accumulatedContent.append(continuationContent);
            return continuationContent;
        }

        String tail = accumulatedContent.substring(existingLen - searchTailLen);
        String overlapCandidate = continuationContent.substring(0, Math.min(continuationContent.length(), STORYBOARD_CONTINUATION_OVERLAP_LENGTH));

        int overlapStart = -1;
        for (int i = Math.min(tail.length(), overlapCandidate.length()); i > 10; i--) {
            String suffix = tail.substring(tail.length() - i);
            String prefix = overlapCandidate.substring(0, i);
            if (suffix.equals(prefix)) {
                overlapStart = i;
                break;
            }
        }

        String newContent;
        if (overlapStart > 0) {
            newContent = continuationContent.substring(overlapStart);
        } else {
            newContent = continuationContent;
        }
        accumulatedContent.append(newContent);
        return newContent;
    }

    private List<ChatMessage> buildStoryboardContinuationMessages(String originalPrompt, String currentContent, boolean explicitLengthTruncation) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("user", originalPrompt));
        messages.add(new ChatMessage("assistant", currentContent));
        String continuePrompt = explicitLengthTruncation
                ? "上一段输出因长度限制被截断了。请**直接从截断的地方继续写**，不要重复已经输出过的内容，也不要说多余的话，保证最终拼起来是一个完整的JSON结构。"
                : "看起来你的输出中 JSON 结构尚未完整闭合（例如缺少 `]}`）。请直接继续补全剩余的内容，不要重复已有的，只补齐剩下的部分。";
        messages.add(new ChatMessage("user", continuePrompt));
        return messages;
    }

    private boolean isCompleteStoryboardPreview(String content, StoryboardGenerateRequest request) {
        if (!hasText(content)) return false;
        String trimmed = content.trim();
        if (trimmed.endsWith("]") || trimmed.endsWith("}")) {
            try {
                String normalizedContent = normalizeStoryboardPreviewContent(content);
                List<Storyboard> shots = parseStoryboardJson(normalizedContent, request, true);
                return !shots.isEmpty();
            } catch (BusinessException ex) {
                return false; // Not a valid JSON or parsing failed completely
            }
        }
        return false;
    }

    private List<ScriptScene> splitScriptScenes(String content) {
        List<ScriptScene> scenes = new ArrayList<>();
        if (!hasText(content)) {
            return scenes;
        }
        String[] lines = content.split("\\r?\\n");
        Pattern scenePattern = Pattern.compile("^\\s*第([零一二三四五六七八九十百千\\d]+)场\\s*(.*)$");

        int currentSceneNo = 0;
        String currentHeader = "";
        String currentLabel = "";
        StringBuilder currentContent = new StringBuilder();

        for (String line : lines) {
            if (!hasText(line.trim())) {
                currentContent.append("\n");
                continue;
            }
            Matcher matcher = scenePattern.matcher(line);
            if (matcher.find()) {
                if (currentContent.toString().trim().length() > 0) {
                    appendScriptScene(scenes, currentSceneNo, currentHeader, currentLabel, currentContent);
                }
                currentSceneNo++;
                currentHeader = matcher.group(0).trim();
                currentLabel = matcher.group(2).trim();
                currentContent.setLength(0);
                currentContent.append(line).append("\n");
            } else {
                currentContent.append(line).append("\n");
            }
        }

        if (currentContent.toString().trim().length() > 0) {
            appendScriptScene(scenes, currentSceneNo > 0 ? currentSceneNo : 1, currentHeader, currentLabel, currentContent);
        }

        return scenes;
    }

    private void appendScriptScene(List<ScriptScene> scenes,
                                   Integer sceneNo,
                                   String header,
                                   String sceneLabel,
                                   StringBuilder content) {
        String normalizedContent = content.toString().trim();
        if (normalizedContent.isEmpty()) {
            return;
        }
        scenes.add(new ScriptScene(sceneNo, header, sceneLabel, normalizedContent));
    }

    private List<SceneBatch> splitSceneBatches(ScriptScene scene, int batchMaxChars) {
        List<String> rawBatches = new ArrayList<>();
        String sceneContent = scene.content().replace("\r\n", "\n").trim();
        String body = sceneContent;
        if (hasText(scene.header()) && body.startsWith(scene.header())) {
            body = body.substring(scene.header().length()).trim();
        }
        if (!hasText(body)) {
            rawBatches.add(sceneContent);
        } else {
            String[] lines = body.split("\n");
            StringBuilder current = new StringBuilder();
            int currentChars = 0;
            int currentLines = 0;

            for (String rawLine : lines) {
                String line = rawLine == null ? "" : rawLine.trim();
                if (!hasText(line)) {
                    continue;
                }
                boolean shouldFlush = current.length() > 0
                        && (currentChars + line.length() > batchMaxChars
                        || currentLines >= STORYBOARD_SCENE_BATCH_MAX_LINES);
                if (shouldFlush) {
                    rawBatches.add(current.toString().trim());
                    current = new StringBuilder();
                    currentChars = 0;
                    currentLines = 0;
                }
                if (current.length() > 0) {
                    current.append('\n');
                }
                current.append(line);
                currentChars += line.length();
                currentLines++;
            }

            if (current.length() > 0) {
                rawBatches.add(current.toString().trim());
            }
        }

        if (rawBatches.isEmpty()) {
            rawBatches.add(sceneContent);
        }

        List<SceneBatch> batches = new ArrayList<>();
        for (int i = 0; i < rawBatches.size(); i++) {
            batches.add(new SceneBatch(i + 1, rawBatches.size(), rawBatches.get(i)));
        }
        return batches;
    }

    private String buildSceneBatchProgressLabel(int totalScenes, int sceneIndex, SceneBatch batch) {
        if (batch.totalBatches() <= 1) {
            return String.format("正在生成第 %d/%d 场分镜", sceneIndex + 1, totalScenes);
        }
        return String.format(
                "正在生成第 %d/%d 场第 %d/%d 段分镜",
                sceneIndex + 1,
                totalScenes,
                batch.batchIndex(),
                batch.totalBatches());
    }

    private String buildStoryboardSceneBatchUserPrompt(List<ScriptScene> scenes, int sceneIndex, SceneBatch batch, Project project) {
        ScriptScene scene = scenes.get(sceneIndex);
        String previousScene = sceneIndex > 0 ? scenes.get(sceneIndex - 1).displayName() : "无";
        String nextScene = sceneIndex + 1 < scenes.size() ? scenes.get(sceneIndex + 1).displayName() : "无";
        String sceneNameHint = hasText(scene.sceneLabel()) ? scene.sceneLabel() : scene.displayName();
        String projectType = resolveProjectType(project);
        String genre = resolveGenre(project);
        return String.format("""
                请仅为当前场景的当前片段生成分镜 JSON，不要输出其他场景或本场其他片段内容。

                项目信息：
                - 项目类型：%s
                - 题材：%s
                - 文本风格约束：
                %s
                - 视觉风格约束：
                %s
                - 语音表现约束：
                %s

                场景拆分上下文：
                - 本集共 %d 场，当前生成第 %d 场
                - 当前场景共 %d 段，本次生成第 %d 段
                - 上一场：%s
                - 下一场：%s
                - 当前场景标题：%s

                当前片段内容：
                %s

                额外要求：
                1. 只生成当前片段对应的镜头，禁止扩写到上一场、下一场。
                2. 当前片段通常拆为 1-4 个镜头。
                3. 如果这不是第 1 段，直接承接当前片段动作。
                4. 如果这是最后一段，把当前场景收束补完整。
                5. sceneName 优先使用“%s”。
                6. 返回格式必须为 {"shots": [...]}。
                7. 严禁Markdown格式文字。
                8. dialogue 只写口播，短句、口语化；不要写“角色名：”前缀，用 characterName 标说话人；无口播可留空。
                9. 若本段有两人交锋，请拆成多个镜头用短句形成一来一回，不要写成长段说明文。
                10. 台词风格示例（仅示意语气，勿照抄）：「你再说一遍试试？」「试就试，你以为我不敢？」
                11. 返回前执行自检，不满足则重写：
                    - duration 必须 1-5 秒；
                    - subtitleText 若有值不得含角色名/情绪头；
                    - narration 仅 VO/OS 且不与 dialogue 同句重复。
                """,
                projectType,
                genre,
                ProjectStyleSupport.buildTextCreationRules(projectType, genre),
                ProjectStyleSupport.buildVisualCreationRules(projectType, genre),
                ProjectStyleSupport.buildAudioPerformanceRules(projectType, genre),
                scenes.size(),
                scene.sceneNo(),
                batch.totalBatches(),
                batch.batchIndex(),
                previousScene,
                nextScene,
                scene.displayName(),
                batch.content(),
                sceneNameHint);
    }

    private record ScriptScene(int sceneNo, String header, String sceneLabel, String content) {
        private String displayName() {
            return sceneLabel != null && !sceneLabel.isBlank()
                    ? "第" + sceneNo + "场[" + sceneLabel + "]"
                    : "第" + sceneNo + "场";
        }
    }

    private record SceneBatch(int batchIndex, int totalBatches, String content) {}

    private record VoiceSelection(Character character, String voiceId, String voiceName, boolean autoAssigned) {}

    public List<Storyboard> saveStoryboardPreview(Long userId, StoryboardPreviewSaveRequest request) {
        log.debug("保存分镜预览: userId={}, projectId={}, scriptId={}, contentLength={}",
                userId, request.getProjectId(), request.getScriptId(), request.getContent() != null ? request.getContent().length() : 0);
        Project project = projectService.getProject(userId, request.getProjectId());
        Script script = requireScript(request.getScriptId(), request.getProjectId());

        StoryboardGenerateRequest generateRequest = new StoryboardGenerateRequest();
        generateRequest.setProjectId(request.getProjectId());
        generateRequest.setScriptId(request.getScriptId());

        List<Storyboard> shots = parseStoryboardJson(request.getContent(), generateRequest, true);
        validateStoryboardShotsSoft(shots, "preview-save");
    log.debug("分镜预览解析完成: projectId={}, scriptId={}, shotCount={}",
        request.getProjectId(), request.getScriptId(), shots.size());
        storyboardMapper.delete(new LambdaQueryWrapper<Storyboard>()
                .eq(Storyboard::getProjectId, request.getProjectId())
                .eq(Storyboard::getScriptId, request.getScriptId()));

        int episodeNo = script.getEpisodeNo() != null ? script.getEpisodeNo() : 1;
        for (Storyboard shot : shots) {
            shot.setEpisodeNo(episodeNo);
            applyDramaTextProcessing(shot, userId, project, null);
            storyboardMapper.insert(shot);
        }
        return shots;
    }

    @Async("aiTaskExecutor")
    public void generateStoryboardAsync(Long userId, StoryboardGenerateRequest request, Long taskId) {
        TaskRecord task = taskRecordMapper.selectById(taskId);
        if (task == null) return;
        try {
            log.debug("异步分镜生成开始: taskId={}, userId={}, projectId={}, scriptId={}",
                    taskId, userId, request.getProjectId(), request.getScriptId());
            updateTask(task, "RUNNING", 10, "读取剧本内容...");
            Project project = projectService.getProject(userId, request.getProjectId());
            Script script = requireScript(request.getScriptId(), request.getProjectId());

            updateTask(task, "RUNNING", 20, "AI正在拆解分镜脚本...");
            TextAiProvider textProvider = aiProviderFactory.getTextProvider(userId);
            String systemPrompt = buildStoryboardSystemPrompt(project);
            // generateStoryboardPreviewByScenes saves each scene's shots to the database
            // incrementally (status=preview_draft), so partial progress is preserved even
            // if the task is interrupted. Pass null for chunkConsumer — we don't need to
            // stream JSON to anyone in the async path. Use the progressConsumer to keep
            // the task message up-to-date while scenes are being generated.
                generateStoryboardPreviewByScenes(textProvider, systemPrompt, script, request, project, null,
                    progressMsg -> updateTask(task, "RUNNING", task.getProgress(), progressMsg));

            long shotCount = storyboardMapper.selectCount(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Storyboard>()
                            .eq(Storyboard::getProjectId, request.getProjectId())
                            .eq(Storyboard::getScriptId, request.getScriptId())
                            .eq(Storyboard::getStatus, "preview_draft"));

            task.setStatus("SUCCESS");
            task.setProgress(100);
            task.setMessage(String.format("分镜生成完成，共%d个镜头", shotCount));
            taskRecordMapper.updateById(task);
            log.debug("异步分镜生成完成: taskId={}, projectId={}, scriptId={}, shotCount={}",
                    taskId, request.getProjectId(), request.getScriptId(), shotCount);

        } catch (Exception e) {
            log.error("分镜生成失败: taskId={}", taskId, e);
            task.setStatus("FAILED");
            task.setMessage("分镜生成失败: " + e.getMessage());
            taskRecordMapper.updateById(task);
        }
    }

    /**
     * Start generating images for all storyboard shots of a project.
     */
    public TaskRecord startGenerateStoryboardImages(Long userId, Long projectId, java.util.List<Long> shotIds) {
        projectService.getProject(userId, projectId);
        List<Storyboard> shots = listByProject(projectId);
        if (shotIds != null && !shotIds.isEmpty()) {
            shots = shots.stream().filter(s -> shotIds.contains(s.getId())).toList();
        }
        if (shots.isEmpty()) throw new BusinessException("项目下没有分镜数据，请先生成分镜");

        shots = shots.stream()
                .filter(shot -> !Boolean.TRUE.equals(shot.getDynamicSelected()))
                .toList();
        if (shots.isEmpty()) {
            throw new BusinessException("当前选择的镜头均已勾选动态生成，请在动态镜头生成中处理");
        }

        log.debug("创建分镜图片任务: userId={}, projectId={}, shotCount={}, filteredByIds={}",
                userId, projectId, shots.size(), shotIds != null && !shotIds.isEmpty());

        TaskRecord task = new TaskRecord();
        task.setProjectId(projectId);
        task.setUserId(userId);
        task.setTaskType("IMAGE_GEN");
        task.setStatus("PENDING");
        task.setProgress(0);
        task.setMessage("任务已提交，等待为分镜生成图片...");
        taskRecordMapper.insert(task);
        selfProvider.getObject().generateStoryboardImagesAsync(userId, projectId, shots, task.getId());
        return task;
    }

    /**
     * 同步为缺少图片的分镜补全参考图（供视频生成流程调用）。
     * 已有图片的分镜会被跳过。
     */
    public void ensureShotsHaveImages(Long userId, Long projectId, List<Storyboard> shots) {
        ImageAiProvider imageProvider = aiProviderFactory.getImageProvider(userId);
        Project project = projectService.getProject(userId, projectId);
        for (Storyboard shot : shots) {
            if (hasText(shot.getImageUrl())) continue;
            try {
                String prompt = shot.getImagePrompt();
                Character character = resolveShotCharacter(shot);
                if (prompt == null || prompt.isBlank()) {
                    prompt = buildImagePrompt(shot, character, project);
                }
                List<String> referenceImageUrls = collectReferenceImageUrls(shot, character);
                String generationPrompt = buildImageGenerationPrompt(prompt, character, referenceImageUrls, project);
                String negativePrompt = buildImageNegativePrompt(character, project);
                String imageUrl = generateImageWithRetry(imageProvider,
                        generationPrompt, "1024*1024", referenceImageUrls, negativePrompt, shot);
                shot.setImageUrl(imageUrl);
                shot.setStatus("image_generated");
                storyboardMapper.updateById(shot);
                log.info("视频前补全分镜图片成功: shotNo={}, imageUrl={}", shot.getShotNo(), imageUrl);
            } catch (Exception e) {
                log.error("视频前补全分镜图片失败: shotNo={}, error={}", shot.getShotNo(), e.getMessage());
                throw new RuntimeException("分镜 " + shot.getShotNo() + " 图片生成失败: " + e.getMessage(), e);
            }
        }
    }

    @Async("aiTaskExecutor")
    public void generateStoryboardImagesAsync(Long userId, Long projectId, List<Storyboard> shots, Long taskId) {
        TaskRecord task = taskRecordMapper.selectById(taskId);
        if (task == null) return;
        ArrayNode traceCalls = objectMapper.createArrayNode();
        int omittedTraceCalls = 0;
        try {
            ImageAiProvider imageProvider = aiProviderFactory.getImageProvider(userId);
            Project project = resolveProjectById(projectId);
            int total = shots.size();
            int completed = 0;
            int replacedExisting = 0;
            int generated = 0;
            int reused = 0;
            int failed = 0;
            List<String> failedDetails = new ArrayList<>();

            log.debug("开始分镜图片生成: taskId={}, userId={}, projectId={}, shotCount={}, reuseEnabled={}",
                    taskId, userId, projectId, total, imageReuseEnabled);

            // Build image reuse cache for this batch only. Do not pre-populate it
            // from the selected shots, otherwise a "re-generate" request could
            // immediately reuse the old stored image instead of producing a new one.
            Map<String, String> imageCache = new HashMap<>();

            for (Storyboard shot : shots) {
                boolean hadExistingImage = hasText(shot.getImageUrl());

                String prompt = shot.getImagePrompt();
                Character character = resolveShotCharacter(shot);
                if (prompt == null || prompt.isBlank()) {
                    prompt = buildImagePrompt(shot, character, project);
                }
                List<String> referenceImageUrls = collectReferenceImageUrls(shot, character);
                String generationPrompt = buildImageGenerationPrompt(prompt, character, referenceImageUrls, project);
                String negativePrompt = buildImageNegativePrompt(character, project);
                log.debug("分镜图片请求已准备: taskId={}, shotId={}, shotNo={}, characterId={}, promptLength={}, referenceCount={}, negativePromptLength={}",
                    taskId,
                    shot.getId(),
                    shot.getShotNo(),
                    shot.getCharacterId(),
                    generationPrompt.length(),
                    referenceImageUrls.size(),
                    negativePrompt != null ? negativePrompt.length() : 0);

                updateTask(task, "RUNNING",
                        10 + (80 * completed / total),
                        String.format("正在生成第%d/%d个分镜图片...", completed + 1, total));

                try {
                    // Check image cache for reuse
                    String cacheKey = buildImageCacheKey(shot);
                    if (imageReuseEnabled && imageCache.containsKey(cacheKey)) {
                        shot.setImageUrl(imageCache.get(cacheKey));
                        shot.setStatus("image_generated");
                        storyboardMapper.updateById(shot);
                        if (hadExistingImage) {
                            replacedExisting++;
                        } else {
                            reused++;
                        }
                        log.debug("分镜图片命中批次缓存: taskId={}, shotId={}, shotNo={}, cacheKey={}, replacedExisting={}, imageUrl={}",
                                taskId, shot.getId(), shot.getShotNo(), cacheKey, hadExistingImage, shot.getImageUrl());
                    } else {
                        // Use smart resolution based on camera angle
                        String imageSize = costEstimationService.getOptimalImageSize(shot.getCameraAngle());
                        String imageUrl = generateImageWithRetry(
                                imageProvider,
                                generationPrompt,
                                imageSize,
                                referenceImageUrls,
                                negativePrompt,
                                shot);
                        if (imageUrl == null || imageUrl.isBlank()) {
                            throw new BusinessException("图片接口未返回有效图片地址");
                        }
                        imageUrl = publicAssetStorageService.ensurePublicUrl(imageUrl, "generated-images", "png");
                        shot.setImageUrl(imageUrl);
                        shot.setStatus("image_generated");
                        storyboardMapper.updateById(shot);
                        if (hadExistingImage) {
                            replacedExisting++;
                        } else {
                            generated++;
                        }
                        log.debug("分镜图片生成完成: taskId={}, shotId={}, shotNo={}, imageSize={}, replacedExisting={}, imageUrl={}",
                                taskId, shot.getId(), shot.getShotNo(), imageSize, hadExistingImage, imageUrl);

                        // Cache this image for reuse
                        if (imageReuseEnabled && cacheKey != null) {
                            imageCache.put(cacheKey, imageUrl);
                        }
                    }
                    backfillCharacterImage(character, shot);
                } catch (Exception e) {
                    failed++;
                    shot.setStatus("image_failed");
                    storyboardMapper.updateById(shot);
                    String shotLabel = shot.getShotNo() != null ? String.valueOf(shot.getShotNo()) : String.valueOf(shot.getId());
                    String errorMessage = hasText(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName();
                    failedDetails.add(String.format("镜头%s: %s", shotLabel, errorMessage));
                    log.warn("分镜图片生成失败: shot={}", shotLabel, e);
                } finally {
                    omittedTraceCalls = appendTraceCalls(traceCalls, shot, AiTraceContext.drain(), omittedTraceCalls);
                }
                completed++;
            }

            task.setProgress(100);
            int readyCount = replacedExisting + reused + generated;
            if (readyCount == 0 && failed > 0) {
                task.setStatus("FAILED");
                task.setMessage(String.format("分镜图片生成失败，所选%d个镜头均未生成成功。%s", total, buildFailureReasonSummary(failedDetails, 3)));
            } else {
                task.setStatus("SUCCESS");
                if (failed > 0) {
                    task.setMessage(String.format("分镜图片生成完成：新增%d个，复用%d个，覆盖%d个，失败%d个。%s", generated, reused, replacedExisting, failed, buildFailureReasonSummary(failedDetails, 3)));
                } else {
                    task.setMessage(String.format("分镜图片生成完成，共处理%d个镜头，新增%d张，复用%d张，覆盖%d张", total, generated, reused, replacedExisting));
                }
            }
            task.setResult(safeTaskResult(buildTaskTraceResult("image", projectId, traceCalls, omittedTraceCalls,
                    Map.of(
                            "total", total,
                            "generated", generated,
                            "reused", reused,
                            "replacedExisting", replacedExisting,
                            "failed", failed))));
            taskRecordMapper.updateById(task);
                log.debug("分镜图片生成完成: taskId={}, projectId={}, total={}, generated={}, reused={}, replacedExisting={}, failed={}",
                    taskId, projectId, total, generated, reused, replacedExisting, failed);

        } catch (Exception e) {
            log.error("分镜图片生成失败: taskId={}", taskId, e);
            task.setStatus("FAILED");
            task.setMessage("分镜图片生成失败: " + e.getMessage());
            task.setResult(safeTaskResult(buildTaskTraceResult("image", projectId, traceCalls, omittedTraceCalls,
                    Map.of("error", e.getMessage()))));
            taskRecordMapper.updateById(task);
        }
    }

    private String buildFailureReasonSummary(List<String> failedDetails, int maxItems) {
        if (failedDetails == null || failedDetails.isEmpty()) {
            return "";
        }
        int limit = Math.max(1, maxItems);
        List<String> reasons = failedDetails.stream().limit(limit).toList();
        String summary = String.join("；", reasons);
        int remaining = failedDetails.size() - reasons.size();
        if (remaining > 0) {
            summary = summary + String.format("；另有%d个失败镜头", remaining);
        }
        return "失败原因：" + summary;
    }

    /**
     * Build image cache key from shot's scene, character, and camera angle.
     * Same combination can reuse the same image to reduce API costs.
     * Returns null when neither sceneId nor characterId is set, because without
     * scene/character context two shots are unlikely to share the same visual.
     */
    private String buildImageCacheKey(Storyboard shot) {
        Long sceneId = shot.getSceneId();
        Long characterId = shot.getCharacterId();
        String angle = shot.getCameraAngle();
        if (sceneId == null && characterId == null) {
            return null; // Cannot cache without scene or character context
        }
        return String.format("s%d_c%d_%s",
                sceneId != null ? sceneId : 0,
                characterId != null ? characterId : 0,
                angle != null ? angle : "medium");
    }

    private List<String> collectReferenceImageUrls(Storyboard shot, Character resolvedCharacter) {
        Set<String> referenceImageUrls = new LinkedHashSet<>();
        Character character = resolvedCharacter != null ? resolvedCharacter : resolveShotCharacter(shot);
        if (character != null) {
            addReferenceImageUrl(referenceImageUrls, character.getImageUrl());
        }
        if (shot.getSceneId() != null) {
            Scene scene = sceneMapper.selectById(shot.getSceneId());
            if (scene != null) {
                addReferenceImageUrl(referenceImageUrls, scene.getImageUrl());
            }
        }
        return new ArrayList<>(referenceImageUrls);
    }

    private void backfillCharacterImage(Character character, Storyboard shot) {
        if (character == null || hasText(character.getImageUrl()) || shot == null || !hasText(shot.getImageUrl())) {
            return;
        }
        character.setImageUrl(shot.getImageUrl());
        characterMapper.updateById(character);
        log.debug("已从分镜回填角色图片: projectId={}, characterId={}, characterName={}, shotNo={}, imageUrl={}",
                character.getProjectId(), character.getId(), character.getName(), shot.getShotNo(), shot.getImageUrl());
    }

    private void addReferenceImageUrl(Set<String> referenceImageUrls, String imageUrl) {
        if (hasText(imageUrl)
                && (imageUrl.startsWith("http://") || imageUrl.startsWith("https://"))) {
            referenceImageUrls.add(imageUrl);
        }
    }

    /**
     * Start generating TTS audio for all storyboard shots of a project.
     */
    public TaskRecord startGenerateStoryboardAudio(Long userId, Long projectId, java.util.List<Long> shotIds) {
        projectService.getProject(userId, projectId);
        List<Storyboard> shots = listByProject(projectId);
        if (shotIds != null && !shotIds.isEmpty()) {
            shots = shots.stream().filter(s -> shotIds.contains(s.getId())).toList();
        }
        if (shots.isEmpty()) throw new BusinessException("项目下没有分镜数据，请先生成分镜");

        log.debug("创建分镜音频任务: userId={}, projectId={}, shotCount={}, filteredByIds={}",
                userId, projectId, shots.size(), shotIds != null && !shotIds.isEmpty());

        TaskRecord task = new TaskRecord();
        task.setProjectId(projectId);
        task.setUserId(userId);
        task.setTaskType("AUDIO_GEN");
        task.setStatus("PENDING");
        task.setProgress(0);
        task.setMessage("任务已提交，等待为分镜生成配音...");
        taskRecordMapper.insert(task);
        selfProvider.getObject().generateStoryboardAudioAsync(userId, projectId, shots, task.getId());
        return task;
    }

    @Async("aiTaskExecutor")
    public void generateStoryboardAudioAsync(Long userId, Long projectId, List<Storyboard> shots, Long taskId) {
        TaskRecord task = taskRecordMapper.selectById(taskId);
        if (task == null) return;
        ArrayNode traceCalls = objectMapper.createArrayNode();
        int omittedTraceCalls = 0;
        try {
            TtsProvider ttsProvider = aiProviderFactory.getTtsProvider(userId);
            Project project = resolveProjectById(projectId);
            List<VoiceInfo> availableVoices = safeListVoices(ttsProvider);
            int total = shots.size();
            int completed = 0;
            int replacedExisting = 0;
            int generated = 0;
            int skippedNoText = 0;
            int failed = 0;
            List<String> failedDetails = new ArrayList<>();

            log.debug("开始分镜音频生成: taskId={}, userId={}, projectId={}, shotCount={}, availableVoices={}",
                    taskId, userId, projectId, total, availableVoices.size());

            for (Storyboard shot : shots) {
                // Build text to synthesize: combine dialogue and narration
                String text = buildTtsText(shot);
                if (text.isBlank()) {
                    log.debug("文本为空，跳过分镜音频生成: taskId={}, shotId={}, shotNo={}",
                            taskId, shot.getId(), shot.getShotNo());
                    skippedNoText++;
                    completed++;
                    continue;
                }
                boolean hadExistingAudio = hasText(shot.getAudioUrl());

                updateTask(task, "RUNNING",
                        10 + (80 * completed / total),
                        String.format("正在生成第%d/%d个分镜配音...", completed + 1, total));

                try {
                        VoiceSelection voiceSelection = resolveVoiceSelection(userId, shot, availableVoices, project);
                        VoiceInfo selectedVoiceInfo = findVoiceInfo(voiceSelection.voiceId(), availableVoices);
                        String ttsInstruction = buildTtsInstruction(shot, voiceSelection.character(), selectedVoiceInfo, project);
                        float speechSpeed = resolveTtsSpeechSpeed(voiceSelection.character());
                        log.debug("分镜音频请求已准备: taskId={}, shotId={}, shotNo={}, characterId={}, voiceId={}, voiceName={}, textLength={}, autoAssigned={}, instructionLength={}",
                            taskId,
                            shot.getId(),
                            shot.getShotNo(),
                            shot.getCharacterId(),
                            voiceSelection.voiceId(),
                            voiceSelection.voiceName(),
                            text.length(),
                                voiceSelection.autoAssigned(),
                                ttsInstruction != null ? ttsInstruction.length() : 0);
                            byte[] audioData = ttsProvider.synthesize(text, voiceSelection.voiceId(), speechSpeed, 1.0f, ttsInstruction, "Chinese");
                    if (audioData == null || audioData.length <= 100) {
                        throw new BusinessException("配音接口未返回有效音频数据");
                    }
                    StoredAsset storedAudio = publicAssetStorageService.storeBytes(
                            audioData,
                            "audios",
                            UUID.randomUUID().toString().replace("-", "") + ".mp3",
                            "audio/mpeg",
                            "mp3");

                    shot.setAudioUrl(storedAudio.publicUrl());
                    shot.setStatus("audio_generated");
                    storyboardMapper.updateById(shot);
                    if (hadExistingAudio) {
                        replacedExisting++;
                    } else {
                        generated++;
                    }
                    log.debug("分镜音频生成完成: taskId={}, shotId={}, shotNo={}, replacedExisting={}, audioUrl={}, audioSize={}",
                            taskId, shot.getId(), shot.getShotNo(), hadExistingAudio, shot.getAudioUrl(), audioData.length);
                } catch (Exception e) {
                    failed++;
                    shot.setStatus("audio_failed");
                    storyboardMapper.updateById(shot);
                    String shotLabel = shot.getShotNo() != null ? String.valueOf(shot.getShotNo()) : String.valueOf(shot.getId());
                    String errorMessage = hasText(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName();
                    failedDetails.add(String.format("镜头%s: %s", shotLabel, errorMessage));
                    log.warn("分镜音频生成失败: shotNo={}, reason={}", shot.getShotNo(), e.getMessage());
                } finally {
                    omittedTraceCalls = appendTraceCalls(traceCalls, shot, AiTraceContext.drain(), omittedTraceCalls);
                }
                completed++;
            }

            task.setProgress(100);
            int readyCount = replacedExisting + generated;
            if (readyCount == 0 && failed > 0) {
                task.setStatus("FAILED");
                task.setMessage(String.format("分镜配音生成失败，所选%d个镜头均未生成成功。%s", total, buildFailureReasonSummary(failedDetails, 3)));
            } else {
                task.setStatus("SUCCESS");
                if (failed > 0) {
                    task.setMessage(String.format("分镜配音处理完成：新增%d个，覆盖%d个，无文本%d个，失败%d个。%s", generated, replacedExisting, skippedNoText, failed, buildFailureReasonSummary(failedDetails, 3)));
                } else {
                    task.setMessage(String.format("分镜配音处理完成：新增%d个，覆盖%d个，无文本%d个", generated, replacedExisting, skippedNoText));
                }
            }
            task.setResult(safeTaskResult(buildTaskTraceResult("audio", projectId, traceCalls, omittedTraceCalls,
                    Map.of(
                            "total", total,
                            "generated", generated,
                            "replacedExisting", replacedExisting,
                            "skippedNoText", skippedNoText,
                            "failed", failed))));
            taskRecordMapper.updateById(task);
            log.debug("分镜音频生成完成: taskId={}, projectId={}, total={}, generated={}, replacedExisting={}, skippedNoText={}, failed={}",
                    taskId, projectId, total, generated, replacedExisting, skippedNoText, failed);

        } catch (Exception e) {
            log.error("分镜音频生成失败: taskId={}", taskId, e);
            task.setStatus("FAILED");
            task.setMessage("分镜配音生成失败: " + e.getMessage());
            task.setResult(safeTaskResult(buildTaskTraceResult("tts", projectId, traceCalls, omittedTraceCalls,
                    Map.of("error", e.getMessage()))));
            taskRecordMapper.updateById(task);
        }
    }

    private VoiceSelection resolveVoiceSelection(Long userId, Storyboard shot, List<VoiceInfo> availableVoices, Project project) {
        String defaultVoiceId = resolveDefaultVoiceId(userId);
        VoiceInfo defaultVoice = findVoiceInfo(defaultVoiceId, availableVoices);
        if (shot.getCharacterId() == null) {
            return new VoiceSelection(null, defaultVoiceId, resolveVoiceName(defaultVoiceId, defaultVoice), false);
        }
        Character character = characterMapper.selectById(shot.getCharacterId());
        if (character == null) {
            return new VoiceSelection(null, defaultVoiceId, resolveVoiceName(defaultVoiceId, defaultVoice), false);
        }

        if (hasText(character.getVoiceId())) {
            VoiceInfo existingVoice = findVoiceInfo(character.getVoiceId(), availableVoices);
            String voiceName = hasText(character.getVoiceName())
                    ? character.getVoiceName().trim()
                    : resolveVoiceName(character.getVoiceId(), existingVoice);
            if (!hasText(character.getVoiceName()) && hasText(voiceName)) {
                character.setVoiceName(voiceName);
                characterMapper.updateById(character);
                log.debug("已根据提供商音色回填角色音色名: projectId={}, characterId={}, characterName={}, voiceId={}, voiceName={}",
                        character.getProjectId(), character.getId(), character.getName(), character.getVoiceId(), voiceName);
            }
            return new VoiceSelection(character, character.getVoiceId(), voiceName, false);
        }

        VoiceInfo selectedVoice = selectVoiceForCharacter(character, availableVoices, defaultVoiceId, project);
        String selectedVoiceId = selectedVoice != null && hasText(selectedVoice.getVoiceId())
                ? selectedVoice.getVoiceId()
                : defaultVoiceId;
        String selectedVoiceName = resolveVoiceName(selectedVoiceId, selectedVoice != null ? selectedVoice : defaultVoice);
        if (hasText(selectedVoiceId)) {
            character.setVoiceId(selectedVoiceId);
            character.setVoiceName(selectedVoiceName);
            characterMapper.updateById(character);
            log.debug("已根据TTS提供商自动分配角色音色: projectId={}, characterId={}, characterName={}, voiceId={}, voiceName={}",
                    character.getProjectId(), character.getId(), character.getName(), selectedVoiceId, selectedVoiceName);
        }
        return new VoiceSelection(character, selectedVoiceId, selectedVoiceName, true);
    }

    private String resolveDefaultVoiceId(Long userId) {
        try {
            String provider = aiProviderFactory.resolveConfig(userId, "tts").provider();
            if (hasText(provider)) {
                String normalized = provider.trim().toLowerCase(Locale.ROOT);
                if ("aliyun".equals(normalized)
                        || "dashscope".equals(normalized)
                        || "cosyvoice".equals(normalized)) {
                    return "Cherry";
                }
            }
        } catch (Exception ignored) {
        }
        return "alloy";
    }

    private List<VoiceInfo> safeListVoices(TtsProvider ttsProvider) {
        try {
            List<VoiceInfo> voices = ttsProvider.listVoices();
            return voices != null ? voices : List.of();
        } catch (Exception e) {
            log.warn("加载提供商音色列表失败，回退默认音色ID: {}", e.getMessage());
            return List.of();
        }
    }

    private VoiceInfo selectVoiceForCharacter(Character character, List<VoiceInfo> voices, String defaultVoiceId, Project project) {
        if (voices == null || voices.isEmpty()) {
            return null;
        }

        String preferredGender = normalizeVoiceGender(character != null ? character.getGender() : null);
        VoiceInfo preferredChinese = findFirstVoice(voices, preferredGender, true, project);
        if (preferredChinese != null) {
            return preferredChinese;
        }

        VoiceInfo preferredAnyLanguage = findFirstVoice(voices, preferredGender, false, project);
        if (preferredAnyLanguage != null) {
            return preferredAnyLanguage;
        }

        VoiceInfo defaultVoice = findVoiceInfo(defaultVoiceId, voices);
        if (defaultVoice != null) {
            return defaultVoice;
        }

        VoiceInfo chineseVoice = findFirstVoice(voices, null, true, project);
        return chineseVoice != null ? chineseVoice : voices.get(0);
    }

    private VoiceInfo findFirstVoice(List<VoiceInfo> voices, String preferredGender, boolean chineseOnly, Project project) {
        VoiceInfo bestVoice = null;
        int bestScore = Integer.MIN_VALUE;
        for (VoiceInfo voice : voices) {
            if (voice == null || !hasText(voice.getVoiceId())) {
                continue;
            }
            if (chineseOnly && !isChineseVoice(voice)) {
                continue;
            }
            if (preferredGender != null && !preferredGender.equals(normalizeVoiceGender(voice.getGender()))) {
                continue;
            }
            int score = scoreVoiceForProject(voice, project);
            if (preferredGender != null) {
                score += 4;
            }
            if (bestVoice == null || score > bestScore) {
                bestVoice = voice;
                bestScore = score;
            }
        }
        return bestVoice;
    }

    private int scoreVoiceForProject(VoiceInfo voice, Project project) {
        String descriptor = ((voice.getName() == null ? "" : voice.getName()) + " "
                + (voice.getDescription() == null ? "" : voice.getDescription())).trim();
        int score = 0;
        if (ProjectStyleSupport.isComicProjectType(project != null ? project.getProjectType() : null)) {
            if (containsKeyword(descriptor, new String[] {"元气", "活泼", "灵动", "热血", "动漫", "漫画", "少女", "少年", "俏皮"})) {
                score += 8;
            }
            if (containsKeyword(descriptor, new String[] {"纪录", "播音", "严肃", "新闻"})) {
                score -= 2;
            }
        } else {
            if (containsKeyword(descriptor, new String[] {"自然", "真实", "温润", "沉稳", "成熟", "影视", "叙事", "旁白", "温柔"})) {
                score += 8;
            }
            if (containsKeyword(descriptor, new String[] {"动漫", "卡通", "夸张", "萝莉", "元气", "俏皮"})) {
                score -= 4;
            }
        }

        String genre = resolveGenre(project);
        if (containsKeyword(genre, new String[] {"民国", "古装", "历史", "仙侠"})
                && containsKeyword(descriptor, new String[] {"古风", "端庄", "书卷", "温婉", "低沉", "沉稳"})) {
            score += 4;
        }
        if (containsKeyword(genre, new String[] {"悬疑", "惊悚", "刑侦", "犯罪"})
                && containsKeyword(descriptor, new String[] {"冷静", "克制", "悬疑", "沉着", "旁白"})) {
            score += 4;
        }
        if (containsKeyword(genre, new String[] {"校园", "青春", "喜剧"})
                && containsKeyword(descriptor, new String[] {"年轻", "清亮", "轻快", "灵动"})) {
            score += 3;
        }
        return score;
    }

    private VoiceInfo findVoiceInfo(String voiceId, List<VoiceInfo> voices) {
        if (!hasText(voiceId) || voices == null || voices.isEmpty()) {
            return null;
        }
        for (VoiceInfo voice : voices) {
            if (voice != null && hasText(voice.getVoiceId()) && voiceId.equalsIgnoreCase(voice.getVoiceId())) {
                return voice;
            }
        }
        return null;
    }

    private String resolveVoiceName(String voiceId, VoiceInfo voiceInfo) {
        if (voiceInfo != null && hasText(voiceInfo.getName())) {
            return voiceInfo.getName();
        }
        return hasText(voiceId) ? voiceId : "default";
    }

    private String normalizeVoiceGender(String value) {
        String normalized = lower(value);
        if (!hasText(normalized)) {
            return null;
        }
        if (normalized.contains("female") || normalized.contains("女")) {
            return "female";
        }
        if (normalized.contains("male") || normalized.contains("男")) {
            return "male";
        }
        return "neutral";
    }

    private boolean isChineseVoice(VoiceInfo voiceInfo) {
        return voiceInfo != null
                && hasText(voiceInfo.getLanguage())
                && voiceInfo.getLanguage().toLowerCase(Locale.ROOT).startsWith("zh");
    }

    private float resolveTtsSpeechSpeed(Character character) {
        if (character == null || character.getSpeechRate() == null) {
            return 1.0f;
        }
        float r = character.getSpeechRate() / 100f;
        return Math.max(0.5f, Math.min(1.5f, r));
    }

    private String buildTtsText(Storyboard shot) {
        return DramaTextSanitizer.resolveEffectiveTts(shot);
    }

    private String buildTtsInstruction(Storyboard shot, Character character, VoiceInfo voiceInfo, Project project) {
        List<String> directives = new ArrayList<>();
        directives.add("全程使用中文表达，保持自然口语化和类人停连");
        if (voiceInfo != null && hasText(voiceInfo.getDescription())) {
            directives.add("音色基调：" + voiceInfo.getDescription());
        }
        if (character != null) {
            if (hasText(character.getPersonality())) {
                directives.add("角色性格：" + character.getPersonality().trim());
            }
            if (hasText(character.getDescription())) {
                directives.add("人物设定：" + character.getDescription().trim());
            }
        } else {
            directives.add("以旁白或叙事者口吻演绎，吐字清晰，层次稳定");
        }

        if (hasText(shot != null ? shot.getNarration() : null) && !hasText(shot != null ? shot.getDialogue() : null)) {
            directives.add("整体偏旁白解说感，沉稳连贯，不要生硬播读");
        } else if (hasText(shot != null ? shot.getDialogue() : null) && !hasText(shot != null ? shot.getNarration() : null)) {
            directives.add("整体偏角色对白感，带交流对象感和现场感");
        } else {
            directives.add("兼顾旁白与对白的自然衔接，避免机械切换");
        }

        String emotionDirective = resolveShotEmotionDirective(shot, project);
        if (hasText(emotionDirective)) {
            directives.add(emotionDirective);
        }

        String projectAudioGuide = compactGuide(ProjectStyleSupport.buildAudioPerformanceRules(resolveProjectType(project), resolveGenre(project)));
        if (hasText(projectAudioGuide)) {
            directives.add("项目演绎约束：" + projectAudioGuide);
        }
        if (character != null && hasText(character.getTtsNote())) {
            directives.add("导演补充：" + character.getTtsNote().trim());
        }
        directives.add("避免机械朗读、避免夸张做作，保证情绪自然递进");

        String instruction = String.join("；", directives.stream()
                .map(item -> trimPromptSegment(item, 80))
                .toList());
        if (instruction.length() > TTS_INSTRUCTION_MAX_CHARS) {
            return instruction.substring(0, TTS_INSTRUCTION_MAX_CHARS);
        }
        return instruction;
    }

    private String resolveShotEmotionDirective(Storyboard shot, Project project) {
        String combined = lower((shot != null ? shot.getDescription() : null) + " "
                + (shot != null ? shot.getDialogue() : null) + " "
                + (shot != null ? shot.getNarration() : null));
        if (containsKeyword(combined, new String[] {"哭", "哽咽", "抽泣", "泪", "崩溃"})) {
            return "情绪带轻微哭腔和紧张感，允许少量颤音，但仍需保证可懂度";
        }
        if (containsKeyword(combined, new String[] {"怒", "吼", "喊", "质问", "爆发", "冲", "快跑", "立刻", "马上", "！", "!"})) {
            return "情绪更外露，语速偏快，爆发力更强，但避免失真和过度嘶喊";
        }
        if (containsKeyword(combined, new String[] {"悬疑", "惊悚", "秘密", "黑夜", "危险", "调查", "真相"})
                || containsKeyword(resolveGenre(project), new String[] {"悬疑", "惊悚", "刑侦", "犯罪"})) {
            return "语气克制偏低沉，留出悬念和压迫感，重音更集中";
        }
        if (containsKeyword(combined, new String[] {"温柔", "安慰", "拥抱", "喜欢", "爱", "陪你", "晚安"})
                || containsKeyword(resolveGenre(project), new String[] {"言情", "治愈", "都市"})) {
            return "语速偏慢，语气温柔，情绪细腻，像贴近耳边的真实交流";
        }
        if (containsKeyword(resolveGenre(project), new String[] {"喜剧", "校园", "青春"})) {
            return "整体更轻快灵动，带一点少年少女感，节奏自然明亮";
        }
        return "语速中等，吐字清晰，情绪自然，保持真实人物说话状态";
    }

    public List<Storyboard> listByProject(Long projectId) {
        List<Storyboard> list = storyboardMapper.selectList(new LambdaQueryWrapper<Storyboard>()
                .eq(Storyboard::getProjectId, projectId)
                .orderByAsc(Storyboard::getEpisodeNo)
                .orderByAsc(Storyboard::getShotNo));
        Project project = resolveProjectById(projectId);
        recomputeDynamicRecommendations(list, project);
        enrichResolvedStoryboardFields(list);
        return list;
    }

    public List<Storyboard> listByScript(Long scriptId) {
        List<Storyboard> list = storyboardMapper.selectList(new LambdaQueryWrapper<Storyboard>()
                .eq(Storyboard::getScriptId, scriptId)
                .orderByAsc(Storyboard::getShotNo));
        if (!list.isEmpty()) {
            Project project = resolveProjectById(list.get(0).getProjectId());
            recomputeDynamicRecommendations(list, project);
        }
        enrichResolvedStoryboardFields(list);
        return list;
    }

    public Storyboard getStoryboard(Long id) {
        Storyboard s = storyboardMapper.selectById(id);
        if (s == null) throw new BusinessException("分镜不存在");
        enrichResolvedStoryboardFields(s);
        return s;
    }

    private void enrichResolvedStoryboardFields(Storyboard s) {
        if (s == null) {
            return;
        }
        s.setResolvedSubtitle(DramaTextSanitizer.resolveEffectiveWrappedSubtitle(s, enrichSubtitleIncludeNarration,
                enrichSubtitleStripSpeakerPrefix, enrichSubtitleStripSpeakerPrefix));
        s.setResolvedTts(DramaTextSanitizer.resolveEffectiveTts(s));
    }

    private void enrichResolvedStoryboardFields(List<Storyboard> list) {
        if (list == null) {
            return;
        }
        for (Storyboard s : list) {
            enrichResolvedStoryboardFields(s);
        }
    }

    public Storyboard updateStoryboard(Long id, Storyboard update) {
        Storyboard storyboard = storyboardMapper.selectById(id);
        if (storyboard == null) throw new BusinessException("分镜不存在");
        if (update.getDescription() != null) storyboard.setDescription(update.getDescription());
        if (update.getDialogue() != null) storyboard.setDialogue(update.getDialogue());
        if (update.getNarration() != null) storyboard.setNarration(update.getNarration());
        if (update.getCameraAngle() != null) storyboard.setCameraAngle(update.getCameraAngle());
        if (update.getDuration() != null) storyboard.setDuration(update.getDuration());
        if (update.getImagePrompt() != null) storyboard.setImagePrompt(update.getImagePrompt());
        if (update.getVideoPrompt() != null) storyboard.setVideoPrompt(update.getVideoPrompt());
        if (update.getImageUrl() != null) {
            storyboard.setImageUrl(hasText(update.getImageUrl()) ? update.getImageUrl().trim() : null);
        }
        if (update.getVideoUrl() != null) {
            storyboard.setVideoUrl(hasText(update.getVideoUrl()) ? update.getVideoUrl().trim() : null);
        }
        if (update.getMotionLevel() != null) storyboard.setMotionLevel(normalizeMotionLevel(update.getMotionLevel()));
        if (update.getDynamicSelected() != null) {
            storyboard.setDynamicSelected(update.getDynamicSelected());
            storyboard.setRenderMode(Boolean.TRUE.equals(update.getDynamicSelected()) ? "video" : "image");
        }
        if (update.getRenderMode() != null) storyboard.setRenderMode(update.getRenderMode());
        if (update.getSubtitleText() != null) {
            if (!hasText(update.getSubtitleText())) {
                storyboard.setSubtitleText(null);
                storyboard.setUserLockedSubtitle(false);
            } else {
                storyboard.setSubtitleText(DramaTextSanitizer.normalizeSpokenText(update.getSubtitleText().trim()));
                storyboard.setUserLockedSubtitle(true);
            }
        }
        if (update.getTtsText() != null) {
            if (!hasText(update.getTtsText())) {
                storyboard.setTtsText(null);
                storyboard.setUserLockedTts(false);
            } else {
                storyboard.setTtsText(DramaTextSanitizer.normalizeSpokenText(update.getTtsText().trim()));
                storyboard.setUserLockedTts(true);
            }
        }
        if (update.getUserLockedSubtitle() != null) {
            storyboard.setUserLockedSubtitle(update.getUserLockedSubtitle());
        }
        if (update.getUserLockedTts() != null) {
            storyboard.setUserLockedTts(update.getUserLockedTts());
        }
        DramaTextSanitizer.applyToStoryboard(storyboard, stripDialogueSpeakerPrefix, stripNarrationSpeakerPrefix, dedupeDialogueNarration);
        storyboardMapper.updateById(storyboard);
        enrichResolvedStoryboardFields(storyboard);
        return storyboard;
    }

    private void updateTask(TaskRecord task, String status, int progress, String message) {
        log.debug("任务状态更新: taskId={}, taskType={}, status={}, progress={}, message={}",
            task.getId(), task.getTaskType(), status, progress, message);
        task.setStatus(status);
        task.setProgress(progress);
        task.setMessage(message);
        taskRecordMapper.updateById(task);
    }

    private int appendTraceCalls(ArrayNode calls, Storyboard shot, List<AiCallTrace> traces, int omittedTraceCalls) {
        if (traces == null || traces.isEmpty()) {
            return omittedTraceCalls;
        }
        for (AiCallTrace trace : traces) {
            if (calls.size() >= MAX_TASK_TRACE_CALLS) {
                omittedTraceCalls++;
                continue;
            }
            ObjectNode node = objectMapper.valueToTree(trace);
            if (shot != null) {
                if (shot.getId() != null) {
                    node.put("shotId", shot.getId());
                }
                if (shot.getShotNo() != null) {
                    node.put("shotNo", shot.getShotNo());
                }
            }
            calls.add(node);
        }
        return omittedTraceCalls;
    }

    private String buildTaskTraceResult(String mediaType,
                                        Long projectId,
                                        ArrayNode calls,
                                        int omittedTraceCalls,
                                        Map<String, ?> summary) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("mediaType", mediaType);
            root.put("projectId", projectId);
            ArrayNode storedCalls = objectMapper.createArrayNode();
            int copied = 0;
            for (JsonNode call : calls) {
                if (copied >= TASK_RESULT_MAX_CALLS) {
                    omittedTraceCalls++;
                    continue;
                }
                storedCalls.add(call);
                copied++;
            }
            root.put("storedCalls", storedCalls.size());
            root.put("omittedCalls", omittedTraceCalls);
            root.set("summary", objectMapper.valueToTree(summary));
            root.set("calls", storedCalls);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("序列化 AI 任务追踪结果失败", e);
            return null;
        }
    }

    private String safeTaskResult(String payload) {
        if (!hasText(payload)) {
            return payload;
        }
        if (payload.length() <= TASK_RESULT_MAX_CHARS) {
            return payload;
        }
        return payload.substring(0, TASK_RESULT_MAX_CHARS) + "...(truncated)";
    }

    private String generateImageWithRetry(ImageAiProvider imageProvider,
                                          String generationPrompt,
                                          String imageSize,
                                          List<String> referenceImageUrls,
                                          String negativePrompt,
                                          Storyboard shot) {
        RuntimeException lastException = null;
        int maxAttempts = Math.max(1, imageRetryMaxAttempts);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                if (imageRetryThrottleMs > 0L) {
                    Thread.sleep(imageRetryThrottleMs);
                }
                return imageProvider.generateImage(
                        generationPrompt,
                        imageSize,
                        PORTRAIT_IMAGE_STYLE,
                        referenceImageUrls,
                        negativePrompt);
            } catch (RuntimeException e) {
                lastException = e;
                if (!isRetryableImageError(e) || attempt >= maxAttempts) {
                    break;
                }
                long backoff = Math.max(100L, imageRetryBackoffMs) * (1L << (attempt - 1));
                log.warn("分镜图片生成重试: shotNo={}, attempt={}/{}, reason={}, backoffMs={}",
                        shot != null ? shot.getShotNo() : null, attempt, maxAttempts, e.getMessage(), backoff);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("图片生成重试被中断", ie);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("图片生成节流等待被中断", e);
            }
        }
        if (lastException != null) {
            throw lastException;
        }
        throw new RuntimeException("图片生成失败：未知错误");
    }

    private boolean isRetryableImageError(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        String msg = lower(throwable.getMessage());
        return msg.contains("502")
                || msg.contains("503")
                || msg.contains("504")
                || msg.contains("timeout")
                || msg.contains("timed out")
                || msg.contains("connection reset")
                || msg.contains("stream disconnected")
                || msg.contains("internal_server_error");
    }

    private String buildStoryboardSystemPrompt(Project project) {
        String projectType = resolveProjectType(project);
        String genre = resolveGenre(project);
        return """
                # 角色定位
                你是一位顶级短剧分镜导演，专精竖屏短剧（9:16）分镜脚本制作。
                你的分镜脚本对标红果短剧、抖音短剧保底S+评级标准，需要做到：节奏精准、视觉冲击力强、爽点镜头密集。
                
                # 项目类型与题材
                %s

                # 文本风格锚点
                %s

                # 视觉风格锚点
                %s

                # 语音风格锚点
                %s

                # 分集/整场节奏（与剧本大纲一致，拆镜时遵守镜头预算）
                %s

                # 禁用词与替代示例
                %s
                
                # 分镜拆解规范
                请将剧本拆解为JSON格式的分镜列表，每个镜头包含以下字段：
                - shotNo: 镜头序号（从1开始）
                - description: 极短镜头提示（景别/动线/光感即可）+ 生图仍靠 imagePrompt 写全细节
                - cameraAngle: 镜头语言（close-up/medium/wide/overhead/pov/low-angle/high-angle/tracking）
                - dialogue: 角色口播台词（口语短句、一句一镜；不要写“角色名：”前缀，用 characterName 表示；无口播可留空；禁小说说明体与长心理）
                - narration: 少用笔法；仅极少数 VO/OS 画外；与 dialogue 不重复；不要当小说旁白铺满
                - subtitleText: 可选。上屏短句（无角色名/无情绪头）；默认可空，由系统从 dialogue 派生
                - ttsText: 可选。更口语的念稿，可与上屏不同；默认可空，由旁白+对白派生
                - duration: 镜头时长（秒，3-8 秒为主，对白镜头不少于3秒，动作或情绪爆发镜头5-8秒，避免每秒一切割的碎片感）
                - characterName: 主要角色名（如有，用于角色一致性和图片复用）
                - sceneName: 场景名称（用于场景复用优化）
                - isDynamic: 必须为 true，短剧平台主流程所有镜头都需要AI视频
                - dynamicReason: 说明该镜头的动态设计，不要写“静态图片即可”
                - imagePrompt: AI生图提示词（中文，需包含：主体描述+表情动作+环境光影+构图+风格关键词，竖版9:16）
                - videoPrompt: 动态镜头视频提示词（基于关键帧的动作+镜头运动描述）
                - motionLevel: 动态强度（low/medium/high）
                
                # 分镜优化要求（稳定拆镜）
                1. 按场景和对白稳定拆镜，不追求镜头数量堆叠，不得无意义乱切
                2. 同一场景优先连续镜头表达：通常每个场景拆 2-5 个镜头
                3. 全集建议 20-45 个镜头（可根据台词和动作适度增减）
                4. 开场第1-3个镜头要建立人物关系与核心冲突
                5. 对话场景优先 close-up 和 medium，动作场景再使用 wide/tracking
                6. 所有镜头都必须可生成动态视频；对白镜头也要设计呼吸、眨眼、轻微推镜、衣摆/光影等低幅动态
                7. imagePrompt 必须足够详细：包含人物外貌、服装、表情、动作、场景环境、光影氛围、画面风格
                8. videoPrompt 只描述基于关键帧的动作和镜头运动，不重复画面基础描述
                9. 禁止输出静态镜头；如果动作弱，也要输出轻动态设计并将 isDynamic 设为 true
                10. 集末镜头组要形成明确悬念或情绪收束，服务下一集衔接
                11. 若 subtitleText 为空，必须保证 dialogue/narration 至少一个可用于派生；不得三者全空
                12. 若 ttsText 包含角色或情绪标签，必须放在可剥离前缀里（如【角色|情绪】），正文保持可直接朗读
                
                # imagePrompt 模板
                每个 imagePrompt 必须非常详细，包含以下全部要素（按顺序）：
                "竖版9:16构图，[镜头类型：特写/中景/全景/俯拍/低角度/POV/跟拍]，
                 [主体人物描述：姓名+性别+年龄段+五官特征+发型+服装+配饰]，[人物动作与表情详情]，
                 [场景环境：具体地点+空间大小+主要陈设+背景元素]，[光影氛围：时间/光源方向/色温/明暗对比]，
                 [调色风格：电影级冷暖对比/高饱和度/柔光/霓虹/暗调等]，[特殊效果：景深/粒子/光斑/雾气/雨雪]，
                 电影级质感，超高清，8K，短剧封面级画质，C4D渲染级精度，皮肤纹理细腻，服装材质真实，
                 [项目题材风格关键词]"

                重要约束：
                - 同一角色在不同镜头中的 imagePrompt 必须保持外貌描述一致（年龄/五官/发型/服装不变）
                - 必须先描述人物主体，再描述环境，最后描述光影和调色
                - 禁止出现模糊描述如"一个男人"或"某个房间"——必须用角色名和具体场景
                - 如果镜头有角色，必须明确写出角色姓名

                # videoPrompt 模板
                每个 videoPrompt 必须描述基于该镜头关键帧的动态变化（不重复 imagePrompt 中的画面基础描述）：
                "[镜头运动方式：缓推/横移/摇镜/升格/急推]，[运动时长与节奏]，[主体动态：人物的具体动作/表情变化/肢体语言]，
                 [环境动态：飘动的发丝/衣摆/风吹草动/光影流转/背景虚化推进]，
                 [情绪氛围变化]，[声音配合提示（可选）：如"配合紧张BGM"或"对白同步口型"]"

                重要约束：
                - videoPrompt 只描述动态变化，不要重复画面构图和外观
                - 必须包含镜头运动方式（不是"可能有运动"这种模糊表述）
                - 如果 isDynamic=true，videoPrompt 必须详细；如果 isDynamic=false，videoPrompt 可简单

                %s
                
                返回格式：{"shots": [...]}
                """.formatted(
                ProjectStyleSupport.buildProjectIdentity(projectType, genre),
                ProjectStyleSupport.buildTextCreationRules(projectType, genre),
                ProjectStyleSupport.buildVisualCreationRules(projectType, genre),
                ProjectStyleSupport.buildAudioPerformanceRules(projectType, genre),
                ProjectStyleSupport.buildShortDramaBeatBlock(),
                ProjectStyleSupport.buildNovelToneBlacklistFewShot(),
                ProjectStyleSupport.buildStoryboardSelfCheckBlock());
    }

    private String normalizeStoryboardPreviewContent(String content) {
        if (content == null) return "{}";
        int startObj = content.indexOf('{');
        int startArr = content.indexOf('[');
        if (startObj == -1 && startArr == -1) {
            return content; // fall back to generic text parser
        }
        int startIdx = (startObj != -1 && startArr != -1) ? Math.min(startObj, startArr) : Math.max(startObj, startArr);
        int endObj = content.lastIndexOf('}');
        int endArr = content.lastIndexOf(']');
        int endIdx = Math.max(endObj, endArr);
        if (endIdx > startIdx) {
            return content.substring(startIdx, endIdx + 1);
        }
        return content.substring(startIdx);
    }

    private JsonNode extractStoryboardRoot(String json) throws IOException {
        String cleanJson = json.replace("`json", "").replace("`", "").trim();
        return objectMapper.readTree(cleanJson);
    }

    private JsonNode resolveShotsNode(JsonNode root) {
        if (root.isArray()) return root;
        if (root.has("shots") && root.get("shots").isArray()) return root.get("shots");
        if (root.has("分镜") && root.get("分镜").isArray()) return root.get("分镜");
        return root;
    }









    private List<Storyboard> parseStoryboardJson(String json, StoryboardGenerateRequest request, boolean strict) {
        List<Storyboard> shots = new ArrayList<>();
        try {
            // Extract JSON block from response
            String cleanJson = json;
            int start = json.indexOf("{");
            int end = json.lastIndexOf("}");
            if (start >= 0 && end > start) {
                cleanJson = json.substring(start, end + 1);
            }
            Project project = resolveProjectById(request.getProjectId());
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
                shot.setSubtitleText(textOrNull(shotNode, "subtitleText"));
                shot.setTtsText(textOrNull(shotNode, "ttsText"));
                int rawDur = shotNode.path("duration").asInt(5);
                shot.setDuration(Math.min(Math.max(rawDur, 3), 8));
                shot.setUserLockedSubtitle(false);
                shot.setUserLockedTts(false);
                shot.setStatus("draft");
                String characterName = textOrNull(shotNode, "characterName");
                Character resolvedCharacter = resolveCharacterByName(request.getProjectId(), characterName);
                if (resolvedCharacter != null) {
                    shot.setCharacterId(resolvedCharacter.getId());
                    log.debug("分镜角色匹配成功: projectId={}, scriptId={}, shotNo={}, characterName={}, characterId={}",
                            request.getProjectId(), request.getScriptId(), shot.getShotNo(), characterName, resolvedCharacter.getId());
                } else if (hasText(characterName)) {
                    log.debug("分镜角色未匹配: projectId={}, scriptId={}, shotNo={}, characterName={}",
                            request.getProjectId(), request.getScriptId(), shot.getShotNo(), characterName);
                }
                shot.setImagePrompt(textOrNull(shotNode, "imagePrompt"));
                if (!hasText(shot.getImagePrompt())) {
                    shot.setImagePrompt(buildImagePrompt(shot, resolvedCharacter, project));
                }
                shot.setVideoPrompt(textOrNull(shotNode, "videoPrompt"));
                applyDynamicRecommendation(shot, shotNode, project);
                DramaTextSanitizer.applyToStoryboard(shot, stripDialogueSpeakerPrefix, stripNarrationSpeakerPrefix, dedupeDialogueNarration);
                if (hasText(shot.getSubtitleText())) {
                    shot.setSubtitleText(DramaTextSanitizer.normalizeSpokenText(shot.getSubtitleText().trim()));
                }
                if (hasText(shot.getTtsText())) {
                    shot.setTtsText(DramaTextSanitizer.normalizeSpokenText(shot.getTtsText().trim()));
                }
                shots.add(shot);
            }
            validateStoryboardShotsSoft(shots, strict ? "parse-strict" : "parse-loose");
        } catch (Exception e) {
            if (strict) {
                throw new BusinessException("分镜预览解析失败，请检查 JSON 结构和字段名称");
            }
            log.warn("分镜 JSON 解析失败，改为生成占位分镜: {}", e.getMessage());
            // Create a placeholder shot if parsing fails
            Storyboard placeholder = new Storyboard();
            placeholder.setProjectId(request.getProjectId());
            placeholder.setScriptId(request.getScriptId());
            placeholder.setEpisodeNo(1);
            placeholder.setShotNo(1);
            placeholder.setDescription("AI生成的分镜脚本（解析失败，请手动编辑）");
            placeholder.setDuration(5);
            Project project = resolveProjectById(request.getProjectId());
            placeholder.setImagePrompt(buildImagePrompt(placeholder, null, project));
            placeholder.setVideoPrompt(buildVideoPrompt(placeholder, "low", project));
            placeholder.setMotionLevel("low");
            placeholder.setDynamicRecommended(true);
            placeholder.setDynamicSelected(true);
            placeholder.setDynamicScore(0);
            placeholder.setDynamicReason("短剧平台主流程要求全部镜头生成动态视频");
            placeholder.setMotionTier("B");
            placeholder.setMotionTierReason("解析失败占位镜头，按轻动态视频处理");
            placeholder.setRenderMode("video");
            placeholder.setStatus("draft");
            shots.add(placeholder);
        }
        return shots;
    }

    private void validateStoryboardShotsSoft(List<Storyboard> shots, String source) {
        if (shots == null || shots.isEmpty()) {
            return;
        }
        int overDuration = 0;
        int novelTone = 0;
        int narrationCount = 0;
        int badSubtitle = 0;
        int noDerive = 0;
        for (Storyboard shot : shots) {
            if (shot.getDuration() != null && shot.getDuration() > 5) {
                overDuration++;
            }
            if (hasText(shot.getNarration())) {
                narrationCount++;
            }
            String dialogue = lower(shot.getDialogue());
            if (containsKeyword(dialogue, new String[]{"冷冷地看着", "心里一沉", "开口说道", "在心里", "空气仿佛"})) {
                novelTone++;
            }
            if (hasText(shot.getSubtitleText()) && shot.getSubtitleText().matches(".*[：:].*")) {
                badSubtitle++;
            }
            if (!hasText(shot.getSubtitleText()) && !hasText(shot.getDialogue()) && !hasText(shot.getNarration())) {
                noDerive++;
            }
        }
        int total = shots.size();
        if (overDuration > 0 || novelTone > 0 || badSubtitle > 0 || noDerive > 0) {
            log.warn("分镜软校验: source={}, total={}, overDuration={}, novelTone={}, badSubtitle={}, noDerive={}, narrationRatio={}/{}",
                    source, total, overDuration, novelTone, badSubtitle, noDerive, narrationCount, total);
        } else {
            log.debug("分镜软校验通过: source={}, total={}, narrationRatio={}/{}", source, total, narrationCount, total);
        }
    }

    private Script requireScript(Long scriptId, Long projectId) {
        Script script = scriptMapper.selectById(scriptId);
        if (script == null) {
            throw new BusinessException("剧本不存在");
        }
        if (projectId != null && !projectId.equals(script.getProjectId())) {
            throw new BusinessException("剧本与项目不匹配");
        }
        return script;
    }

    private String buildImagePrompt(Storyboard shot, Character character, Project project) {
        String cameraAngle = shot.getCameraAngle() != null ? shot.getCameraAngle() : "medium";
        String cameraLabel = toCameraLabel(cameraAngle);
        String description = shot.getDescription() != null ? shot.getDescription() : "短剧场景画面";
        String characterAnchor = buildCharacterAnchor(character);
        String projectVisualGuide = compactGuide(ProjectStyleSupport.buildVisualCreationRules(resolveProjectType(project), resolveGenre(project)));

        StringBuilder sb = new StringBuilder();
        sb.append("竖版9:16构图，").append(cameraLabel).append("镜头");
        if (hasText(characterAnchor)) {
            sb.append("，主体角色：").append(characterAnchor);
            sb.append("。角色面部五官、发型、体型必须与角色设定严格一致");
        }
        sb.append("。画面内容：").append(description);
        sb.append("。项目视觉约束：").append(projectVisualGuide);
        sb.append("。超高清8K，电影级戏剧性光影，高饱和度色彩，短剧封面级画质，");
        sb.append("景深虚化效果，C4D渲染级精度，皮肤纹理细腻，服装材质真实，专业电影摄影");

        return clampPromptLength(sb.toString(), IMAGE_PROMPT_MAX_CHARS);
    }

    private String toCameraLabel(String cameraAngle) {
        return switch (cameraAngle != null ? cameraAngle.trim().toLowerCase(java.util.Locale.ROOT) : "") {
            case "close-up", "closeup" -> "面部特写";
            case "medium" -> "半身中景";
            case "wide" -> "全景";
            case "overhead" -> "俯拍";
            case "low-angle" -> "仰角";
            case "pov" -> "主观视角POV";
            case "high-angle" -> "高角度俯视";
            case "tracking" -> "跟拍运动";
            default -> cameraAngle;
        };
    }

    private void applyDynamicRecommendation(Storyboard shot, JsonNode shotNode, Project project) {
        boolean aiDynamic = shotNode != null && shotNode.path("isDynamic").asBoolean(false);
        String aiReason = shotNode != null ? textOrNull(shotNode, "dynamicReason") : null;
        String aiMotionLevel = normalizeMotionLevel(shotNode != null ? textOrNull(shotNode, "motionLevel") : null);
        int score = 0;
        LinkedHashSet<String> reasons = new LinkedHashSet<>();

        String description = lower(shot.getDescription());
        String dialogue = lower(shot.getDialogue());
        String narration = lower(shot.getNarration());
        String cameraAngle = lower(shot.getCameraAngle());
        boolean hasAction = containsKeyword(description, ACTION_KEYWORDS)
                || containsKeyword(dialogue, ACTION_KEYWORDS)
                || containsKeyword(narration, ACTION_KEYWORDS);
        boolean highEmotionDialogue = containsKeyword(dialogue, HIGH_EMOTION_KEYWORDS);

        if (aiDynamic) {
            score += 34;
            reasons.add(hasText(aiReason) ? aiReason : "AI判断该镜头存在明显动作或镜头运动");
        }

        if (hasAction) {
            score += 26;
            reasons.add("画面存在动作变化，适合加入动态表现");
        }
        if (containsKeyword(description, CONFLICT_ACTION_KEYWORDS)
                || containsKeyword(dialogue, CONFLICT_DIALOGUE_KEYWORDS)) {
            score += 16;
            reasons.add("冲突情绪明显，动态镜头更能放大戏剧张力");
        }

        if (containsKeyword(description, TRANSITION_KEYWORDS)
                || containsKeyword(narration, TRANSITION_KEYWORDS)) {
            score += 16;
            reasons.add("该镜头承担转场或氛围推进作用");
        }

        if ("wide".equals(cameraAngle) || "overhead".equals(cameraAngle) || "pov".equals(cameraAngle)) {
            score += 10;
            reasons.add("镜头语言更适合做运动或推进");
        }

        if (shot.getDuration() != null && shot.getDuration() >= 4) {
            score += 6;
            reasons.add("镜头接近时长上限，适合加入轻动态避免画面停滞");
        }

        double dialogueDensity = calculateDialogueDensity(shot);
        if (dialogueDensity >= 26d) {
            score -= 18;
            reasons.add("台词密度偏高，建议以对白节奏为主减少画面运动");
        } else if (dialogueDensity >= 18d) {
            score -= 8;
            reasons.add("该镜头以信息传达为主，宜控制运动强度");
        } else if (dialogueDensity <= 7d && hasAction) {
            score += 8;
            reasons.add("低台词密度且动作明确，适合提升动态表现");
        }

        if (hasText(dialogue)
                && !hasAction
                && !highEmotionDialogue) {
            score -= 14;
            reasons.add("对白镜头动作弱，采用呼吸感与轻微推镜保持动态");
        }

        if (!hasText(dialogue) && !hasText(narration)) {
            score += 6;
            reasons.add("纯画面镜头更适合通过动态强化氛围");
        }

        if (shot.getShotNo() != null && (shot.getShotNo() == 1 || shot.getShotNo() % 10 == 0)) {
            score += 6;
            reasons.add("关键节点镜头值得提高动态优先级");
        }

        score = Math.max(0, Math.min(score, 100));
        MotionTierDecision tierDecision = decideMotionTier(score, dialogueDensity, hasAction, highEmotionDialogue, aiDynamic);
        boolean recommended = true;

        if (!hasText(shot.getVideoPrompt())) {
            shot.setVideoPrompt(buildVideoPrompt(shot, resolveMotionLevel(aiMotionLevel, score), project));
        }

        String motionTier = "C".equalsIgnoreCase(tierDecision.tier()) ? "B" : tierDecision.tier();
        String tierReason = "C".equalsIgnoreCase(tierDecision.tier())
                ? "短剧平台主流程要求全部镜头生成动态视频，弱动作镜头按轻动态处理"
                : tierDecision.reason();
        shot.setMotionLevel(resolveMotionLevelByTier(aiMotionLevel, score, motionTier));
        shot.setDynamicRecommended(recommended);
        shot.setDynamicSelected(true);
        shot.setDynamicScore(score);
        shot.setDynamicReason(buildDynamicReason(reasons, recommended));
        shot.setMotionTier(motionTier);
        shot.setMotionTierReason(tierReason);
        shot.setRenderMode("video");
    }

    private void recomputeDynamicRecommendations(List<Storyboard> shots, Project project) {
        if (!dynamicRecommendEnabled || shots == null || shots.isEmpty()) {
            return;
        }
        for (Storyboard shot : shots) {
            applyDynamicRecommendation(shot, null, project);
        }
        Map<Integer, List<Storyboard>> byEpisode = new HashMap<>();
        for (Storyboard shot : shots) {
            Integer ep = shot.getEpisodeNo() != null ? shot.getEpisodeNo() : 1;
            byEpisode.computeIfAbsent(ep, k -> new ArrayList<>()).add(shot);
        }
        for (Map.Entry<Integer, List<Storyboard>> entry : byEpisode.entrySet()) {
            Integer ep = entry.getKey();
            List<Storyboard> episodeShots = entry.getValue();
            int total = episodeShots.size();
            int allowed = resolveEpisodeDynamicBudget(total);
            for (Storyboard shot : episodeShots) {
                shot.setDynamicRecommended(true);
                shot.setDynamicSelected(true);
                shot.setRenderMode("video");
                if (!hasText(shot.getMotionTier()) || "C".equalsIgnoreCase(shot.getMotionTier())) {
                    shot.setMotionTier("B");
                    shot.setMotionTierReason("短剧平台主流程要求全部镜头生成动态视频，弱动作镜头按轻动态处理");
                }
            }
            int kept = episodeShots.size();
            int avgScore = episodeShots.isEmpty()
                    ? 0
                    : (int) Math.round(episodeShots.stream().mapToInt(s -> s.getDynamicScore() != null ? s.getDynamicScore() : 0).average().orElse(0));
            long tierACount = episodeShots.stream().filter(s -> "A".equalsIgnoreCase(s.getMotionTier())).count();
            long tierBCount = episodeShots.stream().filter(s -> "B".equalsIgnoreCase(s.getMotionTier())).count();
            long tierCCount = episodeShots.stream().filter(s -> "C".equalsIgnoreCase(s.getMotionTier())).count();
            log.debug("动态推荐统计: episodeNo={}, totalShots={}, allowedBudget={}, selected={}, avgScore={}, tierA={}, tierB={}, tierC={}",
                    ep, total, allowed, kept, avgScore, tierACount, tierBCount, tierCCount);
        }
        if (forceDynamicByDefault && !motionTierEnabled) {
            enforceDynamicTargetRatio(shots);
        }
    }

    private void enforceDynamicTargetRatio(List<Storyboard> shots) {
        if (shots == null || shots.isEmpty()) {
            return;
        }
        int total = shots.size();
        int target = Math.max(1, (int) Math.ceil(total * Math.max(0.5d, Math.min(1.0d, targetDynamicRatio))));
        List<Storyboard> sorted = shots.stream()
                .sorted((a, b) -> Integer.compare(
                        b.getDynamicScore() != null ? b.getDynamicScore() : 0,
                        a.getDynamicScore() != null ? a.getDynamicScore() : 0))
                .toList();
        int selected = 0;
        for (Storyboard shot : sorted) {
            boolean enable = selected < target;
            shot.setDynamicSelected(enable);
            shot.setRenderMode(enable ? "video" : "image");
            if (enable) {
                selected++;
            }
        }
        log.debug("动态占比策略生效: totalShots={}, targetDynamicRatio={}, targetCount={}, selected={}",
                total, targetDynamicRatio, target, selected);
    }

    private int resolveEpisodeDynamicBudget(int totalShots) {
        int maxByRecommendRatio = (int) Math.ceil(totalShots * Math.max(0.05d, dynamicRecommendMaxRatioPerEpisode));
        int allowedByRecommend = Math.max(1, Math.min(Math.max(1, dynamicRecommendMaxCountPerEpisode), maxByRecommendRatio));
        if (!costVideoAiEnabled) {
            return allowedByRecommend;
        }
        int allowedByCost = (int) Math.ceil(totalShots * Math.max(0.05d, Math.min(0.95d, costVideoAiShotRatio)));
        return Math.max(1, Math.min(allowedByRecommend, allowedByCost));
    }

    private double calculateDialogueDensity(Storyboard shot) {
        int chars = countEffectiveChars(shot != null ? shot.getDialogue() : null)
                + countEffectiveChars(shot != null ? shot.getNarration() : null);
        int duration = shot != null && shot.getDuration() != null && shot.getDuration() > 0 ? shot.getDuration() : 5;
        return chars / Math.max(1d, duration);
    }

    private int countEffectiveChars(String text) {
        if (!hasText(text)) {
            return 0;
        }
        return text.replaceAll("\\s+", "").length();
    }

    private MotionTierDecision decideMotionTier(int score,
                                                double dialogueDensity,
                                                boolean hasAction,
                                                boolean highEmotionDialogue,
                                                boolean aiDynamic) {
        if (!motionTierEnabled) {
            if (score >= dynamicRecommendMinScoreToRecommend) {
                return new MotionTierDecision("A", "兼容旧逻辑：高分镜头按动态处理");
            }
            return new MotionTierDecision("C", "兼容旧逻辑：低分镜头按轻动态兜底处理");
        }
        if ((score >= 76 && dialogueDensity <= 20d) || (score >= 70 && hasAction && aiDynamic)) {
            return new MotionTierDecision("A", "动作与冲突强，且台词密度可控，优先真 i2v");
        }
        if (dialogueDensity >= 24d && !hasAction && !highEmotionDialogue) {
            return new MotionTierDecision("C", "台词密度高且动作弱，使用低幅轻动态避免喧宾夺主");
        }
        return new MotionTierDecision("B", "采用基线轻动态，保持节奏连贯并控制成本");
    }

    private String resolveMotionLevel(String aiMotionLevel, int score) {
        if (hasText(aiMotionLevel)) {
            return normalizeMotionLevel(aiMotionLevel);
        }
        if (score >= 78) {
            return "high";
        }
        if (score >= 58) {
            return "medium";
        }
        return "low";
    }

    private String resolveMotionLevelByTier(String aiMotionLevel, int score, String motionTier) {
        if (hasText(aiMotionLevel)) {
            return normalizeMotionLevel(aiMotionLevel);
        }
        if ("A".equalsIgnoreCase(motionTier)) {
            return score >= 84 ? "high" : "medium";
        }
        if ("B".equalsIgnoreCase(motionTier)) {
            return score >= 62 ? "medium" : "low";
        }
        return "low";
    }

    private String buildDynamicReason(LinkedHashSet<String> reasons, boolean recommended) {
        if (reasons.isEmpty()) {
            return recommended ? "建议做轻动态处理以增强镜头表现" : "动作弱，采用轻动态保持画面生命力";
        }

        List<String> topReasons = new ArrayList<>(reasons).subList(0, Math.min(2, reasons.size()));
        return String.join("；", topReasons);
    }

    private String buildVideoPrompt(Storyboard shot, String motionLevel, Project project) {
        String motionInstruction = switch (normalizeMotionLevel(motionLevel)) {
            case "high" -> "镜头快速推进或急甩，人物动作幅度大且连贯流畅（走动/转身/大幅度手势），情绪爆发式表情变化，画面张力和冲击力十足，背景虚化快速变化";
            case "medium" -> "镜头持续平缓推进（Ken Burns推拉），人物有明确可见的肢体动作（转头/抬手/眼神流转/嘴微动说话），面部微表情丰富变化，光影缓慢移动，背景虚化层次渐变";
            default -> "镜头持续缓慢但可见的推进（5-8%%缓推），人物保持自然姿态但有明确微动作（眨眼/嘴唇微动/转头/身体微倾/手势轻抬），背景光影自然流转，发丝和衣角轻微飘动，营造真实的呼吸感和画面生命力，画面必须有可见的动态变化，不能是完全静止的图片";
        };

        String sceneContext = hasText(shot.getDescription()) ? trimPromptSegment(shot.getDescription(), 100) : "保持剧情连续性";
        String characterContext = buildCharacterContextForVideo(shot);
        String projectVisualGuide = compactGuide(ProjectStyleSupport.buildVisualCreationRules(resolveProjectType(project), resolveGenre(project)));
        return clampPromptLength(String.format(
                "基于该关键帧生成%ds竖屏9:16动态镜头。%s。%s项目视觉约束：%s。角色主体保持一致，避免面部漂移变形和场景穿帮。镜头内容：%s。动态效果自然流畅，符合短剧叙事张力节奏。",
                shot.getDuration() != null && shot.getDuration() > 0 ? shot.getDuration() : 5,
                motionInstruction,
                hasText(characterContext) ? characterContext + "。" : "",
                projectVisualGuide,
                sceneContext), VIDEO_PROMPT_MAX_CHARS);
    }

    private String buildCharacterContextForVideo(Storyboard shot) {
        if (shot.getCharacterId() == null) return "";
        Character character = characterMapper.selectById(shot.getCharacterId());
        if (character == null || !hasText(character.getName())) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("核心角色：").append(character.getName());
        if (hasText(character.getGender())) {
            sb.append("（").append(displayGender(character.getGender())).append("）");
        }
        if (hasText(character.getAppearance())) {
            sb.append("，外貌：").append(trimPromptSegment(character.getAppearance(), 40));
        }
        return sb.toString();
    }

    private String normalizeMotionLevel(String motionLevel) {
        if (!hasText(motionLevel)) {
            return "low";
        }
        String normalized = motionLevel.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "high", "medium", "low" -> normalized;
            default -> "low";
        };
    }

    /**
     * 分镜落库前：统一清洗对白/旁白，可选走一遍「台词短打」二遍（仅在有 userId 的保存路径上启用）。
     */
    private void applyDramaTextProcessing(Storyboard shot, Long userId, Project project, String jsonCharacterNameHint) {
        if (shot == null) {
            return;
        }
        DramaTextSanitizer.applyToStoryboard(shot, stripDialogueSpeakerPrefix, stripNarrationSpeakerPrefix, dedupeDialogueNarration);
        if (!lineDoctorEnabled || userId == null || !hasText(shot.getDialogue()) || project == null) {
            return;
        }
        String chLabel = jsonCharacterNameHint;
        if (!hasText(chLabel) && shot.getCharacterId() != null) {
            Character c = characterMapper.selectById(shot.getCharacterId());
            if (c != null) {
                chLabel = c.getName();
            }
        }
        String polished = maybePolishDialogueLine(userId, project, shot.getDialogue(), chLabel);
        if (hasText(polished)) {
            shot.setDialogue(polished);
            DramaTextSanitizer.applyToStoryboard(shot, stripDialogueSpeakerPrefix, false, dedupeDialogueNarration);
        }
    }

    private String maybePolishDialogueLine(Long userId, Project project, String dialogue, String characterLabel) {
        try {
            TextAiProvider textProvider = aiProviderFactory.getTextProvider(userId);
            String system = """
                    你是短剧口语台词编辑。只输出一行最终台词，不要引号、不要解释、不要前后缀。
                    要求：8-22 个汉字为宜，口语、可一口气念完；尽量去掉“因为/所以/其实/也就是说”等说明腔。
                    若原句已足够口语，可只做微调。只输出这一行，不要换行。
                    """;
            String user = String.format("题材：%s %s\n角色：%s\n原句：%s",
                    resolveProjectType(project),
                    resolveGenre(project),
                    hasText(characterLabel) ? characterLabel : "（未指定）",
                    dialogue);
            String out = textProvider.chat(system, user);
            if (!hasText(out)) {
                return dialogue;
            }
            String oneLine = out.trim().split("\\R", 2)[0].trim();
            return hasText(oneLine) ? oneLine : dialogue;
        } catch (Exception e) {
            log.warn("台词二遍润色失败，保留原句: {}", e.getMessage());
            return dialogue;
        }
    }

    private String textOrNull(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return hasText(text) ? text.trim() : null;
    }

    private boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    private boolean containsKeyword(String text, String[] keywords) {
        if (!hasText(text)) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private Character resolveShotCharacter(Storyboard shot) {
        if (shot == null || shot.getCharacterId() == null) {
            return null;
        }
        return characterMapper.selectById(shot.getCharacterId());
    }

    private Project resolveProjectById(Long projectId) {
        if (projectId == null) {
            return null;
        }
        return projectService.getProject(projectId);
    }

    private Character resolveCharacterByName(Long projectId, String characterName) {
        if (projectId == null || !hasText(characterName)) {
            return null;
        }
        String trimmedName = characterName.trim();
        Character exactMatch = characterMapper.selectOne(new LambdaQueryWrapper<Character>()
                .eq(Character::getProjectId, projectId)
                .eq(Character::getName, trimmedName)
                .last("LIMIT 1"));
        if (exactMatch != null) {
            return exactMatch;
        }

        String normalizedTarget = normalizeCharacterLookupKey(trimmedName);
        if (!hasText(normalizedTarget)) {
            return null;
        }
        List<Character> characters = characterMapper.selectList(new LambdaQueryWrapper<Character>()
                .eq(Character::getProjectId, projectId)
                .orderByAsc(Character::getSortOrder)
                .orderByAsc(Character::getCreateTime));
        for (Character character : characters) {
            if (normalizedTarget.equals(normalizeCharacterLookupKey(character.getName()))) {
                return character;
            }
        }
        return null;
    }

    private String normalizeCharacterLookupKey(String value) {
        if (!hasText(value)) {
            return "";
        }
        StringBuilder normalized = new StringBuilder();
        for (char ch : value.trim().toCharArray()) {
            if (java.lang.Character.isLetterOrDigit(ch)) {
                normalized.append(java.lang.Character.toLowerCase(ch));
            }
        }
        return normalized.toString();
    }

    private String buildImageGenerationPrompt(String originalPrompt, Character character, List<String> referenceImageUrls, Project project) {
        String promptCore = trimPromptSegment(originalPrompt, 280);
        if (character == null) {
            return clampPromptLength("项目视觉约束："
                    + compactGuide(ProjectStyleSupport.buildVisualCreationRules(resolveProjectType(project), resolveGenre(project)))
                    + "。当前镜头要求："
                    + promptCore, IMAGE_PROMPT_MAX_CHARS);
        }
        StringBuilder builder = new StringBuilder();
        builder.append("项目视觉约束：")
                .append(compactGuide(ProjectStyleSupport.buildVisualCreationRules(resolveProjectType(project), resolveGenre(project))))
                .append("。");
        builder.append("角色一致性锚点：主体必须是角色“")
                .append(character.getName())
                .append("”");

        List<String> anchors = new ArrayList<>();
        if (hasText(character.getGender())) {
            anchors.add("性别" + displayGender(character.getGender()));
        }
        if (hasText(character.getAge())) {
            anchors.add("年龄" + character.getAge());
        }
        if (hasText(character.getAppearance())) {
            anchors.add("外貌特征" + character.getAppearance());
        }
        if (hasText(character.getPersonality())) {
            anchors.add("气质" + character.getPersonality());
        }
        if (!anchors.isEmpty()) {
            builder.append("，").append(String.join("，", anchors));
        }
        builder.append("。人物年龄感、脸型五官、发型、体型、服装必须保持完全稳定，脸部清晰可辨无变形。");

        if (hasCharacterReferenceImage(character, referenceImageUrls)) {
            builder.append("已提供该角色多角度参考图（正面/侧面/回眸），必须严格保持与参考图为同一人物，不得改变年龄感、五官结构、发型、肤色和服装。面部五官比例和轮廓必须与参考图高度一致，不可更换演员。");
        }

        builder.append("当前镜头要求：").append(promptCore);
        return clampPromptLength(builder.toString(), IMAGE_PROMPT_MAX_CHARS);
    }

    private boolean hasCharacterReferenceImage(Character character, List<String> referenceImageUrls) {
        return character != null
                && hasText(character.getImageUrl())
                && referenceImageUrls != null
                && referenceImageUrls.contains(character.getImageUrl());
    }

    private String buildImageNegativePrompt(Character character, Project project) {
        LinkedHashSet<String> negativeTerms = new LinkedHashSet<>();
        for (String term : DEFAULT_IMAGE_NEGATIVE_PROMPT.split("，")) {
            if (hasText(term)) {
                negativeTerms.add(term);
            }
        }
        for (String term : ProjectStyleSupport.buildVisualNegativeTerms(resolveProjectType(project), resolveGenre(project)).split("，")) {
            if (hasText(term)) {
                negativeTerms.add(term);
            }
        }
        if (character != null) {
            String gender = lower(character.getGender());
            if ("female".equals(gender)) {
                negativeTerms.add("男性化五官");
                negativeTerms.add("男性身形");
            } else if ("male".equals(gender)) {
                negativeTerms.add("女性化五官");
                negativeTerms.add("女性化妆容");
            }
            if (looksYoung(character.getAge())) {
                negativeTerms.add("中老年面相");
                negativeTerms.add("年龄偏大");
            }
        }
        return clampPromptLength(String.join("，", negativeTerms), IMAGE_NEGATIVE_MAX_CHARS);
    }

    private String trimPromptSegment(String text, int maxLen) {
        if (!hasText(text)) {
            return "";
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        if (compact.length() <= maxLen) {
            return compact;
        }
        return compact.substring(0, Math.max(32, maxLen - 1)) + "…";
    }

    private String clampPromptLength(String text, int maxLen) {
        if (!hasText(text)) {
            return "";
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        if (compact.length() <= maxLen) {
            return compact;
        }
        return compact.substring(0, Math.max(40, maxLen - 1)) + "…";
    }

    private String resolveProjectType(Project project) {
        return ProjectStyleSupport.resolveProjectType(project != null ? project.getProjectType() : null);
    }

    private String resolveGenre(Project project) {
        return ProjectStyleSupport.resolveGenre(project != null ? project.getGenre() : null);
    }

    private String compactGuide(String text) {
        if (!hasText(text)) {
            return "";
        }
        return text.replace("\n", " ")
                .replace("- ", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean looksYoung(String age) {
        if (!hasText(age)) {
            return false;
        }
        String normalized = lower(age);
        if (normalized.contains("少女") || normalized.contains("年轻") || normalized.contains("青年")) {
            return true;
        }
        Matcher matcher = Pattern.compile("\\d+").matcher(normalized);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group()) <= 30;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }

    private String displayGender(String gender) {
        if (!hasText(gender)) {
            return "";
        }
        return switch (gender.trim().toLowerCase(Locale.ROOT)) {
            case "female" -> "女性";
            case "male" -> "男性";
            default -> gender;
        };
    }

    private String buildCharacterAnchor(Character character) {
        if (character == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (hasText(character.getName())) {
            parts.add("角色“" + character.getName() + "”");
        }
        if (hasText(character.getGender())) {
            parts.add(displayGender(character.getGender()));
        }
        if (hasText(character.getAge())) {
            parts.add(character.getAge());
        }
        if (hasText(character.getAppearance())) {
            parts.add(character.getAppearance());
        }
        return String.join("，", parts);
    }

    private static final String[] ACTION_KEYWORDS = {
            "跑", "冲", "追", "打", "拥抱", "转身", "回头", "推门", "拉开", "坠", "摔", "扑", "走向",
            "奔", "跳", "挥手", "起身", "镜头推进", "镜头拉远", "移动", "摇镜", "风吹", "雨", "火", "爆炸"
    };
    private static final String[] HIGH_EMOTION_KEYWORDS = {"！", "?", "？", "滚", "杀", "不行", "别碰", "马上"};
    private static final String[] CONFLICT_ACTION_KEYWORDS = {"突然", "猛地", "反手", "爆发", "撕扯", "冲上前"};
    private static final String[] CONFLICT_DIALOGUE_KEYWORDS = {"你敢", "闭嘴", "住手", "不可能", "你输了", "给我停下"};

    private static final String[] TRANSITION_KEYWORDS = {
            "转场", "切换", "空镜", "远景", "夜景", "天台", "街道", "车流", "门外", "入场", "登场", "离开"
    };

    private record MotionTierDecision(String tier, String reason) {}
}
