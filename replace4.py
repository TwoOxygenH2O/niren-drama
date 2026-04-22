import sys
import re

fpath = r"d:\pythonProject\niren-drama\backend\src\main\java\com\niren\drama\service\StoryboardService.java"
with open(fpath, "r", encoding="utf-8") as f:
    text = f.read()

# Fix compilation error at line 665
text = text.replace(
    "generateStoryboardPreviewByScenes(textProvider, systemPrompt, script.getContent(), request, chunk -> {", 
    "generateStoryboardPreviewByScenes(textProvider, systemPrompt, script, request, chunk -> {"
)

# Remove duplicate saving logic blocks (lines 191-214)
patt = r'(// parse into Storyboard locally to save[\s\S]*?storyboardMapper\.insert\(draftShot\);\s*){2,}'
repl = """// parse into Storyboard locally to save
                Storyboard draftShot = objectMapper.convertValue(shotObject, Storyboard.class);
                draftShot.setProjectId(request.getProjectId());
                draftShot.setScriptId(request.getScriptId());
                draftShot.setSceneId((long) sceneIndex); // hack to remember sceneIndex
                draftShot.setStatus("preview_draft");
                if (script.getEpisodeNo() != null) {
                    draftShot.setEpisodeNo(script.getEpisodeNo());
                } else {
                    draftShot.setEpisodeNo(1);
                }
                storyboardMapper.insert(draftShot);
"""

text = re.sub(patt, repl, text)

with open(fpath, "w", encoding="utf-8") as f:
    f.write(text)

