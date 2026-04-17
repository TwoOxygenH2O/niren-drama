package com.niren.drama.controller;

import com.niren.drama.ai.TtsProvider;
import com.niren.drama.ai.VoiceInfo;
import com.niren.drama.ai.AiProviderFactory;
import com.niren.drama.common.Result;
import com.niren.drama.entity.TaskRecord;
import com.niren.drama.entity.User;
import com.niren.drama.mapper.TaskRecordMapper;
import com.niren.drama.mapper.UserMapper;
import com.niren.drama.service.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.List;

@Tag(name = "任务管理", description = "查询异步任务状态与进度")
@RestController
@RequestMapping("/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final AiProviderFactory aiProviderFactory;
    private final UserMapper userMapper;

    @Operation(summary = "查询任务详情（进度轮询）")
    @GetMapping("/{id}")
    public Result<TaskRecord> getTask(@PathVariable Long id) {
        return Result.success(taskService.getTask(id));
    }

    @Operation(summary = "获取项目任务列表")
    @GetMapping("/project/{projectId}")
    public Result<List<TaskRecord>> listByProject(@PathVariable Long projectId) {
        return Result.success(taskService.listByProject(projectId));
    }

    @Operation(summary = "获取我的最近任务")
    @GetMapping("/my")
    public Result<List<TaskRecord>> myTasks(@AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        return Result.success(taskService.listByUser(userId));
    }

    @Operation(summary = "获取TTS音色列表")
    @GetMapping("/voices")
    public Result<List<VoiceInfo>> voices(@AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        TtsProvider tts = aiProviderFactory.getTtsProvider(userId);
        return Result.success(tts.listVoices());
    }

    private Long getUserId(UserDetails userDetails) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, userDetails.getUsername()));
        return user.getId();
    }
}
