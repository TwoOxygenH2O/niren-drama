import sys
import re

fpath = r"d:\pythonProject\niren-drama\backend\src\main\java\com\niren\drama\service\StoryboardService.java"
with open(fpath, "r", encoding="utf-8") as f:
    text = f.read()

patt = r"int nextShotNo = 1;\s*boolean firstShotEmitted = false;\s*for \(int sceneIndex = 0; sceneIndex < scenes\.size\(\); sceneIndex\+\+\) \{"
repl = """int nextShotNo = 1;
        boolean firstShotEmitted = false;
        
        List<Storyboard> savedShots = storyboardMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Storyboard>()
                .eq(Storyboard::getProjectId, request.getProjectId())
                .eq(Storyboard::getScriptId, request.getScriptId())
                .eq(Storyboard::getStatus, "preview_draft")
                .orderByAsc(Storyboard::getShotNo));

        int startSceneIndex = 0;

        if (!savedShots.isEmpty()) {
            java.time.LocalDateTime scriptUpdate = script.getUpdateTime();
            java.time.LocalDateTime previewTime = savedShots.get(0).getCreateTime();
            if (scriptUpdate != null && scriptUpdate.isAfter(previewTime)) {
                storyboardMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Storyboard>()
                        .eq(Storyboard::getProjectId, request.getProjectId())
                        .eq(Storyboard::getScriptId, request.getScriptId())
                        .eq(Storyboard::getStatus, "preview_draft"));
            } else {
                long maxSceneId = -1;
                for (Storyboard s : savedShots) {
                    if (s.getSceneId() != null && s.getSceneId() > maxSceneId) {
                        maxSceneId = s.getSceneId();
                    }
                    if (s.getShotNo() >= nextShotNo) {
                        nextShotNo = s.getShotNo() + 1;
                    }
                    // re-emit
                    if (isStream) {
                        try {
                            String shotJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(s);
                            String chunk = (firstShotEmitted ? ",\\n" : "") + "    " + shotJson.replace("\\n", "\\n    ");
                            chunkConsumer.accept(chunk);
                            firstShotEmitted = true;
                        } catch (Exception ignored) {}
                    }
                }
                startSceneIndex = (int) maxSceneId + 1;
            }
        }

        for (int sceneIndex = startSceneIndex; sceneIndex < scenes.size(); sceneIndex++) {"""

if re.search(patt, text):
    text = re.sub(patt, repl, text)
    print("RESUME_INJECTED_VIA_REGEX")
else:
    print("RESUME_TARGET_NOT_FOUND_REGEX")

with open(fpath, "w", encoding="utf-8") as f:
    f.write(text)

