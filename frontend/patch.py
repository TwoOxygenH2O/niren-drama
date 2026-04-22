import sys
import os

fpath = r"d:\pythonProject\niren-drama\backend\src\main\java\com\niren\drama\service\StoryboardService.java"
with open(fpath, "r", encoding="utf-8") as f:
    text = f.read()

target = "    private List<Storyboard> parseStoryboardJson(String json, StoryboardGenerateRequest request, boolean strict) {"
val = """    private String normalizeStoryboardPreviewContent(String content) {
        if (content == null) return "{}";
        int startObj = content.indexOf('{');
        int startArr = content.indexOf('[');
        if (startObj == -1 && startArr == -1) {
            return content;
        }
        int startIdx = (startObj != -1 && startArr != -1) ? Math.min(startObj, startArr) : Math.max(startObj, startArr);
        int endObj = content.lastIndexOf('}');
        int endArr = content.lastIndexOf(']');
        int endIdx = Math.max(endObj, endArr);
        if (endIdx > startIdx) {
            return content.substring(startIdx, endIdx + 1);
        }
        return content.substring(startIdx);
    }

    private com.fasterxml.jackson.databind.JsonNode extractStoryboardRoot(String json) throws java.io.IOException {
        String cleanJson = json.replace("```json", "").replace("```", "").trim();
        return objectMapper.readTree(cleanJson);
    }

    private com.fasterxml.jackson.databind.JsonNode resolveShotsNode(com.fasterxml.jackson.databind.JsonNode root) {
        if (root.isArray()) return root;
        if (root.has("shots") && root.get("shots").isArray()) return root.get("shots");
        if (root.has("分镜") && root.get("分镜").isArray()) return root.get("分镜");
        return root;
    }

    private String textOrNull(com.fasterxml.jackson.databind.JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }

    private boolean hasText(String str) {
        return str != null && !str.trim().isEmpty();
    }

    private List<Storyboard> parseStoryboardJson(String json, StoryboardGenerateRequest request, boolean strict) {"""

if target in text:
    text = text.replace(target, val)
    with open(fpath, "w", encoding="utf-8") as f:
        f.write(text)
    print("PATCH_DONE")
else:
    print("TARGET_NOT_FOUND")
