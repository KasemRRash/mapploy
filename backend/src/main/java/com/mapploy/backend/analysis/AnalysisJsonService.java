package com.mapploy.backend.analysis;

import com.mapploy.backend.analysis.AnalysisModels.Analysis;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class AnalysisJsonService {
    private final ObjectMapper objectMapper;

    public AnalysisJsonService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(Analysis analysis) {
        try {
            return objectMapper.writeValueAsString(analysis);
        } catch (JacksonException error) {
            throw new IllegalStateException("Could not store the job analysis.", error);
        }
    }

    public Analysis read(String json) {
        try {
            return objectMapper.readValue(json, Analysis.class);
        } catch (JacksonException error) {
            throw new IllegalStateException("Could not read the stored job analysis.", error);
        }
    }
}
