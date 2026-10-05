package com.mapploy.backend.cv;

import com.mapploy.backend.analysis.AnalysisJsonService;
import com.mapploy.backend.analysis.AnalysisModels.Analysis;
import com.mapploy.backend.analysis.OllamaStatusService;
import com.mapploy.backend.cv.CvChecklistService.BaselineCvReview;
import com.mapploy.backend.cv.CvModels.AiCvReview;
import com.mapploy.backend.cv.CvModels.CvReview;
import com.mapploy.backend.cv.CvModels.ExtractedCv;
import com.mapploy.backend.cv.CvModels.PrivacyInfo;
import com.mapploy.backend.cv.CvPrivacyService.RedactedCv;
import com.mapploy.backend.job.JobEntity;
import com.mapploy.backend.job.JobNotFoundException;
import com.mapploy.backend.job.JobRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Service
public class CvAnalysisService {
    private final CvTextExtractor extractor;
    private final CvChecklistService checklistService;
    private final LocalCvReviewService localReviewService;
    private final OllamaStatusService ollamaStatusService;
    private final JobRepository jobRepository;
    private final AnalysisJsonService analysisJson;
    private final CvPrivacyService privacyService;

    public CvAnalysisService(
            CvTextExtractor extractor,
            CvChecklistService checklistService,
            LocalCvReviewService localReviewService,
            OllamaStatusService ollamaStatusService,
            JobRepository jobRepository,
            AnalysisJsonService analysisJson,
            CvPrivacyService privacyService) {
        this.extractor = extractor;
        this.checklistService = checklistService;
        this.localReviewService = localReviewService;
        this.ollamaStatusService = ollamaStatusService;
        this.jobRepository = jobRepository;
        this.analysisJson = analysisJson;
        this.privacyService = privacyService;
    }

    public CvReview analyze(MultipartFile file, String jobId) {
        ExtractedCv extracted = extractor.extract(file);
        JobEntity job = findJob(jobId);
        Analysis jobAnalysis = job == null ? null : analysisJson.read(job.getAnalysisJson());
        BaselineCvReview baseline = checklistService.review(extracted.text(), extracted.wordCount(), job, jobAnalysis);
        RedactedCv redacted = privacyService.redact(extracted.text());

        String engine = "rules";
        String model = "Transparent CV checklist";
        String summary = baseline.summary();
        List<String> strengths = baseline.strengths();
        List<String> improvements = baseline.improvements();

        if (ollamaStatusService.status().available()) {
            try {
                AiCvReview ai = redacted.sanitize(localReviewService.review(redacted, job, baseline));
                summary = nonBlank(ai.summary(), summary);
                strengths = safeList(ai.strengths(), strengths, 4);
                improvements = safeList(ai.improvements(), improvements, 5);
                engine = "local-ai";
                model = localReviewService.model();
            } catch (Exception ignored) {
                // The deterministic privacy-safe report remains available when the local model is unavailable.
            }
        }

        AiCvReview safeNarrative = redacted.sanitize(new AiCvReview(summary, strengths, improvements));
        return new CvReview(
                extracted.fileName(),
                extracted.mediaType(),
                extracted.wordCount(),
                baseline.completenessScore(),
                engine,
                model,
                safeNarrative.summary(),
                baseline.checklist(),
                safeNarrative.strengths(),
                safeNarrative.improvements(),
                redacted.sanitize(baseline.jobFit()),
                new PrivacyInfo(true, false,
                        "Processed on this computer. Mapploy does not save the CV file or extracted text in its database."));
    }

    private JobEntity findJob(String jobId) {
        if (jobId == null || jobId.isBlank()) return null;
        return jobRepository.findById(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static List<String> safeList(List<String> values, List<String> fallback, int limit) {
        if (values == null || values.isEmpty()) return fallback;
        List<String> cleaned = values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .limit(limit)
                .toList();
        return cleaned.isEmpty() ? fallback : cleaned;
    }
}
