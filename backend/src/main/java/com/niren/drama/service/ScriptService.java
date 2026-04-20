package com.niren.drama.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.niren.drama.ai.AiProviderFactory;
import com.niren.drama.ai.TextAiProvider;
import com.niren.drama.dto.script.ScriptGenerateRequest;
import com.niren.drama.dto.script.ScriptSaveRequest;
import com.niren.drama.entity.Script;
import com.niren.drama.entity.TaskRecord;
import com.niren.drama.exception.BusinessException;
import com.niren.drama.mapper.ScriptMapper;
import com.niren.drama.mapper.TaskRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptService {

    private final ScriptMapper scriptMapper;
    private final TaskRecordMapper taskRecordMapper;
    private final AiProviderFactory aiProviderFactory;
    private final ProjectService projectService;

    public TaskRecord startGenerateScript(Long userId, ScriptGenerateRequest request) {
        projectService.getProject(userId, request.getProjectId());
        TaskRecord task = new TaskRecord();
        task.setProjectId(request.getProjectId());
        task.setUserId(userId);
        task.setTaskType("SCRIPT_GEN");
        task.setStatus("PENDING");
        task.setProgress(0);
        task.setMessage("任务已提交，等待执行...");
        taskRecordMapper.insert(task);
        generateScriptAsync(userId, request, task.getId());
        return task;
    }

    public void streamGenerateScript(Long userId, ScriptGenerateRequest request, Consumer<String> chunkConsumer) {
        projectService.getProject(userId, request.getProjectId());
        TextAiProvider textProvider = aiProviderFactory.getTextProvider(userId);
        String systemPrompt = buildScriptSystemPrompt(request.getGenre(), request.getStyle());
        String userPrompt = buildScriptUserPrompt(request);
        textProvider.streamChat(systemPrompt, userPrompt, chunkConsumer);
    }

    @Async("aiTaskExecutor")
    public void generateScriptAsync(Long userId, ScriptGenerateRequest request, Long taskId) {
        TaskRecord task = taskRecordMapper.selectById(taskId);
        if (task == null) return;
        try {
            updateTask(task, "RUNNING", 10, "开始生成剧本...");

            TextAiProvider textProvider = aiProviderFactory.getTextProvider(userId);
            String systemPrompt = buildScriptSystemPrompt(request.getGenre(), request.getStyle());
            String userPrompt = buildScriptUserPrompt(request);

            updateTask(task, "RUNNING", 30, "AI正在生成剧本内容...");
            String scriptContent = textProvider.chat(systemPrompt, userPrompt);

            updateTask(task, "RUNNING", 80, "保存剧本内容...");
            Script script = new Script();
            script.setProjectId(request.getProjectId());
            script.setEpisodeNo(request.getEpisodeNo() != null ? request.getEpisodeNo() : 1);
            script.setContent(scriptContent);
            script.setAiPrompt(request.getIdea());
            script.setStatus("ai_generated");
            script.setTitle("第" + script.getEpisodeNo() + "集");
            scriptMapper.insert(script);

            task.setStatus("SUCCESS");
            task.setProgress(100);
            task.setMessage("剧本生成完成");
            task.setResult(String.valueOf(script.getId()));
            taskRecordMapper.updateById(task);

        } catch (Exception e) {
            log.error("Script generation failed for task {}", taskId, e);
            task.setStatus("FAILED");
            task.setMessage("剧本生成失败: " + e.getMessage());
            taskRecordMapper.updateById(task);
        }
    }

    public Script getScript(Long id) {
        Script script = scriptMapper.selectById(id);
        if (script == null) throw new BusinessException("剧本不存在");
        return script;
    }

    public Script saveScript(Long userId, ScriptSaveRequest request) {
        projectService.getProject(userId, request.getProjectId());

        Script script;
        if (request.getId() != null) {
            script = getScript(request.getId());
        } else {
            script = new Script();
            script.setProjectId(request.getProjectId());
        }

        script.setEpisodeNo(request.getEpisodeNo());
        script.setTitle(request.getTitle());
        script.setContent(request.getContent());
        script.setAiPrompt(request.getAiPrompt());
        script.setStatus("reviewed");

        if (request.getId() != null) {
            scriptMapper.updateById(script);
        } else {
            scriptMapper.insert(script);
        }
        return script;
    }

    public List<Script> listByProject(Long projectId) {
        return scriptMapper.selectList(new LambdaQueryWrapper<Script>()
                .eq(Script::getProjectId, projectId)
                .orderByAsc(Script::getEpisodeNo));
    }

    public Script updateScript(Long id, String content, String title) {
        Script script = getScript(id);
        if (content != null) script.setContent(content);
        if (title != null) script.setTitle(title);
        script.setStatus("reviewed");
        scriptMapper.updateById(script);
        return script;
    }

    public void deleteScript(Long id) {
        scriptMapper.deleteById(id);
    }

    private void updateTask(TaskRecord task, String status, int progress, String message) {
        task.setStatus(status);
        task.setProgress(progress);
        task.setMessage(message);
        taskRecordMapper.updateById(task);
    }

    private String buildScriptSystemPrompt(String genre, String style) {
        String genreText = genre != null ? genre : "都市言情";
        String styleText = style != null ? style : "";
        return String.format("""
                # 角色定位
                你是一位拥有10年爆款短剧编剧经验的金牌编剧，专精红果短剧、抖音短剧平台。
                你的核心目标：产出保底级付费短剧剧本,红果短剧、抖音短剧标准，合规无违规，爽点密集，适合平台保底S+评级。
                
                # 平台合规红线（必须遵守）
                - 无色情/软色情暗示，无血腥暴力特写，无政治敏感内容
                - 不丑化军人/警察/医生/教师等职业形象
                - 不宣扬封建迷信、赌博、毒品
                - 正向价值观：善有善报、正义终将胜利、真爱至上
                - 不涉及未成年人恋爱/暴力情节
                
                # 爆款方法论
                ## 钩子设计（黄金3秒 + 30秒法则）
                - 第1秒：视觉冲击或悬念台词（如"三年前你亲手毁了我，三年后我带着十亿回来了"）
                - 前30秒：必须出现核心冲突事件，让用户停不下来
                - 每集结尾：必须设置强悬念钩子（反转/新危机/身份揭露），驱动付费解锁下一集
                
                ## 爽点节奏模板（每集必须包含）
                - 每2分钟一个小爽点（打脸/逆袭/甜蜜暴击/身份揭露）
                - 每集至少3个大爽点（核心冲突升级/重大反转/高燃台词）
                - 集末反转：必须在最后30秒制造新的强悬念
                
                ## 人物弧光设计
                - 主角必须有清晰的成长弧线：从低谷 → 觉醒 → 反击 → 逆袭
                - 反派不能脸谱化：需有合理动机，让观众"恨得过瘾"
                - 关键配角需有记忆点：金句/口头禅/标志性动作
                - 人物关系网必须有张力：至少包含2组对立关系 + 1组隐藏关系
                
                # 剧本结构规范
                ## 长度约束
                - 每集正文 3000-4000 字
                - 每集包含 15-25 个场景
                - 每集时长约 6-8 分钟
                - 单个场景 100-200 字（含描述+对白+动作指示）
                
                ## 对白要求
                - 台词简短有力，单句不超过20字
                - 金句密度：每集至少5句可截图传播的台词
                - 角色语言个性化：不同角色说话风格明显不同
                - 潜台词丰富：表面意思与真实意图之间有反差
                
                ## 视觉化写作
                - 每个场景必须有明确的视觉指示（表情/动作/机位建议）
                - 适合竖屏（9:16）构图
                - 充分复用场景和角色，减少不必要的场景切换
                - 同一场景内多个镜头保持视觉连贯性
                - 对话场景优先使用特写和中景镜头
                
                # 题材风格
                %s %s
                """, genreText, styleText);
    }

    private String buildScriptUserPrompt(ScriptGenerateRequest request) {
        int episodeNo = request.getEpisodeNo() != null ? request.getEpisodeNo() : 1;
        int totalEpisodes = request.getTotalEpisodes() != null ? request.getTotalEpisodes() : 1;
        return String.format("""
                请基于以下一句话创意，生成一部完整的短剧项目方案。当前生成第 %d 集（共 %d 集）。
                
                ## 创意描述
                %s
                
                ## 输出要求（请严格按以下结构输出，不要遗漏任何板块）
                
                ### 一、剧名
                给出一个吸睛的剧名（6-12字），要有悬念感或情绪冲击力。
                
                ### 二、一句话剧情梗概
                用一句话（50字以内）概括全剧核心冲突与卖点。
                
                ### 三、完整人物小传
                列出所有主要角色（至少3个），每个角色包含：
                - 姓名、性别、年龄
                - 外貌特征（详细到发型/穿搭风格/标志性特征）
                - 性格关键词（3-5个）
                - 人物背景故事（50-100字）
                - 人物弧光：起点状态 → 关键转折 → 终点状态
                - 口头禅或标志性台词
                - 与其他角色的关系
                
                ### 四、分集大纲（每集100字）
                为全部 %d 集各写一段100字左右的剧情梗概，标注每集的核心爽点和集末钩子。
                格式：
                第X集《集标题》：剧情梗概... 【爽点】xxx 【集末钩子】xxx
                
                ### 五、3张高质量AI封面提示词
                为本剧设计3张封面的AI绘图提示词（中文），每张提示词需包含：
                - 画面主体描述（人物姿态/表情/服装）
                - 场景氛围（灯光/色调/背景元素）
                - 构图说明（竖版9:16，电影级画质）
                - 文字标题叠加建议
                - 风格关键词（如：电影级质感、高饱和度、戏剧性光影）
                
                ### 六、第 %d 集完整剧本正文
                按以下格式输出完整剧本（3000-4000字）：
                
                【第%d集】集标题
                
                第X场 [场景名称/时间/地点]
                （场景描述：环境氛围、光线、人物站位，需包含镜头建议如"特写/中景/全景"）
                
                角色名：（表情+动作）"对白"
                
                旁白：旁白内容
                
                [镜头指示：推/拉/摇/移/特写 等]
                
                要求：
                - 开场前30秒必须有强冲突或悬念台词
                - 每2分钟一个小爽点，每集至少3个大爽点
                - 集末最后30秒必须设置强悬念钩子
                - 台词简短有力，金句密度高
                - 场景复用率≥60%%，减少不必要的场景切换
                - 包含15-25个场景
                
                ---
                注意：以上所有内容必须完整输出，不可省略任何板块。这是保底级付费短剧剧本，对标红果短剧、抖音短剧S+评级标准。
                """, episodeNo, totalEpisodes, request.getIdea(),
                totalEpisodes, episodeNo, episodeNo);
    }
}
