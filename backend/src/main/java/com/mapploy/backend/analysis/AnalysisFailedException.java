package com.mapploy.backend.analysis;

import com.mapploy.backend.analysis.AnalysisModels.Analysis;

public class AnalysisFailedException extends RuntimeException {
    private final Analysis fallback;

    public AnalysisFailedException(String message, Analysis fallback, Throwable cause) {
        super(message, cause);
        this.fallback = fallback;
    }

    public Analysis getFallback() {
        return fallback;
    }
}
