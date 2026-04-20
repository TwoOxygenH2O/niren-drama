package com.niren.drama.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.niren.drama.ai.AiProviderFactory;
import com.niren.drama.ai.ImageAiProvider;
import com.niren.drama.ai.TextAiProvider;
import com.niren.drama.ai.TtsProvider;
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
    private final CostEstimationService costEstimationService;
    private final ObjectMapper objectMapper;

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
        generateStoryboardImagesAsync(userId, projectId, shots, task.getId());
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
        generateStoryboardAudioAsync(userId, projectId, shots, task.getId());
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
                你是一位专业的分镜导演，擅长将剧本拆解为精确的分镜脚本。
                请将剧本拆解为JSON格式的分镜列表，每个镜头包含以下字段：
                - shotNo: 镜头序号（从1开始）
                - description: 画面描述（用于AI生图的详细描述）
                - cameraAngle: 镜头语言（close-up/medium/wide/overhead/pov）
                - dialogue: 角色台词（如有）
                - narration: 旁白（如有）
                - duration: 镜头时长（秒，3-8秒）
                - characterName: 主要角色名（如有，用于图片复用优化）
                - sceneName: 场景名称（用于图片复用优化）
                - isDynamic: 是否为动态镜头（true=需要AI视频生成，false=静态图片即可）
                - dynamicReason: 推荐该镜头做动态的原因
                - imagePrompt: 关键帧生图提示词
                - videoPrompt: 基于关键帧生成动态镜头时的动作提示词
                - motionLevel: 动态强度（low/medium/high）
                
                分镜优化要求：
                1. 每集应包含80-100个镜头，总时长约8分钟（480秒）
                2. 尽量复用场景：同一场景中的连续对话镜头使用相同sceneName
                3. 标记动态镜头：只有需要明显运动的镜头（如追逐、打斗、转场）标记isDynamic为true
                4. 静态对话镜头占比应≥60%，以控制AI视频生成成本
                5. 对话场景优先使用close-up和medium镜头
                6. imagePrompt 负责描述关键帧画面，videoPrompt 只描述动作、镜头运动和情绪变化
                7. 如果镜头更适合保留静态图，isDynamic 应明确返回 false，dynamicReason 说明不建议动态化的原因
                
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
                shot.setImagePrompt(textOrNull(shotNode, "imagePrompt"));
                if (!hasText(shot.getImagePrompt())) {
                    shot.setImagePrompt(buildImagePrompt(shot));
                }
                shot.setVideoPrompt(textOrNull(shotNode, "videoPrompt"));
                applyDynamicRecommendation(shot, shotNode);
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

    private String buildImagePrompt(Storyboard shot) {
        return String.format("垂直构图9:16，%s，镜头：%s，%s风格，电影质感，高清4K",
                shot.getDescription(),
                shot.getCameraAngle() != null ? shot.getCameraAngle() : "medium shot",
                "现代都市");
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
            case "high" -> "镜头推进明显，人物动作幅度更大，情绪变化清晰";
            case "medium" -> "镜头缓慢推进或轻微平移，突出人物动作与气氛变化";
            default -> "保持关键帧主体不变，仅做轻微镜头推进和自然动作";
        };

        return String.format("基于该关键帧生成%ss的竖屏动态镜头，%s。画面主体保持一致，避免角色和场景漂移。%s",
                shot.getDuration() != null ? shot.getDuration() : 5,
                motionInstruction,
                hasText(shot.getDescription()) ? shot.getDescription() : "保持剧情镜头连续性");
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
