import sys

fpath = r"d:\pythonProject\niren-drama\backend\src\main\java\com\niren\drama\service\StoryboardService.java"
with open(fpath, "r", encoding="utf-8") as f:
    text = f.read()

val1 = """    private String textOrNull(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }"""
val2 = """    private boolean hasText(String str) {
        return str != null && !str.trim().isEmpty();
    }"""

if val1 in text:
    text = text.replace(val1, "")
    print("Found and removed var1")

if val2 in text:
    text = text.replace(val2, "")
    print("Found and removed var2")

with open(fpath, "w", encoding="utf-8") as f:
    f.write(text)

