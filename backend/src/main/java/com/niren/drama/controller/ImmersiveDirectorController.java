package com.niren.drama.controller;

import com.niren.drama.common.CurrentUserHelper;
import com.niren.drama.common.Result;
import com.niren.drama.dto.immersive.ImmersiveDirectorChatRequest;
import com.niren.drama.dto.immersive.ImmersiveDirectorChatResponse;
import com.niren.drama.service.ImmersiveDirectorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "沉浸式创作", description = "导演助手对话与意图执行")
@RestController
@RequestMapping("/projects/{projectId}")
@RequiredArgsConstructor
public class ImmersiveDirectorController {

    private final ImmersiveDirectorService immersiveDirectorService;
    private final CurrentUserHelper currentUserHelper;

    @Operation(summary = "沉浸式导演助手：结合项目上下文调用文本模型，并可触发分镜/剧本/大纲修复")
    @PostMapping("/immersive-chat")
    public Result<ImmersiveDirectorChatResponse> immersiveChat(@PathVariable Long projectId,
                                                               @RequestBody @Valid ImmersiveDirectorChatRequest request,
                                                               @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = currentUserHelper.getUserId(userDetails);
        request.setProjectId(projectId);
        return Result.success(immersiveDirectorService.chat(userId, request));
    }
}
