import sys
fpath = r"src\main\java\com\niren\drama\controller\VideoController.java"
with open(fpath, "r", encoding="utf-8") as f:
    text = f.read()

import re
text = re.sub(r'public Result<TaskRecord> generateDynamic\(@PathVariable Long projectId,\s*@AuthenticationPrincipal UserDetails userDetails\)\s*\{\s*Long userId = currentUserHelper\.getUserId\(userDetails\);\s*return Result\.success\(videoCompositionService\.startGenerateDynamicVideos\(userId, projectId\)\);\s*\}', 
    r'public Result<TaskRecord> generateDynamic(@PathVariable Long projectId, @RequestBody(required = false) List<Long> shotIds,\n                                               @AuthenticationPrincipal UserDetails userDetails) {\n        Long userId = currentUserHelper.getUserId(userDetails);\n        return Result.success(videoCompositionService.startGenerateDynamicVideos(userId, projectId, shotIds));\n    }', 
    text)

text = re.sub(r'public Result<TaskRecord> compose\(@PathVariable Long projectId,\s*@AuthenticationPrincipal UserDetails userDetails\)\s*\{\s*Long userId = currentUserHelper\.getUserId\(userDetails\);\s*return Result\.success\(videoCompositionService\.startCompose\(userId, projectId\)\);\s*\}',
    r'public Result<TaskRecord> compose(@PathVariable Long projectId, @RequestBody(required = false) List<Long> shotIds,\n                                       @AuthenticationPrincipal UserDetails userDetails) {\n        Long userId = currentUserHelper.getUserId(userDetails);\n        return Result.success(videoCompositionService.startCompose(userId, projectId, shotIds));\n    }',
    text)

with open(fpath, "w", encoding="utf-8") as f:
    f.write(text)
print("Updated!")
