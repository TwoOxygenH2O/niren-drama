package com.niren.drama.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.niren.drama.ai.AiProviderFactory;
import com.niren.drama.ai.ImageAiProvider;
import com.niren.drama.ai.TextAiProvider;
import com.niren.drama.ai.TtsProvider;
import com.niren.drama.dto.storyboard.StoryboardGenerateRequest;
import com.niren.drama.dto.storyboard.StoryboardPreviewSaveRequest;
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
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class StoryboardService {

    private final StoryboardMapper storyboardMapper;
    private final ScriptMapper scriptMapper;
    private final TaskRecordMapper taskRecordMapper;
    private final AiProviderFactory aiProviderFactory;
    private final ProjectService projectService;
    private final CostEstimationService costEstimationService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<StoryboardService> selfProvider;

    /** Portrait image size for vertical (9:16) storyboard images */
    private static final String PORTRAIT_IMAGE_SIZE = "1024x1792";
    /** Image generation style */
    private static final String PORTRAIT_IMAGE_STYLE = "vivid";

    /** Enable image reuse for same scene+character+angle combinations */
    @Value("${niren.cost.image-reuse-enabled:true}")
    private boolean imageReuseEnabled;

    @Value("${niren.upload.path:./uploads}")
    private String uploadPath;

    @Value("${niren.upload.base-url:http://localhost:8080/api/files}")
    private String baseUrl;

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
        selfProvider.getObject().generateStoryboardAsync(userId, request, task.getId());
        return task;
    }

    public void streamGenerateStoryboard(Long userId, StoryboardGenerateRequest request, java.util.function.Consumer<String> chunkConsumer) {
        projectService.getProject(userId, request.getProjectId());
        Script script = requireScript(request.getScriptId(), request.getProjectId());
        TextAiProvider textProvider = aiProviderFactory.getTextProvider(userId);
        String systemPrompt = buildStoryboardSystemPrompt();
        String userPrompt = buildStoryboardUserPrompt(script.getContent());
        textProvider.streamChat(systemPrompt, userPrompt, chunkConsumer);
    }

    public List<Storyboard> saveStoryboardPreview(Long userId, StoryboardPreviewSaveRequest request) {
        projectService.getProject(userId, request.getProjectId());
        Script script = requireScript(request.getScriptId(), request.getProjectId());

        StoryboardGenerateRequest generateRequest = new StoryboardGenerateRequest();
        generateRequest.setProjectId(request.getProjectId());
        generateRequest.setScriptId(request.getScriptId());

        List<Storyboard> shots = parseStoryboardJson(request.getContent(), generateRequest, true);
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
            updateTask(task, "RUNNING", 10, "读取剧本内容...");
            Script script = requireScript(request.getScriptId(), request.getProjectId());

            updateTask(task, "RUNNING", 30, "AI正在拆解分镜脚本...");
            TextAiProvider textProvider = aiProviderFactory.getTextProvider(userId);
            String systemPrompt = buildStoryboardSystemPrompt();
            String userPrompt = buildStoryboardUserPrompt(script.getContent());
            String storyboardJson = textProvider.chat(systemPrompt, userPrompt);

            updateTask(task, "RUNNING", 70, "保存分镜数据...");
            List<Storyboard> shots = parseStoryboardJson(storyboardJson, request, false);
            for (Storyboard shot : shots) {
                shot.setEpisodeNo(script.getEpisodeNo() != null ? script.getEpisodeNo() : 1);
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

    /**
     * Start generating images for all storyboard shots of a project.
     */
    public TaskRecord startGenerateStoryboardImages(Long userId, Long projectId) {
        List<Storyboard> shots = listByProject(projectId);
        if (shots.isEmpty()) throw new BusinessException("项目下没有分镜数据，请先生成分镜");

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
        try {
            ImageAiProvider imageProvider = aiProviderFactory.getImageProvider(userId);
            int total = shots.size();
            int completed = 0;
            int reused = 0;

            // Build image reuse cache: scene+character+angle → imageUrl
            Map<String, String> imageCache = new HashMap<>();
            if (imageReuseEnabled) {
                buildImageCache(imageCache, shots);
            }

            for (Storyboard shot : shots) {
                if (shot.getImageUrl() != null && !shot.getImageUrl().isBlank()) {
                    completed++;
                    continue;
                }

                String prompt = shot.getImagePrompt();
                if (prompt == null || prompt.isBlank()) {
                    prompt = buildImagePrompt(shot);
                }

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
                        log.info("Reused cached image for shot {} (cacheKey={})", shot.getShotNo(), cacheKey);
                    } else {
                        // Use smart resolution based on camera angle
                        String imageSize = costEstimationService.getOptimalImageSize(shot.getCameraAngle());
                        String imageUrl = imageProvider.generateImage(prompt, imageSize, PORTRAIT_IMAGE_STYLE);
                        shot.setImageUrl(imageUrl);
                        shot.setStatus("image_generated");
                        storyboardMapper.updateById(shot);

                        // Cache this image for reuse
                        if (imageReuseEnabled && cacheKey != null) {
                            imageCache.put(cacheKey, imageUrl);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to generate image for shot {}: {}", shot.getShotNo(), e.getMessage());
                }
                completed++;
            }

            task.setStatus("SUCCESS");
            task.setProgress(100);
            task.setMessage(String.format("分镜图片生成完成，共处理%d个镜头，复用%d张图片", total, reused));
            taskRecordMapper.updateById(task);

        } catch (Exception e) {
            log.error("Storyboard image generation failed for task {}", taskId, e);
            task.setStatus("FAILED");
            task.setMessage("分镜图片生成失败: " + e.getMessage());
            taskRecordMapper.updateById(task);
        }
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
        log.info("Image cache initialized with {} entries", cache.size());
    }

    /**
     * Start generating TTS audio for all storyboard shots of a project.
     */
    public TaskRecord startGenerateStoryboardAudio(Long userId, Long projectId) {
        List<Storyboard> shots = listByProject(projectId);
        if (shots.isEmpty()) throw new BusinessException("项目下没有分镜数据，请先生成分镜");

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
        try {
            TtsProvider ttsProvider = aiProviderFactory.getTtsProvider(userId);
            int total = shots.size();
            int completed = 0;

            Path audioDir = Paths.get(uploadPath, "audios");
            Files.createDirectories(audioDir);

            for (Storyboard shot : shots) {
                // Build text to synthesize: combine dialogue and narration
                String text = buildTtsText(shot);
                if (text.isBlank()) {
                    completed++;
                    continue;
                }

                if (shot.getAudioUrl() != null && !shot.getAudioUrl().isBlank()) {
                    completed++;
                    continue;
                }

                updateTask(task, "RUNNING",
                        10 + (80 * completed / total),
                        String.format("正在生成第%d/%d个分镜配音...", completed + 1, total));

                try {
                    byte[] audioData = ttsProvider.synthesize(text, "alloy", 1.0f, 1.0f);
                    if (audioData != null && audioData.length > 100) {
                        String filename = UUID.randomUUID().toString().replace("-", "") + ".mp3";
                        Path audioFile = audioDir.resolve(filename);
                        Files.write(audioFile, audioData);

                        shot.setAudioUrl(baseUrl + "/audios/" + filename);
                        shot.setStatus("audio_generated");
                        storyboardMapper.updateById(shot);
                    }
                } catch (Exception e) {
                    log.warn("Failed to generate audio for shot {}: {}", shot.getShotNo(), e.getMessage());
                }
                completed++;
            }

            task.setStatus("SUCCESS");
            task.setProgress(100);
            task.setMessage(String.format("分镜配音生成完成，共处理%d个镜头", total));
            taskRecordMapper.updateById(task);

        } catch (Exception e) {
            log.error("Storyboard audio generation failed for task {}", taskId, e);
            task.setStatus("FAILED");
            task.setMessage("分镜配音生成失败: " + e.getMessage());
            taskRecordMapper.updateById(task);
        }
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
        task.setStatus(status);
        task.setProgress(progress);
        task.setMessage(message);
        taskRecordMapper.updateById(task);
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
                shot.setImagePrompt(textOrNull(shotNode, "imagePrompt"));
                if (!hasText(shot.getImagePrompt())) {
                    shot.setImagePrompt(buildImagePrompt(shot));
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
        String cameraAngle = shot.getCameraAngle() != null ? shot.getCameraAngle() : "medium shot";
        String description = shot.getDescription() != null ? shot.getDescription() : "短剧场景画面";
        return String.format(
                "竖版9:16构图，%s镜头，%s，电影级质感，高清4K，戏剧性光影，高饱和度色彩，短剧封面级画质，景深效果，专业摄影",
                cameraAngle, description);
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

    private static final String[] ACTION_KEYWORDS = {
            "跑", "冲", "追", "打", "拥抱", "转身", "回头", "推门", "拉开", "坠", "摔", "扑", "走向",
            "奔", "跳", "挥手", "起身", "镜头推进", "镜头拉远", "移动", "摇镜", "风吹", "雨", "火", "爆炸"
    };

    private static final String[] TRANSITION_KEYWORDS = {
            "转场", "切换", "空镜", "远景", "夜景", "天台", "街道", "车流", "门外", "入场", "登场", "离开"
    };
}
