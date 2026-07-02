package com.niren.drama.service;

import com.twooxygen.casr.domain.CasrAnalysisResult;
import com.twooxygen.casr.domain.CasrRepairOption;
import com.twooxygen.casr.domain.CasrRepairPlan;
import com.twooxygen.casr.domain.CasrShotDiagnosis;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CasrPolicySearchServiceTest {

    private final CasrPolicySearchService service = new CasrPolicySearchService();

    @Test
    void planPrioritizesWanMotionBoostRepairForBlockingMotionFailures() {
        CasrShotDiagnosis shot = new CasrShotDiagnosis();
        shot.setShotId(20L);
        shot.setShotNo(3);
        shot.setFailureTypes(List.of("animated_still", "motion_failure"));
        shot.setRecommendedAction("retryVideo");

        CasrAnalysisResult analysis = new CasrAnalysisResult();
        analysis.setProjectId(10L);
        analysis.setQualityScore(58);
        analysis.setContinuityScore(62);
        analysis.setShotDiagnoses(List.of(shot));
        analysis.setFailureTypes(List.of("animated_still", "motion_failure"));

        CasrRepairPlan plan = service.plan(analysis);

        assertThat(plan.getRecommendedOption().getId()).isEqualTo("performance-wan-motion");
        assertThat(plan.getRecommendedOption().getReward())
                .isGreaterThan(option(plan, "fast-preview-ltx").getReward());
        assertThat(plan.getRecommendedOption().getActions())
                .extracting("action")
                .containsExactly("snapshot", "switchWan", "retryVideo", "qualityCheck");
        assertThat(plan.getOptions()).hasSizeGreaterThanOrEqualTo(3);
    }

    private CasrRepairOption option(CasrRepairPlan plan, String id) {
        return plan.getOptions().stream()
                .filter(item -> id.equals(item.getId()))
                .findFirst()
                .orElseThrow();
    }
}
