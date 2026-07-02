package com.niren.drama.service;

import com.twooxygen.casr.domain.CasrAnalysisResult;
import com.twooxygen.casr.domain.CasrRepairAction;
import com.twooxygen.casr.domain.CasrRepairOption;
import com.twooxygen.casr.domain.CasrRepairPlan;
import com.twooxygen.casr.domain.CasrShotDiagnosis;
import com.twooxygen.casr.engine.CasrEngine;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class CasrPolicySearchService {

    private final CasrEngine casrEngine = new CasrEngine();

    public CasrRepairPlan plan(CasrAnalysisResult analysis) {
        CasrRepairPlan plan = casrEngine.plan(analysis);
        if (requiresWanMotionRepair(analysis)) {
            prioritizeWanMotionRepair(plan, analysis);
        }
        return plan;
    }

    private boolean requiresWanMotionRepair(CasrAnalysisResult analysis) {
        if (analysis == null) {
            return false;
        }
        if (containsMotionFailure(analysis.getFailureTypes())) {
            return true;
        }
        List<CasrShotDiagnosis> diagnoses = analysis.getShotDiagnoses();
        if (diagnoses == null) {
            return false;
        }
        return diagnoses.stream().anyMatch(shot ->
                containsMotionFailure(shot.getFailureTypes()) || "switchWan".equals(shot.getRecommendedAction()));
    }

    private boolean containsMotionFailure(List<String> failureTypes) {
        if (failureTypes == null) {
            return false;
        }
        return failureTypes.stream().anyMatch(type ->
                "animated_still".equals(type) || "motion_failure".equals(type) || "weak_motion".equals(type));
    }

    private void prioritizeWanMotionRepair(CasrRepairPlan plan, CasrAnalysisResult analysis) {
        if (plan == null) {
            return;
        }
        CasrRepairOption option = buildWanMotionOption(plan, analysis);
        List<CasrRepairOption> options = new ArrayList<>();
        options.add(option);
        if (plan.getOptions() != null) {
            for (CasrRepairOption existing : plan.getOptions()) {
                if (!option.getId().equals(existing.getId())) {
                    options.add(existing);
                }
            }
        }
        plan.setOptions(options);
        plan.setRecommendedOption(option);
        Map<String, Object> trace = plan.getPolicyTrace() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(plan.getPolicyTrace());
        trace.put("nirenMotionOverride", "animated_still -> switchWan");
        plan.setPolicyTrace(trace);
    }

    private CasrRepairOption buildWanMotionOption(CasrRepairPlan plan, CasrAnalysisResult analysis) {
        List<Long> shotIds = affectedMotionShotIds(analysis);
        double maxReward = plan.getOptions() == null ? 0d : plan.getOptions().stream()
                .mapToDouble(CasrRepairOption::getReward)
                .max()
                .orElse(0d);
        CasrRepairOption option = new CasrRepairOption();
        option.setId("performance-wan-motion");
        option.setLabel("Wan 动作增强重生");
        option.setActions(List.of(
                new CasrRepairAction("snapshot", "保存当前镜头资产快照", shotIds, 0.0d, 1, 0),
                new CasrRepairAction("switchWan", "切换 Wan 2.2 动作增强 I2V 工作流", shotIds, 1.0d, 2, 10),
                new CasrRepairAction("retryVideo", "重生动图化镜头视频", shotIds, 6.0d, 14, 28),
                new CasrRepairAction("qualityCheck", "重新运行 CASR 视觉质检", shotIds, 0.0d, 2, 8)
        ));
        option.setScoreGain(28d);
        option.setCostPenalty(7d);
        option.setTimePenalty(18d);
        option.setRiskPenalty(7d);
        option.setSuccessProbability(0.78d);
        option.setReward(Math.max(maxReward + 1d, 62d));
        option.setExplanation("镜头被判定为动图化静帧或运动失败时，优先切到 Wan 2.2 动作增强 I2V，强化人物局部表演、肢体动作和真实视差，再重新质检。");
        return option;
    }

    private List<Long> affectedMotionShotIds(CasrAnalysisResult analysis) {
        if (analysis == null || analysis.getShotDiagnoses() == null) {
            return List.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (CasrShotDiagnosis shot : analysis.getShotDiagnoses()) {
            if (shot.getShotId() != null
                    && (containsMotionFailure(shot.getFailureTypes()) || "switchWan".equals(shot.getRecommendedAction()))) {
                ids.add(shot.getShotId());
            }
        }
        return new ArrayList<>(ids);
    }
}
