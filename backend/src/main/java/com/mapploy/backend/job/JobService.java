package com.mapploy.backend.job;

import com.mapploy.backend.analysis.AnalysisJsonService;
import com.mapploy.backend.analysis.AnalysisFailedException;
import com.mapploy.backend.analysis.AnalysisModels.Analysis;
import com.mapploy.backend.analysis.LocalAiAnalysisService;
import com.mapploy.backend.api.ApiModels.JobView;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class JobService {
    private final JobRepository repository;
    private final AnalysisJsonService analysisJson;
    private final LocalAiAnalysisService aiAnalysisService;

    public JobService(JobRepository repository, AnalysisJsonService analysisJson, LocalAiAnalysisService aiAnalysisService) {
        this.repository = repository;
        this.analysisJson = analysisJson;
        this.aiAnalysisService = aiAnalysisService;
    }

    public List<JobView> findAll() {
        return repository.findAll().stream()
                .sorted(Comparator.comparing(JobEntity::isActive).reversed()
                        .thenComparing(job -> value(job.getLocation()) + value(job.getTitle()), String.CASE_INSENSITIVE_ORDER))
                .map(this::toView)
                .toList();
    }

    public Optional<JobView> findById(String id) {
        return repository.findById(id).map(this::toView);
    }

    public JobView analyze(String id) {
        JobEntity job = repository.findById(id).orElseThrow(() -> new JobNotFoundException(id));
        try {
            Analysis analysis = aiAnalysisService.analyze(job);
            job.setAnalysisJson(analysisJson.write(analysis));
            return toView(repository.save(job));
        } catch (Exception error) {
            throw new AnalysisFailedException(rootMessage(error), analysisJson.read(job.getAnalysisJson()), error);
        }
    }

    public JobView toView(JobEntity job) {
        List<Double> coordinates = job.getLongitude() == null || job.getLatitude() == null
                ? null
                : List.of(job.getLongitude(), job.getLatitude());
        return new JobView(
                job.getId(),
                job.getSourceKey(),
                job.getSourceName(),
                job.getSourceType(),
                job.getSourceFeedUrl(),
                job.getSourceUrl(),
                job.getExternalJobId(),
                job.getTitle(),
                job.getCompany(),
                job.getLocation(),
                job.getCategory(),
                coordinates,
                job.getDepartment(),
                job.getEmploymentType(),
                job.getSeniority(),
                job.getSchedule(),
                job.getSourceCreatedAt(),
                job.getRawDescription(),
                job.getContentHash(),
                job.getFirstSeenAt(),
                job.getLastSeenAt(),
                job.getLastCheckedAt(),
                job.isActive(),
                job.isInScope(),
                job.getConsecutiveMissingCount(),
                analysisJson.read(job.getBaselineJson()),
                analysisJson.read(job.getAnalysisJson()));
    }

    private static String value(String input) {
        return input == null ? "" : input.toLowerCase(Locale.ROOT);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? error.getClass().getSimpleName() : current.getMessage();
    }
}
