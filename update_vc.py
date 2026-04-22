import sys
import re

fpath = r"backend\src\main\java\com\niren\drama\controller\VideoController.java"
with open(fpath, "r", encoding="utf-8") as f:
    text = f.read()

# Replace parameter lists to accept shotIds list
text = text.replace("public Result<TaskRecord> generateImages(@PathVariable Long projectId,", "public Result<TaskRecord> generateImages(@PathVariable Long projectId, @RequestBody(required = false) List<Long> shotIds,")
text = text.replace("public Result<TaskRecord> generateAudio(@PathVariable Long projectId,", "public Result<TaskRecord> generateAudio(@PathVariable Long projectId, @RequestBody(required = false) List<Long> shotIds,")
text = text.replace("public Result<TaskRecord> generateDynamic(@PathVariable Long projectId,", "public Result<TaskRecord> generateDynamic(@PathVariable Long projectId, @RequestBody(required = false) List<Long> shotIds,")
text = text.replace("public Result<TaskRecord> compose(@PathVariable Long projectId,", "public Result<TaskRecord> compose(@PathVariable Long projectId, @RequestBody(required = false) List<Long> shotIds,")

# Pass shotIds to the services
text = text.replace("return Result.success(storyboardService.startGenerateStoryboardImages(userId, projectId));", "return Result.success(storyboardService.startGenerateStoryboardImages(userId, projectId, shotIds));")
text = text.replace("return Result.success(storyboardService.startGenerateStoryboardAudio(userId, projectId));", "return Result.success(storyboardService.startGenerateStoryboardAudio(userId, projectId, shotIds));")
text = text.replace("return Result.success(videoCompositionService.startGenerateDynamicVideos(userId, projectId));", "return Result.success(videoCompositionService.startGenerateDynamicVideos(userId, projectId, shotIds));")
text = text.replace("return Result.success(videoCompositionService.startCompose(userId, projectId));", "return Result.success(videoCompositionService.startCompose(userId, projectId, shotIds));")

with open(fpath, "w", encoding="utf-8") as f:
    f.write(text)

