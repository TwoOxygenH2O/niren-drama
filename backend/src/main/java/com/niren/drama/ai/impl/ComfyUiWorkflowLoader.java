package com.niren.drama.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * 从 ComfyUI 服务器加载工作流模板，支持 UI 格式 → API 格式转换，注入 prompt / image 等运行时参数。
 */
@Slf4j
public final class ComfyUiWorkflowLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ComfyUiWorkflowLoader() {
    }

    // ─────────────── 工作流列表 ───────────────

    /**
     * 从 ComfyUI 服务器获取可用工作流列表，包括用户工作流和插件模板。
     *
     * @return 用户工作流以 "user:workflowName" 格式返回，插件模板以 "PluginName/TemplateName" 格式返回
     */
    public static List<String> listWorkflows(String apiBaseUrl, HttpClient httpClient) {
        List<String> result = new ArrayList<>();
        String base = normalizeBaseUrl(apiBaseUrl);

        // 1. 优先获取用户保存的工作流（ComfyUI 0.8+ User Data API）
        result.addAll(listUserWorkflows(base, httpClient));

        // 2. 获取插件模板
        result.addAll(listPluginTemplates(base, httpClient));

        return result;
    }

    /**
     * 获取用户在 ComfyUI 中保存的工作流（通过 User Data API）。
     * ComfyUI 0.8+ 版本支持，存储在 user/default/workflows/ 目录。
     *
     * @return "user:workflowName" 格式的列表
     */
    public static List<String> listUserWorkflows(String apiBaseUrl, HttpClient httpClient) {
        List<String> result = new ArrayList<>();
        String base = normalizeBaseUrl(apiBaseUrl);
        String url = base + "/list_user_data?dir=workflows&recurse=true&split=false";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = MAPPER.readTree(response.body());
                if (root.isArray()) {
                    for (JsonNode item : root) {
                        String name = item.asText("");
                        if (!name.isEmpty()) {
                            // 去掉 .json 后缀，添加 "user:" 前缀区分
                            String displayName = name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
                            result.add("user:" + displayName);
                        }
                    }
                }
                log.info("从 ComfyUI 获取到 {} 个用户工作流", result.size());
            } else {
                log.debug("ComfyUI 用户工作流 API 返回状态 {}: 可能版本不支持", response.statusCode());
            }
        } catch (Exception e) {
            log.debug("无法获取 ComfyUI 用户工作流（可能版本不支持）: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 获取 ComfyUI 插件提供的工作流模板列表。
     *
     * @return "PluginName/TemplateName" 格式的列表
     */
    public static List<String> listPluginTemplates(String apiBaseUrl, HttpClient httpClient) {
        List<String> result = new ArrayList<>();
        String base = normalizeBaseUrl(apiBaseUrl);
        String url = base + "/workflow_templates";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = MAPPER.readTree(response.body());
                if (root.isObject()) {
                    for (Iterator<Map.Entry<String, JsonNode>> it = root.fields(); it.hasNext(); ) {
                        Map.Entry<String, JsonNode> entry = it.next();
                        String plugin = entry.getKey();
                        JsonNode templates = entry.getValue();
                        if (templates.isArray()) {
                            for (JsonNode t : templates) {
                                result.add(plugin + "/" + t.asText());
                            }
                        }
                    }
                }
                log.info("从 ComfyUI 获取到 {} 个插件工作流模板", result.size());
            }
        } catch (Exception e) {
            log.warn("无法从 ComfyUI 获取插件工作流模板列表: {}", e.getMessage());
        }
        return result;
    }

    // ─────────────── 工作流加载 ───────────────

    /**
     * 加载指定名称的工作流，自动转换为 API 格式。
     * 支持三种来源：
     * 1. "user:xxx" 前缀 → 从 ComfyUI User Data API 加载用户保存的工作流
     * 2. 从 ComfyUI 文件系统读取插件模板
     * 3. 从 classpath 加载内置模板
     */
    public static ObjectNode loadWorkflow(String apiBaseUrl, HttpClient httpClient, String name) {
        String base = normalizeBaseUrl(apiBaseUrl);

        // 1. 用户工作流（"user:xxx" 前缀）→ 通过 User Data API 加载
        if (name != null && name.startsWith("user:")) {
            String userWfName = name.substring(5); // 去掉 "user:" 前缀
            ObjectNode fromUserData = loadFromUserDataApi(base, httpClient, userWfName);
            if (fromUserData != null) {
                return ensureApiFormat(fromUserData, "User Data API");
            }
            log.warn("无法从 ComfyUI User Data API 加载工作流: {}", userWfName);
            return null;
        }

        // 2. 尝试从 ComfyUI 文件系统读取（模板以 UI 格式存储，需转换）
        ObjectNode fromFs = loadFromComfyUiFs(apiBaseUrl, name);
        if (fromFs != null) {
            ObjectNode apiFormat = convertUiToApiFormat(fromFs);
            if (apiFormat != null) {
                log.info("从 ComfyUI 文件系统加载并转换工作流: {}", name);
                return apiFormat;
            }
            // 如果转换失败，可能是已经是 API 格式
            if (fromFs.has("1") || fromFs.has("2")) {
                log.info("从 ComfyUI 文件系统加载工作流（已是 API 格式）: {}", name);
                return fromFs;
            }
        }
        // 3. 尝试从 classpath 加载
        ObjectNode fromCp = loadFromClasspath(name);
        if (fromCp != null) {
            // classpath 中的可能是 API 格式或 UI 格式
            if (fromCp.has("nodes")) {
                ObjectNode apiFormat = convertUiToApiFormat(fromCp);
                if (apiFormat != null) return apiFormat;
            }
            return fromCp;
        }
        log.debug("未找到工作流模板: {}", name);
        return null;
    }

    /**
     * 加载默认工作流：优先从 ComfyUI 服务器获取用户的第一个工作流，
     * 找不到时才回退到 classpath 内置模板。
     * 用于用户未指定具体工作流文件时的默认行为。
     *
     * @param fallbackClasspathName classpath 中的回退模板名（如 "image_z_image_turbo.json"）
     */
    public static ObjectNode loadDefaultWorkflow(String apiBaseUrl, HttpClient httpClient,
                                                  String fallbackClasspathName, String configType) {
        String base = normalizeBaseUrl(apiBaseUrl);

        // 1. 优先从 ComfyUI User Data API 获取用户保存的工作流
        List<String> userWorkflows = listUserWorkflows(base, httpClient);
        if (!userWorkflows.isEmpty()) {
            // 根据 configType 尝试匹配合适的工作流
            String matched = findBestMatch(userWorkflows, configType);
            if (matched != null) {
                String wfName = matched.substring(5); // 去掉 "user:" 前缀
                ObjectNode fromUserData = loadFromUserDataApi(base, httpClient, wfName);
                if (fromUserData != null) {
                    log.info("使用用户 ComfyUI 工作流: {}", matched);
                    return ensureApiFormat(fromUserData, "User Data API");
                }
            }
        }

        // 2. 尝试获取插件模板中的第一个
        List<String> pluginTemplates = listPluginTemplates(base, httpClient);
        if (!pluginTemplates.isEmpty()) {
            String firstTemplate = pluginTemplates.get(0);
            ObjectNode fromFs = loadFromComfyUiFs(base, firstTemplate);
            if (fromFs != null) {
                ObjectNode apiFormat = convertUiToApiFormat(fromFs);
                if (apiFormat != null) {
                    log.info("使用 ComfyUI 插件模板: {}", firstTemplate);
                    return apiFormat;
                }
                if (fromFs.has("1") || fromFs.has("2")) {
                    log.info("使用 ComfyUI 插件模板（已是 API 格式）: {}", firstTemplate);
                    return fromFs;
                }
            }
        }

        // 3. 回退到 classpath 内置模板
        ObjectNode fromCp = loadFromClasspath(fallbackClasspathName);
        if (fromCp != null) {
            if (fromCp.has("nodes")) {
                ObjectNode apiFormat = convertUiToApiFormat(fromCp);
                if (apiFormat != null) return apiFormat;
            }
            log.info("回退使用 classpath 内置模板: {}", fallbackClasspathName);
            return fromCp;
        }

        log.warn("未找到任何可用工作流（ComfyUI 服务器无工作流，classpath 模板 {} 也不存在）", fallbackClasspathName);
        return null;
    }

    /**
     * 从用户工作流列表中找到最匹配的一个。
     * 优先匹配包含 configType 关键词（image/video）的工作流，否则返回第一个。
     */
    private static String findBestMatch(List<String> workflows, String configType) {
        if (workflows.isEmpty()) return null;
        if (configType == null || configType.isBlank()) return workflows.get(0);

        String lower = configType.toLowerCase();
        // 优先找名称中包含 configType 的
        for (String wf : workflows) {
            if (wf.toLowerCase().contains(lower)) {
                return wf;
            }
        }
        // 没有匹配的，返回第一个
        return workflows.get(0);
    }

    /**
     * 确保工作流为 API 格式，如需则自动转换。
     */
    private static ObjectNode ensureApiFormat(ObjectNode workflow, String source) {
        if (workflow.has("nodes")) {
            ObjectNode apiFormat = convertUiToApiFormat(workflow);
            if (apiFormat != null) {
                log.info("从 {} 加载并转换工作流为 API 格式", source);
                return apiFormat;
            }
        }
        log.info("从 {} 加载工作流（已是 API 格式）", source);
        return workflow;
    }

    // ─────────────── UI → API 格式转换 ───────────────

    /**
     * 将 ComfyUI UI 格式 (nodes/links) 转换为 API 格式 (class_type/inputs)。
     */
    public static ObjectNode convertUiToApiFormat(ObjectNode uiWorkflow) {
        if (!uiWorkflow.has("nodes")) return null;
        try {
            JsonNode nodesArr = uiWorkflow.path("nodes");
            JsonNode linksArr = uiWorkflow.path("links");

            // 构建 link 映射: linkId → [sourceNodeId, sourceSlotIndex]
            Map<Integer, int[]> linkMap = new HashMap<>();
            if (linksArr.isArray()) {
                for (JsonNode link : linksArr) {
                    // [linkId, sourceNodeId, sourceSlot, targetNodeId, targetSlot, type]
                    int linkId = link.get(0).asInt();
                    int srcNode = link.get(1).asInt();
                    int srcSlot = link.get(2).asInt();
                    linkMap.put(linkId, new int[]{srcNode, srcSlot});
                }
            }

            ObjectNode apiWorkflow = MAPPER.createObjectNode();

            for (JsonNode node : nodesArr) {
                String nodeId = String.valueOf(node.get("id").asInt());
                String classType = node.get("type").asText();

                ObjectNode apiNode = MAPPER.createObjectNode();
                apiNode.put("class_type", classType);

                // 构建 inputs
                ObjectNode inputs = MAPPER.createObjectNode();
                JsonNode nodeInputs = node.path("inputs");
                if (nodeInputs.isArray()) {
                    for (JsonNode inp : nodeInputs) {
                        String inpName = inp.path("name").asText("");
                        if (inpName.isEmpty()) continue;

                        if (inp.has("link")) {
                            // 连接的输入 → [sourceNodeId, sourceSlotIndex]
                            int linkId = inp.get("link").asInt();
                            int[] src = linkMap.get(linkId);
                            if (src != null) {
                                ArrayNode ref = MAPPER.createArrayNode();
                                ref.add(String.valueOf(src[0]));
                                ref.add(src[1]);
                                inputs.set(inpName, ref);
                            }
                        } else if (inp.has("value")) {
                            // 固定值输入
                            inputs.set(inpName, inp.get("value"));
                        }
                    }
                }

                // 有些节点的 widget 值在 "widgets_values" 中
                JsonNode widgetValues = node.path("widgets_values");
                if (widgetValues.isArray()) {
                    // 需要根据节点类型的 widget 定义来映射名称
                    // 简单策略：按顺序用 widget 名称（从 object_info 获取太复杂）
                    // 这里用已知的常见映射
                    injectWidgetValues(inputs, classType, widgetValues);
                }

                apiNode.set("inputs", inputs);
                apiWorkflow.set(nodeId, apiNode);
            }

            return apiWorkflow;
        } catch (Exception e) {
            log.warn("UI → API 格式转换失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 将 widgets_values 按常见节点类型映射到 inputs 字段名。
     */
    private static void injectWidgetValues(ObjectNode inputs, String classType, JsonNode widgetValues) {
        // 常见节点的 widget 名称映射
        Map<String, String[]> widgetNames = Map.ofEntries(
                Map.entry("KSampler", new String[]{"seed", "steps", "cfg", "sampler_name", "scheduler", "denoise"}),
                Map.entry("KSamplerAdvanced", new String[]{"noise_seed", "steps", "cfg", "sampler_name", "scheduler", "start_at_step", "end_at_step", "return_with_leftover_noise"}),
                Map.entry("CheckpointLoaderSimple", new String[]{"ckpt_name"}),
                Map.entry("LoraLoader", new String[]{"lora_name", "strength_model", "strength_clip"}),
                Map.entry("CLIPTextEncode", new String[]{"text"}),
                Map.entry("EmptyLatentImage", new String[]{"width", "height", "batch_size"}),
                Map.entry("SaveImage", new String[]{"filename_prefix"}),
                Map.entry("PreviewImage", new String[]{}),
                Map.entry("LoadImage", new String[]{"image", "upload"}),
                Map.entry("VAEDecode", new String[]{}),
                Map.entry("VAEEncode", new String[]{}),
                Map.entry("ControlNetLoader", new String[]{"control_net_name"}),
                Map.entry("ControlNetApply", new String[]{"strength"}),
                Map.entry("ControlNetApplyAdvanced", new String[]{"strength", "start_percent", "end_percent"}),
                Map.entry("CLIPSetLastLayer", new String[]{"stop_at_clip_layer"}),
                Map.entry("VAELoader", new String[]{"vae_name"}),
                Map.entry("UpscaleModelLoader", new String[]{"model_name"}),
                Map.entry("ImageUpscaleWithModel", new String[]{}),
                Map.entry("ImageScale", new String[]{"upscale_method", "width", "height", "crop"}),
                Map.entry("LatentUpscale", new String[]{"upscale_method", "width", "height", "crop"}),
                Map.entry("LatentUpscaleBy", new String[]{"upscale_method", "scale_by"}),
                Map.entry("UNETLoader", new String[]{"unet_name", "weight_dtype"}),
                Map.entry("DualCLIPLoader", new String[]{"clip_name1", "clip_name2", "type"}),
                Map.entry("CLIPLoader", new String[]{"clip_name", "type"}),
                Map.entry("StyleModelLoader", new String[]{"style_model_name"}),
                Map.entry("CLIPVisionLoader", new String[]{"clip_name"}),
                Map.entry("IPAdapterModelLoader", new String[]{"ipadapter_file"}),
                // VHS nodes
                Map.entry("VHS_VideoCombine", new String[]{"frame_rate", "loop_count", "filename_prefix", "format"}),
                Map.entry("VHS_LoadVideo", new String[]{"video", "force_rate", "frame_load_cap", "skip_first_frames", "select_every_nth"}),
                // WanVideo nodes
                Map.entry("WanVideoModelLoader", new String[]{"model", "precision"}),
                Map.entry("WanVideoTextEncode", new String[]{"positive_prompt", "negative_prompt"}),
                Map.entry("WanVideoImageEncode", new String[]{}),
                Map.entry("WanVideoSampler", new String[]{"steps", "cfg", "seed", "width", "height", "num_frames"}),
                // LTX nodes
                Map.entry("LTXVLoader", new String[]{"ckpt_name"}),
                Map.entry("LTXVConditioning", new String[]{"frame_rate", "width", "height", "num_frames", "batch_size"}),
                Map.entry("LTXVScheduler", new String[]{"steps", "max_shift", "base_shift", "stretch", "terminal"}),
                Map.entry("LTXVSampler", new String[]{"seed", "steps", "cfg"})
        );

        String[] names = widgetNames.get(classType);
        if (names == null) {
            // 未知节点类型，尝试用通用策略
            // 如果只有 1 个 widget 值且 inputs 为空，可能是模型名
            if (widgetValues.size() == 1 && inputs.size() == 0) {
                JsonNode val = widgetValues.get(0);
                if (val.isTextual()) {
                    inputs.set("value", val);
                }
            }
            return;
        }

        for (int i = 0; i < Math.min(names.length, widgetValues.size()); i++) {
            String name = names[i];
            if (!inputs.has(name)) {
                inputs.set(name, widgetValues.get(i));
            }
        }
    }

    // ─────────────── User Data API 读取 ───────────────

    /**
     * 通过 ComfyUI User Data API 加载用户保存的工作流。
     * ComfyUI 0.8+ 版本支持此 API。
     */
    private static ObjectNode loadFromUserDataApi(String apiBaseUrl, HttpClient httpClient, String workflowName) {
        String fileName = workflowName.endsWith(".json") ? workflowName : workflowName + ".json";
        String url = apiBaseUrl + "/get_user_data?file=workflows/" + fileName;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode node = MAPPER.readTree(response.body());
                if (node.isObject()) {
                    log.info("从 ComfyUI User Data API 加载工作流: {}", workflowName);
                    return (ObjectNode) node;
                }
            } else {
                log.debug("ComfyUI User Data API 返回状态 {} for {}", response.statusCode(), workflowName);
            }
        } catch (Exception e) {
            log.debug("无法从 ComfyUI User Data API 加载工作流 {}: {}", workflowName, e.getMessage());
        }
        return null;
    }

    // ─────────────── 文件系统读取 ───────────────

    /**
     * 尝试从 ComfyUI 安装目录读取工作流模板文件。
     * 模板存储在 custom_nodes/{plugin}/example_workflows/{name}.json
     */
    private static ObjectNode loadFromComfyUiFs(String apiBaseUrl, String name) {
        // name 格式: "PluginName/TemplateName" 或 "TemplateName"
        String pluginName = null;
        String templateName = name;
        if (name.contains("/")) {
            String[] parts = name.split("/", 2);
            pluginName = parts[0];
            templateName = parts[1];
        }

        // 常见的 ComfyUI 安装路径
        String[] comfyUiPaths = detectComfyUiPaths(apiBaseUrl);

        for (String comfyPath : comfyUiPaths) {
            Path customNodes = Path.of(comfyPath, "custom_nodes");
            if (!Files.isDirectory(customNodes)) continue;

            if (pluginName != null) {
                // 有插件名，直接定位
                Path templateFile = findTemplateFile(customNodes, pluginName, templateName);
                if (templateFile != null) {
                    return readJsonFile(templateFile);
                }
            } else {
                // 没有插件名，搜索所有插件
                try (var stream = Files.list(customNodes)) {
                    for (Path pluginDir : stream.filter(Files::isDirectory).toList()) {
                        Path templateFile = findTemplateFile(customNodes, pluginDir.getFileName().toString(), templateName);
                        if (templateFile != null) {
                            return readJsonFile(templateFile);
                        }
                    }
                } catch (IOException ignored) {
                }
            }
        }
        return null;
    }

    private static Path findTemplateFile(Path customNodes, String pluginName, String templateName) {
        // 尝试多种路径模式
        String[] suffixes = {".json", ""};
        String[] subdirs = {"example_workflows", "workflows", "workflow_templates"};

        for (String subdir : subdirs) {
            for (String suffix : suffixes) {
                Path file = customNodes.resolve(pluginName).resolve(subdir).resolve(templateName + suffix);
                if (Files.isRegularFile(file)) return file;
            }
        }
        // 直接在插件根目录
        for (String suffix : suffixes) {
            Path file = customNodes.resolve(pluginName).resolve(templateName + suffix);
            if (Files.isRegularFile(file)) return file;
        }
        return null;
    }

    private static String[] detectComfyUiPaths(String apiBaseUrl) {
        // 从系统信息推断 ComfyUI 路径，或扫描常见位置
        List<String> paths = new ArrayList<>();

        // 从 apiBaseUrl 推断（不太可靠，但作为 hint）
        // 扫描常见安装位置
        String[] candidates = {
                "D:\\Projects\\ComfyUI-aki\\ComfyUI",
                "D:\\ComfyUI",
                "C:\\ComfyUI",
                System.getProperty("user.home") + "\\ComfyUI",
                System.getProperty("user.home") + "\\Desktop\\ComfyUI",
                "/opt/ComfyUI",
                "/home/" + System.getProperty("user.name") + "/ComfyUI",
        };
        for (String p : candidates) {
            if (Files.isDirectory(Path.of(p, "custom_nodes"))) {
                paths.add(p);
            }
        }
        return paths.toArray(new String[0]);
    }

    // ─────────────── Prompt 注入 ───────────────

    /**
     * 向工作流注入文本 prompt：找到第一个含 "text" 输入的 CLIPTextEncode 节点。
     */
    public static void injectPrompt(ObjectNode workflow, String prompt) {
        for (Iterator<Map.Entry<String, JsonNode>> it = workflow.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            JsonNode node = entry.getValue();
            if (node.isObject()) {
                String classType = node.path("class_type").asText("");
                if ("CLIPTextEncode".equals(classType)) {
                    JsonNode inputs = node.path("inputs");
                    if (inputs.has("text")) {
                        ((ObjectNode) node.path("inputs")).put("text", prompt);
                        return;
                    }
                }
            }
        }
        // 其他常见的文本输入节点
        for (Iterator<Map.Entry<String, JsonNode>> it = workflow.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            JsonNode node = entry.getValue();
            if (node.isObject()) {
                JsonNode inputs = node.path("inputs");
                if (inputs.has("prompt") && inputs.path("prompt").isTextual()) {
                    ((ObjectNode) inputs).put("prompt", prompt);
                    return;
                }
                if (inputs.has("positive_prompt") && inputs.path("positive_prompt").isTextual()) {
                    ((ObjectNode) inputs).put("positive_prompt", prompt);
                    return;
                }
                if (inputs.has("text") && inputs.path("text").isTextual()) {
                    ((ObjectNode) inputs).put("text", prompt);
                    return;
                }
            }
        }
        log.warn("未找到可注入 prompt 的节点");
    }

    /**
     * 向工作流注入图片 URL：找到第一个 LoadImage 节点。
     */
    public static void injectImage(ObjectNode workflow, String imageUrl) {
        for (Iterator<Map.Entry<String, JsonNode>> it = workflow.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            JsonNode node = entry.getValue();
            if (node.isObject()) {
                String classType = node.path("class_type").asText("");
                if ("LoadImage".equals(classType)) {
                    ((ObjectNode) node.path("inputs")).put("image", imageUrl);
                    return;
                }
            }
        }
        log.warn("未找到可注入 image 的 LoadImage 节点");
    }

    // ─────────────── Classpath 加载 ───────────────

    private static ObjectNode loadFromClasspath(String name) {
        String[] searchPaths = {
                "comfyui/workflows/" + name,
                name
        };
        for (String path : searchPaths) {
            try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
                if (is != null) {
                    String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    ObjectNode workflow = (ObjectNode) MAPPER.readTree(json);
                    log.info("从 classpath 加载工作流模板: {}", path);
                    return workflow;
                }
            } catch (IOException e) {
                log.warn("加载工作流模板失败: {} - {}", path, e.getMessage());
            }
        }
        return null;
    }

    private static ObjectNode readJsonFile(Path file) {
        try {
            String json = Files.readString(file);
            JsonNode node = MAPPER.readTree(json);
            if (node.isObject()) {
                return (ObjectNode) node;
            }
        } catch (IOException e) {
            log.warn("读取工作流文件失败: {} - {}", file, e.getMessage());
        }
        return null;
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return "http://localhost:8188";
        String normalized = baseUrl.trim();
        if (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }
}
