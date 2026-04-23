package com.niren.drama.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.niren.drama.ai.AiProviderFactory;
import com.niren.drama.ai.ImageAiProvider;
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

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CharacterService {

    private final CharacterMapper characterMapper;
    private final TaskRecordMapper taskRecordMapper;
    private final AiProviderFactory aiProviderFactory;
    private final ProjectService projectService;
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
        characterMapper.insert(character);
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
        characterMapper.updateById(character);
        return character;
    }

    public void deleteCharacter(Long id) {
        characterMapper.deleteById(id);
    }

    public TaskRecord startGenerateCharacterImage(Long userId, Long characterId) {
        Character character = getCharacter(characterId);
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
            task.setProgress(20);
            task.setMessage("AI正在生成角色图片...");
            taskRecordMapper.updateById(task);

            ImageAiProvider imageProvider = aiProviderFactory.getImageProvider(userId);
            Project project = projectService.getProject(character.getProjectId());
            String prompt = buildCharacterImagePrompt(character, project);
            String imageUrl = imageProvider.generateImage(prompt, "1024x1024", "vivid");

            character.setImageUrl(imageUrl);
            characterMapper.updateById(character);

            task.setStatus("SUCCESS");
            task.setProgress(100);
            task.setMessage("角色图片生成完成");
            task.setResult(imageUrl);
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
        return String.format(
                "竖版9:16构图，短剧角色肖像定妆照，%s，%s，%s岁，"
                + "项目类型：%s，题材：%s，外貌特征：%s，气质：%s，视觉约束：%s，"
                + "半身正面照，眼神有戏，电影级质感，高清4K，"
                + "柔和的伦勃朗光影，浅灰色干净背景，"
                + "专业摄影，浅景深虚化，适合作为短剧角色定妆照",
                character.getName(), gender, age,
                projectType, genre, appearance, personality,
                ProjectStyleSupport.buildVisualCreationRules(projectType, genre).replace("\n", " ").replace("- ", " "));
    }
}
