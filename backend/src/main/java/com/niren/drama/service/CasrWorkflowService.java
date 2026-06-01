package com.niren.drama.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.niren.drama.entity.CasrRun;
import com.niren.drama.entity.ConsistencyBible;
import com.niren.drama.entity.ProductionIssue;
import com.niren.drama.entity.Project;
import com.niren.drama.entity.Storyboard;
import com.niren.drama.entity.TaskRecord;
import com.niren.drama.exception.BusinessException;
import com.niren.drama.mapper.CasrRunMapper;
import com.niren.drama.mapper.ConsistencyBibleMapper;
import com.niren.drama.mapper.ProductionIssueMapper;
import com.niren.drama.mapper.TaskRecordMapper;
import com.twooxygen.casr.domain.CasrAnalysisResult;
import com.twooxygen.casr.domain.CasrRepairAction;
import com.twooxygen.casr.domain.CasrRepairOption;
import com.twooxygen.casr.domain.CasrRepairPlan;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CasrWorkflowService {

    private final ProjectService projectService;
    private final StoryboardService storyboardService;
    private final ProductionIssueMapper productionIssueMapper;
    private final ConsistencyBibleMapper consistencyBibleMapper;
    private final TaskRecordMapper taskRecordMapper;
    private final CasrRunMapper casrRunMapper;
    private final ProductionWorkspaceService productionWorkspaceService;
    private final CasrAnalysisService casrAnalysisService;
    private final CasrPolicySearchService casrPolicySearchService;
    private final ObjectMapper objectMapper;

    public Map<String, Object> analyze(Long userId, Long projectId) {
        CasrContext context = loadContext(userId, projectId);
        CasrAnalysisResult analysis = analyze(context);
        CasrRun run = saveRun(userId, projectId, "analysis", analysis, null, null, "ready");
        return response(analysis, null, run, Map.of());
    }

    public Map<String, Object> plan(Long userId, Long projectId) {
        CasrContext context = loadContext(userId, projectId);
        CasrAnalysisResult analysis = analyze(context);
        CasrRepairPlan plan = casrPolicySearchService.plan(analysis);
        CasrRun run = saveRun(userId, projectId, "plan", analysis, plan, plan.getRecommendedOption(), "ready");
        return response(analysis, plan, run, Map.of());
    }

    public Map<String, Object> execute(Long userId, Long projectId, Map<String, Object> body) {
        Map<String, Object> safeBody = body == null ? Map.of() : body;
        CasrContext context = loadContext(userId, projectId);
        CasrAnalysisResult analysis = analyze(context);
        CasrRepairPlan plan = casrPolicySearchService.plan(analysis);
        CasrRepairOption option = selectOption(plan, textOr(safeBody.get("optionId"), plan.getRecommendedOption().getId()));
        List<String> selectedActionIds = selectedActionIds(safeBody);
        if (selectedActionIds.isEmpty()) {
            throw new BusinessException("请选择 CASR 修复动作后再执行");
        }

        List<Map<String, Object>> executed = new ArrayList<>();
        for (CasrRepairAction action : option.getActions()) {
            if (!selectedActionIds.contains(action.getAction())) {
                continue;
            }
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("action", action.getAction());
            request.put("shotIds", action.getShotIds() == null ? List.of() : action.getShotIds());
            Map<String, Object> actionResult = "qualityCheck".equals(action.getAction())
                    ? productionWorkspaceService.runQualityCheck(userId, projectId, request)
                    : productionWorkspaceService.repair(userId, projectId, request);
            executed.add(Map.of(
                    "action", action.getAction(),
                    "label", action.getLabel(),
                    "result", actionResult
            ));
        }
        if (executed.isEmpty()) {
            throw new BusinessException("选中的 CASR 动作不属于当前策略路径");
        }

        CasrRun run = saveRun(userId, projectId, "execute", analysis, plan, option, "executed");
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("selectedOptionId", option.getId());
        extra.put("executedActions", executed);
        return response(analysis, plan, run, extra);
    }

    private CasrContext loadContext(Long userId, Long projectId) {
        Project project = projectService.getProject(userId, projectId);
        List<Storyboard> shots = storyboardService.listByProject(projectId);
        List<ProductionIssue> issues = productionIssueMapper.selectList(new LambdaQueryWrapper<ProductionIssue>()
                .eq(ProductionIssue::getProjectId, projectId)
                .in(ProductionIssue::getStatus, List.of("open", "repairing"))
                .orderByDesc(ProductionIssue::getCreateTime)
                .last("LIMIT 200"));
        List<ConsistencyBible> consistencyItems = consistencyBibleMapper.selectList(new LambdaQueryWrapper<ConsistencyBible>()
                .eq(ConsistencyBible::getProjectId, projectId)
                .orderByAsc(ConsistencyBible::getBibleType)
                .orderByDesc(ConsistencyBible::getLocked)
                .last("LIMIT 100"));
        List<TaskRecord> activeTasks = taskRecordMapper.selectList(new LambdaQueryWrapper<TaskRecord>()
                .eq(TaskRecord::getProjectId, projectId)
                .in(TaskRecord::getStatus, List.of("PENDING", "RUNNING"))
                .orderByDesc(TaskRecord::getUpdateTime)
                .last("LIMIT 100"));
        return new CasrContext(project, shots, issues, consistencyItems, activeTasks);
    }

    private CasrAnalysisResult analyze(CasrContext context) {
        return casrAnalysisService.analyze(
                context.project(),
                context.shots(),
                context.issues(),
                context.consistencyItems(),
                context.activeTasks()
        );
    }

    private CasrRun saveRun(Long userId,
                            Long projectId,
                            String runType,
                            CasrAnalysisResult analysis,
                            CasrRepairPlan plan,
                            CasrRepairOption selectedOption,
                            String status) {
        CasrRun run = new CasrRun();
        run.setProjectId(projectId);
        run.setUserId(userId);
        run.setRunType(runType);
        run.setQualityScore(analysis.getQualityScore());
        run.setContinuityScore(analysis.getContinuityScore());
        run.setOverallScore(analysis.getOverallScore());
        run.setFailureTypes(toJson(analysis.getFailureTypes()));
        run.setAnalysisJson(toJson(analysis));
        run.setPlanJson(plan == null ? null : toJson(plan));
        run.setRecommendedAction(selectedOption == null ? null : selectedOption.getId());
        run.setEstimatedCost(selectedOption == null ? null : selectedOption.getCostPenalty());
        run.setEstimatedSavings(plan == null ? null : plan.getEstimatedSavings());
        run.setStatus(status);
        casrRunMapper.insert(run);
        return run;
    }

    private Map<String, Object> response(CasrAnalysisResult analysis,
                                         CasrRepairPlan plan,
                                         CasrRun run,
                                         Map<String, Object> extra) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("analysis", analysis);
        if (plan != null) {
            result.put("plan", plan);
        }
        result.put("runId", run.getId());
        result.put("generatedAt", java.time.LocalDateTime.now().toString());
        result.putAll(extra);
        return result;
    }

    private CasrRepairOption selectOption(CasrRepairPlan plan, String optionId) {
        return plan.getOptions().stream()
                .filter(option -> option.getId().equals(optionId))
                .findFirst()
                .orElseThrow(() -> new BusinessException("CASR 策略不存在: " + optionId));
    }

    private List<String> selectedActionIds(Map<String, Object> body) {
        Object actionIds = body.get("actionIds");
        if (actionIds instanceof List<?> list) {
            return list.stream().map(this::text).filter(this::hasText).distinct().toList();
        }
        String single = text(body.get("actionId"));
        return hasText(single) ? List.of(single) : List.of();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String textOr(Object value, String fallback) {
        String text = text(value);
        return hasText(text) ? text : fallback;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private record CasrContext(Project project,
                               List<Storyboard> shots,
                               List<ProductionIssue> issues,
                               List<ConsistencyBible> consistencyItems,
                               List<TaskRecord> activeTasks) {
    }
}
