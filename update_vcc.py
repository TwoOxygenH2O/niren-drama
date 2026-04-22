import sys

fpath = r"backend\src\main\java\com\niren\drama\controller\VideoController.java"
with open(fpath, "r", encoding="utf-8") as f:
    lines = f.readlines()

new_lines = []
for line in lines:
    if "public Result<TaskRecord> generateDynamic(" in line and "shotIds" not in line:
        line = line.replace("generateDynamic(@PathVariable Long projectId,", "generateDynamic(@PathVariable Long projectId, @RequestBody(required = false) List<Long> shotIds,")
    if "videoCompositionService.startGenerateDynamicVideos(userId, projectId)" in line:
        line = line.replace("(userId, projectId)", "(userId, projectId, shotIds)")
    new_lines.append(line)

with open(fpath, "w", encoding="utf-8") as f:
    f.writelines(new_lines)
print("done")
