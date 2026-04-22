$file = "d:\pythonProject\niren-drama\backend\src\main\java\com\niren\drama\service\StoryboardService.java"
$content = Get-Content $file -Encoding UTF8 -Raw
$toReplace = @"
    private List<Storyboard> parseStoryboardJson(String json, StoryboardGenerateRequest request, boolean strict) {
"@
$replacement = @"
    private String normalizeStoryboardPreviewContent(String content) {
        if (content == null) return "{}";
        int startObj = content.indexOf('{');
        int startArr = content.indexOf('[');
        if (startObj == -1 && startArr == -1) {
            return content; // fall back to generic text parser
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

    private JsonNode extractStoryboardRoot(String json) throws IOException {
        String cleanJson = json.replace("```json", "").replace("```", "").trim();
        return objectMapper.readTree(cleanJson);
    }

    private JsonNode resolveShotsNode(JsonNode root) {
        if (root.isArray()) return root;
        if (root.has("shots") && root.get("shots").isArray()) return root.get("shots");
        if (root.has("·Ö¾µ") && root.get("·Ö¾µ").isArray()) return root.get("·Ö¾µ");
        return root;
    }

    private String textOrNull(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }

    private boolean hasText(String str) {
        return str != null && !str.trim().isEmpty();
    }

    private List<Storyboard> parseStoryboardJson(String json, StoryboardGenerateRequest request, boolean strict) {
"@
$newContent = $content.Replace($toReplace, $replacement)
Set-Content $file -Value $newContent -Encoding UTF8
