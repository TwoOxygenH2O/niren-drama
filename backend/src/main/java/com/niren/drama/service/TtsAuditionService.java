package com.niren.drama.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.niren.drama.ai.TtsAuditionAudioGenerator;
import com.niren.drama.ai.TtsAuditionGenerationRequest;
import com.niren.drama.ai.TtsAuditionGenerationResult;
import com.niren.drama.ai.impl.ComfyUiTtsProviderFactory;
import com.niren.drama.common.AudioFormatSupport;
import com.niren.drama.dto.tts.TtsAuditionRequest;
import com.niren.drama.dto.tts.TtsAuditionRoleOverride;
import com.niren.drama.entity.Character;
import com.niren.drama.entity.Project;
import com.niren.drama.entity.TaskRecord;
import com.niren.drama.mapper.CharacterMapper;
import com.niren.drama.mapper.StoryboardMapper;
import com.niren.drama.mapper.TaskRecordMapper;
import com.niren.drama.service.storage.StoredAsset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class TtsAuditionService {

    private final CharacterMapper characterMapper;
    @SuppressWarnings("unused")
    private final StoryboardMapper storyboardMapper;
    private final TaskRecordMapper taskRecordMapper;
    private final ProjectService projectService;
    private final PublicAssetStorageService publicAssetStorageService;
    private final ComfyUiTtsProviderFactory providerFactory;
    private final ObjectProvider<TtsAuditionService> selfProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${niren.ai.tts.audition.candidate-count:3}")
    private int defaultCandidateCount = 3;

    @Value("${niren.ai.tts.audition.max-roles:4}")
    private int maxRoles = 4;

    @Value("${niren.ai.tts.audition.max-text-chars:80}")
    private int maxTextChars = 80;

    public TaskRecord startAudition(Long userId, Long projectId, TtsAuditionRequest request) {
        projectService.getProject(userId, projectId);
        TtsAuditionRequest safeRequest = request != null ? request : new TtsAuditionRequest();
        TaskRecord task = new TaskRecord();
        task.setProjectId(projectId);
        task.setUserId(userId);
        task.setTaskType("TTS_AUDITION");
        task.setStatus("PENDING");
        task.setProgress(0);
        task.setMessage("TTS 角色试听包任务已提交");
        taskRecordMapper.insert(task);
        selfProvider.getObject().generateAuditionAsync(userId, projectId, task.getId(), safeRequest);
        return task;
    }

    @Async("aiTaskExecutor")
    public void generateAuditionAsync(Long userId, Long projectId, Long taskId, TtsAuditionRequest request) {
        TaskRecord task = taskRecordMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        TtsAuditionRequest safeRequest = request != null ? request : new TtsAuditionRequest();
        int generated = 0;
        int failed = 0;
        try {
            updateTask(task, "RUNNING", 5, "正在准备角色配音试听包...");
            Project project = projectService.getProject(userId, projectId);
            List<RoleSlot> roles = selectRoles(projectId, safeRequest);
            if (roles.isEmpty()) {
                throw new IllegalStateException("没有可试听的角色");
            }

            int candidateCount = resolveCandidateCount(safeRequest);
            int total = roles.size() * candidateCount;
            int completed = 0;
            TtsAuditionAudioGenerator generator = providerFactory.create(userId);
            ArrayNode roleResults = objectMapper.createArrayNode();
            String subDir = "audios/audition/" + projectId + "/" + taskId;

            for (RoleSlot role : roles) {
                ObjectNode roleNode = objectMapper.createObjectNode();
                roleNode.put("roleKey", role.roleKey());
                if (role.characterId() != null) {
                    roleNode.put("characterId", role.characterId());
                }
                roleNode.put("roleName", role.roleName());
                ArrayNode candidates = objectMapper.createArrayNode();
                roleNode.set("candidates", candidates);
                roleResults.add(roleNode);

                for (int i = 1; i <= candidateCount; i++) {
                    completed++;
                    updateTask(task, "RUNNING", 5 + (85 * Math.max(0, completed - 1) / Math.max(1, total)),
                            String.format("正在生成%s试听 %d/%d...", role.roleName(), i, candidateCount));
                    ObjectNode candidate = objectMapper.createObjectNode();
                    candidate.put("candidateNo", i);
                    try {
                        TtsAuditionGenerationResult result = generator.generate(buildGenerationRequest(role, safeRequest, i, project));
                        byte[] audio = result.audio();
                        if (audio == null || audio.length <= 100) {
                            throw new IllegalStateException("TTS 试听音频无效");
                        }
                        StoredAsset stored = publicAssetStorageService.storeBytes(
                                audio,
                                subDir,
                                AudioFormatSupport.filename("role_" + role.roleKey() + "_candidate_" + i, audio),
                                AudioFormatSupport.contentTypeFor(audio),
                                AudioFormatSupport.extensionFor(audio));
                        candidate.put("status", "SUCCESS");
                        candidate.put("audioUrl", stored.publicUrl());
                        candidate.put("promptId", nullToEmpty(result.promptId()));
                        candidate.put("comfyOutputUrl", nullToEmpty(result.outputUrl()));
                        candidate.put("workflowFile", nullToEmpty(result.workflowFile()));
                        if (result.durationSeconds() != null) {
                            candidate.put("durationSeconds", result.durationSeconds());
                        }
                        generated++;
                    } catch (Exception e) {
                        failed++;
                        candidate.put("status", "FAILED");
                        candidate.put("error", failureMessage(e));
                        log.warn("TTS 试听候选生成失败: taskId={}, role={}, candidateNo={}, reason={}",
                                taskId, role.roleName(), i, failureMessage(e));
                    }
                    candidates.add(candidate);
                }
            }

            finishTask(task, projectId, roleResults, generated, failed);
        } catch (Exception e) {
            task.setStatus("FAILED");
            task.setProgress(100);
            task.setMessage("TTS 试听包生成失败: " + failureMessage(e));
            task.setResult(errorResult(projectId, generated, failed, failureMessage(e)));
            taskRecordMapper.updateById(task);
        }
    }

    private List<RoleSlot> selectRoles(Long projectId, TtsAuditionRequest request) {
        List<Character> characters = characterMapper.selectList(new LambdaQueryWrapper<Character>()
                .eq(Character::getProjectId, projectId)
                .orderByAsc(Character::getSortOrder)
                .orderByAsc(Character::getId));
        if (characters == null) {
            characters = List.of();
        }
        List<Long> characterIds = request.getCharacterIds();
        if (characterIds != null && !characterIds.isEmpty()) {
            Set<Long> wanted = new HashSet<>(characterIds);
            characters = characters.stream()
                    .filter(character -> wanted.contains(character.getId()))
                    .toList();
        }

        List<RoleSlot> roles = new ArrayList<>();
        if (Boolean.TRUE.equals(request.getIncludeNarrator())) {
            roles.add(new RoleSlot("narrator", null, "旁白", "other", null));
        }
        for (Character character : characters) {
            String key = character.getId() != null ? String.valueOf(character.getId()) : character.getName();
            roles.add(new RoleSlot(key, character.getId(), character.getName(), character.getGender(), character.getTtsNote()));
        }
        if (maxRoles > 0 && roles.size() > maxRoles) {
            return roles.subList(0, maxRoles);
        }
        return roles;
    }

    private int resolveCandidateCount(TtsAuditionRequest request) {
        int requested = request.getCandidateCount() != null ? request.getCandidateCount() : defaultCandidateCount;
        return Math.max(1, Math.min(3, requested));
    }

    private TtsAuditionGenerationRequest buildGenerationRequest(RoleSlot role,
                                                                TtsAuditionRequest request,
                                                                int candidateNo,
                                                                Project project) {
        TtsAuditionRoleOverride override = resolveOverride(role, request);
        String text = truncate(hasText(request.getSampleText()) ? request.getSampleText().trim() : defaultSampleText(role, project));
        String filenamePrefix = "tts_audition_" + project.getId() + "_" + role.roleKey() + "_" + candidateNo;
        return new TtsAuditionGenerationRequest(
                role.roleName(),
                text,
                override != null ? override.getSpeakerReferenceAudioUrl() : null,
                override != null ? override.getEmotionReferenceAudioUrl() : null,
                resolveEmotionText(role, override),
                override != null ? override.getEmotionVector() : List.of(),
                override != null ? override.getSpeed() : null,
                System.currentTimeMillis() + candidateNo,
                filenamePrefix);
    }

    private TtsAuditionRoleOverride resolveOverride(RoleSlot role, TtsAuditionRequest request) {
        if (request.getRoleOverrides() == null || request.getRoleOverrides().isEmpty()) {
            return null;
        }
        TtsAuditionRoleOverride byKey = request.getRoleOverrides().get(role.roleKey());
        if (byKey != null) {
            return byKey;
        }
        return request.getRoleOverrides().get(role.roleName());
    }

    private String resolveEmotionText(RoleSlot role, TtsAuditionRoleOverride override) {
        if (override != null && hasText(override.getEmotionText())) {
            return override.getEmotionText().trim();
        }
        if (hasText(role.ttsNote())) {
            return role.ttsNote().trim();
        }
        if ("female".equalsIgnoreCase(role.gender())) {
            return "情绪真实，声音清楚，有短剧角色的受伤和克制";
        }
        if ("male".equalsIgnoreCase(role.gender())) {
            return "情绪克制，声音清楚，有短剧角色的压迫和犹豫";
        }
        return "旁白清楚稳定，情绪克制";
    }

    private String defaultSampleText(RoleSlot role, Project project) {
        if ("narrator".equals(role.roleKey())) {
            return "她终于看清了真相，却已经没有退路。";
        }
        if ("female".equalsIgnoreCase(role.gender())) {
            return "你为什么骗我？我明明那么相信你。";
        }
        if ("male".equalsIgnoreCase(role.gender())) {
            return "这件事到此为止，别再追问了。";
        }
        return "这一刻，所有秘密都浮出了水面。";
    }

    private String truncate(String value) {
        if (!hasText(value) || maxTextChars <= 0 || value.length() <= maxTextChars) {
            return value;
        }
        return value.substring(0, maxTextChars);
    }

    private void finishTask(TaskRecord task, Long projectId, ArrayNode roleResults, int generated, int failed) {
        task.setProgress(100);
        task.setStatus(generated == 0 && failed > 0 ? "FAILED" : "SUCCESS");
        if ("FAILED".equals(task.getStatus())) {
            task.setMessage("TTS 试听包生成失败，所有候选均不可用");
        } else if (failed > 0) {
            task.setMessage(String.format("TTS 试听包生成完成：成功%d条，失败%d条", generated, failed));
        } else {
            task.setMessage(String.format("TTS 试听包生成完成：成功%d条", generated));
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.put("mediaType", "audio");
        result.put("projectId", projectId);
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("generated", generated);
        summary.put("failed", failed);
        result.set("summary", summary);
        result.set("roles", roleResults);
        task.setResult(toJson(result));
        taskRecordMapper.updateById(task);
    }

    private void updateTask(TaskRecord task, String status, int progress, String message) {
        task.setStatus(status);
        task.setProgress(progress);
        task.setMessage(message);
        taskRecordMapper.updateById(task);
    }

    private String errorResult(Long projectId, int generated, int failed, String error) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("mediaType", "audio");
        result.put("projectId", projectId);
        result.put("error", error);
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("generated", generated);
        summary.put("failed", failed);
        result.set("summary", summary);
        return toJson(result);
    }

    private String toJson(ObjectNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String failureMessage(Exception e) {
        return e.getMessage() != null && !e.getMessage().isBlank() ? e.getMessage() : e.getClass().getSimpleName();
    }

    private String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record RoleSlot(String roleKey, Long characterId, String roleName, String gender, String ttsNote) {
    }
}
