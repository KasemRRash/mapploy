package com.mapploy.backend.analysis;

import com.mapploy.backend.analysis.AnalysisModels.*;
import com.mapploy.backend.job.JobEntity;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class EvidenceVerificationService {

    private static final Pattern WORKPLACE = Pattern.compile(
            "remote|homeoffice|home office|hybrid|vor ort|on[- ]site",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final BaselineAnalysisService baselineService;

    public EvidenceVerificationService(BaselineAnalysisService baselineService) {
        this.baselineService = baselineService;
    }

    public Analysis verify(JobEntity job, AiAnalysis supplied, String model) {
        Analysis baseline = baselineService.analyze(job);
        String haystack = normalize(job.getRawDescription());

        List<EvidenceItem> required = safe(supplied.requiredSkills()).stream()
                .map(item -> new EvidenceItem(item.name(), item.evidence(), verified(haystack, item.evidence())))
                .toList();
        List<EvidenceItem> optional = safe(supplied.optionalSkills()).stream()
                .map(item -> new EvidenceItem(item.name(), item.evidence(), verified(haystack, item.evidence())))
                .toList();
        List<LanguageRequirement> languages = safe(supplied.languageRequirements()).stream()
                .map(item -> {
                    Matcher level = Pattern.compile("\\b(?:A1|A2|B1|B2|C1|C2)\\b", Pattern.CASE_INSENSITIVE).matcher(nullToEmpty(item.evidence()));
                    return new LanguageRequirement(item.language(), level.find() ? level.group().toUpperCase() : "Not specified",
                            item.evidence(), verified(haystack, item.evidence()));
                })
                .toList();
        List<ExperienceRequirement> experience = safe(supplied.experienceRequirements()).stream()
                .map(item -> new ExperienceRequirement(item.text(), item.evidence(), verified(haystack, item.evidence())))
                .toList();
        List<Contradiction> contradictions = safe(supplied.contradictions()).stream()
                .map(item -> new Contradiction(item.finding(), item.evidence(), verified(haystack, item.evidence())))
                .toList();

        String suppliedWorkplace = nullToEmpty(supplied.workplaceEvidence());
        boolean workplaceVerified = verified(haystack, suppliedWorkplace) && WORKPLACE.matcher(suppliedWorkplace).find();
        String workplaceEvidence = workplaceVerified ? suppliedWorkplace : baseline.workplaceEvidence();

        Map<String, String> unclearByKey = new LinkedHashMap<>();
        List<String> allUnclear = new ArrayList<>(safe(supplied.unclearInformation()));
        allUnclear.addAll(baseline.unclearInformation());
        for (String item : allUnclear) {
            if (item != null && !item.isBlank() && !(workplaceEvidence != null && !workplaceEvidence.isBlank() && unclearKey(item).equals("workplace"))) {
                unclearByKey.putIfAbsent(unclearKey(item), item);
            }
        }

        List<Boolean> evidenceChecks = new ArrayList<>();
        required.forEach(item -> evidenceChecks.add(item.evidenceVerified()));
        optional.forEach(item -> evidenceChecks.add(item.evidenceVerified()));
        languages.forEach(item -> evidenceChecks.add(item.evidenceVerified()));
        experience.forEach(item -> evidenceChecks.add(item.evidenceVerified()));
        contradictions.forEach(item -> evidenceChecks.add(item.evidenceVerified()));
        boolean allEvidenceVerified = evidenceChecks.stream().allMatch(Boolean::booleanValue);
        String confidence = "high".equalsIgnoreCase(supplied.confidence()) && !allEvidenceVerified
                ? "medium"
                : normalizeConfidence(supplied.confidence());

        return new Analysis(
                "local-ai",
                model,
                Instant.now().toString(),
                defaultString(supplied.summary(), baseline.summary()),
                defaultString(supplied.seniority(), baseline.seniority()),
                BaselineAnalysisService.workplaceModel(workplaceEvidence),
                nullToEmpty(workplaceEvidence),
                workplaceEvidence != null && !workplaceEvidence.isBlank(),
                required,
                optional,
                languages,
                experience,
                contradictions,
                List.copyOf(unclearByKey.values()),
                confidence);
    }

    static String normalize(String value) {
        String normalized = Normalizer.normalize(nullToEmpty(value), Normalizer.Form.NFKC)
                .toLowerCase(Locale.GERMAN)
                .replaceAll("[“”„\\\"']", "")
                .replaceAll("\\s+", " ")
                .trim();
        return normalized;
    }

    private static boolean verified(String haystack, String evidence) {
        String needle = normalize(evidence);
        return needle.length() >= 6 && haystack.contains(needle);
    }

    private static String unclearKey(String item) {
        String normalized = normalize(item);
        if (normalized.matches(".*(?:years?|jahre?).*")) return "experience-years";
        if (normalized.matches(".*(?:salary|gehalt|vergütung).*")) return "salary";
        if (normalized.matches(".*(?:work(?:place)? model|arbeitsmodell).*")) return "workplace";
        if (normalized.matches(".*(?:language|sprache).*")) return "language";
        return normalized;
    }

    private static String normalizeConfidence(String value) {
        if (value == null) return "low";
        String normalized = value.toLowerCase(Locale.ROOT);
        return List.of("low", "medium", "high").contains(normalized) ? normalized : "low";
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static <T> List<T> safe(List<T> value) {
        return value == null ? List.of() : value;
    }
}
