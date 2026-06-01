package com.niren.drama.service;

import com.twooxygen.casr.domain.CasrAnalysisResult;
import com.twooxygen.casr.domain.CasrRepairPlan;
import com.twooxygen.casr.domain.CasrShotDiagnosis;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CasrPolicySearchServiceTest {

    private final CasrPolicySearchService service = new CasrPolicySearchService();

    @Test
    void planPrioritizesWanRepairForBlockingContinuityFailures() {
        CasrShotDiagnosis shot = new CasrShotDiagnosis();
        shot.setShotId(20L);
        shot.setShotNo(3);
        shot.setFailureTypes(List.of("black_frame", "identity_drift_risk"));
        shot.setRecommendedAction("retryVideo");

        CasrAnalysisResult analysis = new CasrAnalysisResult();
        analysis.setProjectId(10L);
        analysis.setQualityScore(58);
        analysis.setContinuityScore(62);
        analysis.setShotDiagnoses(List.of(shot));
        analysis.setFailureTypes(List.of("black_frame", "identity_drift_risk"));

        CasrRepairPlan plan = service.plan(analysis);

        assertThat(plan.getRecommendedOption().getId()).isEqualTo("preserve-continuity-wan");
        assertThat(plan.getRecommendedOption().getReward()).isGreaterThan(40);
        assertThat(plan.getRecommendedOption().getActions())
                .extracting("action")
                .containsExactly("snapshot", "switchWan", "retryVideo", "qualityCheck");
        assertThat(plan.getOptions()).hasSizeGreaterThanOrEqualTo(3);
    }
}
