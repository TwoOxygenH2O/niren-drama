package com.niren.drama.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.niren.drama.entity.AiConfig;
import com.niren.drama.entity.AssetSnapshot;
import com.niren.drama.entity.ConsistencyBible;
import com.niren.drama.entity.ProductionIssue;
import com.niren.drama.entity.Project;
import com.niren.drama.entity.Scene;
import com.niren.drama.entity.Script;
import com.niren.drama.entity.Storyboard;
import com.niren.drama.entity.TaskRecord;
import com.niren.drama.exception.BusinessException;
import com.niren.drama.mapper.AssetSnapshotMapper;
import com.niren.drama.mapper.CharacterMapper;
import com.niren.drama.mapper.ConsistencyBibleMapper;
import com.niren.drama.mapper.ProductionIssueMapper;
import com.niren.drama.mapper.SceneMapper;
import com.niren.drama.mapper.ScriptMapper;
import com.niren.drama.mapper.StoryboardMapper;
import com.niren.drama.mapper.TaskRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductionWorkspaceService {

    private static final Set<String> ACTIVE_TASK_STATUS = Set.of("PENDING", "RUNNING");
    private static final List<String> QUALITY_ISSUE_TYPES = List.of(
            "missing_media",
            "missing_video",
            "wrong_aspect_ratio",
            "duration_out_of_range",
            "black_frame",
            "frozen_frame",
            "low_visual_detail",
            "unwatchable_visual",
            "weak_motion",
            "animated_still",
            "motion_smear",
            "washed_gray_video",
            "low_effective_fps",
            "first_frame_drift_risk",
            "identity_drift",
            "wardrobe_inconsistent",
            "face_broken",
            "action_mismatch",
            "unpublishable_frame",
            "storyboard_mismatch",
            "reference_mismatch",
            "probe_failed"
    );

    private final ProjectService projectService;
    private final StoryboardService storyboardService;
    private final VideoCompositionService videoCompositionService;
    private final AiConfigService aiConfigService;
    private final StoryboardMapper storyboardMapper;
    private final ScriptMapper scriptMapper;
    private final TaskRecordMapper taskRecordMapper;
    private final AssetSnapshotMapper assetSnapshotMapper;
    private final ProductionIssueMapper productionIssueMapper;
    private final ConsistencyBibleMapper consistencyBibleMapper;
    private final CharacterMapper characterMapper;
    private final SceneMapper sceneMapper;
    private final ObjectMapper objectMapper;
    private final VisualQualityAnalyzer visualQualityAnalyzer;
    private final VisualReviewService visualReviewService;

    @Value("${niren.upload.path:./uploads}")
    private String uploadPath;

    @Value("${niren.upload.base-url:http://localhost:8080/api/files}")
    private String uploadBaseUrl;

    @Value("${niren.ffmpeg.path:ffmpeg}")
    private String ffmpegPath;

    @Value("${niren.ffmpeg.ffprobe-path:}")
    private String ffprobePath;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public Map<String, Object> getWorkspace(Long userId, Long projectId) {
        Project project = projectService.getProject(userId, projectId);
        List<Script> scripts = listScripts(projectId);
        List<Storyboard> shots = storyboardService.listByProject(projectId);
        List<TaskRecord> activeTasks = listActiveTasks(projectId);
        List<ProductionIssue> openIssues = listOpenIssues(projectId);
        List<Map<String, Object>> derivedIssues = deriveWorkspaceIssues(shots, activeTasks);
        TaskRecord latestComposeTask = videoCompositionService.getLatestVideoTask(projectId);
        String finalVideoUrl = latestComposeTask != null && "SUCCESS".equals(latestComposeTask.getStatus())
                ? videoCompositionService.extractVideoUrl(latestComposeTask.getResult())
                : "";

        Map<Long, List<Map<String, Object>>> snapshotMap = groupSnapshots(projectId, shots);
        Map<Long, List<Map<String, Object>>> issueMap = groupIssues(openIssues, derivedIssues);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("project", projectSummary(project));
        data.put("mode", inferProductionMode(userId));
        data.put("completion", buildCompletion(project, scripts, shots, finalVideoUrl, openIssues, derivedIssues));
        data.put("nextActions", buildNextActions(scripts, shots, finalVideoUrl, openIssues, derivedIssues));
        data.put("activeTasks", activeTasks.stream().map(this::taskSummary).toList());
        data.put("issues", combineIssues(openIssues, derivedIssues));
        data.put("health", buildHealth(userId));
        data.put("shots", shots.stream()
                .sorted(Comparator.comparing(Storyboard::getEpisodeNo, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(Storyboard::getShotNo, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(Storyboard::getId, Comparator.nullsLast(Long::compareTo)))
                .map(shot -> shotSummary(shot, snapshotMap.getOrDefault(shot.getId(), List.of()), issueMap.getOrDefault(shot.getId(), List.of())))
                .toList());
        data.put("lineageEnabled", true);
        data.put("consistency", buildConsistency(projectId, project));
        data.put("exportProfiles", exportProfiles());
        data.put("finalVideoUrl", finalVideoUrl == null ? "" : finalVideoUrl);
        data.put("generatedAt", LocalDateTime.now().toString());
        return data;
    }

    public Map<String, Object> repair(Long userId, Long projectId, Map<String, Object> body) {
        projectService.getProject(userId, projectId);
        String action = text(body.get("action"));
        List<Long> shotIds = longList(body.get("shotIds"));
        TaskRecord task = null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", action);

        switch (action) {
            case "generateImages", "regenerateFirstFrame" -> task = storyboardService.startGenerateStoryboardImages(userId, projectId, emptyToNull(shotIds));
            case "generateAudio" -> task = storyboardService.startGenerateStoryboardAudio(userId, projectId, emptyToNull(shotIds));
            case "retry", "retryVideo" -> task = videoCompositionService.startGenerateDynamicVideos(userId, projectId, emptyToNull(shotIds));
            case "switchLtx" -> {
                result.put("videoConfig", applyVideoPreset(userId, "ltx"));
                task = videoCompositionService.startGenerateDynamicVideos(userId, projectId, emptyToNull(shotIds));
            }
            case "switchWan" -> {
                result.put("videoConfig", applyVideoPreset(userId, "wan"));
                task = videoCompositionService.startGenerateDynamicVideos(userId, projectId, emptyToNull(shotIds));
            }
            case "switchHunyuan" -> {
                result.put("videoConfig", applyVideoPreset(userId, "hunyuan"));
                task = videoCompositionService.startGenerateDynamicVideos(userId, projectId, emptyToNull(shotIds));
            }
            case "downgradeTier" -> {
                updateShotTier(projectId, shotIds, "C", true);
                task = videoCompositionService.startGenerateDynamicVideos(userId, projectId, emptyToNull(shotIds));
            }
            case "useFirstFrameOnly" -> {
                int count = setUseFirstFrameOnly(projectId, shotIds);
                result.put("updatedShots", count);
            }
            case "clearStaleTasks" -> result.put("updatedTasks", clearStaleDynamicTasks(projectId));
            case "composePreview", "composePublish" -> task = videoCompositionService.startCompose(userId, projectId, emptyToNull(shotIds), composeOptionsFor(action));
            case "snapshot" -> result.put("snapshots", createShotSnapshots(userId, projectId, shotIds));
            case "runEpisodePipeline" -> task = runEpisodePipeline(userId, projectId, body, result);
            default -> throw new BusinessException("不支持的修复动作: " + action);
        }

        if (task != null) {
            result.put("task", taskSummary(task));
            markIssuesRepairing(projectId, shotIds, action);
        }
        result.put("workspace", getWorkspace(userId, projectId));
        return result;
    }

    private TaskRecord runEpisodePipeline(Long userId, Long projectId, Map<String, Object> body, Map<String, Object> result) {
        List<Script> scripts = listScripts(projectId);
        List<Storyboard> shots = storyboardService.listByProject(projectId);
        String mode = textOr(body.get("mode"), "preview");

        if (scripts.stream().noneMatch(script -> hasText(script.getContent()))) {
            result.put("pipeline", pipelineState("script", "needRoute", "请先完善剧本，再生成分镜。", 0));
            return null;
        }
        if (shots.isEmpty()) {
            result.put("pipeline", pipelineState("storyboard", "needRoute", "请先生成分镜。", 0));
            return null;
        }

        List<Long> missingImageShotIds = shots.stream()
                .filter(shot -> !hasText(shot.getImageUrl()))
                .map(Storyboard::getId)
                .filter(id -> id != null)
                .toList();
        if (!missingImageShotIds.isEmpty()) {
            result.put("pipeline", pipelineState("firstFrames", "taskSubmitted", "正在补齐参考首帧。", missingImageShotIds.size()));
            return storyboardService.startGenerateStoryboardImages(userId, projectId, missingImageShotIds);
        }

        List<Long> missingVideoShotIds = shots.stream()
                .filter(this::needsPipelineVideo)
                .filter(shot -> !hasText(shot.getVideoUrl()))
                .map(Storyboard::getId)
                .filter(id -> id != null)
                .toList();
        if (!missingVideoShotIds.isEmpty()) {
            result.put("pipeline", pipelineState("videos", "taskSubmitted", "正在补齐分镜视频。", missingVideoShotIds.size()));
            return videoCompositionService.startGenerateDynamicVideos(userId, projectId, missingVideoShotIds);
        }

        List<Long> missingAudioShotIds = shots.stream()
                .filter(shot -> !hasText(shot.getAudioUrl()))
                .map(Storyboard::getId)
                .filter(id -> id != null)
                .toList();
        if (!missingAudioShotIds.isEmpty()) {
            result.put("pipeline", pipelineState("audio", "taskSubmitted", "正在补齐配音。", missingAudioShotIds.size()));
            return storyboardService.startGenerateStoryboardAudio(userId, projectId, missingAudioShotIds);
        }

        TaskRecord latestComposeTask = videoCompositionService.getLatestVideoTask(projectId);
        String finalVideoUrl = latestComposeTask != null && "SUCCESS".equals(latestComposeTask.getStatus())
                ? videoCompositionService.extractVideoUrl(latestComposeTask.getResult())
                : "";
        if (!hasText(finalVideoUrl)) {
            String composeAction = "publish".equalsIgnoreCase(mode) ? "composePublish" : "composePreview";
            result.put("pipeline", pipelineState("compose", "taskSubmitted", "正在合成成片。", shots.size()));
            return videoCompositionService.startCompose(userId, projectId, null, composeOptionsFor(composeAction));
        }

        if (!listOpenIssues(projectId).isEmpty()) {
            result.put("pipeline", pipelineState("quality", "needsReview", "仍有质检问题需要处理。", 0));
            return null;
        }

        result.put("pipeline", pipelineState("export", "ready", "素材已齐，可生成发布包。", shots.size()));
        result.put("export", exportPackage(userId, projectId, Map.of("platformProfile", textOr(body.get("platformProfile"), "douyin"))));
        return null;
    }

    private boolean needsPipelineVideo(Storyboard shot) {
        if (shot == null) {
            return false;
        }
        return "video".equalsIgnoreCase(shot.getRenderMode())
                || Boolean.TRUE.equals(shot.getDynamicSelected())
                || hasText(shot.getVideoPrompt())
                || hasText(shot.getDescription());
    }

    private Map<String, Object> pipelineState(String nextStage, String status, String message, int count) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("nextStage", nextStage);
        map.put("status", status);
        map.put("message", message);
        map.put("count", count);
        return map;
    }

    public Map<String, Object> runQualityCheck(Long userId, Long projectId, Map<String, Object> body) {
        projectService.getProject(userId, projectId);
        List<Long> requestedShotIds = longList(body.get("shotIds"));
        List<Storyboard> shots = selectShots(projectId, requestedShotIds);
        closePreviousQualityIssues(projectId, shots.stream().map(Storyboard::getId).toList());

        List<ProductionIssue> created = new ArrayList<>();
        for (Storyboard shot : shots) {
            created.addAll(checkShotQuality(userId, projectId, shot));
        }
        created.forEach(productionIssueMapper::insert);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checkedShots", shots.size());
        result.put("issues", created.stream().map(this::issueSummary).toList());
        result.put("workspace", getWorkspace(userId, projectId));
        return result;
    }

    public Map<String, Object> createSnapshot(Long userId, Long projectId, Map<String, Object> body) {
        projectService.getProject(userId, projectId);
        List<Long> shotIds = longList(body.get("shotIds"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("snapshots", createShotSnapshots(userId, projectId, shotIds));
        return result;
    }

    public Map<String, Object> restoreSnapshot(Long userId, Long projectId, Long snapshotId) {
        projectService.getProject(userId, projectId);
        AssetSnapshot snapshot = assetSnapshotMapper.selectById(snapshotId);
        if (snapshot == null || !projectId.equals(snapshot.getProjectId()) || !"storyboard".equals(snapshot.getEntityType())) {
            throw new BusinessException("快照不存在或不属于当前项目");
        }
        Storyboard shot = storyboardMapper.selectById(snapshot.getEntityId());
        if (shot == null || !projectId.equals(shot.getProjectId())) {
            throw new BusinessException("快照对应镜头不存在");
        }
        applySnapshotToShot(snapshot, shot);
        storyboardMapper.updateById(shot);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("restored", true);
        result.put("shotId", shot.getId());
        result.put("assetType", snapshot.getAssetType());
        result.put("workspace", getWorkspace(userId, projectId));
        return result;
    }

    public Map<String, Object> exportPackage(Long userId, Long projectId, Map<String, Object> body) {
        Project project = projectService.getProject(userId, projectId);
        String profile = textOr(body.get("platformProfile"), "douyin");
        List<Storyboard> shots = storyboardService.listByProject(projectId);
        TaskRecord latestComposeTask = videoCompositionService.getLatestVideoTask(projectId);
        String finalVideoUrl = latestComposeTask != null && "SUCCESS".equals(latestComposeTask.getStatus())
                ? videoCompositionService.extractVideoUrl(latestComposeTask.getResult())
                : "";
        Map<String, Object> spec = exportProfiles().stream()
                .filter(item -> profile.equals(item.get("id")))
                .findFirst()
                .orElse(exportProfiles().get(0));

        List<String> checks = new ArrayList<>();
        if (!hasText(finalVideoUrl)) checks.add("缺少成片文件");
        if (shots.stream().anyMatch(s -> !hasText(s.getImageUrl()))) checks.add("存在未生成首帧的镜头");
        if (shots.stream().anyMatch(s -> !hasText(s.getAudioUrl()))) checks.add("存在未生成配音的镜头");
        if (!listOpenIssues(projectId).isEmpty()) checks.add("仍有未关闭的质检问题");

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("projectId", projectId);
        manifest.put("projectName", project.getName());
        manifest.put("platformProfile", profile);
        manifest.put("spec", spec);
        manifest.put("videoUrl", finalVideoUrl == null ? "" : finalVideoUrl);
        manifest.put("coverUrl", shots.stream().map(Storyboard::getImageUrl).filter(this::hasText).findFirst().orElse(""));
        manifest.put("title", project.getName());
        manifest.put("description", project.getDescription());
        manifest.put("subtitleMode", "burned_in_or_external_srt");
        manifest.put("metadata", Map.of(
                "shots", shots.size(),
                "exportedAt", LocalDateTime.now().toString()
        ));
        manifest.put("checks", checks);
        manifest.put("ready", checks.isEmpty());
        attachExportPackage(projectId, shots, finalVideoUrl, manifest, checks);
        manifest.put("ready", checks.isEmpty());
        return manifest;
    }

    private void attachExportPackage(Long projectId,
                                     List<Storyboard> shots,
                                     String finalVideoUrl,
                                     Map<String, Object> manifest,
                                     List<String> checks) {
        try {
            Path exportDir = Paths.get(uploadPath, "exports", String.valueOf(projectId)).normalize();
            Files.createDirectories(exportDir);
            String fileName = "niren-drama-" + projectId + "-" + System.currentTimeMillis() + ".zip";
            Path packagePath = exportDir.resolve(fileName).normalize();
            String relativeUrl = "exports/" + projectId + "/" + fileName;
            manifest.put("packageUrl", buildUploadUrl(relativeUrl));
            manifest.put("packagePath", packagePath.toString());

            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(packagePath))) {
                addZipBytes(zip, "manifest.json",
                        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
                Set<String> entries = new java.util.LinkedHashSet<>();
                addLocalAssetToZip(zip, entries, finalVideoUrl, "video/" + safeFileName(fileNameOf(finalVideoUrl, "final.mp4")));
                List<Storyboard> orderedShots = shots == null ? List.of() : shots.stream()
                        .sorted(Comparator.comparing(Storyboard::getEpisodeNo, Comparator.nullsLast(Integer::compareTo))
                                .thenComparing(Storyboard::getShotNo, Comparator.nullsLast(Integer::compareTo))
                                .thenComparing(Storyboard::getId, Comparator.nullsLast(Long::compareTo)))
                        .toList();
                for (Storyboard shot : orderedShots) {
                    String label = safeFileName("shot-" + (shot.getShotNo() != null ? shot.getShotNo() : shot.getId()));
                    addLocalAssetToZip(zip, entries, shot.getImageUrl(),
                            "images/" + label + "-" + safeFileName(fileNameOf(shot.getImageUrl(), "frame.png")));
                    addLocalAssetToZip(zip, entries, shot.getAudioUrl(),
                            "audio/" + label + "-" + safeFileName(fileNameOf(shot.getAudioUrl(), "audio.wav")));
                    addLocalAssetToZip(zip, entries, shot.getVideoUrl(),
                            "shots/" + label + "-" + safeFileName(fileNameOf(shot.getVideoUrl(), "video.mp4")));
                }
            }
        } catch (Exception e) {
            checks.add("导出包生成失败: " + (hasText(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName()));
            log.warn("导出包生成失败: projectId={}", projectId, e);
        }
    }

    private void addLocalAssetToZip(ZipOutputStream zip,
                                    Set<String> entries,
                                    String assetUrl,
                                    String entryName) throws Exception {
        Path path = resolveLocalAsset(assetUrl);
        if (path == null || !Files.exists(path) || !Files.isRegularFile(path)) {
            return;
        }
        String uniqueEntryName = uniqueZipEntryName(entries, entryName);
        zip.putNextEntry(new ZipEntry(uniqueEntryName));
        Files.copy(path, zip);
        zip.closeEntry();
    }

    private void addZipBytes(ZipOutputStream zip, String entryName, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(entryName));
        zip.write(bytes);
        zip.closeEntry();
    }

    private String uniqueZipEntryName(Set<String> entries, String entryName) {
        String safe = entryName.replace('\\', '/');
        if (entries.add(safe)) {
            return safe;
        }
        int slash = safe.lastIndexOf('/');
        String dir = slash >= 0 ? safe.substring(0, slash + 1) : "";
        String name = slash >= 0 ? safe.substring(slash + 1) : safe;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int index = 2;
        while (true) {
            String candidate = dir + base + "-" + index + ext;
            if (entries.add(candidate)) {
                return candidate;
            }
            index++;
        }
    }

    private String buildUploadUrl(String relativePath) {
        String base = uploadBaseUrl == null ? "" : uploadBaseUrl.replaceAll("/+$", "");
        return base + "/" + relativePath.replace('\\', '/').replaceAll("^/+", "");
    }

    private String fileNameOf(String assetUrl, String fallback) {
        if (!hasText(assetUrl)) {
            return fallback;
        }
        String clean = assetUrl.trim();
        int query = clean.indexOf('?');
        if (query >= 0) clean = clean.substring(0, query);
        int hash = clean.indexOf('#');
        if (hash >= 0) clean = clean.substring(0, hash);
        int slash = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('\\'));
        String name = slash >= 0 ? clean.substring(slash + 1) : clean;
        return hasText(name) ? name : fallback;
    }

    private String safeFileName(String value) {
        String safe = hasText(value) ? value.trim() : "asset";
        safe = safe.replaceAll("[^A-Za-z0-9._-]", "_");
        return hasText(safe) ? safe : "asset";
    }

    public Map<String, Object> upsertBible(Long userId, Long projectId, Map<String, Object> body) {
        projectService.getProject(userId, projectId);
        Long id = longValue(body.get("id"));
        ConsistencyBible bible = id == null ? new ConsistencyBible() : consistencyBibleMapper.selectById(id);
        if (bible == null || (bible.getProjectId() != null && !projectId.equals(bible.getProjectId()))) {
            throw new BusinessException("一致性条目不存在或无权操作");
        }
        bible.setProjectId(projectId);
        bible.setBibleType(textOr(body.get("bibleType"), "style"));
        bible.setRefId(longValue(body.get("refId")));
        bible.setTitle(textOr(body.get("title"), "未命名条目"));
        bible.setLockedAttributes(text(body.get("lockedAttributes")));
        bible.setReferenceSnapshotIds(text(body.get("referenceSnapshotIds")));
        bible.setNotes(text(body.get("notes")));
        bible.setLocked(boolValue(body.get("locked"), true));
        if (bible.getId() == null) {
            consistencyBibleMapper.insert(bible);
        } else {
            consistencyBibleMapper.updateById(bible);
        }
        return Map.of("bible", bibleSummary(bible));
    }

    private List<Script> listScripts(Long projectId) {
        return scriptMapper.selectList(new LambdaQueryWrapper<Script>()
                .eq(Script::getProjectId, projectId)
                .orderByAsc(Script::getEpisodeNo));
    }

    private List<TaskRecord> listActiveTasks(Long projectId) {
        return taskRecordMapper.selectList(new LambdaQueryWrapper<TaskRecord>()
                .eq(TaskRecord::getProjectId, projectId)
                .in(TaskRecord::getStatus, ACTIVE_TASK_STATUS)
                .orderByDesc(TaskRecord::getCreateTime)
                .last("LIMIT 20"));
    }

    private List<ProductionIssue> listOpenIssues(Long projectId) {
        return productionIssueMapper.selectList(new LambdaQueryWrapper<ProductionIssue>()
                .eq(ProductionIssue::getProjectId, projectId)
                .in(ProductionIssue::getStatus, List.of("open", "repairing"))
                .orderByDesc(ProductionIssue::getCreateTime)
                .last("LIMIT 200"));
    }

    private Map<String, Object> projectSummary(Project project) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", project.getId());
        map.put("name", project.getName());
        map.put("description", project.getDescription());
        map.put("genre", project.getGenre());
        map.put("projectType", project.getProjectType());
        map.put("episodes", project.getEpisodes());
        map.put("status", project.getStatus());
        return map;
    }

    private Map<String, Object> buildCompletion(Project project,
                                                List<Script> scripts,
                                                List<Storyboard> shots,
                                                String finalVideoUrl,
                                                List<ProductionIssue> openIssues,
                                                List<Map<String, Object>> derivedIssues) {
        int expectedEpisodes = project.getEpisodes() == null || project.getEpisodes() <= 0 ? Math.max(1, scripts.size()) : project.getEpisodes();
        int scriptReady = (int) scripts.stream().filter(s -> hasText(s.getContent())).count();
        int totalShots = shots.size();
        int firstFrameReady = count(shots, Storyboard::getImageUrl);
        int videoReady = count(shots, Storyboard::getVideoUrl);
        int audioReady = count(shots, Storyboard::getAudioUrl);
        int issueCount = openIssues.size() + derivedIssues.size();

        List<Map<String, Object>> stages = new ArrayList<>();
        stages.add(stage("script", "剧本", scriptReady, expectedEpisodes, scriptReady > 0 ? "done" : "todo"));
        stages.add(stage("storyboard", "分镜", totalShots, Math.max(totalShots, 1), totalShots > 0 ? "done" : "todo"));
        stages.add(stage("firstFrame", "首帧", firstFrameReady, Math.max(totalShots, 1), stageStatus(firstFrameReady, totalShots)));
        stages.add(stage("video", "视频", videoReady, Math.max(totalShots, 1), stageStatus(videoReady, totalShots)));
        stages.add(stage("audio", "配音", audioReady, Math.max(totalShots, 1), stageStatus(audioReady, totalShots)));
        stages.add(stage("quality", "质检", Math.max(0, totalShots - issueCount), Math.max(totalShots, 1), issueCount > 0 ? "review" : (totalShots > 0 ? "done" : "todo")));
        stages.add(stage("publish", "发布包", hasText(finalVideoUrl) ? 1 : 0, 1, hasText(finalVideoUrl) && issueCount == 0 ? "done" : "todo"));

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("stages", stages);
        map.put("summary", Map.of(
                "scriptReady", scriptReady,
                "totalShots", totalShots,
                "firstFrameReady", firstFrameReady,
                "videoReady", videoReady,
                "audioReady", audioReady,
                "issueCount", issueCount,
                "finalReady", hasText(finalVideoUrl)
        ));
        return map;
    }

    private List<Map<String, Object>> buildNextActions(List<Script> scripts,
                                                       List<Storyboard> shots,
                                                       String finalVideoUrl,
                                                       List<ProductionIssue> openIssues,
                                                       List<Map<String, Object>> derivedIssues) {
        List<Map<String, Object>> actions = new ArrayList<>();
        int totalShots = shots.size();
        int firstFrameReady = count(shots, Storyboard::getImageUrl);
        int videoReady = count(shots, Storyboard::getVideoUrl);
        int audioReady = count(shots, Storyboard::getAudioUrl);
        int issueCount = openIssues.size() + derivedIssues.size();

        if (totalShots > 0) {
            actions.add(nextAction("runEpisodePipeline", "一键推进生产线", "自动从当前缺口继续：首帧、视频、配音、合成或导出。", "pipeline", true));
        }

        if (scripts.stream().noneMatch(s -> hasText(s.getContent()))) {
            actions.add(nextAction("script", "完善剧本", "先确认本集文本，再进入分镜拆解。", "route", true));
        } else if (totalShots == 0) {
            actions.add(nextAction("storyboard", "生成分镜", "将剧本拆成可生产的镜头。", "route", true));
        } else if (firstFrameReady < totalShots) {
            actions.add(nextAction("generateImages", "补齐参考首帧", "生成缺失镜头的 9:16 首帧/参考资产，供分镜视频保持角色与场景一致。", "repair", true));
        } else if (videoReady < totalShots) {
            actions.add(nextAction("retryVideo", "生成分镜视频", "按当前预览/发布模式补齐镜头视频，预览快测少量镜头，发布全量生成。", "repair", true));
        } else if (audioReady < totalShots) {
            actions.add(nextAction("generateAudio", "补齐配音", "生成镜头对白和旁白音频。", "repair", true));
        } else if (issueCount > 0) {
            actions.add(nextAction("quality", "处理质检问题", "先修复阻塞项，再打发布包。", "panel", true));
        } else if (!hasText(finalVideoUrl)) {
            actions.add(nextAction("composePreview", "合成预览版", "把镜头、配音和字幕合成一条可审片文件。", "repair", true));
        } else {
            actions.add(nextAction("export", "生成发布包", "按平台规格输出成片、封面、字幕和元数据。", "export", true));
        }

        actions.add(nextAction("qualityCheck", "运行质检", "检查比例、时长、黑屏和冻结。", "quality", totalShots > 0));
        return actions;
    }

    private Map<String, Object> stage(String id, String label, int ready, int total, String status) {
        int safeTotal = Math.max(total, 1);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("label", label);
        map.put("ready", ready);
        map.put("total", safeTotal);
        map.put("percent", Math.max(0, Math.min(100, Math.round(ready * 100f / safeTotal))));
        map.put("status", status);
        map.put("statusLabel", stageStatusLabel(status));
        return map;
    }

    private String stageStatus(int ready, int total) {
        if (total <= 0) return "todo";
        if (ready <= 0) return "todo";
        if (ready < total) return "active";
        return "done";
    }

    private String stageStatusLabel(String status) {
        return switch (status) {
            case "done" -> "已就绪";
            case "active" -> "进行中";
            case "review" -> "待确认";
            default -> "待开始";
        };
    }

    private Map<String, Object> nextAction(String id, String title, String description, String type, boolean enabled) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("id", id);
        action.put("title", title);
        action.put("description", description);
        action.put("type", type);
        action.put("enabled", enabled);
        return action;
    }

    private Map<String, Object> taskSummary(TaskRecord task) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", task.getId());
        map.put("taskType", task.getTaskType());
        map.put("typeLabel", taskTypeLabel(task.getTaskType()));
        map.put("status", task.getStatus());
        map.put("statusLabel", taskStatusLabel(task.getStatus(), task.getMessage()));
        map.put("progress", task.getProgress() == null ? 0 : task.getProgress());
        map.put("message", task.getMessage());
        map.put("createTime", task.getCreateTime());
        map.put("updateTime", task.getUpdateTime());
        return map;
    }

    private String taskTypeLabel(String type) {
        return switch (type == null ? "" : type) {
            case "SCRIPT_GEN" -> "剧本生成";
            case "STORYBOARD_GEN" -> "分镜生成";
            case "IMAGE_GEN" -> "首帧生成";
            case "DYNAMIC_VIDEO_GEN" -> "镜头视频";
            case "AUDIO_GEN" -> "配音生成";
            case "VIDEO_COMPOSE" -> "成片合成";
            default -> hasText(type) ? type : "任务";
        };
    }

    private String taskStatusLabel(String status, String message) {
        if ("PENDING".equals(status)) return "排队中";
        if ("RUNNING".equals(status)) {
            if (message != null && message.toLowerCase(Locale.ROOT).contains("comfy")) return "等待 ComfyUI";
            if (message != null && message.contains("上传")) return "上传参考图";
            if (message != null && message.contains("采样")) return "采样中";
            return "处理中";
        }
        if ("SUCCESS".equals(status)) return "已完成";
        if ("FAILED".equals(status)) return "失败";
        return status == null ? "未知" : status;
    }

    private List<Map<String, Object>> deriveWorkspaceIssues(List<Storyboard> shots, List<TaskRecord> activeTasks) {
        List<Map<String, Object>> issues = new ArrayList<>();
        for (Storyboard shot : shots) {
            if (!hasText(shot.getImageUrl())) {
                issues.add(derivedIssue(shot.getId(), "missing_first_frame", "blocking", "首帧缺失",
                        "该镜头还没有可用于视频生产的首帧。", "generateImages"));
            }
            if (hasText(shot.getVideoTaskStatus()) && "failed".equalsIgnoreCase(shot.getVideoTaskStatus())) {
                issues.add(derivedIssue(shot.getId(), "video_task_failed", "blocking", "镜头视频生成失败",
                        "外部视频任务返回失败，需要重试或切换工作流。", "retryVideo"));
            }
        }
        for (TaskRecord task : activeTasks) {
            if ("DYNAMIC_VIDEO_GEN".equals(task.getTaskType()) && task.getUpdateTime() != null
                    && task.getUpdateTime().isBefore(LocalDateTime.now().minusHours(6))) {
                issues.add(derivedIssue(null, "stale_task", "warning", "存在陈旧动态任务",
                        "动态视频任务长时间停留在运行状态，可清理后重新提交。", "clearStaleTasks"));
            }
        }
        return issues;
    }

    private Map<String, Object> derivedIssue(Long shotId,
                                             String issueType,
                                             String severity,
                                             String title,
                                             String message,
                                             String recommendedAction) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", "derived:" + issueType + ":" + (shotId == null ? "project" : shotId));
        map.put("shotId", shotId);
        map.put("issueType", issueType);
        map.put("severity", severity);
        map.put("status", "open");
        map.put("title", title);
        map.put("message", message);
        map.put("recommendedAction", recommendedAction);
        map.put("actions", actionList(recommendedAction));
        map.put("derived", true);
        enrichIssueDisplay(map, issueType, severity, recommendedAction, shotId);
        return map;
    }

    private List<Map<String, Object>> combineIssues(List<ProductionIssue> openIssues, List<Map<String, Object>> derivedIssues) {
        List<Map<String, Object>> all = new ArrayList<>();
        all.addAll(openIssues.stream().map(this::issueSummary).toList());
        all.addAll(derivedIssues);
        return all;
    }

    private Map<String, Object> issueSummary(ProductionIssue issue) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", issue.getId());
        map.put("shotId", issue.getShotId());
        map.put("issueType", issue.getIssueType());
        map.put("severity", issue.getSeverity());
        map.put("status", issue.getStatus());
        map.put("title", issue.getTitle());
        map.put("message", issue.getMessage());
        map.put("recommendedAction", issue.getRecommendedAction());
        map.put("actions", parseActions(issue.getActions(), issue.getRecommendedAction()));
        map.put("metadata", parseJsonObject(issue.getMetadata()));
        map.put("derived", false);
        enrichIssueDisplay(map, issue.getIssueType(), issue.getSeverity(), issue.getRecommendedAction(), issue.getShotId());
        return map;
    }

    private Map<String, Object> shotSummary(Storyboard shot,
                                            List<Map<String, Object>> snapshots,
                                            List<Map<String, Object>> issues) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", shot.getId());
        map.put("episodeNo", shot.getEpisodeNo());
        map.put("shotNo", shot.getShotNo());
        map.put("description", shot.getDescription());
        map.put("cameraAngle", shot.getCameraAngle());
        map.put("dialogue", shot.getDialogue());
        map.put("narration", shot.getNarration());
        map.put("resolvedTts", shot.getResolvedTts());
        map.put("subtitleText", shot.getResolvedSubtitle());
        map.put("duration", shot.getDuration());
        map.put("imageUrl", shot.getImageUrl());
        map.put("videoUrl", shot.getVideoUrl());
        map.put("audioUrl", shot.getAudioUrl());
        map.put("imagePrompt", shot.getImagePrompt());
        map.put("videoPrompt", shot.getVideoPrompt());
        map.put("motionTier", hasText(shot.getMotionTier()) ? shot.getMotionTier() : "C");
        map.put("renderMode", hasText(shot.getRenderMode()) ? shot.getRenderMode() : (hasText(shot.getVideoUrl()) ? "video" : "image"));
        map.put("locked", Boolean.FALSE);
        map.put("status", Map.of(
                "firstFrame", hasText(shot.getImageUrl()) ? "首帧已就绪" : "待首帧",
                "video", resolveVideoStatus(shot),
                "audio", hasText(shot.getAudioUrl()) ? "配音已就绪" : "待配音",
                "quality", issues.isEmpty() ? "待确认" : "需修复"
        ));
        map.put("quality", Map.of(
                "issueCount", issues.size(),
                "manualChecks", List.of("人物漂移", "服装变化", "突然多人物"),
                "lastResult", issues.isEmpty() ? "待人工确认" : "发现问题"
        ));
        map.put("issues", issues);
        map.put("snapshots", snapshots);
        map.put("lineage", Map.of(
                "taskId", shot.getVideoTaskRecordId() == null ? "" : String.valueOf(shot.getVideoTaskRecordId()),
                "videoProvider", shot.getVideoTaskProvider() == null ? "" : shot.getVideoTaskProvider(),
                "externalTaskId", shot.getVideoTaskId() == null ? "" : shot.getVideoTaskId(),
                "workflow", snapshots.stream().map(s -> text(s.get("workflowFile"))).filter(this::hasText).findFirst().orElse("")
        ));
        map.put("actions", shotActions(shot, issues));
        return map;
    }

    private String resolveVideoStatus(Storyboard shot) {
        if (hasText(shot.getVideoUrl())) return "视频已就绪";
        String vendorStatus = shot.getVideoTaskStatus();
        if (!hasText(vendorStatus)) return hasText(shot.getImageUrl()) ? "可生成预览" : "等待首帧";
        return switch (vendorStatus.toLowerCase(Locale.ROOT)) {
            case "submitted" -> "等待 ComfyUI";
            case "running", "processing" -> "采样中";
            case "success" -> "回传中";
            case "failed" -> "生成失败";
            default -> vendorStatus;
        };
    }

    private List<Map<String, Object>> shotActions(Storyboard shot, List<Map<String, Object>> issues) {
        List<Map<String, Object>> actions = new ArrayList<>();
        actions.add(nextAction("retryVideo", "重跑视频", "重新提交当前镜头。", "repair", hasText(shot.getImageUrl())));
        actions.add(nextAction("useFirstFrameOnly", "只用首帧", "预览阶段改为静态镜头。", "repair", hasText(shot.getImageUrl())));
        actions.add(nextAction("regenerateFirstFrame", "重生首帧", "重新生成当前镜头参考画面。", "repair", true));
        actions.add(nextAction("snapshot", "保存快照", "保存当前镜头资产版本。", "snapshot", hasText(shot.getImageUrl()) || hasText(shot.getVideoUrl())));
        return actions;
    }

    private Map<Long, List<Map<String, Object>>> groupSnapshots(Long projectId, List<Storyboard> shots) {
        List<Long> shotIds = shots.stream().map(Storyboard::getId).toList();
        if (shotIds.isEmpty()) return Map.of();
        List<AssetSnapshot> snapshots = assetSnapshotMapper.selectList(new LambdaQueryWrapper<AssetSnapshot>()
                .eq(AssetSnapshot::getProjectId, projectId)
                .eq(AssetSnapshot::getEntityType, "storyboard")
                .in(AssetSnapshot::getEntityId, shotIds)
                .eq(AssetSnapshot::getActive, true)
                .orderByDesc(AssetSnapshot::getCreateTime)
                .last("LIMIT 500"));
        return snapshots.stream()
                .collect(Collectors.groupingBy(AssetSnapshot::getEntityId, LinkedHashMap::new,
                        Collectors.mapping(this::snapshotSummary, Collectors.toList())));
    }

    private Map<String, Object> snapshotSummary(AssetSnapshot snapshot) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", snapshot.getId());
        map.put("assetType", snapshot.getAssetType());
        map.put("assetUrl", snapshot.getAssetUrl());
        map.put("provider", snapshot.getProvider());
        map.put("model", snapshot.getModel());
        map.put("workflowFile", snapshot.getWorkflowFile());
        map.put("sourceTaskId", snapshot.getSourceTaskId());
        map.put("createTime", snapshot.getCreateTime());
        return map;
    }

    private Map<Long, List<Map<String, Object>>> groupIssues(List<ProductionIssue> openIssues, List<Map<String, Object>> derivedIssues) {
        Map<Long, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (ProductionIssue issue : openIssues) {
            if (issue.getShotId() == null) continue;
            grouped.computeIfAbsent(issue.getShotId(), k -> new ArrayList<>()).add(issueSummary(issue));
        }
        for (Map<String, Object> issue : derivedIssues) {
            Long shotId = longValue(issue.get("shotId"));
            if (shotId == null) continue;
            grouped.computeIfAbsent(shotId, k -> new ArrayList<>()).add(issue);
        }
        return grouped;
    }

    private Map<String, Object> buildConsistency(Long projectId, Project project) {
        List<ConsistencyBible> bibles = consistencyBibleMapper.selectList(new LambdaQueryWrapper<ConsistencyBible>()
                .eq(ConsistencyBible::getProjectId, projectId)
                .orderByAsc(ConsistencyBible::getBibleType)
                .orderByAsc(ConsistencyBible::getCreateTime));
        List<Map<String, Object>> items = bibles.stream().map(this::bibleSummary).collect(Collectors.toCollection(ArrayList::new));
        if (items.isEmpty()) {
            items.addAll(derivedCharacterBible(projectId));
            items.addAll(derivedSceneBible(projectId));
            items.add(Map.of(
                    "id", "derived:style",
                    "bibleType", "style",
                    "title", "视觉风格",
                    "locked", false,
                    "lockedAttributes", Map.of("projectType", textOr(project.getProjectType(), "未设置"), "genre", project.getGenre() == null ? "" : project.getGenre()),
                    "derived", true
            ));
        }
        Map<String, Long> counts = items.stream()
                .collect(Collectors.groupingBy(item -> textOr(item.get("bibleType"), "style"), LinkedHashMap::new, Collectors.counting()));
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("items", items);
        map.put("counts", counts);
        return map;
    }

    private List<Map<String, Object>> derivedCharacterBible(Long projectId) {
        return characterMapper.selectList(new LambdaQueryWrapper<com.niren.drama.entity.Character>()
                        .eq(com.niren.drama.entity.Character::getProjectId, projectId)
                        .orderByAsc(com.niren.drama.entity.Character::getSortOrder))
                .stream()
                .map(c -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", "derived:character:" + c.getId());
                    item.put("bibleType", "character");
                    item.put("refId", c.getId());
                    item.put("title", c.getName());
                    item.put("locked", false);
                    item.put("lockedAttributes", Map.of(
                            "appearance", c.getAppearance() == null ? "" : c.getAppearance(),
                            "voice", c.getVoiceName() == null ? "" : c.getVoiceName(),
                            "imageUrl", c.getImageUrl() == null ? "" : c.getImageUrl()
                    ));
                    item.put("derived", true);
                    return item;
                })
                .toList();
    }

    private List<Map<String, Object>> derivedSceneBible(Long projectId) {
        return sceneMapper.selectList(new LambdaQueryWrapper<Scene>()
                        .eq(Scene::getProjectId, projectId)
                        .orderByAsc(Scene::getSortOrder))
                .stream()
                .map(s -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", "derived:scene:" + s.getId());
                    item.put("bibleType", "scene");
                    item.put("refId", s.getId());
                    item.put("title", s.getName());
                    item.put("locked", false);
                    item.put("lockedAttributes", Map.of(
                            "description", s.getDescription() == null ? "" : s.getDescription(),
                            "timeOfDay", s.getTimeOfDay() == null ? "" : s.getTimeOfDay(),
                            "imageUrl", s.getImageUrl() == null ? "" : s.getImageUrl()
                    ));
                    item.put("derived", true);
                    return item;
                })
                .toList();
    }

    private Map<String, Object> bibleSummary(ConsistencyBible bible) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", bible.getId());
        map.put("bibleType", bible.getBibleType());
        map.put("refId", bible.getRefId());
        map.put("title", bible.getTitle());
        map.put("locked", Boolean.TRUE.equals(bible.getLocked()));
        map.put("lockedAttributes", parseJsonObject(bible.getLockedAttributes()));
        map.put("referenceSnapshotIds", bible.getReferenceSnapshotIds());
        map.put("notes", bible.getNotes());
        map.put("derived", false);
        return map;
    }

    private Map<String, Object> buildHealth(Long userId) {
        AiConfig videoConfig = aiConfigService.getDefaultByType(userId, "video");
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("token", Map.of("status", "ok", "label", "登录有效"));
        health.put("csrf", csrfPolicySummary());
        health.put("ffmpeg", checkCommand(ffmpegPath, "-version", "FFmpeg"));
        health.put("videoConfig", videoConfigSummary(videoConfig));
        health.put("comfyui", checkComfyUi(videoConfig));
        return health;
    }

    private Map<String, Object> csrfPolicySummary() {
        return Map.of(
                "status", "ok",
                "label", "CSRF 已关闭",
                "mode", "stateless-jwt",
                "enabled", false,
                "reason", "REST API 使用 Bearer JWT，不依赖 Cookie Session"
        );
    }

    private Map<String, Object> videoConfigSummary(AiConfig config) {
        if (config == null) {
            return Map.of("status", "degraded", "label", "未配置视频工作流");
        }
        Map<String, Object> extra = parseJsonObject(config.getExtra());
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", "ok");
        map.put("provider", config.getProvider());
        map.put("baseUrl", config.getBaseUrl());
        map.put("model", config.getModel());
        map.put("workflowFile", text(extra.get("workflowFile")));
        map.put("preset", inferPreset(config));
        map.put("label", presetLabel(inferPreset(config)));
        return map;
    }

    private Map<String, Object> checkComfyUi(AiConfig config) {
        if (config == null || !"comfyui".equalsIgnoreCase(config.getProvider())) {
            return Map.of("status", "skipped", "label", "未使用 ComfyUI");
        }
        String baseUrl = hasText(config.getBaseUrl()) ? config.getBaseUrl() : "http://127.0.0.1:8188";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl.replaceAll("/+$", "") + "/queue"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return Map.of("status", "ok", "label", "ComfyUI 在线", "baseUrl", baseUrl);
            }
            return Map.of("status", "degraded", "label", "ComfyUI 返回 " + response.statusCode(), "baseUrl", baseUrl);
        } catch (Exception e) {
            return Map.of("status", "down", "label", "ComfyUI 不可用", "baseUrl", baseUrl, "reason", textOr(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    private Map<String, Object> checkCommand(String command, String argument, String label) {
        if (!hasText(command)) {
            return Map.of("status", "degraded", "label", label + " 未配置");
        }
        try {
            Process process = new ProcessBuilder(command, argument)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(3, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return Map.of("status", "degraded", "label", label + " 响应超时");
            }
            return process.exitValue() == 0
                    ? Map.of("status", "ok", "label", label + " 可用")
                    : Map.of("status", "degraded", "label", label + " 检查失败");
        } catch (Exception e) {
            return Map.of("status", "down", "label", label + " 不可用", "reason", textOr(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    private List<Map<String, Object>> exportProfiles() {
        return List.of(
                Map.of(
                        "id", "douyin",
                        "label", "抖音短剧",
                        "aspectRatio", "9:16",
                        "resolution", "1080x1920",
                        "safeArea", "上下 180px",
                        "codec", "H.264",
                        "subtitle", "建议内嵌字幕"
                ),
                Map.of(
                        "id", "hongguo",
                        "label", "红果短剧",
                        "aspectRatio", "9:16",
                        "resolution", "1080x1920",
                        "safeArea", "标题和字幕避开底部操作区",
                        "codec", "H.264",
                        "subtitle", "内嵌字幕 + 元数据"
                )
        );
    }

    private List<Storyboard> selectShots(Long projectId, List<Long> requestedShotIds) {
        List<Storyboard> shots = storyboardService.listByProject(projectId);
        if (requestedShotIds == null || requestedShotIds.isEmpty()) {
            return shots;
        }
        Set<Long> ids = Set.copyOf(requestedShotIds);
        return shots.stream().filter(shot -> ids.contains(shot.getId())).toList();
    }

    private int setUseFirstFrameOnly(Long projectId, List<Long> shotIds) {
        List<Storyboard> shots = selectShots(projectId, shotIds);
        for (Storyboard shot : shots) {
            if (!hasText(shot.getImageUrl())) continue;
            shot.setRenderMode("image");
            shot.setDynamicSelected(false);
            shot.setMotionTier("C");
            shot.setVideoTaskStatus(null);
            storyboardMapper.updateById(shot);
        }
        return shots.size();
    }

    private void updateShotTier(Long projectId, List<Long> shotIds, String tier, boolean dynamicSelected) {
        for (Storyboard shot : selectShots(projectId, shotIds)) {
            shot.setMotionTier(tier);
            shot.setDynamicSelected(dynamicSelected);
            shot.setRenderMode("video");
            storyboardMapper.updateById(shot);
        }
    }

    private int clearStaleDynamicTasks(Long projectId) {
        TaskRecord update = new TaskRecord();
        update.setStatus("FAILED");
        update.setProgress(100);
        update.setMessage("已由生产线工作台清理：陈旧动态视频任务");
        return taskRecordMapper.update(update, new LambdaUpdateWrapper<TaskRecord>()
                .eq(TaskRecord::getProjectId, projectId)
                .eq(TaskRecord::getTaskType, "DYNAMIC_VIDEO_GEN")
                .in(TaskRecord::getStatus, ACTIVE_TASK_STATUS));
    }

    private VideoCompositionService.ComposeOptions composeOptionsFor(String action) {
        boolean publish = "composePublish".equals(action);
        return new VideoCompositionService.ComposeOptions(
                true,
                publish ? 0.24d : 0.20d,
                true,
                publish,
                publish ? 0.12d : 0.08d
        );
    }

    private Map<String, Object> applyVideoPreset(Long userId, String preset) {
        AiConfig config = aiConfigService.getDefaultByType(userId, "video");
        if (config == null) {
            config = new AiConfig();
            config.setConfigType("video");
            config.setProvider("comfyui");
            config.setBaseUrl("http://127.0.0.1:8188");
            config.setIsDefault(1);
        }
        config.setProvider("comfyui");
        if (!hasText(config.getBaseUrl())) {
            config.setBaseUrl("http://127.0.0.1:8188");
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        if ("hunyuan".equals(preset)) {
            config.setModel("hunyuanvideo1.5_720p_i2v_fp16.safetensors");
            extra.put("workflowFile", "video_hunyuan_video_1.5_720p_i2v.json");
            extra.put("qualityMode", "hunyuan15-i2v-720p");
            extra.put("maxFrames", 49);
            extra.put("maxSteps", 12);
            extra.put("maxReferenceImages", 1);
        } else if ("wan".equals(preset)) {
            config.setModel("wan2.2_i2v_high_noise_14B_fp8_scaled.safetensors");
            extra.put("workflowFile", "video_wan2_2_14B_i2v_series_balanced.json");
            extra.put("qualityMode", "wan22-series-balanced");
            extra.put("maxFrames", 65);
            extra.put("maxSteps", 12);
            extra.put("frameRate", 16);
            extra.put("maxReferenceImages", 3);
            extra.put("patchWanControlnetStrength", true);
            extra.put("bypassWanReferenceEmbeds", false);
        } else {
            config.setModel("ltx-2-19b-distilled.safetensors");
            extra.put("workflowFile", "video_ltx2_i2v_short_drama_consistency.json");
            extra.put("maxReferenceImages", 1);
        }
        try {
            config.setExtra(objectMapper.writeValueAsString(extra));
        } catch (Exception e) {
            throw new BusinessException("视频预设保存失败: " + e.getMessage());
        }
        config.setIsDefault(1);
        AiConfig saved = aiConfigService.saveConfig(userId, config);
        return videoConfigSummary(saved);
    }

    private List<Map<String, Object>> createShotSnapshots(Long userId, Long projectId, List<Long> shotIds) {
        AiConfig videoConfig = aiConfigService.getDefaultByType(userId, "video");
        Map<String, Object> extra = videoConfig == null ? Map.of() : parseJsonObject(videoConfig.getExtra());
        List<Map<String, Object>> snapshots = new ArrayList<>();
        for (Storyboard shot : selectShots(projectId, shotIds)) {
            maybeCreateSnapshot(projectId, shot, "first_frame", shot.getImageUrl(), shot.getImagePrompt(), videoConfig, extra, snapshots);
            maybeCreateSnapshot(projectId, shot, "video", shot.getVideoUrl(), shot.getVideoPrompt(), videoConfig, extra, snapshots);
            maybeCreateSnapshot(projectId, shot, "audio", shot.getAudioUrl(), shot.getResolvedTts(), null, Map.of(), snapshots);
            maybeCreateTextSnapshot(projectId, shot, "subtitle", shot.getResolvedSubtitle(), snapshots);
            maybeCreateTextSnapshot(projectId, shot, "tts", shot.getResolvedTts(), snapshots);
        }
        return snapshots;
    }

    private void maybeCreateSnapshot(Long projectId,
                                     Storyboard shot,
                                     String assetType,
                                     String assetUrl,
                                     String prompt,
                                     AiConfig config,
                                     Map<String, Object> extra,
                                     List<Map<String, Object>> out) {
        if (!hasText(assetUrl) && !hasText(prompt)) return;
        AssetSnapshot snapshot = new AssetSnapshot();
        snapshot.setProjectId(projectId);
        snapshot.setEntityType("storyboard");
        snapshot.setEntityId(shot.getId());
        snapshot.setAssetType(assetType);
        snapshot.setAssetUrl(assetUrl);
        snapshot.setPrompt(prompt);
        snapshot.setProvider(config == null ? null : config.getProvider());
        snapshot.setModel(config == null ? null : config.getModel());
        snapshot.setWorkflowFile(text(extra.get("workflowFile")));
        snapshot.setSourceTaskId(shot.getVideoTaskRecordId());
        snapshot.setActive(true);
        assetSnapshotMapper.insert(snapshot);
        out.add(snapshotSummary(snapshot));
    }

    private void maybeCreateTextSnapshot(Long projectId,
                                         Storyboard shot,
                                         String assetType,
                                         String content,
                                         List<Map<String, Object>> out) {
        if (!hasText(content)) return;
        AssetSnapshot snapshot = new AssetSnapshot();
        snapshot.setProjectId(projectId);
        snapshot.setEntityType("storyboard");
        snapshot.setEntityId(shot.getId());
        snapshot.setAssetType(assetType);
        snapshot.setContent(content);
        snapshot.setActive(true);
        assetSnapshotMapper.insert(snapshot);
        out.add(snapshotSummary(snapshot));
    }

    private void applySnapshotToShot(AssetSnapshot snapshot, Storyboard shot) {
        switch (snapshot.getAssetType()) {
            case "first_frame" -> shot.setImageUrl(snapshot.getAssetUrl());
            case "video" -> shot.setVideoUrl(snapshot.getAssetUrl());
            case "audio" -> shot.setAudioUrl(snapshot.getAssetUrl());
            case "subtitle" -> shot.setSubtitleText(snapshot.getContent());
            case "tts" -> shot.setTtsText(snapshot.getContent());
            case "image_prompt" -> shot.setImagePrompt(snapshot.getPrompt());
            case "video_prompt" -> shot.setVideoPrompt(snapshot.getPrompt());
            default -> throw new BusinessException("暂不支持回滚该资产类型: " + snapshot.getAssetType());
        }
    }

    private void markIssuesRepairing(Long projectId, List<Long> shotIds, String action) {
        ProductionIssue update = new ProductionIssue();
        update.setStatus("repairing");
        LambdaUpdateWrapper<ProductionIssue> wrapper = new LambdaUpdateWrapper<ProductionIssue>()
                .eq(ProductionIssue::getProjectId, projectId)
                .eq(ProductionIssue::getRecommendedAction, action)
                .eq(ProductionIssue::getStatus, "open");
        if (shotIds != null && !shotIds.isEmpty()) {
            wrapper.in(ProductionIssue::getShotId, shotIds);
        }
        productionIssueMapper.update(update, wrapper);
    }

    private void closePreviousQualityIssues(Long projectId, List<Long> shotIds) {
        if (shotIds.isEmpty()) return;
        ProductionIssue update = new ProductionIssue();
        update.setStatus("resolved");
        productionIssueMapper.update(update, new LambdaUpdateWrapper<ProductionIssue>()
                .eq(ProductionIssue::getProjectId, projectId)
                .in(ProductionIssue::getShotId, shotIds)
                .in(ProductionIssue::getIssueType, QUALITY_ISSUE_TYPES)
                .in(ProductionIssue::getStatus, List.of("open", "repairing")));
    }

    private List<ProductionIssue> checkShotQuality(Long userId, Long projectId, Storyboard shot) {
        List<ProductionIssue> issues = new ArrayList<>();
        if (!hasText(shot.getVideoUrl()) && !hasText(shot.getImageUrl())) {
            issues.add(newIssue(projectId, shot.getId(), "missing_media", "blocking", "镜头缺少可合成素材",
                    "该镜头既没有视频，也没有首帧，无法进入预览或发布合成。", "regenerateFirstFrame", Map.of()));
            return issues;
        }
        if (!hasText(shot.getVideoUrl())) {
            if (requiresDynamicVideo(shot)) {
                issues.add(newIssue(projectId, shot.getId(), "missing_video", "blocking", "视频镜头缺少动态视频",
                        "该镜头被设定为视频镜头，但还没有动态视频；不能用首帧静态素材替代发布。", "retryVideo", Map.of()));
            }
            return issues;
        }

        Path localVideo = resolveLocalAsset(shot.getVideoUrl());
        if (localVideo == null || !Files.exists(localVideo)) {
            issues.add(newIssue(projectId, shot.getId(), "probe_failed", "warning", "无法本地读取视频",
                    "视频地址不是本地文件或文件已不存在，已跳过黑屏和冻结检测。", "retryVideo", Map.of("videoUrl", shot.getVideoUrl())));
            return issues;
        }

        Map<String, Object> probe = probeVideo(localVideo);
        int width = intValue(probe.get("width"), 0);
        int height = intValue(probe.get("height"), 0);
        double duration = doubleValue(probe.get("duration"), shot.getDuration() == null ? 0 : shot.getDuration());
        if (width > 0 && height > 0) {
            double ratio = width / (double) height;
            if (Math.abs(ratio - 9d / 16d) > 0.035d) {
                issues.add(newIssue(projectId, shot.getId(), "wrong_aspect_ratio", "blocking", "视频比例不是 9:16",
                        "当前视频尺寸为 " + width + "x" + height + "，发布前需要重新裁切或重生。", "retryVideo", probe));
            }
        }
        int expectedDuration = shot.getDuration() == null || shot.getDuration() <= 0 ? 5 : shot.getDuration();
        if (isVideoDurationOutOfRange(duration, expectedDuration)) {
            issues.add(newIssue(projectId, shot.getId(), "duration_out_of_range", "warning", "视频时长偏离镜头设定",
                    "当前检测时长约 " + Math.round(duration * 10d) / 10d + " 秒，镜头设定为 " + expectedDuration + " 秒。", "retryVideo", probe));
        }
        if (detectWithFfmpeg(localVideo, "blackdetect=d=0.45:pic_th=0.98", "black_start")) {
            issues.add(newIssue(projectId, shot.getId(), "black_frame", "blocking", "检测到黑屏片段",
                    "本地质检发现视频内存在明显黑屏，需要重跑或切换工作流。", "retryVideo", probe));
        }
        if (detectWithFfmpeg(localVideo, "freezedetect=n=0.003:d=1.2", "freeze_start")) {
            issues.add(newIssue(projectId, shot.getId(), "frozen_frame", "warning", "检测到冻结画面",
                    "本地质检发现视频长时间不动，预览可接受但发布前建议重跑。", "retryVideo", probe));
        }
        issues.addAll(checkVisualQuality(userId, projectId, shot, localVideo, expectedDuration, probe));
        return issues;
    }

    private boolean requiresDynamicVideo(Storyboard shot) {
        if (shot == null) {
            return false;
        }
        String renderMode = shot.getRenderMode();
        if (hasText(renderMode) && "video".equalsIgnoreCase(renderMode.trim())) {
            return true;
        }
        return Boolean.TRUE.equals(shot.getDynamicSelected());
    }

    private boolean isVideoDurationOutOfRange(double duration, int expectedDuration) {
        if (duration <= 0d) {
            return false;
        }
        double minimumDuration = expectedDuration >= 7
                ? 3.75d
                : Math.max(2d, expectedDuration - 3d);
        return duration < minimumDuration || duration > expectedDuration + 8d;
    }

    private List<ProductionIssue> checkVisualQuality(Long userId,
                                                     Long projectId,
                                                     Storyboard shot,
                                                     Path localVideo,
                                                     int expectedDuration,
                                                     Map<String, Object> probe) {
        List<ProductionIssue> issues = new ArrayList<>();

        VisualQualityReport localReport = null;
        if (visualQualityAnalyzer != null) {
            localReport = visualQualityAnalyzer.analyze(localVideo, expectedDuration);
            if (localReport != null && localReport.analyzed() && localReport.findings() != null) {
                for (VisualQualityFinding finding : localReport.findings()) {
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    if (probe != null) {
                        metadata.putAll(probe);
                    }
                    if (localReport.metrics() != null) {
                        metadata.put("visualMetrics", localReport.metrics());
                    }
                    if (finding.metadata() != null && !finding.metadata().isEmpty()) {
                        metadata.put("finding", finding.metadata());
                    }
                    metadata.put("visualAnalyzer", "local_frame_metrics");
                    issues.add(newIssue(
                            projectId,
                            shot.getId(),
                            textOr(finding.issueType(), "visual_quality"),
                            textOr(finding.severity(), "warning"),
                            textOr(finding.title(), "视觉质检异常"),
                            textOr(finding.message(), "本地视觉分析发现镜头画面质量异常，建议重跑或切换工作流。"),
                            textOr(finding.recommendedAction(), "retryVideo"),
                            metadata));
                }
            }
        }

        if (visualReviewService != null) {
            Path referenceImage = resolveLocalAsset(shot.getImageUrl());
            Map<String, Object> localMetrics = localReport == null || localReport.metrics() == null
                    ? Map.of()
                    : localReport.metrics();
            VisualReviewReport reviewReport = visualReviewService.review(userId, shot, localVideo, referenceImage, expectedDuration, localMetrics);
            if (reviewReport != null && reviewReport.analyzed() && reviewReport.findings() != null) {
                for (VisualReviewFinding finding : reviewReport.findings()) {
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    if (probe != null) {
                        metadata.putAll(probe);
                    }
                    if (!localMetrics.isEmpty()) {
                        metadata.put("visualMetrics", localMetrics);
                    }
                    if (reviewReport.metrics() != null && !reviewReport.metrics().isEmpty()) {
                        metadata.put("vlmReview", reviewReport.metrics());
                    }
                    if (finding.metadata() != null && !finding.metadata().isEmpty()) {
                        metadata.put("finding", finding.metadata());
                    }
                    metadata.put("visualAnalyzer", "vlm_keyframe_review");
                    issues.add(newIssue(
                            projectId,
                            shot.getId(),
                            textOr(finding.issueType(), "visual_review"),
                            textOr(finding.severity(), "warning"),
                            textOr(finding.title(), "VLM 视觉审片异常"),
                            textOr(finding.message(), "VLM 逐镜审片发现镜头与参考图或分镜不一致。"),
                            textOr(finding.recommendedAction(), "retryVideo"),
                            metadata));
                }
            }
        }
        return issues;
    }

    private ProductionIssue newIssue(Long projectId,
                                     Long shotId,
                                     String issueType,
                                     String severity,
                                     String title,
                                     String message,
                                     String recommendedAction,
                                     Map<String, Object> metadata) {
        ProductionIssue issue = new ProductionIssue();
        issue.setProjectId(projectId);
        issue.setShotId(shotId);
        issue.setIssueType(issueType);
        issue.setSeverity(severity);
        issue.setStatus("open");
        issue.setTitle(title);
        issue.setMessage(message);
        issue.setRecommendedAction(recommendedAction);
        issue.setActions(toJson(actionList(recommendedAction)));
        issue.setMetadata(toJson(metadata));
        return issue;
    }

    private Map<String, Object> probeVideo(Path path) {
        String command = hasText(ffprobePath) ? ffprobePath : "ffprobe";
        List<String> args = List.of(
                command,
                "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=width,height,duration",
                "-of", "json",
                path.toString()
        );
        try {
            String output = runProcess(args, Duration.ofSeconds(8));
            JsonNode stream = objectMapper.readTree(output).path("streams").path(0);
            Map<String, Object> probe = new LinkedHashMap<>();
            probe.put("width", stream.path("width").asInt(0));
            probe.put("height", stream.path("height").asInt(0));
            probe.put("duration", stream.path("duration").asDouble(0d));
            return probe;
        } catch (Exception e) {
            return Map.of("error", textOr(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    private boolean detectWithFfmpeg(Path path, String filter, String marker) {
        if (!hasText(ffmpegPath)) return false;
        List<String> args = List.of(
                ffmpegPath,
                "-hide_banner",
                "-i", path.toString(),
                "-vf", filter,
                "-an",
                "-f", "null",
                "-"
        );
        try {
            String output = runProcess(args, Duration.ofSeconds(18));
            return output.contains(marker);
        } catch (Exception e) {
            log.debug("Local ffmpeg quality check failed: path={}, filter={}, error={}", path, filter, e.getMessage());
            return false;
        }
    }

    private String runProcess(List<String> command, Duration timeout) throws Exception {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    output.append(line).append('\n');
                }
            } catch (Exception ignored) {
            }
        });
        reader.setDaemon(true);
        reader.start();
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("process timeout");
        }
        reader.join(500);
        return output.toString();
    }

    private Path resolveLocalAsset(String url) {
        if (!hasText(url)) return null;
        String normalizedBase = uploadBaseUrl == null ? "" : uploadBaseUrl.replaceAll("/+$", "");
        String normalizedUrl = url.trim();
        int idx = normalizedUrl.indexOf(normalizedBase);
        if (idx < 0 && normalizedUrl.startsWith("/api/files")) {
            normalizedBase = "/api/files";
            idx = 0;
        }
        if (idx < 0) return null;
        String relative = normalizedUrl.substring(idx + normalizedBase.length());
        if (relative.startsWith("/")) relative = relative.substring(1);
        if (!hasText(relative)) return null;
        return Paths.get(uploadPath).resolve(relative).normalize();
    }

    private List<Map<String, Object>> actionList(String recommendedAction) {
        List<Map<String, Object>> actions = new ArrayList<>();
        if (hasText(recommendedAction)) {
            actions.add(Map.of("id", recommendedAction, "label", actionLabel(recommendedAction)));
        }
        actions.add(Map.of("id", "retryVideo", "label", "重试"));
        actions.add(Map.of("id", "useFirstFrameOnly", "label", "只用首帧"));
        actions.add(Map.of("id", "switchLtx", "label", "切 LTX"));
        actions.add(Map.of("id", "switchWan", "label", "切 Wan"));
        actions.add(Map.of("id", "switchHunyuan", "label", "切 Hunyuan"));
        return actions.stream().distinct().toList();
    }

    private void enrichIssueDisplay(Map<String, Object> map,
                                    String issueType,
                                    String severity,
                                    String recommendedAction,
                                    Long shotId) {
        map.put("severityLabel", severityLabel(severity));
        map.put("impact", issueImpact(issueType, shotId));
        map.put("recommendedActionLabel", actionLabel(recommendedAction));
        map.put("estimatedMinutes", estimatedRepairMinutes(issueType, recommendedAction));
    }

    private String severityLabel(String severity) {
        return switch (severity == null ? "" : severity) {
            case "blocking" -> "阻塞发布";
            case "warning" -> "建议优化";
            case "info" -> "可忽略";
            default -> "待确认";
        };
    }

    private String issueImpact(String issueType, Long shotId) {
        String scope = shotId == null ? "项目级" : "镜头级";
        return switch (issueType == null ? "" : issueType) {
            case "missing_first_frame" -> scope + "问题：缺少参考首帧，无法稳定生成分镜视频。";
            case "missing_media" -> scope + "问题：缺少可合成素材，无法进入预览或发布。";
            case "missing_video" -> scope + "问题：视频镜头缺少动态视频，不能用静态首帧替代发布。";
            case "missing_tts_text" -> scope + "问题：镜头没有对白或旁白文本，配音任务已跳过该镜头。";
            case "video_task_failed" -> scope + "问题：分镜视频失败，当前镜头不会进入成片。";
            case "stale_task" -> scope + "问题：任务长时间卡住，可能阻塞后续重新提交。";
            case "wrong_aspect_ratio" -> scope + "问题：视频不是 9:16，阻塞平台发布。";
            case "duration_out_of_range" -> scope + "问题：时长偏离镜头设定，影响节奏。";
            case "black_frame" -> scope + "问题：存在黑屏片段，阻塞发布。";
            case "frozen_frame" -> scope + "问题：画面冻结，发布前建议重跑。";
            case "low_visual_detail" -> scope + "问题：画面细节不足，人物或场景可能不可读。";
            case "unwatchable_visual" -> scope + "问题：视觉质量不可用，阻塞发布。";
            case "weak_motion" -> scope + "问题：动态过弱，可能接近静态图。";
            case "animated_still" -> scope + "问题：疑似只把参考图做成动图，缺少真实表演和视差。";
            case "motion_smear" -> scope + "问题：存在拖影糊化，影响观看。";
            case "first_frame_drift_risk" -> scope + "问题：视频可能偏离参考首帧，存在连续性风险。";
            case "identity_drift" -> scope + "问题：VLM 审片发现人物身份漂移，连续性不可接受。";
            case "wardrobe_inconsistent" -> scope + "问题：VLM 审片发现服装/妆造不一致。";
            case "face_broken" -> scope + "问题：VLM 审片发现脸部崩坏或五官异常。";
            case "action_mismatch" -> scope + "问题：VLM 审片发现动作不符合分镜。";
            case "unpublishable_frame" -> scope + "问题：VLM 审片判定画面不可发布。";
            case "storyboard_mismatch" -> scope + "问题：VLM 审片发现视频偏离分镜意图。";
            case "reference_mismatch" -> scope + "问题：VLM 审片发现视频偏离参考首帧。";
            case "probe_failed" -> scope + "问题：本地无法读取视频，质检结果不完整。";
            default -> scope + "问题：需要处理后再确认发布。";
        };
    }

    private int estimatedRepairMinutes(String issueType, String recommendedAction) {
        String action = hasText(recommendedAction) ? recommendedAction : "";
        if ("clearStaleTasks".equals(action) || "useFirstFrameOnly".equals(action)) return 1;
        if ("generateImages".equals(action) || "regenerateFirstFrame".equals(action)) return 2;
        if ("generateAudio".equals(action)) return 3;
        if ("switchHunyuan".equals(action)) return 18;
        if ("switchWan".equals(action)) return 12;
        if ("retryVideo".equals(action) || "retry".equals(action) || "switchLtx".equals(action)) return 5;
        return switch (issueType == null ? "" : issueType) {
            case "wrong_aspect_ratio", "black_frame", "frozen_frame", "video_task_failed",
                    "low_visual_detail", "unwatchable_visual", "weak_motion", "animated_still", "motion_smear",
                    "first_frame_drift_risk", "identity_drift", "wardrobe_inconsistent", "face_broken",
                    "action_mismatch", "unpublishable_frame", "storyboard_mismatch", "reference_mismatch" -> 8;
            case "missing_tts_text" -> 1;
            case "duration_out_of_range", "probe_failed" -> 4;
            default -> 3;
        };
    }

    private String actionLabel(String action) {
        if (!hasText(action)) {
            return "查看详情";
        }
        return switch (action) {
            case "generateImages" -> "补齐参考首帧";
            case "runEpisodePipeline" -> "一键推进生产线";
            case "regenerateFirstFrame" -> "重生首帧";
            case "retryVideo", "retry" -> "重试视频";
            case "generateAudio" -> "补齐配音";
            case "useFirstFrameOnly" -> "只用首帧";
            case "switchLtx" -> "切 LTX";
            case "switchWan" -> "切 Wan";
            case "switchHunyuan" -> "切 Hunyuan";
            case "clearStaleTasks" -> "清理任务";
            default -> action;
        };
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseActions(String raw, String fallback) {
        if (hasText(raw)) {
            try {
                Object value = objectMapper.readValue(raw, Object.class);
                if (value instanceof List<?> list) {
                    return list.stream()
                            .filter(Map.class::isInstance)
                            .map(item -> (Map<String, Object>) item)
                            .toList();
                }
            } catch (Exception ignored) {
            }
        }
        return actionList(fallback);
    }

    private String inferProductionMode(Long userId) {
        AiConfig config = aiConfigService.getDefaultByType(userId, "video");
        String preset = inferPreset(config);
        return ("wan".equals(preset) || "hunyuan".equals(preset)) ? "publish" : "preview";
    }

    private String inferPreset(AiConfig config) {
        if (config == null) return "unset";
        String haystack = ((config.getModel() == null ? "" : config.getModel()) + " " + (config.getExtra() == null ? "" : config.getExtra()))
                .toLowerCase(Locale.ROOT);
        if (haystack.contains("hunyuan")) return "hunyuan";
        if (haystack.contains("wan")) return "wan";
        if (haystack.contains("ltx")) return "ltx";
        return "custom";
    }

    private String presetLabel(String preset) {
        return switch (preset) {
            case "ltx" -> "快测 LTX";
            case "wan" -> "高质 Wan2.2";
            case "hunyuan" -> "高质 Hunyuan";
            case "custom" -> "专家模式";
            default -> "未配置";
        };
    }

    private Map<String, Object> parseJsonObject(String raw) {
        if (!hasText(raw)) return Map.of();
        try {
            Object value = objectMapper.readValue(raw, Object.class);
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((k, v) -> result.put(String.valueOf(k), v));
                return result;
            }
        } catch (Exception ignored) {
        }
        return Map.of("raw", raw);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private int count(List<Storyboard> shots, java.util.function.Function<Storyboard, String> getter) {
        return (int) shots.stream().map(getter).filter(this::hasText).count();
    }

    private List<Long> emptyToNull(List<Long> values) {
        return values == null || values.isEmpty() ? null : values;
    }

    private List<Long> longList(Object value) {
        if (value == null) return List.of();
        if (value instanceof List<?> list) {
            return list.stream().map(this::longValue).filter(v -> v != null && v > 0).distinct().toList();
        }
        Long single = longValue(value);
        return single == null ? List.of() : List.of(single);
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String text && hasText(text)) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text && hasText(text)) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String text && hasText(text)) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private boolean boolValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof String text && hasText(text)) return Boolean.parseBoolean(text.trim());
        return fallback;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String textOr(Object value, String fallback) {
        String text = text(value);
        return hasText(text) ? text : fallback;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
