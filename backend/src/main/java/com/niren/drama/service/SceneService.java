package com.niren.drama.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.niren.drama.ai.AiProviderFactory;
import com.niren.drama.ai.ImageAiProvider;
import com.niren.drama.dto.scene.SceneCreateRequest;
import com.niren.drama.entity.Scene;
import com.niren.drama.entity.TaskRecord;
import com.niren.drama.exception.BusinessException;
import com.niren.drama.mapper.SceneMapper;
import com.niren.drama.mapper.TaskRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SceneService {

    private final SceneMapper sceneMapper;
    private final TaskRecordMapper taskRecordMapper;
    private final AiProviderFactory aiProviderFactory;

    public Scene createScene(SceneCreateRequest request) {
        Scene scene = new Scene();
        scene.setProjectId(request.getProjectId());
        scene.setName(request.getName());
        scene.setDescription(request.getDescription());
        scene.setTimeOfDay(request.getTimeOfDay());
        scene.setLocation(request.getLocation());
        sceneMapper.insert(scene);
        return scene;
    }

    public List<Scene> listByProject(Long projectId) {
        return sceneMapper.selectList(new LambdaQueryWrapper<Scene>()
                .eq(Scene::getProjectId, projectId)
                .orderByAsc(Scene::getSortOrder)
                .orderByAsc(Scene::getCreateTime));
    }

    public Scene getScene(Long id) {
        Scene s = sceneMapper.selectById(id);
        if (s == null) throw new BusinessException("场景不存在");
        return s;
    }

    public Scene updateScene(Long id, SceneCreateRequest request) {
        Scene scene = getScene(id);
        if (request.getName() != null) scene.setName(request.getName());
        if (request.getDescription() != null) scene.setDescription(request.getDescription());
        if (request.getTimeOfDay() != null) scene.setTimeOfDay(request.getTimeOfDay());
        if (request.getLocation() != null) scene.setLocation(request.getLocation());
        sceneMapper.updateById(scene);
        return scene;
    }

    public void deleteScene(Long id) {
        sceneMapper.deleteById(id);
    }

    public TaskRecord startGenerateSceneImage(Long userId, Long sceneId) {
        Scene scene = getScene(sceneId);
        TaskRecord task = new TaskRecord();
        task.setProjectId(scene.getProjectId());
        task.setUserId(userId);
        task.setTaskType("IMAGE_GEN");
        task.setStatus("PENDING");
        task.setProgress(0);
        task.setMessage("任务已提交，等待生成场景图片...");
        task.setRefId(sceneId);
        taskRecordMapper.insert(task);
        generateSceneImageAsync(userId, scene, task.getId());
        return task;
    }

    @Async("aiTaskExecutor")
    public void generateSceneImageAsync(Long userId, Scene scene, Long taskId) {
        TaskRecord task = taskRecordMapper.selectById(taskId);
        if (task == null) return;
        try {
            task.setStatus("RUNNING");
            task.setProgress(20);
            task.setMessage("AI正在生成场景图片...");
            taskRecordMapper.updateById(task);

            ImageAiProvider imageProvider = aiProviderFactory.getImageProvider(userId);
            String prompt = buildSceneImagePrompt(scene);
            String imageUrl = imageProvider.generateImage(prompt, "1024x1792", null);

            scene.setImageUrl(imageUrl);
            sceneMapper.updateById(scene);

            task.setStatus("SUCCESS");
            task.setProgress(100);
            task.setMessage("场景图片生成完成");
            task.setResult(imageUrl);
            taskRecordMapper.updateById(task);

        } catch (Exception e) {
            log.error("Scene image generation failed for task {}", taskId, e);
            task.setStatus("FAILED");
            task.setMessage("场景图片生成失败: " + e.getMessage());
            taskRecordMapper.updateById(task);
        }
    }

    private String buildSceneImagePrompt(Scene scene) {
        return String.format(
                "场景背景图，%s，%s，%s，%s，竖版构图9:16，电影级画质，无人物，高清4K",
                scene.getName(),
                scene.getDescription() != null ? scene.getDescription() : "",
                scene.getTimeOfDay() != null ? scene.getTimeOfDay() : "day",
                scene.getLocation() != null ? scene.getLocation() : "outdoor"
        );
    }
}
