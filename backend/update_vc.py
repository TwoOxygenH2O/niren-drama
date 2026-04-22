import sys

fpath = r"src\main\java\com\niren\drama\controller\VideoController.java"
with open(fpath, "r", encoding="utf-8") as f:
    text = f.read()

replacements = [
    (
        'public Result<TaskRecord> generateImages(@PathVariable Long projectId,\n                                              @AuthenticationPrincipal UserDetails userDetails) {\n        Long userId = currentUserHelper.getUserId(userDetails);\n        return Result.success(storyboardService.startGenerateStoryboardImages(userId, projectId));',
        'public Result<TaskRecord> generateImages(@PathVariable Long projectId, @RequestBody(required = false) List<Long> shotIds,\n                                              @AuthenticationPrincipal UserDetails userDetails) {\n        Long userId = currentUserHelper.getUserId(userDetails);\n        return Result.success(storyboardService.startGenerateStoryboardImages(userId, projectId, shotIds));'
    ),
    (
        'public Result<TaskRecord> generateAudio(@PathVariable Long projectId,\n                                             @AuthenticationPrincipal UserDetails userDetails) {\n        Long userId = currentUserHelper.getUserId(userDetails);\n        return Result.success(storyboardService.startGenerateStoryboardAudio(userId, projectId));',
        'public Result<TaskRecord> generateAudio(@PathVariable Long projectId, @RequestBody(required = false) List<Long> shotIds,\n                                             @AuthenticationPrincipal UserDetails userDetails) {\n        Long userId = currentUserHelper.getUserId(userDetails);\n        return Result.success(storyboardService.startGenerateStoryboardAudio(userId, projectId, shotIds));'
    ),
    (
        'public Result<TaskRecord> generateDynamic(@PathVariable Long projectId,\n                                               @AuthenticationPrincipal UserDetails userDetails) {\n        Long userId = currentUserHelper.getUserId(userDetails);\n        return Result.success(videoCompositionService.startGenerateDynamicVideos(userId, projectId));',
        'public Result<TaskRecord> generateDynamic(@PathVariable Long projectId, @RequestBody(required = false) List<Long> shotIds,\n                                               @AuthenticationPrincipal UserDetails userDetails) {\n        Long userId = currentUserHelper.getUserId(userDetails);\n        return Result.success(videoCompositionService.startGenerateDynamicVideos(userId, projectId, shotIds));'
    ),
    (
        'public Result<TaskRecord> compose(@PathVariable Long projectId,\n                                       @AuthenticationPrincipal UserDetails userDetails) {\n        Long userId = currentUserHelper.getUserId(userDetails);\n        return Result.success(videoCompositionService.startCompose(userId, projectId));',
        'public Result<TaskRecord> compose(@PathVariable Long projectId, @RequestBody(required = false) List<Long> shotIds,\n                                       @AuthenticationPrincipal UserDetails userDetails) {\n        Long userId = currentUserHelper.getUserId(userDetails);\n        return Result.success(videoCompositionService.startCompose(userId, projectId, shotIds));'
    )
]

for o, n in replacements:
    text = text.replace(o, n)

with open(fpath, "w", encoding="utf-8") as f:
    f.write(text)

print("Updated VideoController.java")
