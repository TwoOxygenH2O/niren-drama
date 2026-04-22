import sys
import re

fpath = r"d:\pythonProject\niren-drama\backend\src\main\java\com\niren\drama\service\StoryboardService.java"
with open(fpath, "r", encoding="utf-8") as f:
    text = f.read()

# We need to insert the resume logic before `for (int sceneIndex = 0; sceneIndex < scenes.size(); sceneIndex++) {`
target = """        int nextShotNo = 1;
        boolean firstShotEmitted = false;

        for (int sceneIndex = 0; sceneIndex < scenes.size(); sceneIndex++) {"""

resume_logic = """        int nextShotNo = 1;
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

if target in text:
    text = text.replace(target, resume_logic)
    print("RESUME_INJECTED")
else:
    print("RESUME_TARGET_NOT_FOUND")

# Now inject saving mechanism inside the loop!
target2 = """                if (isStream) {
                    try {
                        String shotJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(shotObject);
                        String chunk = (firstShotEmitted ? ",\\n" : "") + "    " + shotJson.replace("\\n", "\\n    ");
                        chunkConsumer.accept(chunk);
                        firstShotEmitted = true;
                    } catch (Exception e) {
                        log.warn("Failed to stringify shot", e);
                    }
                }"""

saving_logic = """                // parse into Storyboard locally to save
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

                if (isStream) {
                    try {
                        String shotJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(shotObject);
                        String chunk = (firstShotEmitted ? ",\\n" : "") + "    " + shotJson.replace("\\n", "\\n    ");
                        chunkConsumer.accept(chunk);
                        firstShotEmitted = true;
                    } catch (Exception e) {
                        log.warn("Failed to stringify shot", e);
                    }
                }"""

if target2 in text:
    text = text.replace(target2, saving_logic)
    print("SAVE_INJECTED")
else:
    print("SAVE_TARGET_NOT_FOUND")

with open(fpath, "w", encoding="utf-8") as f:
    f.write(text)

