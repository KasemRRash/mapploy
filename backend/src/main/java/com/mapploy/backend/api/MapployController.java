package com.mapploy.backend.api;

import com.mapploy.backend.analysis.OllamaStatusService;
import com.mapploy.backend.api.ApiModels.AnalyzeResponse;
import com.mapploy.backend.api.ApiModels.JobsResponse;
import com.mapploy.backend.api.ApiModels.SyncResponse;
import com.mapploy.backend.cv.CvAnalysisService;
import com.mapploy.backend.cv.CvModels.CvReview;
import com.mapploy.backend.job.JobService;
import com.mapploy.backend.sync.FeedCatalog;
import com.mapploy.backend.sync.FeedSyncService;
import com.mapploy.backend.sync.FeedSyncService.SyncStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.MediaType;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = {"http://127.0.0.1:4173", "http://localhost:4173"})
public class MapployController {
    private final JobService jobService;
    private final FeedSyncService syncService;
    private final OllamaStatusService ollamaStatusService;
    private final CvAnalysisService cvAnalysisService;
    private final long syncIntervalMinutes;

    public MapployController(
            JobService jobService,
            FeedSyncService syncService,
            OllamaStatusService ollamaStatusService,
            CvAnalysisService cvAnalysisService,
            @Value("${mapploy.sync.interval-ms:86400000}") long syncIntervalMs) {
        this.jobService = jobService;
        this.syncService = syncService;
        this.ollamaStatusService = ollamaStatusService;
        this.cvAnalysisService = cvAnalysisService;
        this.syncIntervalMinutes = Math.max(1, syncIntervalMs / 60_000);
    }

    @GetMapping("/jobs")
    public JobsResponse jobs() {
        SyncStatus status = syncService.status();
        return new JobsResponse(
                jobService.findAll(),
                status.lastSyncAt(),
                status.summary(),
                status.syncing(),
                status.syncError());
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ollama", ollamaStatusService.status());
        response.put("syncIntervalMinutes", syncIntervalMinutes);
        response.put("feeds", FeedCatalog.FEEDS.stream()
                .map(feed -> Map.of("key", feed.key(), "name", feed.name(), "url", feed.url()))
                .toList());
        return response;
    }

    @PostMapping("/sync")
    public SyncResponse sync() {
        return new SyncResponse(syncService.sync(), jobService.findAll());
    }

    @PostMapping("/jobs/{id}/analyze")
    public AnalyzeResponse analyze(@PathVariable String id) {
        return new AnalyzeResponse(jobService.analyze(id));
    }

    @PostMapping(value = "/cv/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CvReview> analyzeCv(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String jobId) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store, max-age=0")
                .header("Pragma", "no-cache")
                .body(cvAnalysisService.analyze(file, jobId));
    }
}
