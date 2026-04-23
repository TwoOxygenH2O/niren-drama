package com.niren.drama.ai.trace;

import java.util.ArrayList;
import java.util.List;

public final class AiTraceContext {

    private static final ThreadLocal<List<AiCallTrace>> CONTEXT = ThreadLocal.withInitial(ArrayList::new);

    private AiTraceContext() {
    }

    public static void clear() {
        CONTEXT.remove();
    }

    public static void record(AiCallTrace trace) {
        if (trace == null) {
            return;
        }
        CONTEXT.get().add(trace);
    }

    public static List<AiCallTrace> drain() {
        List<AiCallTrace> traces = new ArrayList<>(CONTEXT.get());
        CONTEXT.remove();
        return traces;
    }
}