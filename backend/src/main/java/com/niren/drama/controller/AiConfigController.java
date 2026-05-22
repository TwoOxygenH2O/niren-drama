package com.niren.drama.controller;

import com.niren.drama.ai.AiProviderFactory;
import com.niren.drama.ai.impl.ComfyUiWorkflowLoader;
import com.niren.drama.common.Result;
import com.niren.drama.entity.AiConfig;

import com.niren.drama.service.AiConfigService;
import com.niren.drama.service.AiImageDebugService;
import com.niren.drama.service.AiVideoDebugService;
import com.niren.drama.common.CurrentUserHelper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Tag(name = "AI配置管理", description = "管理AI服务商配置（文本/图像/视频/TTS）")
@RestController
@RequestMapping("/ai-configs")
@RequiredArgsConstructor
public class AiConfigController {

    private final AiConfigService aiConfigService;
    private final AiImageDebugService aiImageDebugService;
    private final AiVideoDebugService aiVideoDebugService;
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

    @Operation(summary = "调试图生视频：输入图片 URL 和提示词生成视频")
    @PostMapping("/debug/generate-image-to-video")
    public Result<Map<String, Object>> debugGenerateImageToVideo(@RequestBody(required = false) Map<String, Object> body,
                                                                 @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        String imageUrl = body != null ? stringValue(body.get("imageUrl")) : null;
        String prompt = body != null ? stringValue(body.get("prompt")) : null;
        Integer duration = body != null ? intValue(body.get("duration")) : null;
        String resolution = body != null ? stringValue(body.get("resolution")) : null;
        String quality = body != null ? stringValue(body.get("quality")) : null;
        Boolean withSound = body != null ? boolValue(body.get("withSound")) : null;
        return Result.success(aiVideoDebugService.generateImageToVideo(userId, imageUrl, prompt, duration, resolution, quality, withSound));
    }

    @Operation(summary = "获取 ComfyUI 当前用户工作流列表")
    @GetMapping("/comfyui/workflows")
    public Result<List<String>> listComfyUiWorkflows(@AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        String[] conn = resolveComfyUiConnection(userId);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        List<String> workflows = ComfyUiWorkflowLoader.listWorkflows(conn[0], client);
        return Result.success(workflows);
    }

    @Operation(summary = "获取指定 ComfyUI 工作流模板详情")
    @GetMapping("/comfyui/workflow")
    public Result<ObjectNode> getComfyUiWorkflow(@RequestParam String name,
                                                  @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        String[] conn = resolveComfyUiConnection(userId);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        ObjectNode workflow = ComfyUiWorkflowLoader.loadWorkflow(conn[0], client, name);
        if (workflow == null) {
            return Result.fail(404, "未找到工作流: " + name);
        }
        return Result.success(workflow);
    }

    /**
     * 解析用户的 ComfyUI 连接信息 [baseUrl, apiKey]
     */
    private String[] resolveComfyUiConnection(Long userId) {
        // 优先从用户视频配置获取（ComfyUI 主要用于视频/图片生成）
        AiConfig config = aiConfigService.getDefaultByType(userId, "video");
        if (config == null || !"comfyui".equalsIgnoreCase(config.getProvider())) {
            config = aiConfigService.getDefaultByType(userId, "image");
        }
        String baseUrl = (config != null && config.getBaseUrl() != null && !config.getBaseUrl().isBlank())
                ? config.getBaseUrl() : "http://localhost:8188";
        String apiKey = (config != null) ? config.getApiKey() : "";
        return new String[]{baseUrl, apiKey};
    }

    private String stringValue(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text);
        }
        return null;
    }

    private Boolean boolValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && !text.isBlank()) {
            return Boolean.parseBoolean(text);
        }
        return null;
    }

    private Long getUserId(UserDetails userDetails) {
        return currentUserHelper.getUserId(userDetails);
    }
}
