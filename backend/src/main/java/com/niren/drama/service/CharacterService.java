package com.niren.drama.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.niren.drama.ai.AiProviderFactory;
import com.niren.drama.ai.ImageAiProvider;
import com.niren.drama.ai.TtsProvider;
import com.niren.drama.common.AudioFormatSupport;
import com.niren.drama.common.ProjectStyleSupport;
import com.niren.drama.dto.character.CharacterCreateRequest;
import com.niren.drama.entity.Character;
import com.niren.drama.entity.Project;
import com.niren.drama.entity.TaskRecord;
import com.niren.drama.exception.BusinessException;
import com.niren.drama.mapper.CharacterMapper;
import com.niren.drama.mapper.TaskRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CharacterService {

    private final CharacterMapper characterMapper;
    private final TaskRecordMapper taskRecordMapper;
    private final AiProviderFactory aiProviderFactory;
    private final ProjectService projectService;
    private final PublicAssetStorageService publicAssetStorageService;
    private final ConsistencyBibleService consistencyBibleService;
    private final ObjectProvider<CharacterService> selfProvider;

    public Character createCharacter(CharacterCreateRequest request) {
        Character character = new Character();
        character.setProjectId(request.getProjectId());
        character.setName(request.getName());
        character.setDescription(request.getDescription());
        character.setPersonality(request.getPersonality());
        character.setAppearance(request.getAppearance());
        character.setGender(request.getGender());
        character.setAge(request.getAge());
        character.setVoiceId(request.getVoiceId());
        character.setVoiceName(request.getVoiceName());
        character.setSpeechRate(request.getSpeechRate());
        character.setTtsNote(request.getTtsNote());
        characterMapper.insert(character);
        consistencyBibleService.syncCharacterBible(character);
        return character;
    }

    public List<Character> listByProject(Long projectId) {
        return characterMapper.selectList(new LambdaQueryWrapper<Character>()
                .eq(Character::getProjectId, projectId)
                .orderByAsc(Character::getSortOrder)
                .orderByAsc(Character::getCreateTime));
    }

    public Character getCharacter(Long id) {
        Character c = characterMapper.selectById(id);
        if (c == null) throw new BusinessException("角色不存在");
        return c;
    }

    public Character updateCharacter(Long id, CharacterCreateRequest request) {
        Character character = getCharacter(id);
        if (request.getName() != null) character.setName(request.getName());
        if (request.getDescription() != null) character.setDescription(request.getDescription());
        if (request.getPersonality() != null) character.setPersonality(request.getPersonality());
        if (request.getAppearance() != null) character.setAppearance(request.getAppearance());
        if (request.getGender() != null) character.setGender(request.getGender());
        if (request.getAge() != null) character.setAge(request.getAge());
        if (request.getVoiceId() != null) character.setVoiceId(request.getVoiceId());
        if (request.getVoiceName() != null) character.setVoiceName(request.getVoiceName());
        if (request.getSpeechRate() != null) character.setSpeechRate(request.getSpeechRate());
        if (request.getTtsNote() != null) character.setTtsNote(request.getTtsNote());
        characterMapper.updateById(character);
        consistencyBibleService.syncCharacterBible(character);
        return character;
    }

    public void deleteCharacter(Long id) {
        characterMapper.deleteById(id);
    }

    public void deleteByProject(Long projectId) {
        characterMapper.delete(new LambdaQueryWrapper<Character>()
                .eq(Character::getProjectId, projectId));
    }

    public Map<String, Object> previewTts(Long userId, Long characterId, String text) {
        Character character = getCharacter(characterId);
        String previewText = text != null && !text.isBlank()
                ? text.trim()
                : buildDefaultPreviewText(character);
        String voiceId = character.getVoiceId();
        if (voiceId == null || voiceId.isBlank()) {
            throw new BusinessException("该角色还未配置音色，请先选择 voiceId");
        }

        TtsProvider ttsProvider = aiProviderFactory.getTtsProvider(userId);
        float speed = resolveSpeechSpeed(character);
        String instruction = buildPreviewInstruction(character);
        byte[] audio = ttsProvider.synthesize(previewText, voiceId, speed, 1.0f, instruction, "Chinese");
        if (audio == null || audio.length <= 100) {
            throw new BusinessException("预听失败，TTS 未返回有效音频");
        }

        String filename = AudioFormatSupport.filename(
                "tts_preview_" + characterId + "_" + UUID.randomUUID().toString().replace("-", ""),
                audio);
        String url;
        try {
            url = publicAssetStorageService.storeBytes(
                    audio,
                    "audios/preview",
                    filename,
                    AudioFormatSupport.contentTypeFor(audio),
                    AudioFormatSupport.extensionFor(audio)).publicUrl();
        } catch (Exception e) {
            throw new BusinessException("预听音频保存失败: " + e.getMessage());
        }
        Map<String, Object> result = new HashMap<>();
        result.put("audioUrl", url);
        result.put("text", previewText);
        result.put("voiceId", voiceId);
        result.put("speechRate", character.getSpeechRate());
        return result;
    }

    public TaskRecord startGenerateCharacterImage(Long userId, Long characterId) {
        Character character = getCharacter(characterId);
        projectService.getProject(userId, character.getProjectId());
        TaskRecord task = new TaskRecord();
        task.setProjectId(character.getProjectId());
        task.setUserId(userId);
        task.setTaskType("IMAGE_GEN");
        task.setStatus("PENDING");
        task.setProgress(0);
        task.setMessage("任务已提交，等待生成角色图片...");
        task.setRefId(characterId);
        taskRecordMapper.insert(task);
        selfProvider.getObject().generateCharacterImageAsync(userId, character, task.getId());
        return task;
    }

    @Async("aiTaskExecutor")
    public void generateCharacterImageAsync(Long userId, Character character, Long taskId) {
        TaskRecord task = taskRecordMapper.selectById(taskId);
        if (task == null) return;
        try {
            task.setStatus("RUNNING");
            task.setProgress(10);
            task.setMessage("AI正在生成角色图片（共3张）...");
            taskRecordMapper.updateById(task);

            ImageAiProvider imageProvider = aiProviderFactory.getImageProvider(userId);
            Project project = projectService.getProject(userId, character.getProjectId());
            String basePrompt = buildCharacterImagePrompt(character, project);

            String[] angleHints = {
                "，半身正面照，直视镜头，自信微笑",
                "，四分之三侧面照，微微低头，沉思神态",
                "，半身侧面回眸照，带一丝神秘感"
            };

            List<String> imageUrls = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                try {
                    String url = imageProvider.generateImage(basePrompt + angleHints[i], "1024x1024", "vivid");
                    if (url != null && !url.isBlank()) {
                        imageUrls.add(url);
                    }
                } catch (Exception e) {
                    log.warn("Character image generation failed for shot {}/3: {}", i + 1, e.getMessage());
                }
                task.setProgress(10 + (i + 1) * 25);
                task.setMessage(String.format("AI正在生成角色图片（%d/3）...", i + 1));
                taskRecordMapper.updateById(task);
            }

            if (imageUrls.isEmpty()) {
                throw new RuntimeException("3张图片全部生成失败");
            }

            // 主图取第一张，所有图片存入 imageUrls
            character.setImageUrl(imageUrls.get(0));
            try {
                String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(imageUrls);
                character.setImageUrls(json);
            } catch (Exception e) {
                log.warn("Failed to serialize imageUrls", e);
            }
            characterMapper.updateById(character);
            consistencyBibleService.syncCharacterBible(character);

            task.setStatus("SUCCESS");
            task.setProgress(100);
            task.setMessage(String.format("角色图片生成完成（%d/3张）", imageUrls.size()));
            task.setResult(character.getImageUrls());
            taskRecordMapper.updateById(task);

        } catch (Exception e) {
            log.error("Character image generation failed for task {}", taskId, e);
            task.setStatus("FAILED");
            task.setMessage("角色图片生成失败: " + e.getMessage());
            taskRecordMapper.updateById(task);
        }
    }

    private String buildCharacterImagePrompt(Character character, Project project) {
        String gender = character.getGender() != null
                ? (character.getGender().equals("female") ? "女性" : "男性")
                : "";
        String age = character.getAge() != null ? character.getAge() : "25";
        String appearance = character.getAppearance() != null ? character.getAppearance() : "五官精致，气质出众";
        String personality = character.getPersonality() != null ? character.getPersonality() : "自信从容";
        String projectType = ProjectStyleSupport.resolveProjectType(project != null ? project.getProjectType() : null);
        String genre = ProjectStyleSupport.resolveGenre(project != null ? project.getGenre() : null);
        String prompt = String.format(
                "竖版9:16构图，短剧角色肖像定妆照，%s，%s，%s岁，"
                + "项目类型：%s，题材：%s，外貌特征：%s，气质：%s，视觉约束：%s，"
                + "半身正面照，眼神有戏，电影级质感，高清4K，"
                + "柔和的伦勃朗光影，浅灰色干净背景，"
                + "专业摄影，浅景深虚化，适合作为短剧角色定妆照",
                character.getName(), gender, age,
                projectType, genre, appearance, personality,
                ProjectStyleSupport.buildVisualCreationRules(projectType, genre).replace("\n", " ").replace("- ", " "));
        return consistencyBibleService.appendPromptConstraints(
                character.getProjectId(), character.getId(), null, prompt, 1400);
    }

    private float resolveSpeechSpeed(Character character) {
        if (character == null || character.getSpeechRate() == null) {
            return 1.0f;
        }
        float rate = character.getSpeechRate() / 100f;
        return Math.max(0.5f, Math.min(1.5f, rate));
    }

    private String buildPreviewInstruction(Character character) {
        StringBuilder sb = new StringBuilder("中文短剧口语演绎，吐字清楚，避免播音腔");
        if (character != null && character.getTtsNote() != null && !character.getTtsNote().isBlank()) {
            sb.append("；导演补充：").append(character.getTtsNote().trim());
        }
        String out = sb.toString();
        return out.length() > 260 ? out.substring(0, 260) : out;
    }

    private String buildDefaultPreviewText(Character character) {
        String name = character != null && character.getName() != null ? character.getName() : "角色";
        return name + "，这句是语音预听，用来确认音色、语速和情绪是否匹配。";
    }
}
