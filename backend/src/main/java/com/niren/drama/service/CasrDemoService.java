package com.niren.drama.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.niren.drama.entity.CasrRun;
import com.niren.drama.entity.ConsistencyBible;
import com.niren.drama.entity.ProductionIssue;
import com.niren.drama.entity.Project;
import com.niren.drama.entity.Script;
import com.niren.drama.entity.Storyboard;
import com.niren.drama.mapper.CasrRunMapper;
import com.niren.drama.mapper.ConsistencyBibleMapper;
import com.niren.drama.mapper.ProductionIssueMapper;
import com.niren.drama.mapper.ProjectMapper;
import com.niren.drama.mapper.ScriptMapper;
import com.niren.drama.mapper.StoryboardMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CasrDemoService {

    private final ProjectMapper projectMapper;
    private final ScriptMapper scriptMapper;
    private final StoryboardMapper storyboardMapper;
    private final ProductionIssueMapper productionIssueMapper;
    private final ConsistencyBibleMapper consistencyBibleMapper;
    private final CasrRunMapper casrRunMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, Object> createDemo(Long userId) {
        Project project = new Project();
        project.setUserId(userId);
        project.setName("CASR 连续性感知自修复 Demo");
        project.setDescription("用于研究展示的 AI 短剧生产线自诊断、自修复策略搜索演示项目。");
        project.setCommonInfo("核心角色：林晚，白色风衣，雨夜冷光。核心场景：旧城雨巷和天台。CASR 用于约束首帧、视频、配音和合成质量。");
        project.setProjectType("真人短剧");
        project.setGenre("悬疑逆袭");
        project.setEpisodes(1);
        project.setEpisodeDuration(90);
        project.setStatus("draft");
        projectMapper.insert(project);

        Script script = new Script();
        script.setProjectId(project.getId());
        script.setEpisodeNo(1);
        script.setTitle("雨夜反击");
        script.setSummary("女主在雨夜发现骗局证据，并在天台完成反击。");
        script.setContent("林晚在雨夜巷口发现关键证据，追逐、对峙、反转和公开证据构成一个完整短剧闭环。");
        script.setStatus("ai_generated");
        scriptMapper.insert(script);

        List<Storyboard> shots = List.of(
                shot(project.getId(), script.getId(), 1, "雨夜巷口，林晚低头发现手机里的转账证据", null, null, "submitted", "首帧缺失，无法稳定进入视频生成"),
                shot(project.getId(), script.getId(), 2, "林晚穿白色风衣冲出巷口", "/demo/casr/frame-2.png", null, "failed", "快速奔跑，禁止切镜、换人、换衣"),
                shot(project.getId(), script.getId(), 3, "男主回头发现证据被录下", "/demo/casr/frame-3.png", "/demo/casr/video-3.mp4", "success", "镜头快速推进"),
                shot(project.getId(), script.getId(), 4, "天台冷光下两人对峙", "/demo/casr/frame-4.png", "/demo/casr/video-4.mp4", "success", "保持同一地点、同一光线"),
                shot(project.getId(), script.getId(), 5, "林晚举起手机播放录音", "/demo/casr/frame-5.png", null, "failed", "首帧已确定，保持同一张脸、同一服装"),
                shot(project.getId(), script.getId(), 6, "众人沉默，男主慌乱后退", "/demo/casr/frame-6.png", "/demo/casr/video-6.mp4", "success", "中景，轻微后退"),
                shot(project.getId(), script.getId(), 7, "林晚转身离开，雨声压住尾声", "/demo/casr/frame-7.png", "/demo/casr/video-7.mp4", "success", "连续单镜头，雨夜冷光")
        );
        shots.forEach(storyboardMapper::insert);

        consistencyBibleMapper.insert(bible(project.getId(), "character", "林晚身份锚点", "同一张脸、白色风衣、低马尾、雨夜冷光"));
        consistencyBibleMapper.insert(bible(project.getId(), "scene", "雨夜场景锚点", "旧城雨巷、潮湿地面、冷蓝路灯、天台栏杆"));

        productionIssueMapper.insert(issue(project.getId(), null, "stale_task", "warning", "存在陈旧动态任务", "clearStaleTasks"));
        productionIssueMapper.insert(issue(project.getId(), null, "scene_drift_risk", "warning", "场景连续性风险", "switchWan"));
        productionIssueMapper.insert(issue(project.getId(), null, "identity_drift_risk", "blocking", "角色身份漂移风险", "switchWan"));
        productionIssueMapper.insert(issue(project.getId(), null, "black_frame", "blocking", "检测到黑屏片段", "retryVideo"));
        productionIssueMapper.insert(issue(project.getId(), null, "duration_out_of_range", "warning", "镜头时长偏离设定", "retryVideo"));

        CasrRun run = new CasrRun();
        run.setProjectId(project.getId());
        run.setUserId(userId);
        run.setRunType("demo_seed");
        run.setQualityScore(61);
        run.setContinuityScore(58);
        run.setOverallScore(60);
        run.setFailureTypes("[\"missing_first_frame\",\"identity_drift_risk\",\"black_frame\",\"duration_out_of_range\"]");
        run.setAnalysisJson(toJson(Map.of("demo", true, "shotCount", shots.size())));
        run.setPlanJson(toJson(Map.of("recommended", "preserve-continuity-wan")));
        run.setRecommendedAction("switchWan");
        run.setEstimatedCost(1.9d);
        run.setEstimatedSavings(6.1d);
        run.setStatus("ready");
        casrRunMapper.insert(run);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", project.getId());
        result.put("projectName", project.getName());
        result.put("route", "/projects/" + project.getId() + "/immersive/workbench?episode=1&tab=video");
        return result;
    }

    private Storyboard shot(Long projectId,
                            Long scriptId,
                            int shotNo,
                            String description,
                            String imageUrl,
                            String videoUrl,
                            String videoTaskStatus,
                            String prompt) {
        Storyboard shot = new Storyboard();
        shot.setProjectId(projectId);
        shot.setScriptId(scriptId);
        shot.setEpisodeNo(1);
        shot.setShotNo(shotNo);
        shot.setDescription(description);
        shot.setCameraAngle(shotNo % 2 == 0 ? "medium" : "close-up");
        shot.setDuration(shotNo == 3 ? 12 : 5);
        shot.setImageUrl(imageUrl);
        shot.setVideoUrl(videoUrl);
        shot.setVideoTaskStatus(videoTaskStatus);
        shot.setImagePrompt("竖屏9:16，电影级雨夜短剧画面，" + description);
        shot.setVideoPrompt(prompt);
        shot.setMotionTier(shotNo <= 3 ? "A" : "B");
        shot.setMotionLevel(shotNo <= 3 ? "high" : "medium");
        shot.setDynamicRecommended(true);
        shot.setDynamicSelected(true);
        shot.setDynamicScore(85 - shotNo);
        shot.setRenderMode(videoUrl == null ? "image" : "video");
        shot.setStatus(videoUrl == null ? "image_generated" : "video_generated");
        return shot;
    }

    private ConsistencyBible bible(Long projectId, String type, String title, String attributes) {
        ConsistencyBible bible = new ConsistencyBible();
        bible.setProjectId(projectId);
        bible.setBibleType(type);
        bible.setTitle(title);
        bible.setLockedAttributes(attributes);
        bible.setNotes("CASR Demo 锁定项，用于展示连续性感知评分。");
        bible.setLocked(true);
        return bible;
    }

    private ProductionIssue issue(Long projectId, Long shotId, String type, String severity, String title, String action) {
        ProductionIssue issue = new ProductionIssue();
        issue.setProjectId(projectId);
        issue.setShotId(shotId);
        issue.setIssueType(type);
        issue.setSeverity(severity);
        issue.setStatus("open");
        issue.setTitle(title);
        issue.setMessage("CASR Demo 内置问题，用于展示失败归因和策略搜索。");
        issue.setRecommendedAction(action);
        issue.setActions("[{\"id\":\"" + action + "\",\"label\":\"CASR 推荐动作\"}]");
        issue.setMetadata("{}");
        return issue;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
