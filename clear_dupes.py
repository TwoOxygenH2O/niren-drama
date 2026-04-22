import sys

fpath = r"d:\pythonProject\niren-drama\backend\src\main\java\com\niren\drama\service\StoryboardService.java"
with open(fpath, "r", encoding="utf-8") as f:
    text = f.read()

val1 = """    private String textOrNull(JsonNode node, String fieldName) {
        if (!node.has(fieldName) || node.get(fieldName).isNull()) {
            return null;
        }
        return node.get(fieldName).asText();
    }"""

val2 = """    private boolean hasText(String text) {
        return text != null && !text.trim().isEmpty();
    }"""

# Actually, the best way to remove the duplicates is simply string replacement of the specific ones
if val1 in text:
    text = text.replace(val1, "")
if val2 in text:
    text = text.replace(val2, "")

with open(fpath, "w", encoding="utf-8") as f:
    f.write(text)
print("CLEARED DUPLICATES")
