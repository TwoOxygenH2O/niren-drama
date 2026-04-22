import sys
import re

fpath = r"d:\pythonProject\niren-drama\backend\src\main\java\com\niren\drama\service\StoryboardService.java"
with open(fpath, "r", encoding="utf-8") as f:
    text = f.read()

text = text.replace("generateStoryboardPreviewByScenes(textProvider, systemPrompt, script.getContent(), request, chunkConsumer, progressConsumer);", "generateStoryboardPreviewByScenes(textProvider, systemPrompt, script, request, chunkConsumer, progressConsumer);")

patt = r'private void generateStoryboardPreviewByScenes\([^)]*String scriptContent,\s*StoryboardGenerateRequest request,\s*java\.util\.function\.Consumer<String>\s+chunkConsumer,\s*java\.util\.function\.Consumer<String>\s+progressConsumer\)\s*\{\s*List<ScriptScene>\s+scenes\s*=\s*splitScriptScenes\(scriptContent\);'
repl = """private void generateStoryboardPreviewByScenes(TextAiProvider textProvider,
                                                   String systemPrompt,
                                                   Script script,
                                                   StoryboardGenerateRequest request,
                                                   java.util.function.Consumer<String> chunkConsumer,
                                                   java.util.function.Consumer<String> progressConsumer) {
        List<ScriptScene> scenes = splitScriptScenes(script.getContent());"""

text = re.sub(patt, repl, text)

with open(fpath, "w", encoding="utf-8") as f:
    f.write(text)

print("PARAM_REPLACED_REGEX")
