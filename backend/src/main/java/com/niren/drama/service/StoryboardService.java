package com.niren.drama.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.niren.drama.ai.AiProviderFactory;
import com.niren.drama.ai.ImageAiProvider;
import com.niren.drama.ai.TextAiProvider;
import com.niren.drama.ai.TtsProvider;
import com.niren.drama.ai.VoiceInfo;
import com.niren.drama.ai.trace.AiCallTrace;
import com.niren.drama.ai.trace.AiTraceContext;
import com.niren.drama.dto.storyboard.StoryboardGenerateRequest;
import com.niren.drama.dto.storyboard.StoryboardPreviewSaveRequest;
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
import com.niren.drama.dto.storyboard.StoryboardPreviewRepairRequest;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    private final ObjectMapper objectMapper;
    private final ObjectProvider<StoryboardService> selfProvider;

    /** Portrait image size for vertical (9:16) storyboard images */
    private static final String PORTRAIT_IMAGE_SIZE = "1024x1792";
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

    /** Enable image reuse for same scene+character+angle combinations */
    @Value("${niren.cost.image-reuse-enabled:true}")
    private boolean imageReuseEnabled;

    @Value("${niren.upload.path:./uploads}")
    private String uploadPath;

    @Value("${niren.upload.base-url:http://localhost:8080/api/files}")
    private String baseUrl;

    public TaskRecord startGenerateStoryboard(Long userId, StoryboardGenerateRequest request) {
        log.debug("Creating storyboard task: userId={}, projectId={}, scriptId={}",
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

    public void streamGenerateStoryboard(Long userId, StoryboardGenerateRequest request, java.util.function.Consumer<String> chunkConsumer, java.util.function.Consumer<String> progressConsumer) {
        log.debug("Start storyboard preview streaming: userId={}, projectId={}, scriptId={}",
            userId, request.getProjectId(), request.getScriptId());
        projectService.getProject(userId, request.getProjectId());
        Script script = requireScript(request.getScriptId(), request.getProjectId());
        TextAiProvider textProvider = aiProviderFactory.getTextProvider(userId);
        String systemPrompt = buildStoryboardSystemPrompt();
        generateStoryboardPreviewByScenes(textProvider, systemPrompt, script, request, chunkConsumer, progressConsumer);
    }

    private void generateStoryboardPreviewByScenes(TextAiProvider textProvider,
                                                   String systemPrompt,
                                                   Script script,
                                                   StoryboardGenerateRequest request,
                                                   java.util.function.Consumer<String> chunkConsumer,
                                                   java.util.function.Consumer<String> progressConsumer) {
        List<ScriptScene> scenes = splitScriptScenes(script.getContent());
        if (scenes.isEmpty()) {
            throw new BusinessException("剧本内容为空，无法拆分场景");
        }

        boolean isStream = chunkConsumer != null;
        log.debug("Storyboard preview scene split complete: projectId={}, scriptId={}, sceneCount={}, streaming={}",
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
                log.debug("Storyboard preview draft invalidated because script changed: projectId={}, scriptId={}, savedShots={}",
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
                log.debug("Storyboard preview resumed from saved draft: projectId={}, scriptId={}, resumedShots={}, nextShotNo={}, startSceneIndex={}",
                        request.getProjectId(), request.getScriptId(), savedShots.size(), nextShotNo, startSceneIndex);
                startSceneIndex = (int) maxSceneId + 1;
            }
        }

        for (int sceneIndex = startSceneIndex; sceneIndex < scenes.size(); sceneIndex++) {
            log.debug("Generating storyboard scene: projectId={}, scriptId={}, sceneIndex={}, sceneLabel={}",
                    request.getProjectId(), request.getScriptId(), sceneIndex, scenes.get(sceneIndex).displayName());
            ArrayNode sceneShots = generateSceneShotsWithFallback(
                    textProvider,
                    systemPrompt,
                    scenes,
                    sceneIndex,
                    request,
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
                storyboardMapper.insert(draftShot);
if (isStream) {
                    try {
                        String shotJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(shotObject);
                        String chunk = (firstShotEmitted ? ",\n" : "") + "    " + shotJson.replace("\n", "\n    ");
                        chunkConsumer.accept(chunk);
                        firstShotEmitted = true;
                    } catch (Exception e) {
                        log.warn("Failed to stringify shot", e);
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
                String batchPrompt = buildStoryboardSceneBatchUserPrompt(scenes, sceneIndex, batch);
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
                    log.warn("Storyboard scene {} failed with batch size {}, retrying with smaller chunks. reason={}",
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
                log.info("Storyboard preview truncated at attempt {}, continuing generation", attempt + 1);
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
            log.info("Storyboard preview still incomplete after attempt {}, requesting continuation", attempt + 1);
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

    private String buildStoryboardSceneBatchUserPrompt(List<ScriptScene> scenes, int sceneIndex, SceneBatch batch) {
        ScriptScene scene = scenes.get(sceneIndex);
        String previousScene = sceneIndex > 0 ? scenes.get(sceneIndex - 1).displayName() : "无";
        String nextScene = sceneIndex + 1 < scenes.size() ? scenes.get(sceneIndex + 1).displayName() : "无";
        String sceneNameHint = hasText(scene.sceneLabel()) ? scene.sceneLabel() : scene.displayName();
        return String.format("""
                请仅为当前场景的当前片段生成分镜 JSON，不要输出其他场景或本场其他片段内容。

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
                """,
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
        log.debug("Saving storyboard preview: userId={}, projectId={}, scriptId={}, contentLength={}",
                userId, request.getProjectId(), request.getScriptId(), request.getContent() != null ? request.getContent().length() : 0);
        projectService.getProject(userId, request.getProjectId());
        Script script = requireScript(request.getScriptId(), request.getProjectId());

        StoryboardGenerateRequest generateRequest = new StoryboardGenerateRequest();
        generateRequest.setProjectId(request.getProjectId());
        generateRequest.setScriptId(request.getScriptId());

        List<Storyboard> shots = parseStoryboardJson(request.getContent(), generateRequest, true);
    log.debug("Storyboard preview parsed: projectId={}, scriptId={}, shotCount={}",
        request.getProjectId(), request.getScriptId(), shots.size());
        storyboardMapper.delete(new LambdaQueryWrapper<Storyboard>()
                .eq(Storyboard::getProjectId, request.getProjectId())
                .eq(Storyboard::getScriptId, request.getScriptId()));

        int episodeNo = script.getEpisodeNo() != null ? script.getEpisodeNo() : 1;
        for (Storyboard shot : shots) {
            shot.setEpisodeNo(episodeNo);
            storyboardMapper.insert(shot);
        }
        return shots;
    }

    @Async("aiTaskExecutor")
    public void generateStoryboardAsync(Long userId, StoryboardGenerateRequest request, Long taskId) {
        TaskRecord task = taskRecordMapper.selectById(taskId);
        if (task == null) return;
        try {
            log.debug("Async storyboard generation start: taskId={}, userId={}, projectId={}, scriptId={}",
                    taskId, userId, request.getProjectId(), request.getScriptId());
            updateTask(task, "RUNNING", 10, "读取剧本内容...");
            Script script = requireScript(request.getScriptId(), request.getProjectId());

            updateTask(task, "RUNNING", 20, "AI正在拆解分镜脚本...");
            TextAiProvider textProvider = aiProviderFactory.getTextProvider(userId);
            String systemPrompt = buildStoryboardSystemPrompt();
            // generateStoryboardPreviewByScenes saves each scene's shots to the database
            // incrementally (status=preview_draft), so partial progress is preserved even
            // if the task is interrupted. Pass null for chunkConsumer — we don't need to
            // stream JSON to anyone in the async path. Use the progressConsumer to keep
            // the task message up-to-date while scenes are being generated.
            generateStoryboardPreviewByScenes(textProvider, systemPrompt, script, request, null,
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
            log.debug("Async storyboard generation complete: taskId={}, projectId={}, scriptId={}, shotCount={}",
                    taskId, request.getProjectId(), request.getScriptId(), shotCount);

        } catch (Exception e) {
            log.error("Storyboard generation failed for task {}", taskId, e);
            task.setStatus("FAILED");
            task.setMessage("分镜生成失败: " + e.getMessage());
            taskRecordMapper.updateById(task);
        }
    }

    /**
     * Start generating images for all storyboard shots of a project.
     */
    public TaskRecord startGenerateStoryboardImages(Long userId, Long projectId, java.util.List<Long> shotIds) {
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

        log.debug("Creating storyboard image task: userId={}, projectId={}, shotCount={}, filteredByIds={}",
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

    @Async("aiTaskExecutor")
    public void generateStoryboardImagesAsync(Long userId, Long projectId, List<Storyboard> shots, Long taskId) {
        TaskRecord task = taskRecordMapper.selectById(taskId);
        if (task == null) return;
        ArrayNode traceCalls = objectMapper.createArrayNode();
        int omittedTraceCalls = 0;
        try {
            ImageAiProvider imageProvider = aiProviderFactory.getImageProvider(userId);
            int total = shots.size();
            int completed = 0;
            int alreadyReady = 0;
            int generated = 0;
            int reused = 0;
            int failed = 0;
            List<String> failedDetails = new ArrayList<>();

            log.debug("Start storyboard image generation: taskId={}, userId={}, projectId={}, shotCount={}, reuseEnabled={}",
                    taskId, userId, projectId, total, imageReuseEnabled);

            // Build image reuse cache: scene+character+angle → imageUrl
            Map<String, String> imageCache = new HashMap<>();
            if (imageReuseEnabled) {
                buildImageCache(imageCache, shots);
            }

            for (Storyboard shot : shots) {
                if (shot.getImageUrl() != null && !shot.getImageUrl().isBlank()) {
                    log.debug("Skip storyboard image generation because image already exists: taskId={}, shotId={}, shotNo={}, imageUrl={}",
                            taskId, shot.getId(), shot.getShotNo(), shot.getImageUrl());
                    alreadyReady++;
                    completed++;
                    continue;
                }

                String prompt = shot.getImagePrompt();
                Character character = resolveShotCharacter(shot);
                if (prompt == null || prompt.isBlank()) {
                    prompt = buildImagePrompt(shot, character);
                }
                List<String> referenceImageUrls = collectReferenceImageUrls(shot, character);
                String generationPrompt = buildImageGenerationPrompt(prompt, character, referenceImageUrls);
                String negativePrompt = buildImageNegativePrompt(character);
                log.debug("Storyboard image request prepared: taskId={}, shotId={}, shotNo={}, characterId={}, promptLength={}, referenceCount={}, negativePromptLength={}",
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
                        reused++;
                        log.debug("Storyboard image reused from cache: taskId={}, shotId={}, shotNo={}, cacheKey={}, imageUrl={}",
                                taskId, shot.getId(), shot.getShotNo(), cacheKey, shot.getImageUrl());
                    } else {
                        // Use smart resolution based on camera angle
                        String imageSize = costEstimationService.getOptimalImageSize(shot.getCameraAngle());
                        String imageUrl = imageProvider.generateImage(
                            generationPrompt,
                                imageSize,
                                PORTRAIT_IMAGE_STYLE,
                            referenceImageUrls,
                            negativePrompt);
                        if (imageUrl == null || imageUrl.isBlank()) {
                            throw new BusinessException("图片接口未返回有效图片地址");
                        }
                        shot.setImageUrl(imageUrl);
                        shot.setStatus("image_generated");
                        storyboardMapper.updateById(shot);
                        generated++;
                        log.debug("Storyboard image generated: taskId={}, shotId={}, shotNo={}, imageSize={}, imageUrl={}",
                                taskId, shot.getId(), shot.getShotNo(), imageSize, imageUrl);

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
                    log.warn("Failed to generate image for shot {}", shotLabel, e);
                } finally {
                    omittedTraceCalls = appendTraceCalls(traceCalls, shot, AiTraceContext.drain(), omittedTraceCalls);
                }
                completed++;
            }

            task.setProgress(100);
            int readyCount = alreadyReady + reused + generated;
            if (readyCount == 0 && failed > 0) {
                task.setStatus("FAILED");
                task.setMessage(String.format("分镜图片生成失败，所选%d个镜头均未生成成功。%s", total, buildFailureReasonSummary(failedDetails, 3)));
            } else {
                task.setStatus("SUCCESS");
                if (failed > 0) {
                    task.setMessage(String.format("分镜图片生成完成：成功%d个，复用%d个，已存在%d个，失败%d个。%s", generated, reused, alreadyReady, failed, buildFailureReasonSummary(failedDetails, 3)));
                } else {
                    task.setMessage(String.format("分镜图片生成完成，共处理%d个镜头，新增%d张，复用%d张，已存在%d张", total, generated, reused, alreadyReady));
                }
            }
            task.setResult(buildTaskTraceResult("image", projectId, traceCalls, omittedTraceCalls,
                    Map.of(
                            "total", total,
                            "generated", generated,
                            "reused", reused,
                            "alreadyReady", alreadyReady,
                            "failed", failed)));
            taskRecordMapper.updateById(task);
                log.debug("Storyboard image generation complete: taskId={}, projectId={}, total={}, generated={}, reused={}, alreadyReady={}, failed={}",
                    taskId, projectId, total, generated, reused, alreadyReady, failed);

        } catch (Exception e) {
            log.error("Storyboard image generation failed for task {}", taskId, e);
            task.setStatus("FAILED");
            task.setMessage("分镜图片生成失败: " + e.getMessage());
            task.setResult(buildTaskTraceResult("image", projectId, traceCalls, omittedTraceCalls,
                    Map.of("error", e.getMessage())));
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

    private List<String> collectReferenceImageUrls(Storyboard shot) {
        return collectReferenceImageUrls(shot, null);
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
        log.debug("Character image backfilled from storyboard shot: projectId={}, characterId={}, characterName={}, shotNo={}, imageUrl={}",
                character.getProjectId(), character.getId(), character.getName(), shot.getShotNo(), shot.getImageUrl());
    }

    private void addReferenceImageUrl(Set<String> referenceImageUrls, String imageUrl) {
        if (hasText(imageUrl)
                && (imageUrl.startsWith("http://") || imageUrl.startsWith("https://"))) {
            referenceImageUrls.add(imageUrl);
        }
    }

    /**
     * Pre-populate image cache from existing shots that already have images.
     */
    private void buildImageCache(Map<String, String> cache, List<Storyboard> shots) {
        for (Storyboard shot : shots) {
            if (shot.getImageUrl() != null && !shot.getImageUrl().isBlank()) {
                String key = buildImageCacheKey(shot);
                if (key != null) {
                    cache.put(key, shot.getImageUrl());
                }
            }
        }
        log.debug("Storyboard image cache initialized with {} entries", cache.size());
    }

    /**
     * Start generating TTS audio for all storyboard shots of a project.
     */
    public TaskRecord startGenerateStoryboardAudio(Long userId, Long projectId, java.util.List<Long> shotIds) {
        List<Storyboard> shots = listByProject(projectId);
        if (shotIds != null && !shotIds.isEmpty()) {
            shots = shots.stream().filter(s -> shotIds.contains(s.getId())).toList();
        }
        if (shots.isEmpty()) throw new BusinessException("项目下没有分镜数据，请先生成分镜");

        log.debug("Creating storyboard audio task: userId={}, projectId={}, shotCount={}, filteredByIds={}",
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
            List<VoiceInfo> availableVoices = safeListVoices(ttsProvider);
            int total = shots.size();
            int completed = 0;
            int alreadyReady = 0;
            int generated = 0;
            int skippedNoText = 0;
            int failed = 0;
            List<String> failedDetails = new ArrayList<>();

            log.debug("Start storyboard audio generation: taskId={}, userId={}, projectId={}, shotCount={}, availableVoices={}",
                    taskId, userId, projectId, total, availableVoices.size());

            Path audioDir = Paths.get(uploadPath, "audios");
            Files.createDirectories(audioDir);

            for (Storyboard shot : shots) {
                // Build text to synthesize: combine dialogue and narration
                String text = buildTtsText(shot);
                if (text.isBlank()) {
                    log.debug("Skip storyboard audio generation because text is blank: taskId={}, shotId={}, shotNo={}",
                            taskId, shot.getId(), shot.getShotNo());
                    skippedNoText++;
                    completed++;
                    continue;
                }

                if (shot.getAudioUrl() != null && !shot.getAudioUrl().isBlank()) {
                    log.debug("Skip storyboard audio generation because audio already exists: taskId={}, shotId={}, shotNo={}, audioUrl={}",
                            taskId, shot.getId(), shot.getShotNo(), shot.getAudioUrl());
                    alreadyReady++;
                    completed++;
                    continue;
                }

                updateTask(task, "RUNNING",
                        10 + (80 * completed / total),
                        String.format("正在生成第%d/%d个分镜配音...", completed + 1, total));

                try {
                    VoiceSelection voiceSelection = resolveVoiceSelection(userId, shot, availableVoices);
                    log.debug("Storyboard audio request prepared: taskId={}, shotId={}, shotNo={}, characterId={}, voiceId={}, voiceName={}, textLength={}, autoAssigned={}",
                            taskId,
                            shot.getId(),
                            shot.getShotNo(),
                            shot.getCharacterId(),
                            voiceSelection.voiceId(),
                            voiceSelection.voiceName(),
                            text.length(),
                            voiceSelection.autoAssigned());
                    byte[] audioData = ttsProvider.synthesize(text, voiceSelection.voiceId(), 1.0f, 1.0f);
                    if (audioData == null || audioData.length <= 100) {
                        throw new BusinessException("配音接口未返回有效音频数据");
                    }
                    String filename = UUID.randomUUID().toString().replace("-", "") + ".mp3";
                    Path audioFile = audioDir.resolve(filename);
                    Files.write(audioFile, audioData);

                    shot.setAudioUrl(baseUrl + "/audios/" + filename);
                    shot.setStatus("audio_generated");
                    storyboardMapper.updateById(shot);
                    generated++;
                    log.debug("Storyboard audio generated: taskId={}, shotId={}, shotNo={}, audioUrl={}, audioSize={}",
                            taskId, shot.getId(), shot.getShotNo(), shot.getAudioUrl(), audioData.length);
                } catch (Exception e) {
                    failed++;
                    shot.setStatus("audio_failed");
                    storyboardMapper.updateById(shot);
                    String shotLabel = shot.getShotNo() != null ? String.valueOf(shot.getShotNo()) : String.valueOf(shot.getId());
                    String errorMessage = hasText(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName();
                    failedDetails.add(String.format("镜头%s: %s", shotLabel, errorMessage));
                    log.warn("Failed to generate audio for shot {}: {}", shot.getShotNo(), e.getMessage());
                } finally {
                    omittedTraceCalls = appendTraceCalls(traceCalls, shot, AiTraceContext.drain(), omittedTraceCalls);
                }
                completed++;
            }

            task.setProgress(100);
            int readyCount = alreadyReady + generated;
            if (readyCount == 0 && failed > 0) {
                task.setStatus("FAILED");
                task.setMessage(String.format("分镜配音生成失败，所选%d个镜头均未生成成功。%s", total, buildFailureReasonSummary(failedDetails, 3)));
            } else {
                task.setStatus("SUCCESS");
                if (failed > 0 || skippedNoText > 0) {
                    task.setMessage(String.format("分镜配音处理完成：新增%d个，已存在%d个，无文本%d个，失败%d个。%s", generated, alreadyReady, skippedNoText, failed, buildFailureReasonSummary(failedDetails, 3)));
                } else {
                    task.setMessage(String.format("分镜配音生成完成，共处理%d个镜头", total));
                }
            }
            task.setResult(buildTaskTraceResult("tts", projectId, traceCalls, omittedTraceCalls,
                    Map.of(
                            "total", total,
                            "generated", generated,
                            "alreadyReady", alreadyReady,
                            "skippedNoText", skippedNoText,
                            "failed", failed)));
            taskRecordMapper.updateById(task);
                log.debug("Storyboard audio generation complete: taskId={}, projectId={}, total={}, generated={}, alreadyReady={}, skippedNoText={}, failed={}",
                    taskId, projectId, total, generated, alreadyReady, skippedNoText, failed);

        } catch (Exception e) {
            log.error("Storyboard audio generation failed for task {}", taskId, e);
            task.setStatus("FAILED");
            task.setMessage("分镜配音生成失败: " + e.getMessage());
            task.setResult(buildTaskTraceResult("tts", projectId, traceCalls, omittedTraceCalls,
                    Map.of("error", e.getMessage())));
            taskRecordMapper.updateById(task);
        }
    }

    private VoiceSelection resolveVoiceSelection(Long userId, Storyboard shot, List<VoiceInfo> availableVoices) {
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
                log.debug("Character voice name backfilled from provider voices: projectId={}, characterId={}, characterName={}, voiceId={}, voiceName={}",
                        character.getProjectId(), character.getId(), character.getName(), character.getVoiceId(), voiceName);
            }
            return new VoiceSelection(character, character.getVoiceId(), voiceName, false);
        }

        VoiceInfo selectedVoice = selectVoiceForCharacter(character, availableVoices, defaultVoiceId);
        String selectedVoiceId = selectedVoice != null && hasText(selectedVoice.getVoiceId())
                ? selectedVoice.getVoiceId()
                : defaultVoiceId;
        String selectedVoiceName = resolveVoiceName(selectedVoiceId, selectedVoice != null ? selectedVoice : defaultVoice);
        if (hasText(selectedVoiceId)) {
            character.setVoiceId(selectedVoiceId);
            character.setVoiceName(selectedVoiceName);
            characterMapper.updateById(character);
            log.debug("Character voice auto-assigned from TTS provider: projectId={}, characterId={}, characterName={}, voiceId={}, voiceName={}",
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
            log.warn("Failed to load provider voice list, fallback to default voice id only: {}", e.getMessage());
            return List.of();
        }
    }

    private VoiceInfo selectVoiceForCharacter(Character character, List<VoiceInfo> voices, String defaultVoiceId) {
        if (voices == null || voices.isEmpty()) {
            return null;
        }

        String preferredGender = normalizeVoiceGender(character != null ? character.getGender() : null);
        VoiceInfo preferredChinese = findFirstVoice(voices, preferredGender, true);
        if (preferredChinese != null) {
            return preferredChinese;
        }

        VoiceInfo preferredAnyLanguage = findFirstVoice(voices, preferredGender, false);
        if (preferredAnyLanguage != null) {
            return preferredAnyLanguage;
        }

        VoiceInfo defaultVoice = findVoiceInfo(defaultVoiceId, voices);
        if (defaultVoice != null) {
            return defaultVoice;
        }

        VoiceInfo chineseVoice = findFirstVoice(voices, null, true);
        return chineseVoice != null ? chineseVoice : voices.get(0);
    }

    private VoiceInfo findFirstVoice(List<VoiceInfo> voices, String preferredGender, boolean chineseOnly) {
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
            return voice;
        }
        return null;
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

    private String buildTtsText(Storyboard shot) {
        StringBuilder sb = new StringBuilder();
        if (shot.getNarration() != null && !shot.getNarration().isBlank()) {
            sb.append(shot.getNarration());
        }
        if (shot.getDialogue() != null && !shot.getDialogue().isBlank()) {
            if (!sb.isEmpty()) sb.append("。");
            sb.append(shot.getDialogue());
        }
        return sb.toString().trim();
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
        if (update.getVideoPrompt() != null) storyboard.setVideoPrompt(update.getVideoPrompt());
        if (update.getMotionLevel() != null) storyboard.setMotionLevel(normalizeMotionLevel(update.getMotionLevel()));
        if (update.getDynamicSelected() != null) {
            storyboard.setDynamicSelected(update.getDynamicSelected());
            storyboard.setRenderMode(Boolean.TRUE.equals(update.getDynamicSelected()) ? "video" : "image");
        }
        if (update.getRenderMode() != null) storyboard.setRenderMode(update.getRenderMode());
        storyboardMapper.updateById(storyboard);
        return storyboard;
    }

    private void updateTask(TaskRecord task, String status, int progress, String message) {
        log.debug("Task update: taskId={}, taskType={}, status={}, progress={}, message={}",
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
            root.put("storedCalls", calls.size());
            root.put("omittedCalls", omittedTraceCalls);
            root.set("summary", objectMapper.valueToTree(summary));
            root.set("calls", calls);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("Failed to serialize AI task trace result", e);
            return null;
        }
    }

    private String buildStoryboardSystemPrompt() {
        return """
                # 角色定位
                你是一位顶级短剧分镜导演，专精竖屏短剧（9:16）分镜脚本制作。
                你的分镜脚本对标红果短剧、抖音短剧保底S+评级标准，需要做到：节奏精准、视觉冲击力强、爽点镜头密集。
                
                # 分镜拆解规范
                请将剧本拆解为JSON格式的分镜列表，每个镜头包含以下字段：
                - shotNo: 镜头序号（从1开始）
                - description: 画面描述（详细到人物表情、肢体动作、环境光影、景深效果，用于AI精准生图）
                - cameraAngle: 镜头语言（close-up/medium/wide/overhead/pov/low-angle/high-angle/tracking）
                - dialogue: 角色台词（如有，标注说话角色名）
                - narration: 旁白（如有）
                - duration: 镜头时长（秒，2-8秒，爽点镜头≤3秒加速节奏）
                - characterName: 主要角色名（如有，用于角色一致性和图片复用）
                - sceneName: 场景名称（用于场景复用优化）
                - isDynamic: 是否为动态镜头（true=需要AI视频，false=静态图片即可）
                - dynamicReason: 推荐/不推荐动态的具体原因
                - imagePrompt: AI生图提示词（中文，需包含：主体描述+表情动作+环境光影+构图+风格关键词，竖版9:16）
                - videoPrompt: 动态镜头视频提示词（基于关键帧的动作+镜头运动描述）
                - motionLevel: 动态强度（low/medium/high）
                
                # 分镜优化要求（稳定拆镜）
                1. 按场景和对白稳定拆镜，不追求镜头数量堆叠，不得无意义乱切
                2. 同一场景优先连续镜头表达：通常每个场景拆 2-5 个镜头
                3. 全集建议 20-45 个镜头（可根据台词和动作适度增减）
                4. 开场第1-3个镜头要建立人物关系与核心冲突
                5. 对话场景优先 close-up 和 medium，动作场景再使用 wide/tracking
                6. 动态镜头仅用于明显运动或情绪爆发段落，不超过镜头总数 30%%
                7. imagePrompt 必须足够详细：包含人物外貌、服装、表情、动作、场景环境、光影氛围、画面风格
                8. videoPrompt 只描述基于关键帧的动作和镜头运动，不重复画面基础描述
                9. 如果镜头更适合静态图，isDynamic 必须为 false，并给出 dynamicReason
                10. 集末镜头组要形成明确悬念或情绪收束，服务下一集衔接
                
                # imagePrompt 模板
                每个 imagePrompt 应包含以下要素：
                "竖版9:16构图，[镜头类型]，[人物主体描述含外貌服装表情动作]，[场景环境描述]，[光影氛围]，电影级质感，高清4K，[风格关键词如：戏剧性光影/高饱和度/冷色调/暖色调]"
                
                返回格式：{"shots": [...]}
                """;
    }

    private String buildStoryboardUserPrompt(String scriptContent) {
        return String.format("""
                请将以下短剧剧本精准拆解为分镜脚本，以JSON格式返回。
                
                ## 拆解要求
                1. 严格按剧本场景顺序拆解，不遗漏任何场景和对白
                2. 每个场景拆为3-8个镜头（根据场景重要性调整）
                3. 爽点场景加密镜头（快切+特写），平叙场景精简镜头
                4. imagePrompt 需详细到可以直接用于AI生图，包含人物外貌、表情、场景、光影
                5. 保持角色外貌描述一致性：同一角色在不同镜头的外貌描述必须统一
                6. 这是保底级付费短剧分镜,红果短剧、抖音短剧标准，爽点密集，适合平台保底S+评级
                
                ## 剧本内容
                %s
                
                注意：画面描述需要足够详细，便于AI高质量生图。每个imagePrompt至少50字。
                """, scriptContent);
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
                String characterName = textOrNull(shotNode, "characterName");
                Character resolvedCharacter = resolveCharacterByName(request.getProjectId(), characterName);
                if (resolvedCharacter != null) {
                    shot.setCharacterId(resolvedCharacter.getId());
                    log.debug("Storyboard shot character resolved: projectId={}, scriptId={}, shotNo={}, characterName={}, characterId={}",
                            request.getProjectId(), request.getScriptId(), shot.getShotNo(), characterName, resolvedCharacter.getId());
                } else if (hasText(characterName)) {
                    log.debug("Storyboard shot character unresolved: projectId={}, scriptId={}, shotNo={}, characterName={}",
                            request.getProjectId(), request.getScriptId(), shot.getShotNo(), characterName);
                }
                shot.setImagePrompt(textOrNull(shotNode, "imagePrompt"));
                if (!hasText(shot.getImagePrompt())) {
                    shot.setImagePrompt(buildImagePrompt(shot, resolvedCharacter));
                }
                shot.setVideoPrompt(textOrNull(shotNode, "videoPrompt"));
                applyDynamicRecommendation(shot, shotNode);
                shots.add(shot);
            }
        } catch (Exception e) {
            if (strict) {
                throw new BusinessException("分镜预览解析失败，请检查 JSON 结构和字段名称");
            }
            log.warn("Failed to parse storyboard JSON, creating placeholder shots. Error: {}", e.getMessage());
            // Create a placeholder shot if parsing fails
            Storyboard placeholder = new Storyboard();
            placeholder.setProjectId(request.getProjectId());
            placeholder.setScriptId(request.getScriptId());
            placeholder.setEpisodeNo(1);
            placeholder.setShotNo(1);
            placeholder.setDescription("AI生成的分镜脚本（解析失败，请手动编辑）");
            placeholder.setDuration(5);
            placeholder.setImagePrompt(buildImagePrompt(placeholder));
            placeholder.setVideoPrompt(buildVideoPrompt(placeholder, "low"));
            placeholder.setMotionLevel("low");
            placeholder.setDynamicRecommended(false);
            placeholder.setDynamicSelected(false);
            placeholder.setDynamicScore(0);
            placeholder.setDynamicReason("当前镜头更适合保留为静态图片");
            placeholder.setRenderMode("image");
            placeholder.setStatus("draft");
            shots.add(placeholder);
        }
        return shots;
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

    private String buildImagePrompt(Storyboard shot) {
        return buildImagePrompt(shot, resolveShotCharacter(shot));
    }

    private String buildImagePrompt(Storyboard shot, Character character) {
        String cameraAngle = shot.getCameraAngle() != null ? shot.getCameraAngle() : "medium shot";
        String description = shot.getDescription() != null ? shot.getDescription() : "短剧场景画面";
        String characterAnchor = buildCharacterAnchor(character);
        return String.format(
                "竖版9:16构图，%s镜头，%s%s，电影级质感，高清4K，戏剧性光影，高饱和度色彩，短剧封面级画质，景深效果，专业摄影",
                cameraAngle,
                hasText(characterAnchor) ? characterAnchor + "，" : "",
                description);
    }

    private void applyDynamicRecommendation(Storyboard shot, JsonNode shotNode) {
        boolean aiDynamic = shotNode.path("isDynamic").asBoolean(false);
        String aiReason = textOrNull(shotNode, "dynamicReason");
        String motionLevel = normalizeMotionLevel(textOrNull(shotNode, "motionLevel"));
        int score = 0;
        LinkedHashSet<String> reasons = new LinkedHashSet<>();

        String description = lower(shot.getDescription());
        String dialogue = lower(shot.getDialogue());
        String narration = lower(shot.getNarration());
        String cameraAngle = lower(shot.getCameraAngle());

        if (aiDynamic) {
            score += 34;
            reasons.add(hasText(aiReason) ? aiReason : "AI判断该镜头存在明显动作或镜头运动");
        }

        if (containsKeyword(description, ACTION_KEYWORDS)
                || containsKeyword(dialogue, ACTION_KEYWORDS)
                || containsKeyword(narration, ACTION_KEYWORDS)) {
            score += 26;
            reasons.add("画面存在动作变化，适合加入动态表现");
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

        if (shot.getDuration() != null && shot.getDuration() >= 6) {
            score += 8;
        }

        if (hasText(dialogue) && !containsKeyword(description, ACTION_KEYWORDS) && !containsKeyword(narration, ACTION_KEYWORDS)) {
            score -= 14;
            reasons.add("该镜头更偏静态对白，保留图片即可");
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
        boolean recommended = score >= 55;

        if (!hasText(shot.getVideoPrompt())) {
            shot.setVideoPrompt(buildVideoPrompt(shot, motionLevel));
        }

        shot.setMotionLevel(resolveMotionLevel(motionLevel, score));
        shot.setDynamicRecommended(recommended);
        shot.setDynamicSelected(false);
        shot.setDynamicScore(score);
        shot.setDynamicReason(buildDynamicReason(reasons, recommended));
        shot.setRenderMode("image");
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

    private String buildDynamicReason(LinkedHashSet<String> reasons, boolean recommended) {
        if (reasons.isEmpty()) {
            return recommended ? "建议做轻动态处理以增强镜头表现" : "当前镜头更适合保留为静态图片";
        }

        List<String> topReasons = new ArrayList<>(reasons).subList(0, Math.min(2, reasons.size()));
        return String.join("；", topReasons);
    }

    private String buildVideoPrompt(Storyboard shot, String motionLevel) {
        String motionInstruction = switch (normalizeMotionLevel(motionLevel)) {
            case "high" -> "镜头快速推进，人物动作幅度大且连贯，情绪变化强烈，画面张力十足";
            case "medium" -> "镜头缓慢推进或平滑横移，人物有自然的肢体动作和表情变化，氛围渐进式增强";
            default -> "保持关键帧主体不变，仅做极轻微的镜头缓推和自然微动（呼吸感/发丝飘动/光影变化）";
        };

        String sceneContext = hasText(shot.getDescription()) ? shot.getDescription() : "保持剧情镜头连续性";
        return String.format(
                "基于该关键帧生成%ds竖屏9:16动态镜头。%s。画面主体保持一致，避免角色面部和场景漂移变形。场景：%s。确保动态自然流畅，符合短剧叙事节奏。",
                shot.getDuration() != null ? shot.getDuration() : 5,
                motionInstruction,
                sceneContext);
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

    private String buildImageGenerationPrompt(String originalPrompt, Character character, List<String> referenceImageUrls) {
        if (character == null) {
            return originalPrompt;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("角色设定锚点：主体必须是角色“")
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
        if (!anchors.isEmpty()) {
            builder.append("，").append(String.join("，", anchors));
        }
        builder.append("。人物年龄感、脸型、发型、体态必须保持稳定，脸部清晰可辨。");

        if (hasCharacterReferenceImage(character, referenceImageUrls)) {
            builder.append("已提供该角色参考图，必须严格保持与参考图为同一人物，不得改变年龄感、五官、发型和服装识别特征。");
        }

        builder.append("当前镜头要求：").append(originalPrompt);
        return builder.toString();
    }

    private boolean hasCharacterReferenceImage(Character character, List<String> referenceImageUrls) {
        return character != null
                && hasText(character.getImageUrl())
                && referenceImageUrls != null
                && referenceImageUrls.contains(character.getImageUrl());
    }

    private String buildImageNegativePrompt(Character character) {
        LinkedHashSet<String> negativeTerms = new LinkedHashSet<>();
        for (String term : DEFAULT_IMAGE_NEGATIVE_PROMPT.split("，")) {
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
        return String.join("，", negativeTerms);
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

    private static final String[] TRANSITION_KEYWORDS = {
            "转场", "切换", "空镜", "远景", "夜景", "天台", "街道", "车流", "门外", "入场", "登场", "离开"
    };
}

