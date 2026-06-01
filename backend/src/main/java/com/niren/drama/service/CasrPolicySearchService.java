package com.niren.drama.service;

import com.twooxygen.casr.domain.CasrAnalysisResult;
import com.twooxygen.casr.domain.CasrRepairPlan;
import com.twooxygen.casr.engine.CasrEngine;
import org.springframework.stereotype.Service;

@Service
public class CasrPolicySearchService {

    private final CasrEngine casrEngine = new CasrEngine();

    public CasrRepairPlan plan(CasrAnalysisResult analysis) {
        return casrEngine.plan(analysis);
    }
}
