package com.mapploy.backend.sync;

import com.mapploy.backend.analysis.AnalysisJsonService;
import com.mapploy.backend.analysis.AnalysisModels.Analysis;
import com.mapploy.backend.analysis.BaselineAnalysisService;
import com.mapploy.backend.job.JobEntity;
import com.mapploy.backend.job.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class FeedSyncService {
    private static final Logger log = LoggerFactory.getLogger(FeedSyncService.class);
    private static final int MISSING_RUNS_BEFORE_INACTIVE = 3;

    private final JobRepository jobRepository;
    private final SyncStateRepository stateRepository;
    private final PersonioFeedParser parser;
    private final JobScopeService scopeService;
    private final BaselineAnalysisService baselineService;
    private final AnalysisJsonService analysisJson;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AtomicBoolean syncing = new AtomicBoolean(false);
    private final boolean syncEnabled;
    private final boolean syncOnStartup;

    public FeedSyncService(
            JobRepository jobRepository,
            SyncStateRepository stateRepository,
            PersonioFeedParser parser,
            JobScopeService scopeService,
            BaselineAnalysisService baselineService,
            AnalysisJsonService analysisJson,
            ObjectMapper objectMapper,
            @Value("${mapploy.sync.enabled:true}") boolean syncEnabled,
            @Value("${mapploy.sync.on-startup:true}") boolean syncOnStartup) {
        this.jobRepository = jobRepository;
        this.stateRepository = stateRepository;
        this.parser = parser;
        this.scopeService = scopeService;
        this.baselineService = baselineService;
        this.analysisJson = analysisJson;
        this.objectMapper = objectMapper;
        this.syncEnabled = syncEnabled;
        this.syncOnStartup = syncOnStartup;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        if (!syncEnabled || !syncOnStartup) return;
        CompletableFuture.runAsync(() -> {
            try {
                SyncSummary summary = sync();
                log.info("Initial feed sync complete: {} active jobs from {} sources", summary.activeJobs(), summary.sourcesChecked());
            } catch (Exception error) {
                log.warn("Initial feed sync failed: {}", error.getMessage());
            }
        });
    }

    @Scheduled(
            fixedDelayString = "${mapploy.sync.interval-ms:86400000}",
            initialDelayString = "${mapploy.sync.interval-ms:86400000}")
    public void scheduledSync() {
        if (!syncEnabled) return;
        try {
            SyncSummary summary = sync();
            log.info("Scheduled feed sync complete: {} active jobs", summary.activeJobs());
        } catch (Exception error) {
            log.warn("Scheduled feed sync failed: {}", error.getMessage());
        }
    }

    public SyncSummary sync() {
        if (!syncing.compareAndSet(false, true)) return status().summary();

        Instant now = Instant.now();
        Map<String, JobEntity> existingById = new HashMap<>();
        jobRepository.findAll().forEach(job -> existingById.put(job.getId(), job));
        Set<String> completedSources = new HashSet<>();
        Set<String> seenIds = new HashSet<>();
        Set<String> observedIds = new HashSet<>();
        List<String> errors = new ArrayList<>();
        int added = 0;
        int changed = 0;

        try {
            for (FeedCatalog.Feed feed : FeedCatalog.FEEDS) {
                try {
                    List<ParsedJob> parsedJobs = fetch(feed);
                    completedSources.add(feed.key());
                    parsedJobs.forEach(job -> observedIds.add(job.id()));

                    for (ParsedJob incoming : parsedJobs.stream().filter(scopeService::isInScope).toList()) {
                        seenIds.add(incoming.id());
                        JobEntity existing = existingById.get(incoming.id());
                        boolean isNew = existing == null;
                        if (isNew) existing = new JobEntity();
                        boolean contentChanged = !isNew && !existing.getContentHash().equals(incoming.contentHash());

                        merge(existing, incoming, now, isNew);
                        Analysis baseline = baselineService.analyze(existing);
                        existing.setBaselineJson(analysisJson.write(baseline));
                        if (isNew || contentChanged || existing.getAnalysisJson() == null) {
                            existing.setAnalysisJson(analysisJson.write(baseline));
                        }
                        existingById.put(existing.getId(), existing);
                        if (isNew) added++;
                        else if (contentChanged) changed++;
                    }
                } catch (Exception error) {
                    errors.add(feed.name() + ": " + rootMessage(error));
                }
            }

            for (JobEntity job : existingById.values()) {
                if (!completedSources.contains(job.getSourceKey()) || seenIds.contains(job.getId())) continue;
                job.setLastCheckedAt(now);
                if (observedIds.contains(job.getId())) {
                    job.setInScope(false);
                    job.setLastSeenAt(now);
                    job.setConsecutiveMissingCount(0);
                    job.setActive(true);
                } else {
                    int missing = job.getConsecutiveMissingCount() + 1;
                    job.setConsecutiveMissingCount(missing);
                    job.setActive(missing < MISSING_RUNS_BEFORE_INACTIVE);
                }
            }

            jobRepository.saveAll(existingById.values());
            long activeJobs = existingById.values().stream().filter(job -> job.isActive() && job.isInScope()).count();
            SyncSummary summary = new SyncSummary(
                    now,
                    activeJobs,
                    existingById.size(),
                    added,
                    changed,
                    completedSources.size(),
                    List.copyOf(errors));
            saveState(summary, errors.isEmpty() ? null : String.join("; ", errors));
            return summary;
        } finally {
            syncing.set(false);
        }
    }

    public SyncStatus status() {
        SyncStateEntity entity = stateRepository.findById(1).orElseGet(() -> {
            SyncStateEntity created = new SyncStateEntity();
            created.setId(1);
            return created;
        });
        SyncSummary summary = null;
        if (entity.getSummaryJson() != null) {
            try {
                summary = objectMapper.readValue(entity.getSummaryJson(), SyncSummary.class);
            } catch (JacksonException error) {
                log.warn("Could not read the last sync summary", error);
            }
        }
        return new SyncStatus(entity.getLastSyncAt(), summary, syncing.get(), entity.getSyncError());
    }

    public boolean isSyncing() {
        return syncing.get();
    }

    private List<ParsedJob> fetch(FeedCatalog.Feed feed) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(feed.url()))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Mapploy-BIP-Prototype/0.2 (educational project)")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return parser.parse(response.body(), feed);
    }

    private void merge(JobEntity target, ParsedJob source, Instant now, boolean isNew) {
        target.setId(source.id());
        target.setSourceKey(source.sourceKey());
        target.setSourceName(source.sourceName());
        target.setSourceType(source.sourceType());
        target.setSourceFeedUrl(source.sourceFeedUrl());
        target.setSourceUrl(source.sourceUrl());
        target.setExternalJobId(source.externalJobId());
        target.setTitle(source.title());
        target.setCompany(source.company());
        target.setLocation(source.location());
        target.setCategory(source.category());
        target.setLongitude(source.longitude());
        target.setLatitude(source.latitude());
        target.setDepartment(source.department());
        target.setEmploymentType(source.employmentType());
        target.setSeniority(source.seniority());
        target.setSchedule(source.schedule());
        target.setSourceCreatedAt(source.sourceCreatedAt());
        target.setRawDescription(source.rawDescription());
        target.setContentHash(source.contentHash());
        if (isNew || target.getFirstSeenAt() == null) target.setFirstSeenAt(now);
        target.setLastSeenAt(now);
        target.setLastCheckedAt(now);
        target.setActive(true);
        target.setInScope(true);
        target.setConsecutiveMissingCount(0);
    }

    private void saveState(SyncSummary summary, String syncError) {
        SyncStateEntity state = stateRepository.findById(1).orElseGet(() -> {
            SyncStateEntity created = new SyncStateEntity();
            created.setId(1);
            return created;
        });
        state.setLastSyncAt(summary.checkedAt());
        try {
            state.setSummaryJson(objectMapper.writeValueAsString(summary));
        } catch (JacksonException error) {
            throw new IllegalStateException("Could not store the sync summary.", error);
        }
        state.setSyncError(syncError);
        stateRepository.save(state);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? error.getClass().getSimpleName() : current.getMessage();
    }

    public record SyncSummary(
            Instant checkedAt,
            long activeJobs,
            long totalJobs,
            int added,
            int changed,
            int sourcesChecked,
            List<String> errors
    ) {
    }

    public record SyncStatus(Instant lastSyncAt, SyncSummary summary, boolean syncing, String syncError) {
    }
}
