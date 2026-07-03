package com.niren.drama.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.niren.drama.ai.AiProviderFactory;
import com.niren.drama.ai.ImageAiProvider;
import com.niren.drama.common.ProjectStyleSupport;
import com.niren.drama.dto.scene.SceneCreateRequest;
import com.niren.drama.entity.Project;
import com.niren.drama.entity.Scene;
import com.niren.drama.entity.TaskRecord;
import com.niren.drama.exception.BusinessException;
import com.niren.drama.mapper.SceneMapper;
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
public class SceneService {

    private final SceneMapper sceneMapper;
    private final TaskRecordMapper taskRecordMapper;
    private final AiProviderFactory aiProviderFactory;
    private final ProjectService projectService;
    private final ConsistencyBibleService consistencyBibleService;
    private final ObjectProvider<SceneService> selfProvider;

    public Scene createScene(SceneCreateRequest request) {
        Scene scene = new Scene();
        scene.setProjectId(request.getProjectId());
        scene.setName(request.getName());
        scene.setDescription(request.getDescription());
        scene.setTimeOfDay(request.getTimeOfDay());
        scene.setLocation(request.getLocation());
        sceneMapper.insert(scene);
        consistencyBibleService.syncSceneBible(scene);
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
        consistencyBibleService.syncSceneBible(scene);
        return scene;
    }

    public void deleteScene(Long id) {
        sceneMapper.deleteById(id);
    }

    public TaskRecord startGenerateSceneImage(Long userId, Long sceneId) {
        Scene scene = getScene(sceneId);
        projectService.getProject(userId, scene.getProjectId());
        TaskRecord task = new TaskRecord();
        task.setProjectId(scene.getProjectId());
        task.setUserId(userId);
        task.setTaskType("IMAGE_GEN");
        task.setStatus("PENDING");
        task.setProgress(0);
        task.setMessage("任务已提交，等待生成场景图片...");
        task.setRefId(sceneId);
        taskRecordMapper.insert(task);
        selfProvider.getObject().generateSceneImageAsync(userId, scene, task.getId());
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
            Project project = projectService.getProject(userId, scene.getProjectId());
            String prompt = buildSceneImagePrompt(scene, project);
            String imageUrl = imageProvider.generateImage(prompt, "1024x1792", null);

            scene.setImageUrl(imageUrl);
            sceneMapper.updateById(scene);
            consistencyBibleService.syncSceneBible(scene);

            task.setStatus("SUCCESS");
            task.setProgress(100);
            task.setMessage("场景图片生成完成");
            task.setResult(imageUrl);
            taskRecordMapper.updateById(task);

        } catch (Exception e) {
            log.error("场景图片生成失败: taskId={}", taskId, e);
            task.setStatus("FAILED");
            task.setMessage("场景图片生成失败: " + e.getMessage());
            taskRecordMapper.updateById(task);
        }
    }

    private String buildSceneImagePrompt(Scene scene, Project project) {
        String timeOfDay = scene.getTimeOfDay() != null ? scene.getTimeOfDay() : "day";
        String location = scene.getLocation() != null ? scene.getLocation() : "outdoor";
        String timeLight = switch (timeOfDay) {
            case "night" -> "夜晚氛围，城市灯光/月光照射，冷色调蓝紫光影";
            case "sunset", "dusk" -> "黄昏金色光线，暖色调，天空渐变色";
            case "morning", "dawn" -> "清晨柔和光线，薄雾氛围，暖白色调";
            default -> "日间自然光，明亮通透，光影层次分明";
        };
        String projectType = ProjectStyleSupport.resolveProjectType(project != null ? project.getProjectType() : null);
        String genre = ProjectStyleSupport.resolveGenre(project != null ? project.getGenre() : null);
        String prompt = String.format(
                "竖版9:16构图，短剧场景背景图，项目类型：%s，题材：%s，%s，%s，%s，%s，视觉约束：%s，"
                + "无人物，电影级质感，高清4K，景深效果，丰富的环境细节，"
                + "戏剧性光影，适合作为短剧分镜背景使用",
                projectType,
                genre,
                scene.getName(),
                scene.getDescription() != null ? scene.getDescription() : "",
                location.equals("indoor") ? "室内场景" : "室外场景",
                timeLight,
                ProjectStyleSupport.buildVisualCreationRules(projectType, genre).replace("\n", " ").replace("- ", " "));
        return consistencyBibleService.appendPromptConstraints(
                scene.getProjectId(), null, scene.getId(), prompt, 1400);
    }
}
