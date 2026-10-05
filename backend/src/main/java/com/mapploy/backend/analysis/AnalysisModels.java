package com.mapploy.backend.analysis;

import java.util.List;

public final class AnalysisModels {
    private AnalysisModels() {
    }

    public record EvidenceItem(String name, String evidence, boolean evidenceVerified) {
    }

    public record LanguageRequirement(String language, String level, String evidence, boolean evidenceVerified) {
    }

    public record ExperienceRequirement(String text, String evidence, boolean evidenceVerified) {
    }

    public record Contradiction(String finding, String evidence, boolean evidenceVerified) {
    }

    public record Analysis(
            String engine,
            String model,
            String generatedAt,
            String summary,
            String seniority,
            String workplaceModel,
            String workplaceEvidence,
            boolean workplaceEvidenceVerified,
            List<EvidenceItem> requiredSkills,
            List<EvidenceItem> optionalSkills,
            List<LanguageRequirement> languageRequirements,
            List<ExperienceRequirement> experienceRequirements,
            List<Contradiction> contradictions,
            List<String> unclearInformation,
            String confidence
    ) {
    }

    public record AiEvidenceItem(String name, String evidence) {
    }

    public record AiLanguageRequirement(String language, String level, String evidence) {
    }

    public record AiExperienceRequirement(String text, String evidence) {
    }

    public record AiContradiction(String finding, String evidence) {
    }

    public record AiAnalysis(
            String summary,
            String seniority,
            String workplaceModel,
            String workplaceEvidence,
            List<AiEvidenceItem> requiredSkills,
            List<AiEvidenceItem> optionalSkills,
            List<AiLanguageRequirement> languageRequirements,
            List<AiExperienceRequirement> experienceRequirements,
            List<AiContradiction> contradictions,
            List<String> unclearInformation,
            String confidence
    ) {
    }
}
