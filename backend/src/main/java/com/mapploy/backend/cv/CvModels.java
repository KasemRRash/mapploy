package com.mapploy.backend.cv;

import java.util.List;

public final class CvModels {
    private CvModels() {
    }

    public record ExtractedCv(String fileName, String mediaType, String text, int wordCount) {
    }

    public record ChecklistItem(
            String key,
            String label,
            String status,
            String detail,
            int points,
            int maxPoints
    ) {
    }

    public record SkillEvidence(String name, String evidence) {
    }

    public record JobFit(
            String jobId,
            String title,
            String company,
            int matchScore,
            List<SkillEvidence> matchedRequirements,
            List<String> missingRequirements,
            List<SkillEvidence> matchedOptionalSkills,
            String explanation
    ) {
    }

    public record PrivacyInfo(boolean processedLocally, boolean stored, String statement) {
    }

    public record CvReview(
            String fileName,
            String mediaType,
            int wordCount,
            int completenessScore,
            String engine,
            String model,
            String summary,
            List<ChecklistItem> checklist,
            List<String> strengths,
            List<String> improvements,
            JobFit jobFit,
            PrivacyInfo privacy
    ) {
    }

    public record AiCvReview(String summary, List<String> strengths, List<String> improvements) {
    }
}
