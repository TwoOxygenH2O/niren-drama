package com.niren.drama.service;

import com.niren.drama.entity.ConsistencyBible;
import com.niren.drama.entity.ProductionIssue;
import com.niren.drama.entity.Project;
import com.niren.drama.entity.Storyboard;
import com.niren.drama.entity.TaskRecord;
import com.twooxygen.casr.domain.CasrAnalysisResult;
import com.twooxygen.casr.engine.CasrEngine;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CasrAnalysisService {

    private final NirenCasrInputAdapter inputAdapter;
    private final CasrEngine casrEngine;

    public CasrAnalysisService(NirenCasrInputAdapter inputAdapter) {
        this.inputAdapter = inputAdapter;
        this.casrEngine = new CasrEngine();
    }

    public CasrAnalysisResult analyze(Project project,
                                      List<Storyboard> shots,
                                      List<ProductionIssue> issues,
                                      List<ConsistencyBible> consistencyItems,
                                      List<TaskRecord> activeTasks) {
        return casrEngine.analyze(inputAdapter.toInput(project, shots, issues, consistencyItems, activeTasks));
    }
}
