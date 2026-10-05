package com.mapploy.backend.api;

import com.mapploy.backend.analysis.AnalysisModels.Analysis;
import com.mapploy.backend.sync.FeedSyncService.SyncSummary;

import java.time.Instant;
import java.util.List;

public final class ApiModels {
    private ApiModels() {
    }

    public record JobView(
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
            List<Double> coordinates,
            String department,
            String employmentType,
            String seniority,
            String schedule,
            String sourceCreatedAt,
            String rawDescription,
            String contentHash,
            Instant firstSeenAt,
            Instant lastSeenAt,
            Instant lastCheckedAt,
            boolean active,
            boolean inScope,
            int consecutiveMissingCount,
            Analysis baseline,
            Analysis analysis
    ) {
    }

    public record JobsResponse(
            List<JobView> jobs,
            Instant lastSyncAt,
            SyncSummary lastSyncSummary,
            boolean syncing,
            String syncError
    ) {
    }

    public record SyncResponse(SyncSummary summary, List<JobView> jobs) {
    }

    public record AnalyzeResponse(JobView job) {
    }
}
