package com.mapploy.backend.analysis;

import com.mapploy.backend.analysis.AnalysisModels.Analysis;
import com.mapploy.backend.analysis.AnalysisModels.Contradiction;
import com.mapploy.backend.analysis.AnalysisModels.EvidenceItem;
import com.mapploy.backend.analysis.AnalysisModels.ExperienceRequirement;
import com.mapploy.backend.analysis.AnalysisModels.LanguageRequirement;
import com.mapploy.backend.job.JobEntity;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class BaselineAnalysisService {

    private static final Pattern OPTIONAL_CONTEXT = Pattern.compile(
            "idealerweise|wünschenswert|von vorteil|nice[ -]?to[ -]?have|optional|bonus",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern EXPERIENCE = Pattern.compile(
            "\\b\\d+\\s*(?:\\+\\s*)?(?:jahre?|years?)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern WORKPLACE = Pattern.compile(
            "remote|homeoffice|home office|hybrid|vor ort|on[- ]site",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final List<SkillPattern> SKILLS = List.of(
            skill("JavaScript", "\\bjavascript\\b"),
            skill("TypeScript", "\\btypescript\\b"),
            skill("Spring Boot", "\\bspring\\s*boot\\b"),
            skill("Java", "\\bjava\\b(?!script)"),
            skill("React", "\\breact(?:\\.js)?\\b"),
            skill("Angular", "\\bangular\\b"),
            skill("Vue", "\\bvue(?:\\.js)?\\b"),
            skill("Python", "\\bpython\\b"),
            skill("C#", "\\bc#\\b"),
            skill(".NET", "\\b\\.net\\b"),
            skill("Docker", "\\bdocker\\b"),
            skill("Kubernetes", "\\bkubernetes\\b|\\bk8s\\b"),
            skill("PostgreSQL", "\\bpostgres(?:ql)?\\b"),
            skill("SQL", "\\bsql\\b"),
            skill("AWS", "\\baws\\b|amazon web services"),
            skill("Azure", "\\bazure\\b"),
            skill("Git", "\\bgit\\b"),
            skill("Linux", "\\blinux\\b"),
            skill("REST", "\\brest(?:ful)?\\b"),
            skill("JUnit", "\\bjunit\\b"),
            skill("Cypress", "\\bcypress\\b"),
            skill("Scrum", "\\bscrum\\b"),
            skill("Project Management", "project management|projektmanagement"),
            skill("Product Management", "product management|produktmanagement"),
            skill("Sales", "\\bsales\\b|\\bvertrieb\\b"),
            skill("Customer Success", "customer success"),
            skill("Accounting", "accounting|buchhalt"),
            skill("Finance", "\\bfinance\\b|finanz"),
            skill("Recruiting", "recruiting|personalgewinnung"),
            skill("Human Resources", "human resources|personalwesen"),
            skill("Logistics", "logistik|lagerlogistik"),
            skill("Electrical Engineering", "elektrotechnik|electrical engineering"),
            skill("Mechanical Engineering", "maschinenbau|mechanische konstruktion|mechanical engineering"),
            skill("PLC", "\\bplc\\b|\\bsps\\b"),
            skill("Microsoft 365", "microsoft 365|\\bm365\\b"),
            skill("Excel", "\\bexcel\\b")
    );

    public Analysis analyze(JobEntity job) {
        List<String> sentences = sentenceList(job.getRawDescription());
        List<EvidenceItem> required = new ArrayList<>();
        List<EvidenceItem> optional = new ArrayList<>();

        for (SkillPattern skill : SKILLS) {
            String evidence = findEvidence(sentences, skill.pattern());
            if (evidence.isEmpty()) {
                continue;
            }
            EvidenceItem item = new EvidenceItem(skill.name(), evidence, true);
            if (OPTIONAL_CONTEXT.matcher(evidence).find()) {
                optional.add(item);
            } else {
                required.add(item);
            }
        }

        List<LanguageRequirement> languages = new ArrayList<>();
        addLanguage(sentences, languages, "German", Pattern.compile("(?:deutsch(?:kenntnisse)?|german).*", Pattern.CASE_INSENSITIVE));
        addLanguage(sentences, languages, "English", Pattern.compile("(?:englisch(?:kenntnisse)?|english).*", Pattern.CASE_INSENSITIVE));

        List<ExperienceRequirement> experience = sentences.stream()
                .filter(sentence -> EXPERIENCE.matcher(sentence).find())
                .limit(3)
                .map(sentence -> new ExperienceRequirement(sentence, sentence, true))
                .toList();

        String workplaceEvidence = findEvidence(sentences, WORKPLACE);
        String workplaceModel = workplaceModel(workplaceEvidence);

        List<Contradiction> contradictions = new ArrayList<>();
        if (Pattern.compile("\\bjunior\\b", Pattern.CASE_INSENSITIVE).matcher(job.getTitle()).find()) {
            Pattern seniorSignals = Pattern.compile(
                    "\\b(?:[3-9]|\\d{2,})\\s*(?:\\+\\s*)?(?:jahre?|years?)\\b|architektur|architecture|mentoring|lead",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            String evidence = findEvidence(sentences, seniorSignals);
            if (!evidence.isEmpty()) {
                contradictions.add(new Contradiction(
                        "The junior title may conflict with the requested experience or responsibility.",
                        evidence,
                        true));
            }
        }

        List<String> unclear = new ArrayList<>();
        if (languages.isEmpty()) unclear.add("No explicit language requirement was found.");
        if (workplaceEvidence.isEmpty()) unclear.add("The workplace model is not stated clearly.");
        if (!Pattern.compile("gehalt|salary|vergütung|euro|€", Pattern.CASE_INSENSITIVE).matcher(job.getRawDescription()).find()) {
            unclear.add("No salary range was found.");
        }
        if (experience.isEmpty()) unclear.add("No explicit number of years of experience was found.");

        int detected = required.size() + optional.size() + languages.size() + experience.size();
        return new Analysis(
                "rules",
                "Transparent rule-based baseline",
                Instant.now().toString(),
                detected + " explicit requirement signals found. " + unclear.size() + " important items remain unclear.",
                blankToUnclear(job.getSeniority()),
                workplaceModel,
                workplaceEvidence,
                !workplaceEvidence.isEmpty(),
                required,
                optional,
                languages,
                experience,
                contradictions,
                unclear,
                detected > 5 ? "medium" : "low");
    }

    static List<String> sentenceList(String text) {
        if (text == null || text.isBlank()) return List.of();
        return Pattern.compile("\\n+|(?<=[.!?])\\s+(?=[A-ZÄÖÜ])")
                .splitAsStream(text)
                .map(sentence -> sentence.replaceFirst("^•\\s*", "").trim())
                .filter(sentence -> sentence.length() >= 8)
                .toList();
    }

    static String workplaceModel(String evidence) {
        if (evidence == null || evidence.isBlank()) return "Unclear";
        if (Pattern.compile("hybrid", Pattern.CASE_INSENSITIVE).matcher(evidence).find()) return "Hybrid";
        if (Pattern.compile("remote|homeoffice|home office", Pattern.CASE_INSENSITIVE).matcher(evidence).find()) return "Remote or hybrid";
        if (Pattern.compile("vor ort|on[- ]site", Pattern.CASE_INSENSITIVE).matcher(evidence).find()) return "On-site";
        return "Unclear";
    }

    private static void addLanguage(List<String> sentences, List<LanguageRequirement> target, String language, Pattern pattern) {
        String evidence = findEvidence(sentences, pattern);
        if (evidence.isEmpty()) return;
        Matcher levelMatcher = Pattern.compile("\\b(?:A1|A2|B1|B2|C1|C2)\\b", Pattern.CASE_INSENSITIVE).matcher(evidence);
        String level = levelMatcher.find() ? levelMatcher.group().toUpperCase() : "Not specified";
        target.add(new LanguageRequirement(language, level, evidence, true));
    }

    private static String findEvidence(List<String> sentences, Pattern pattern) {
        return sentences.stream().filter(sentence -> pattern.matcher(sentence).find()).findFirst().orElse("");
    }

    private static SkillPattern skill(String name, String regex) {
        return new SkillPattern(name, Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
    }

    private static String blankToUnclear(String value) {
        return value == null || value.isBlank() ? "Unclear" : value;
    }

    private record SkillPattern(String name, Pattern pattern) {
    }
}
