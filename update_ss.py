import sys
import re

fpath = r"backend\src\main\java\com\niren\drama\service\StoryboardService.java"
with open(fpath, "r", encoding="utf-8") as f:
    text = f.read()

# Update Image Generation Method Signature and implementation
img_target1 = "public TaskRecord startGenerateStoryboardImages(Long userId, Long projectId) {"
img_repl1 = """public TaskRecord startGenerateStoryboardImages(Long userId, Long projectId, java.util.List<Long> shotIds) {
        List<Storyboard> shots = listByProject(projectId);
        if (shotIds != null && !shotIds.isEmpty()) {
            shots = shots.stream().filter(s -> shotIds.contains(s.getId())).toList();
        }"""

text = text.replace(img_target1 + "\n        List<Storyboard> shots = listByProject(projectId);", img_repl1)

# Update Audio Generation Method Signature and implementation
aud_target1 = "public TaskRecord startGenerateStoryboardAudio(Long userId, Long projectId) {"
aud_repl1 = """public TaskRecord startGenerateStoryboardAudio(Long userId, Long projectId, java.util.List<Long> shotIds) {
        List<Storyboard> shots = listByProject(projectId);
        if (shotIds != null && !shotIds.isEmpty()) {
            shots = shots.stream().filter(s -> shotIds.contains(s.getId())).toList();
        }"""
text = text.replace(aud_target1 + "\n        List<Storyboard> shots = listByProject(projectId);", aud_repl1)

with open(fpath, "w", encoding="utf-8") as f:
    f.write(text)

