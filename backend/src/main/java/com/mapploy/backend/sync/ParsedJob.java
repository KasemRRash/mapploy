package com.mapploy.backend.sync;

public record ParsedJob(
        String id,
        String sourceKey,
        String sourceName,
        String sourceType,
        String sourceFeedUrl,
        String sourceUrl,
        String externalJobId,
        String title,
        String company,
        String location,
        String category,
        Double longitude,
        Double latitude,
        String department,
        String employmentType,
        String seniority,
        String schedule,
        String sourceCreatedAt,
        String rawDescription,
        String contentHash
) {
}
