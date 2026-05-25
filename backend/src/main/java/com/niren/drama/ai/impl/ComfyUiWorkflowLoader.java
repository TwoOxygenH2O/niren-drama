package com.niren.drama.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
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

    private record LinkRef(String nodeId, int slot) {
    }

    private ComfyUiWorkflowLoader() {
    }

    // ─────────────── 工作流列表 ───────────────

    /**
     * 从 ComfyUI 服务器获取当前用户保存的工作流列表。
     *
     * @return 用户工作流以 "user:workflowName" 格式返回
     */
    public static List<String> listWorkflows(String apiBaseUrl, HttpClient httpClient) {
        return listUserWorkflows(apiBaseUrl, httpClient);
    }

    /**
     * 获取用户在 ComfyUI 中保存的工作流（通过 User Data API）。
     * ComfyUI 0.9.6 使用 /api/userdata?dir=workflows 列出 user/default/workflows 目录。
     *
     * @return "user:workflowName" 格式的列表
     */
    public static List<String> listUserWorkflows(String apiBaseUrl, HttpClient httpClient) {
        List<String> result = new ArrayList<>();
        String base = normalizeBaseUrl(apiBaseUrl);
        String url = base + "/api/userdata?dir=workflows";
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
     * 2. 从 classpath 加载内置模板
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

        // 2. 尝试从 classpath 加载
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

        // 2. 回退到 classpath 内置模板
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
            Map<String, JsonNode> subgraphs = collectSubgraphs(uiWorkflow);
            Map<Integer, LinkRef> linkMap = buildTopLevelLinkMap(uiWorkflow.path("links"));
            Map<String, LinkRef> subgraphOutputMap = buildSubgraphOutputMap(nodesArr, subgraphs);

            ObjectNode apiWorkflow = MAPPER.createObjectNode();

            for (JsonNode node : nodesArr) {
                String nodeId = String.valueOf(node.get("id").asInt());
                String classType = node.get("type").asText();
                if (isUiOnlyNode(classType)) {
                    continue;
                }
                JsonNode subgraph = subgraphs.get(classType);
                if (subgraph != null) {
                    expandSubgraph(apiWorkflow, node, subgraph, linkMap);
                    continue;
                }

                addApiNode(apiWorkflow, nodeId, classType, node, linkMap, subgraphOutputMap);
            }

            return apiWorkflow;
        } catch (Exception e) {
            log.warn("UI → API 格式转换失败: {}", e.getMessage());
            return null;
        }
    }

    private static Map<String, JsonNode> collectSubgraphs(ObjectNode uiWorkflow) {
        Map<String, JsonNode> subgraphs = new HashMap<>();
        JsonNode subgraphsArr = uiWorkflow.path("definitions").path("subgraphs");
        if (subgraphsArr.isArray()) {
            for (JsonNode subgraph : subgraphsArr) {
                String id = subgraph.path("id").asText("");
                if (!id.isEmpty()) {
                    subgraphs.put(id, subgraph);
                }
            }
        }
        return subgraphs;
    }

    private static Map<Integer, LinkRef> buildTopLevelLinkMap(JsonNode linksArr) {
        Map<Integer, LinkRef> linkMap = new HashMap<>();
        if (linksArr.isArray()) {
            for (JsonNode link : linksArr) {
                int linkId = link.get(0).asInt();
                String srcNode = String.valueOf(link.get(1).asInt());
                int srcSlot = link.get(2).asInt();
                linkMap.put(linkId, new LinkRef(srcNode, srcSlot));
            }
        }
        return linkMap;
    }

    private static Map<String, LinkRef> buildSubgraphOutputMap(JsonNode nodesArr, Map<String, JsonNode> subgraphs) {
        Map<String, LinkRef> outputMap = new HashMap<>();
        for (JsonNode node : nodesArr) {
            String classType = node.path("type").asText("");
            JsonNode subgraph = subgraphs.get(classType);
            if (subgraph == null) {
                continue;
            }
            String nodeId = String.valueOf(node.get("id").asInt());
            Map<Integer, LinkRef> internalLinks = buildSubgraphLinkMap(nodeId, subgraph);
            JsonNode outputs = node.path("outputs");
            if (outputs.isArray()) {
                for (int i = 0; i < outputs.size(); i++) {
                    JsonNode links = outputs.get(i).path("links");
                    JsonNode subgraphOutput = subgraph.path("outputs").path(i);
                    LinkRef source = findSubgraphOutputSource(subgraphOutput, internalLinks);
                    if (source == null) {
                        continue;
                    }
                    if (links.isArray()) {
                        for (JsonNode link : links) {
                            outputMap.put(nodeId + ":" + link.asInt(), source);
                        }
                    }
                    outputMap.put(nodeId + ":" + i, source);
                }
            }
        }
        return outputMap;
    }

    private static LinkRef findSubgraphOutputSource(JsonNode subgraphOutput, Map<Integer, LinkRef> internalLinks) {
        JsonNode linkIds = subgraphOutput.path("linkIds");
        if (linkIds.isArray()) {
            for (JsonNode linkId : linkIds) {
                LinkRef source = internalLinks.get(linkId.asInt());
                if (source != null) {
                    return source;
                }
            }
        }
        return null;
    }

    private static Map<Integer, LinkRef> buildSubgraphLinkMap(String parentNodeId, JsonNode subgraph) {
        Map<Integer, LinkRef> linkMap = new HashMap<>();
        JsonNode links = subgraph.path("links");
        if (links.isArray()) {
            for (JsonNode link : links) {
                int originId = link.path("origin_id").asInt();
                if (originId < 0) {
                    continue;
                }
                int linkId = link.path("id").asInt();
                int originSlot = link.path("origin_slot").asInt();
                linkMap.put(linkId, new LinkRef(parentNodeId + "_" + originId, originSlot));
            }
        }
        return linkMap;
    }

    private static void expandSubgraph(ObjectNode apiWorkflow, JsonNode proxyNode, JsonNode subgraph,
                                       Map<Integer, LinkRef> topLevelLinks) {
        String parentNodeId = String.valueOf(proxyNode.get("id").asInt());
        Map<Integer, LinkRef> internalLinks = buildSubgraphLinkMap(parentNodeId, subgraph);
        Map<Integer, JsonNode> subgraphInputValues = buildSubgraphInputValues(proxyNode, subgraph, topLevelLinks);

        JsonNode nodes = subgraph.path("nodes");
        if (!nodes.isArray()) {
            return;
        }
        for (JsonNode node : nodes) {
            String classType = node.path("type").asText("");
            if (isUiOnlyNode(classType)) {
                continue;
            }
            String nodeId = parentNodeId + "_" + node.get("id").asInt();
            ObjectNode apiNode = MAPPER.createObjectNode();
            apiNode.put("class_type", classType);

            ObjectNode inputs = MAPPER.createObjectNode();
            JsonNode nodeInputs = node.path("inputs");
            if (nodeInputs.isArray()) {
                for (JsonNode input : nodeInputs) {
                    String inputName = input.path("name").asText("");
                    if (inputName.isEmpty()) {
                        continue;
                    }
                    if (input.has("link")) {
                        int linkId = input.get("link").asInt();
                        LinkRef ref = internalLinks.get(linkId);
                        if (ref != null) {
                            setLinkRef(inputs, inputName, ref);
                        } else {
                            JsonNode value = subgraphInputValues.get(linkId);
                            if (value != null && !value.isMissingNode() && !value.isNull()) {
                                inputs.set(inputName, value);
                            }
                        }
                    } else if (input.has("value")) {
                        inputs.set(inputName, input.get("value"));
                    }
                }
            }

            JsonNode widgetValues = node.path("widgets_values");
            if (widgetValues.isArray()) {
                injectWidgetValues(inputs, nodeInputs, classType, widgetValues);
            }

            apiNode.set("inputs", inputs);
            apiWorkflow.set(nodeId, apiNode);
        }
    }

    private static Map<Integer, JsonNode> buildSubgraphInputValues(JsonNode proxyNode, JsonNode subgraph,
                                                                   Map<Integer, LinkRef> topLevelLinks) {
        Map<Integer, JsonNode> values = new HashMap<>();
        Map<String, JsonNode> proxyInputs = buildProxyInputValues(proxyNode, topLevelLinks);
        JsonNode inputs = subgraph.path("inputs");
        if (inputs.isArray()) {
            for (JsonNode input : inputs) {
                String name = input.path("name").asText("");
                JsonNode value = proxyInputs.get(name);
                if (value == null) {
                    continue;
                }
                JsonNode linkIds = input.path("linkIds");
                if (linkIds.isArray()) {
                    for (JsonNode linkId : linkIds) {
                        values.put(linkId.asInt(), value);
                    }
                }
            }
        }
        return values;
    }

    private static Map<String, JsonNode> buildProxyInputValues(JsonNode proxyNode, Map<Integer, LinkRef> topLevelLinks) {
        Map<String, JsonNode> values = new HashMap<>();
        JsonNode proxyInputs = proxyNode.path("inputs");
        if (proxyInputs.isArray()) {
            for (JsonNode input : proxyInputs) {
                String name = input.path("name").asText("");
                if (name.isEmpty()) {
                    continue;
                }
                if (input.has("link")) {
                    LinkRef ref = topLevelLinks.get(input.get("link").asInt());
                    if (ref != null) {
                        ArrayNode arr = MAPPER.createArrayNode();
                        arr.add(ref.nodeId());
                        arr.add(ref.slot());
                        values.put(name, arr);
                    }
                } else if (input.has("value")) {
                    values.put(name, input.get("value"));
                }
            }
        }
        JsonNode widgetValues = proxyNode.path("widgets_values");
        if (widgetValues.isArray()) {
            for (int i = 0; i < Math.min(proxyInputs.size(), widgetValues.size()); i++) {
                String name = proxyInputs.get(i).path("widget").path("name").asText("");
                if (!name.isEmpty() && !values.containsKey(name)) {
                    values.put(name, widgetValues.get(i));
                }
            }
        }
        return values;
    }

    private static void addApiNode(ObjectNode apiWorkflow, String nodeId, String classType, JsonNode node,
                                   Map<Integer, LinkRef> linkMap, Map<String, LinkRef> subgraphOutputMap) {
        ObjectNode apiNode = MAPPER.createObjectNode();
        apiNode.put("class_type", classType);

        ObjectNode inputs = MAPPER.createObjectNode();
        JsonNode nodeInputs = node.path("inputs");
        if (nodeInputs.isArray()) {
            for (JsonNode inp : nodeInputs) {
                String inpName = inp.path("name").asText("");
                if (inpName.isEmpty()) continue;

                if (inp.has("link")) {
                    int linkId = inp.get("link").asInt();
                    LinkRef ref = linkMap.get(linkId);
                    if (ref != null) {
                        LinkRef rewritten = subgraphOutputMap.getOrDefault(ref.nodeId() + ":" + linkId,
                                subgraphOutputMap.getOrDefault(ref.nodeId() + ":" + ref.slot(), ref));
                        setLinkRef(inputs, inpName, rewritten);
                    }
                } else if (inp.has("value")) {
                    inputs.set(inpName, inp.get("value"));
                }
            }
        }

        JsonNode widgetValues = node.path("widgets_values");
        if (widgetValues.isArray()) {
            injectWidgetValues(inputs, nodeInputs, classType, widgetValues);
        }

        apiNode.set("inputs", inputs);
        apiWorkflow.set(nodeId, apiNode);
    }

    private static void setLinkRef(ObjectNode inputs, String inputName, LinkRef ref) {
        ArrayNode arr = MAPPER.createArrayNode();
        arr.add(ref.nodeId());
        arr.add(ref.slot());
        inputs.set(inputName, arr);
    }

    private static boolean isUiOnlyNode(String classType) {
        return "MarkdownNote".equals(classType) || "Note".equals(classType);
    }

    /**
     * 将 widgets_values 按常见节点类型映射到 inputs 字段名。
     */
    private static void injectWidgetValues(ObjectNode inputs, JsonNode nodeInputs, String classType, JsonNode widgetValues) {
        // 常见节点的 widget 名称映射
        Map<String, String[]> widgetNames = Map.ofEntries(
                Map.entry("KSampler", new String[]{"seed", "control_after_generate", "steps", "cfg", "sampler_name", "scheduler", "denoise"}),
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
                Map.entry("LTXVSampler", new String[]{"seed", "steps", "cfg"}),
                Map.entry("ModelSamplingAuraFlow", new String[]{"shift"}),
                Map.entry("CLIPVisionEncode", new String[]{"crop"}),
                Map.entry("HunyuanVideo15ImageToVideo", new String[]{"width", "height", "batch_size", "length"}),
                Map.entry("EasyCache", new String[]{"verbose", "start_percent", "reuse_threshold", "end_percent"}),
                Map.entry("ModelSamplingSD3", new String[]{"shift"}),
                Map.entry("CFGGuider", new String[]{"cfg"}),
                Map.entry("BasicScheduler", new String[]{"scheduler", "steps", "denoise"}),
                Map.entry("CreateVideo", new String[]{"fps"}),
                Map.entry("SaveVideo", new String[]{"format", "codec"}),
                Map.entry("HunyuanVideo15LatentUpscaleWithModel", new String[]{"upscale_method", "width", "height", "crop"}),
                Map.entry("HunyuanVideo15SuperResolution", new String[]{"noise_augmentation"}),
                Map.entry("SplitSigmas", new String[]{"step"})
        );

        String[] names = widgetNames.get(classType);
        if ("SaveVideo".equals(classType)) {
            injectSaveVideoWidgetValues(inputs, widgetValues);
            return;
        }
        if (names == null) {
            for (int i = 0; i < Math.min(nodeInputs.size(), widgetValues.size()); i++) {
                String inputName = nodeInputs.get(i).path("widget").path("name").asText("");
                if (!inputName.isEmpty() && !inputs.has(inputName)) {
                    inputs.set(inputName, widgetValues.get(i));
                }
            }
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

    private static void injectSaveVideoWidgetValues(ObjectNode inputs, JsonNode widgetValues) {
        if (!inputs.has("format")) {
            inputs.put("format", "mp4");
        }
        if (!inputs.has("codec")) {
            inputs.put("codec", resolveSaveVideoCodec(widgetValues));
        }
        if (!inputs.has("filename_prefix")) {
            inputs.put("filename_prefix", resolveSaveVideoFilenamePrefix(widgetValues));
        }
    }

    private static String resolveSaveVideoFilenamePrefix(JsonNode widgetValues) {
        for (JsonNode value : widgetValues) {
            if (value.isTextual()) {
                String text = value.asText();
                if (!"h264".equalsIgnoreCase(text) && !"h265".equalsIgnoreCase(text) && !"vp9".equalsIgnoreCase(text)) {
                    return text;
                }
            }
        }
        return "ComfyUI";
    }

    private static String resolveSaveVideoCodec(JsonNode widgetValues) {
        for (JsonNode value : widgetValues) {
            if (value.isTextual()) {
                String text = value.asText();
                if ("h264".equalsIgnoreCase(text) || "h265".equalsIgnoreCase(text) || "vp9".equalsIgnoreCase(text)) {
                    return text;
                }
            }
        }
        return "h264";
    }

    // ─────────────── User Data API 读取 ───────────────

    /**
     * 通过 ComfyUI User Data API 加载用户保存的工作流。
     * ComfyUI 0.9.6 读取文件时把 workflows/name.json 作为 path 参数，斜杠需要 URL 编码。
     */
    private static ObjectNode loadFromUserDataApi(String apiBaseUrl, HttpClient httpClient, String workflowName) {
        String fileName = workflowName.endsWith(".json") ? workflowName : workflowName + ".json";
        String url = apiBaseUrl + "/api/userdata/workflows%2F" + encodePathSegment(fileName);
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

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return "http://127.0.0.1:8188";
        String normalized = baseUrl.trim();
        if (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }
}
