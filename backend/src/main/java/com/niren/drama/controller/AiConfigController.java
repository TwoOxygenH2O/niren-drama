package com.niren.drama.controller;

import com.niren.drama.ai.AiProviderFactory;
import com.niren.drama.common.Result;
import com.niren.drama.entity.AiConfig;

import com.niren.drama.service.AiConfigService;
import com.niren.drama.service.AiImageDebugService;
import com.niren.drama.common.CurrentUserHelper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "AI配置管理", description = "管理AI服务商配置（文本/图像/视频/TTS）")
@RestController
@RequestMapping("/ai-configs")
@RequiredArgsConstructor
public class AiConfigController {

    private final AiConfigService aiConfigService;
    private final AiImageDebugService aiImageDebugService;
    private final CurrentUserHelper currentUserHelper;

    @Operation(summary = "获取我的AI配置列表")
    @GetMapping
    public Result<List<AiConfig>> list(@AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        return Result.success(aiConfigService.listByUser(userId));
    }

    @Operation(summary = "保存AI配置（新增或更新）")
    @PostMapping
    public Result<AiConfig> save(@RequestBody AiConfig config,
                                 @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        return Result.success(aiConfigService.saveConfig(userId, config));
    }

    @Operation(summary = "删除AI配置")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id,
                               @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        aiConfigService.deleteConfig(userId, id);
        return Result.success();
    }

    @Operation(summary = "设为默认配置")
    @PutMapping("/{id}/default")
    public Result<Void> setDefault(@PathVariable Long id,
                                   @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        aiConfigService.setDefault(userId, id);
        return Result.success();
    }

    @Operation(summary = "获取服务商默认配置")
    @GetMapping("/provider-defaults")
    public Result<Map<String, Object>> getProviderDefaults(@RequestParam String provider,
                                                            @RequestParam(defaultValue = "text") String configType) {
        String baseUrl = AiProviderFactory.getDefaultBaseUrl(provider, configType);
        String model = AiProviderFactory.getDefaultModel(provider, configType);
        return Result.success(Map.of("baseUrl", baseUrl, "model", model));
    }

    @Operation(summary = "调试文生图：输入提示词生成图片并写入 COS（或本地公网可访问路径）")
    @PostMapping("/debug/generate-image")
    public Result<Map<String, Object>> debugGenerateImage(@RequestBody(required = false) Map<String, String> body,
                                                          @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        String prompt = body != null ? body.get("prompt") : null;
        String size = body != null ? body.get("size") : null;
        return Result.success(aiImageDebugService.generateAndStore(userId, prompt, size));
    }

    private Long getUserId(UserDetails userDetails) {
        return currentUserHelper.getUserId(userDetails);
    }
}
