package com.niren.drama.controller;

import com.niren.drama.common.CurrentUserHelper;
import com.niren.drama.common.Result;
import com.niren.drama.dto.tts.TtsAuditionRequest;
import com.niren.drama.entity.TaskRecord;
import com.niren.drama.service.ProjectService;
import com.niren.drama.service.TtsAuditionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "TTS试听评审", description = "生成角色配音试听包，不直接进入视频合成")
@RestController
@RequestMapping("/tts-auditions")
@RequiredArgsConstructor
public class TtsAuditionController {

    private final TtsAuditionService ttsAuditionService;
    private final ProjectService projectService;
    private final CurrentUserHelper currentUserHelper;

    @Operation(summary = "生成项目角色配音试听包")
    @PostMapping("/projects/{projectId}")
    public Result<TaskRecord> create(@PathVariable Long projectId,
                                     @RequestBody(required = false) TtsAuditionRequest request,
                                     @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = currentUserHelper.getUserId(userDetails);
        projectService.getProject(userId, projectId);
        return Result.success(ttsAuditionService.startAudition(
                userId,
                projectId,
                request != null ? request : new TtsAuditionRequest()));
    }
}
