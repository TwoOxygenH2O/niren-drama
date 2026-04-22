import sys
import re

fpath = r"backend\src\main\java\com\niren\drama\service\VideoCompositionService.java"
with open(fpath, "r", encoding="utf-8") as f:
    text = f.read()

# Update dynamic video generation
dy_target = "public TaskRecord startGenerateDynamicVideos(Long userId, Long projectId) {"
dy_repl = """public TaskRecord startGenerateDynamicVideos(Long userId, Long projectId, java.util.List<Long> shotIds) {
        java.util.List<com.niren.drama.entity.Storyboard> allShots = storyboardService.listByProject(projectId);
        if (shotIds != null && !shotIds.isEmpty()) {
            allShots = allShots.stream().filter(s -> shotIds.contains(s.getId())).toList();
        }
        List<com.niren.drama.entity.Storyboard> selectedShots = allShots.stream()
"""
text = text.replace(dy_target + "\n        List<Storyboard> selectedShots = storyboardService.listByProject(projectId).stream()", dy_repl)


# Update compose video
comp_target = "public TaskRecord startCompose(Long userId, Long projectId) {"
comp_repl = """public TaskRecord startCompose(Long userId, Long projectId, java.util.List<Long> shotIds) {
        java.util.List<com.niren.drama.entity.Storyboard> allShots = storyboardService.listByProject(projectId);
        if (shotIds != null && !shotIds.isEmpty()) {
            allShots = allShots.stream().filter(s -> shotIds.contains(s.getId())).toList();
        }
        List<com.niren.drama.entity.Storyboard> shots = allShots;
"""
if comp_target in text:
    # Need to see actual implementation of startCompose to replace exactly. Let's do a regex.
    patt = r"public TaskRecord startCompose\(Long userId, Long projectId\) \{(.*?List<Storyboard> shots = storyboardService\.listByProject\(projectId\);)"
    repl = r"""public TaskRecord startCompose(Long userId, Long projectId, java.util.List<Long> shotIds) {\1
        if (shotIds != null && !shotIds.isEmpty()) {
            shots = shots.stream().filter(s -> shotIds.contains(s.getId())).collect(java.util.stream.Collectors.toList());
        }"""
    text = re.sub(patt, repl, text, flags=re.DOTALL)

with open(fpath, "w", encoding="utf-8") as f:
    f.write(text)

