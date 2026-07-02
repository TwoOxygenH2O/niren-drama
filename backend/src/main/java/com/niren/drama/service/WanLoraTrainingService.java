package com.niren.drama.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.niren.drama.entity.AiConfig;
import com.niren.drama.entity.TaskRecord;
import com.niren.drama.exception.BusinessException;
import com.niren.drama.mapper.AiConfigMapper;
import com.niren.drama.mapper.TaskRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WanLoraTrainingService {

    private static final DateTimeFormatter RUN_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int MAX_UPLOAD_FILES = 80;
    private static final long MAX_SINGLE_FILE_BYTES = 500L * 1024L * 1024L;

    private final AiConfigMapper aiConfigMapper;
    private final TaskRecordMapper taskRecordMapper;
    private final WanLoraTrainingRunner trainingRunner;
    private final ObjectMapper objectMapper;

    @Value("${niren.training.wan22.root-dir:./wan22-video-training}")
    private String trainingRoot;

    public TaskRecord submit(Long userId,
                             Long configId,
                             List<MultipartFile> files,
                             String caption,
                             Boolean licenseConfirmed,
                             String runName,
                             Integer loraRank,
                             Integer epochs,
                             Boolean lowVram) throws IOException {
        return submit(userId, configId, files, caption, licenseConfirmed, runName, loraRank, epochs, lowVram, null);
    }

    public TaskRecord submit(Long userId,
                             Long configId,
                             List<MultipartFile> files,
                             String caption,
                             Boolean licenseConfirmed,
                             String runName,
                             Integer loraRank,
                             Integer epochs,
                             Boolean lowVram,
                             String samplePromptsJson) throws IOException {
        AiConfig config = getOwnedConfig(userId, configId);
        validateWanComfyUiConfig(config);
        if (!Boolean.TRUE.equals(licenseConfirmed)) {
            throw new BusinessException("请先确认上传素材已获得训练授权");
        }
        List<MultipartFile> cleanFiles = normalizeFiles(files);
        if (cleanFiles.isEmpty()) {
            throw new BusinessException("请上传至少一个视频素材");
        }

        String workflowFile = resolveWorkflowFile(config);
        String runId = buildRunId(runName);
        Path runDir = resolveTrainingRoot()
                .resolve("runs")
                .resolve("ui")
                .resolve(runId)
                .normalize();
        Path rawVideoDir = runDir.resolve("raw").resolve("videos");
        Files.createDirectories(rawVideoDir);

        List<TrainingSample> samples = new ArrayList<>();
        Map<String, SamplePrompt> samplePrompts = parseSamplePrompts(samplePromptsJson);
        String fallbackPrompt = safeCaption(caption);
        for (MultipartFile file : cleanFiles) {
            if (!isVideoFile(file)) {
                continue;
            }
            validateUpload(file);
            String safeName = buildSafeFilename(file.getOriginalFilename(), samples.size() + 1, "mp4");
            Path target = rawVideoDir.resolve(safeName).normalize();
            if (!target.startsWith(rawVideoDir)) {
                throw new BusinessException("素材文件名不合法");
            }
            file.transferTo(target.toFile());
            SamplePrompt samplePrompt = resolveSamplePrompt(samplePrompts, file.getOriginalFilename(), safeName, fallbackPrompt);
            samples.add(new TrainingSample(
                    safeName,
                    file.getOriginalFilename(),
                    target,
                    contentType(file),
                    file.getSize(),
                    samplePrompt.prompt(),
                    samplePrompt.negativePrompt()));
        }

        if (samples.isEmpty()) {
            throw new BusinessException("Wan2.2 I2V LoRA 训练需要上传视频素材，系统会自动抽取首帧");
        }

        TaskRecord task = new TaskRecord();
        task.setUserId(userId);
        task.setRefId(configId);
        task.setTaskType("WAN22_LORA_TRAIN");
        task.setStatus("PENDING");
        task.setProgress(0);
        task.setMessage("Wan2.2 LoRA 训练任务已提交，正在准备素材...");
        taskRecordMapper.insert(task);

        TrainingContext context = new TrainingContext(
                task.getId(),
                userId,
                configId,
                runId,
                runDir,
                workflowFile,
                config.getModel(),
                fallbackPrompt,
                safeRank(loraRank),
                safeEpochs(epochs),
                Boolean.TRUE.equals(lowVram),
                samples);
        trainingRunner.startTraining(context);
        return task;
    }

    void setTrainingRootForTest(Path trainingRoot) {
        this.trainingRoot = trainingRoot.toString();
    }

    public TrainingPromptPack buildPromptPack(Long userId,
                                              Long configId,
                                              String theme,
                                              String genre,
                                              Integer count) {
        AiConfig config = getOwnedConfig(userId, configId);
        validateWanComfyUiConfig(config);
        String safeTheme = hasText(theme) ? theme.trim() : "女频 复仇 古代";
        String safeGenre = hasText(genre) ? genre.trim() : "追妻火葬场";
        int safeCount = Math.max(4, Math.min(count == null ? 12 : count, 60));
        List<String> motionBeats = List.of(
                "the heroine turns back under palace lanterns, sleeves moving with a real body turn",
                "the heroine walks through a rain-soaked corridor, hair and robe moving naturally",
                "the heroine kneels, steadies her breath, then rises with controlled anger",
                "the male lead reaches out and misses her sleeve as she steps away",
                "the heroine opens a carved wooden door and enters a cold ancestral hall",
                "two characters face each other across a courtyard, subtle blocking and eye movement",
                "the heroine draws a hairpin and hides evidence in her sleeve",
                "the male lead backs away in shock while servants react in the background",
                "the heroine lifts a letter toward camera, fingers trembling but face calm",
                "the camera tracks beside the heroine as she crosses the palace bridge"
        );
        List<TrainingPromptItem> items = new ArrayList<>();
        for (int i = 0; i < safeCount; i++) {
            String filename = "external_wan22_" + String.format(Locale.ROOT, "%03d", i + 1) + ".mp4";
            String motion = motionBeats.get(i % motionBeats.size());
            String prompt = "Commercial vertical short-drama I2V training sample for " + safeTheme + " / " + safeGenre + ". "
                    + "The reference image is the exact first frame; preserve the same actor identity, face, hairstyle, costume, props, lighting, camera angle, and scene layout. "
                    + "Action beat: " + motion + ". "
                    + "Use one continuous live-action shot with visible acting progression, body movement, cloth or hair response, foreground/background parallax, and no scene cut. "
                    + "Ancient Chinese drama atmosphere, readable emotion, novel-narration mood, cinematic but natural.";
            String negative = "no slideshow, no frozen frame, no static image pan, no gif-like zoom, no camera cut, no new person, no identity drift, no wardrobe change, no face morphing, no subtitles, no logo, no watermark";
            items.add(new TrainingPromptItem(i + 1, filename, prompt, negative, motion));
        }
        return new TrainingPromptPack(safeTheme, safeGenre, items, toSamplePromptsJson(items));
    }

    private AiConfig getOwnedConfig(Long userId, Long configId) {
        AiConfig config = aiConfigMapper.selectOne(new LambdaQueryWrapper<AiConfig>()
                .eq(AiConfig::getId, configId)
                .eq(AiConfig::getUserId, userId)
                .last("LIMIT 1"));
        if (config == null) {
            throw new BusinessException("AI 配置不存在或无权操作");
        }
        return config;
    }

    private void validateWanComfyUiConfig(AiConfig config) {
        if (!"video".equalsIgnoreCase(config.getConfigType())) {
            throw new BusinessException("只能训练视频模型配置");
        }
        if (!"comfyui".equalsIgnoreCase(config.getProvider())) {
            throw new BusinessException("只有 ComfyUI 视频工作流支持训练 Wan2.2 LoRA");
        }
        String haystack = ((config.getModel() == null ? "" : config.getModel()) + " " + resolveWorkflowFile(config))
                .toLowerCase(Locale.ROOT);
        if (!haystack.contains("wan2.2") && !haystack.contains("wan2_2")) {
            throw new BusinessException("当前训练入口仅支持 Wan2.2 ComfyUI 视频工作流");
        }
    }

    private String resolveWorkflowFile(AiConfig config) {
        if (config.getExtra() != null && !config.getExtra().isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(config.getExtra());
                String workflowFile = root.path("workflowFile").asText("");
                if (!workflowFile.isBlank()) {
                    return workflowFile.trim();
                }
            } catch (Exception ignored) {
                // Extra can be hand-written JSON; fall back to model inference.
            }
        }
        return "video_wan2_2_14B_i2v.json";
    }

    private List<MultipartFile> normalizeFiles(List<MultipartFile> files) {
        if (files == null) {
            return List.of();
        }
        return files.stream()
                .filter(file -> file != null && !file.isEmpty())
                .limit(MAX_UPLOAD_FILES)
                .toList();
    }

    private void validateUpload(MultipartFile file) {
        if (file.getSize() > MAX_SINGLE_FILE_BYTES) {
            throw new BusinessException("单个训练素材不能超过 500MB");
        }
    }

    private boolean isVideoFile(MultipartFile file) {
        String contentType = contentType(file).toLowerCase(Locale.ROOT);
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        return contentType.startsWith("video/")
                || name.endsWith(".mp4")
                || name.endsWith(".mov")
                || name.endsWith(".webm")
                || name.endsWith(".mkv");
    }

    private String contentType(MultipartFile file) {
        return file.getContentType() == null ? "application/octet-stream" : file.getContentType();
    }

    private String buildRunId(String runName) {
        String prefix = sanitizeName(runName);
        if (prefix.isBlank()) {
            prefix = "wan22-lora";
        }
        return prefix + "-" + RUN_TS.format(LocalDateTime.now()) + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String buildSafeFilename(String originalName, int index, String fallbackExt) {
        String name = sanitizeName(originalName);
        if (name.isBlank()) {
            name = "sample-" + String.format("%03d", index);
        }
        if (!name.contains(".")) {
            name = name + "." + fallbackExt;
        }
        return String.format("%03d-%s", index, name);
    }

    private String sanitizeName(String value) {
        if (value == null) {
            return "";
        }
        String sanitized = value.trim()
                .replace('\\', '-')
                .replace('/', '-')
                .replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]+", "-")
                .replaceAll("-{2,}", "-");
        while (sanitized.startsWith("-") || sanitized.startsWith(".")) {
            sanitized = sanitized.substring(1);
        }
        while (sanitized.endsWith("-") || sanitized.endsWith(".")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1);
        }
        return sanitized;
    }

    private String safeCaption(String caption) {
        if (caption == null || caption.isBlank()) {
            return "Commercial vertical short-drama shot, keep actor identity, outfit, scene layout and lighting stable in one continuous shot.";
        }
        return caption.trim();
    }

    private Map<String, SamplePrompt> parseSamplePrompts(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            Map<String, SamplePrompt> prompts = new java.util.LinkedHashMap<>();
            JsonNode array = root.isArray() ? root : root.path("samples");
            if (array.isArray()) {
                for (JsonNode item : array) {
                    addSamplePrompt(prompts, item);
                }
                return prompts;
            }
            if (root.isObject()) {
                for (var it = root.fields(); it.hasNext(); ) {
                    var entry = it.next();
                    JsonNode value = entry.getValue();
                    if (value.isTextual()) {
                        prompts.put(promptKey(entry.getKey()), new SamplePrompt(value.asText(), defaultNegativePrompt()));
                    } else if (value.isObject()) {
                        String prompt = firstText(value, "prompt", "caption", "positivePrompt");
                        String negative = firstText(value, "negativePrompt", "negative", "negative_prompt");
                        if (hasText(prompt)) {
                            prompts.put(promptKey(entry.getKey()), new SamplePrompt(prompt.trim(), safeNegativePrompt(negative)));
                        }
                    }
                }
            }
            return prompts;
        } catch (Exception e) {
            throw new BusinessException("samplePromptsJson 格式不正确，请上传文件名与 prompt 的 JSON 对应关系");
        }
    }

    private void addSamplePrompt(Map<String, SamplePrompt> prompts, JsonNode item) {
        if (item == null || !item.isObject()) {
            return;
        }
        String filename = firstText(item, "filename", "fileName", "originalFilename", "name", "sampleId");
        String prompt = firstText(item, "prompt", "caption", "positivePrompt");
        String negative = firstText(item, "negativePrompt", "negative", "negative_prompt");
        if (hasText(filename) && hasText(prompt)) {
            prompts.put(promptKey(filename), new SamplePrompt(prompt.trim(), safeNegativePrompt(negative)));
        }
    }

    private SamplePrompt resolveSamplePrompt(Map<String, SamplePrompt> samplePrompts,
                                             String originalFilename,
                                             String safeName,
                                             String fallbackPrompt) {
        SamplePrompt prompt = samplePrompts.get(promptKey(originalFilename));
        if (prompt == null) {
            prompt = samplePrompts.get(promptKey(safeName));
        }
        if (prompt == null) {
            return new SamplePrompt(fallbackPrompt, defaultNegativePrompt());
        }
        return new SamplePrompt(
                hasText(prompt.prompt()) ? prompt.prompt().trim() : fallbackPrompt,
                safeNegativePrompt(prompt.negativePrompt()));
    }

    private String firstText(JsonNode node, String... names) {
        if (node == null || names == null) {
            return null;
        }
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isTextual() && hasText(value.asText())) {
                return value.asText();
            }
        }
        return null;
    }

    private String safeNegativePrompt(String negativePrompt) {
        return hasText(negativePrompt) ? negativePrompt.trim() : defaultNegativePrompt();
    }

    private String toSamplePromptsJson(List<TrainingPromptItem> items) {
        try {
            List<Map<String, String>> payload = items.stream()
                    .map(item -> Map.of(
                            "filename", item.filename(),
                            "prompt", item.prompt(),
                            "negativePrompt", item.negativePrompt()))
                    .toList();
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String defaultNegativePrompt() {
        return "no cuts, no new people, no wardrobe change, no scene jump, no face morphing, no slideshow";
    }

    private String promptKey(String filename) {
        if (filename == null) {
            return "";
        }
        String normalized = filename.trim().replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private int safeRank(Integer rank) {
        if (rank == null) {
            return 8;
        }
        return Math.max(4, Math.min(rank, 64));
    }

    private int safeEpochs(Integer epochs) {
        if (epochs == null) {
            return 1;
        }
        return Math.max(1, Math.min(epochs, 50));
    }

    private Path resolveTrainingRoot() {
        Path configured = Paths.get(trainingRoot);
        if (configured.isAbsolute()) {
            return configured.toAbsolutePath().normalize();
        }
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        Path projectRoot = findProjectRoot(cwd);
        if (projectRoot != null) {
            return projectRoot.resolve(configured).normalize();
        }
        return cwd.resolve(configured).normalize();
    }

    private Path findProjectRoot(Path cwd) {
        Path current = cwd;
        while (current != null) {
            if (Files.exists(current.resolve("AGENTS.md"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    public record TrainingContext(Long taskId,
                                  Long userId,
                                  Long configId,
                                  String runId,
                                  Path runDir,
                                  String workflowFile,
                                  String baseModel,
                                  String caption,
                                  int loraRank,
                                  int epochs,
                                  boolean lowVram,
                                  List<TrainingSample> samples) {
    }

    public record TrainingSample(String sampleId,
                                 String originalFilename,
                                 Path videoPath,
                                 String contentType,
                                 long fileSize,
                                 String prompt,
                                 String negativePrompt) {
    }

    public record SamplePrompt(String prompt,
                               String negativePrompt) {
    }

    public record TrainingPromptPack(String theme,
                                     String genre,
                                     List<TrainingPromptItem> items,
                                     String samplePromptsJson) {
    }

    public record TrainingPromptItem(int index,
                                     String filename,
                                     String prompt,
                                     String negativePrompt,
                                     String motionFocus) {
    }
}
