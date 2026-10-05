package com.mapploy.backend.cv;

import com.mapploy.backend.analysis.AnalysisModels.Analysis;
import com.mapploy.backend.analysis.AnalysisModels.EvidenceItem;
import com.mapploy.backend.analysis.AnalysisModels.LanguageRequirement;
import com.mapploy.backend.cv.CvModels.ChecklistItem;
import com.mapploy.backend.cv.CvModels.JobFit;
import com.mapploy.backend.cv.CvModels.SkillEvidence;
import com.mapploy.backend.job.JobEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class CvChecklistService {
    private static final Pattern EMAIL = Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE = Pattern.compile("(?:\\+\\d{1,3}[ .()/\\-]*)?(?:\\d[ .()/\\-]*){8,}");
    private static final Pattern SUMMARY = heading("professional summary|profile|about me|summary|profil|kurzprofil|über mich");
    private static final Pattern EXPERIENCE = heading("work experience|professional experience|employment|experience|berufserfahrung|beruflicher werdegang|praxis");
    private static final Pattern EDUCATION_HEADING = heading("education|academic background|ausbildung|studium|bildung");
    private static final Pattern EDUCATION = Pattern.compile("(?im)^\\s*(education|academic background|ausbildung|studium|bildung)\\b|\\b(bachelor|master|university|universität|hochschule|degree|abschluss)\\b");
    private static final Pattern SKILLS = heading("skills|technical skills|competencies|technologies|kenntnisse|fähigkeiten|kompetenzen");
    private static final Pattern LANGUAGES_HEADING = heading("languages|language skills|sprachen|sprachkenntnisse");
    private static final Pattern LANGUAGES = Pattern.compile("(?im)^\\s*(languages|language skills|sprachen|sprachkenntnisse)\\b|\\b(english|englisch|german|deutsch|french|französisch|spanish|spanisch)\\b");
    private static final Pattern DATES = Pattern.compile("\\b(?:19|20)\\d{2}\\b|\\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec|mär|mai|okt|dez)[a-zä]*\\s+(?:19|20)?\\d{2}\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ACHIEVEMENTS = Pattern.compile("(?:\\b\\d+(?:[.,]\\d+)?\\s*%|[€$£]\\s*\\d|\\b\\d+\\s*(?:users?|customers?|clients?|projects?|teams?|people|employees?|kunden?|projekte?|mitarbeitende)\\b)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern QUALIFICATION = Pattern.compile("\\b(bachelor|master|phd|doctorate|degree|diploma|certificate|certification|ausbildung|studium|abschluss)\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern LANGUAGE_LEVEL = Pattern.compile("\\b(A1|A2|B1|B2|C1|C2|native|mother tongue|fluent|professional proficiency|basic|fortgeschritten|muttersprache|fließend|grundkenntnisse)\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ACTION_VERBS = Pattern.compile("\\b(built|developed|designed|implemented|improved|managed|led|created|delivered|supported|automated|reduced|increased|analysed|analyzed|entwickelt|erstellt|implementiert|verbessert|geleitet|betreut|automatisiert|reduziert|gesteigert)\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SECTION_HEADING = heading("professional summary|profile|about me|summary|profil|kurzprofil|über mich|work experience|professional experience|employment|experience|berufserfahrung|beruflicher werdegang|praxis|education|academic background|ausbildung|studium|bildung|skills|technical skills|competencies|technologies|kenntnisse|fähigkeiten|kompetenzen|languages|language skills|sprachen|sprachkenntnisse|projects|projekte|certifications|zertifikate|references|referenzen");

    private static final Map<String, List<String>> SKILL_ALIASES = aliases();

    public BaselineCvReview review(String text, int wordCount, JobEntity job, Analysis jobAnalysis) {
        List<ChecklistItem> checklist = checklist(text, wordCount);
        int score = score(checklist);
        List<String> strengths = checklist.stream()
                .filter(item -> item.status().equals("pass"))
                .limit(4)
                .map(this::strength)
                .toList();
        List<String> improvements = checklist.stream()
                .filter(item -> !item.status().equals("pass"))
                .limit(5)
                .map(this::improvement)
                .toList();
        long passed = checklist.stream().filter(item -> item.status().equals("pass")).count();
        String summary = "The CV clearly covers %d of %d core quality checks. The score reflects document completeness, not candidate quality."
                .formatted(passed, checklist.size());
        JobFit jobFit = job == null || jobAnalysis == null ? null : jobFit(text, job, jobAnalysis);
        return new BaselineCvReview(score, summary, checklist, strengths, improvements, jobFit);
    }

    private List<ChecklistItem> checklist(String text, int wordCount) {
        List<ChecklistItem> items = new ArrayList<>();
        boolean email = EMAIL.matcher(text).find();
        boolean phone = PHONE.matcher(text).find();
        int contactPoints = (email ? 8 : 0) + (phone ? 7 : 0);
        items.add(item("contact", "Contact details", contactPoints, 15,
                email && phone ? "Email and phone contact details are present." : email || phone ? "Only one contact method was detected." : "No email or phone contact details were detected."));

        boolean summaryHeading = SUMMARY.matcher(text).find();
        int summaryWords = words(sectionText(SUMMARY, text));
        int summaryPoints = (summaryHeading ? 4 : 0) + (summaryWords >= 6 && summaryWords <= 120 ? 6 : summaryWords > 0 ? 3 : 0);
        items.add(item("summary", "Professional profile", summaryPoints, 10,
                summaryHeading
                        ? "%d words were found in the labelled profile section.".formatted(summaryWords)
                        : "Add a short role-focused professional summary near the top."));

        boolean experienceHeading = EXPERIENCE.matcher(text).find();
        String experienceText = sectionText(EXPERIENCE, text);
        int dateSignals = count(DATES, experienceHeading ? experienceText : text);
        int experienceWords = words(experienceText);
        int actionSignals = count(ACTION_VERBS, experienceText);
        int experiencePoints = (experienceHeading ? 5 : 0)
                + (dateSignals >= 2 ? 5 : dateSignals == 1 ? 2 : 0)
                + (experienceWords >= 35 ? 5 : experienceWords >= 15 ? 3 : experienceWords > 0 ? 1 : 0)
                + (actionSignals >= 2 ? 5 : actionSignals == 1 ? 3 : 0);
        items.add(item("experience", "Work experience", experiencePoints, 20,
                experienceHeading
                        ? "%d timeline signals and %d action-oriented statements were detected.".formatted(dateSignals, actionSignals)
                        : "Make roles, employers, dates, responsibilities and outcomes easy to scan."));

        boolean educationHeading = EDUCATION_HEADING.matcher(text).find();
        boolean educationSignal = EDUCATION.matcher(text).find();
        boolean qualification = QUALIFICATION.matcher(text).find();
        int educationPoints = (educationHeading ? 6 : educationSignal ? 3 : 0) + (qualification ? 4 : 0);
        items.add(item("education", "Education", educationPoints, 10,
                educationSignal ? "Education or training information was detected." : "Add relevant education, vocational training or certifications."));

        boolean skillsHeading = SKILLS.matcher(text).find();
        int skillItems = sectionItemCount(sectionText(SKILLS, text));
        int skillsPoints = (skillsHeading ? 5 : 0) + (skillItems >= 5 ? 10 : skillItems >= 3 ? 7 : skillItems > 0 ? 3 : 0);
        items.add(item("skills", "Skills section", skillsPoints, 15,
                skillsHeading ? "%d scannable skill entries were detected.".formatted(skillItems) : "Add a concise skills section using terms relevant to the target role."));

        boolean languageSignal = LANGUAGES.matcher(text).find();
        boolean languageHeading = LANGUAGES_HEADING.matcher(text).find();
        boolean languageLevel = LANGUAGE_LEVEL.matcher(text).find();
        int languagePoints = (languageHeading ? 4 : languageSignal ? 2 : 0) + (languageLevel ? 6 : 0);
        items.add(item("languages", "Languages", languagePoints, 10,
                languageSignal && languageLevel ? "Language names and at least one proficiency level are present." : "State languages and proficiency levels explicitly."));

        int achievementSignals = count(ACHIEVEMENTS, text);
        int achievementPoints = achievementSignals >= 2 ? 10 : achievementSignals == 1 ? 6 : 0;
        items.add(item("achievements", "Measurable achievements", achievementPoints, 10,
                achievementSignals > 0
                        ? "%d quantified achievement signal%s detected.".formatted(achievementSignals, achievementSignals == 1 ? " was" : "s were")
                        : "Strengthen experience bullets with measurable outcomes where truthful."));

        int lengthPoints = wordCount >= 250 && wordCount <= 1_200 ? 5 : wordCount >= 150 && wordCount <= 1_800 ? 3 : wordCount >= 60 && wordCount <= 2_200 ? 1 : 0;
        items.add(item("length", "Focused length", lengthPoints, 5,
                "%d words extracted. %s".formatted(wordCount,
                        lengthPoints == 5 ? "The amount of content is focused." : "Review whether the CV is too brief or too dense.")));

        int headings = (summaryHeading ? 1 : 0) + (experienceHeading ? 1 : 0) + (educationHeading ? 1 : 0)
                + (skillsHeading ? 1 : 0) + (languageHeading ? 1 : 0);
        int structurePoints = headings >= 4 ? 5 : headings == 3 ? 4 : headings == 2 ? 2 : headings == 1 ? 1 : 0;
        items.add(item("structure", "Scannable structure", structurePoints, 5,
                headings >= 3 ? "%d recognisable CV section headings were detected.".formatted(headings) : "Use clear section headings and consistent chronology."));
        return List.copyOf(items);
    }

    private JobFit jobFit(String cvText, JobEntity job, Analysis analysis) {
        List<SkillEvidence> matchedRequired = new ArrayList<>();
        List<String> missingRequired = new ArrayList<>();
        for (EvidenceItem requirement : analysis.requiredSkills()) {
            addMatch(cvText, requirement.name(), matchedRequired, missingRequired);
        }
        for (LanguageRequirement requirement : analysis.languageRequirements()) {
            addLanguageMatch(cvText, requirement, matchedRequired, missingRequired);
        }

        List<SkillEvidence> matchedOptional = new ArrayList<>();
        for (EvidenceItem requirement : analysis.optionalSkills()) {
            String evidence = findEvidence(cvText, aliasesFor(requirement.name()));
            if (!evidence.isBlank()) matchedOptional.add(new SkillEvidence(requirement.name(), evidence));
        }

        int requiredTotal = matchedRequired.size() + missingRequired.size();
        int optionalTotal = analysis.optionalSkills().size();
        int matchScore;
        if (requiredTotal == 0 && optionalTotal == 0) {
            matchScore = 0;
        } else if (requiredTotal == 0) {
            matchScore = Math.round(100f * matchedOptional.size() / optionalTotal);
        } else if (optionalTotal == 0) {
            matchScore = Math.round(100f * matchedRequired.size() / requiredTotal);
        } else {
            matchScore = Math.round(85f * matchedRequired.size() / requiredTotal + 15f * matchedOptional.size() / optionalTotal);
        }

        String explanation = requiredTotal == 0
                ? "The advertisement contains too few explicit requirements for a reliable match score."
                : "%d of %d explicit required skills or language requirements were found in the CV text. This is evidence support, not a hiring decision."
                .formatted(matchedRequired.size(), requiredTotal);
        return new JobFit(job.getId(), job.getTitle(), job.getCompany(), matchScore,
                List.copyOf(matchedRequired), List.copyOf(missingRequired), List.copyOf(matchedOptional), explanation);
    }

    private void addMatch(String cvText, String name, List<SkillEvidence> matched, List<String> missing) {
        String evidence = findEvidence(cvText, aliasesFor(name));
        if (evidence.isBlank()) missing.add(name);
        else matched.add(new SkillEvidence(name, evidence));
    }

    private void addLanguageMatch(String cvText, LanguageRequirement requirement, List<SkillEvidence> matched, List<String> missing) {
        List<String> languageAliases = switch (requirement.language().toLowerCase(Locale.ROOT)) {
            case "german" -> List.of("german", "deutsch");
            case "english" -> List.of("english", "englisch");
            default -> List.of(requirement.language().toLowerCase(Locale.ROOT));
        };
        String evidence = findEvidence(cvText, languageAliases);
        boolean explicitLevel = requirement.level() == null || requirement.level().equalsIgnoreCase("Not specified")
                || evidence.toLowerCase(Locale.ROOT).contains(requirement.level().toLowerCase(Locale.ROOT));
        String label = requirement.level() == null || requirement.level().equalsIgnoreCase("Not specified")
                ? requirement.language()
                : requirement.language() + " " + requirement.level();
        if (evidence.isBlank() || !explicitLevel) missing.add(label);
        else matched.add(new SkillEvidence(label, evidence));
    }

    private static String findEvidence(String text, List<String> aliases) {
        return Pattern.compile("\\n+|(?<=[.!?])\\s+")
                .splitAsStream(text)
                .map(String::trim)
                .filter(sentence -> sentence.length() >= 3)
                .filter(sentence -> aliases.stream().anyMatch(alias -> sentence.toLowerCase(Locale.ROOT).contains(alias)))
                .findFirst()
                .map(sentence -> sentence.length() > 220 ? sentence.substring(0, 217) + "..." : sentence)
                .orElse("");
    }

    private static List<String> aliasesFor(String name) {
        return SKILL_ALIASES.getOrDefault(name, List.of(name.toLowerCase(Locale.ROOT)));
    }

    private int score(List<ChecklistItem> checklist) {
        int points = checklist.stream().mapToInt(ChecklistItem::points).sum();
        int maximum = checklist.stream().mapToInt(ChecklistItem::maxPoints).sum();
        return maximum == 0 ? 0 : Math.round(100f * points / maximum);
    }

    private String strength(ChecklistItem item) {
        return switch (item.key()) {
            case "experience" -> "The experience section has recognisable chronology.";
            case "achievements" -> "The CV includes at least one measurable outcome.";
            case "skills" -> "Relevant skills are grouped in a dedicated section.";
            default -> item.label() + " is clearly covered.";
        };
    }

    private String improvement(ChecklistItem item) {
        return switch (item.key()) {
            case "contact" -> "Include both a professional email address and a reachable phone number.";
            case "summary" -> "Add a concise professional profile tailored to the desired role.";
            case "experience" -> "Show each role with employer, dates, responsibilities and outcomes.";
            case "education" -> "Add relevant education, training or certifications.";
            case "skills" -> "Create a scannable skills section using role-relevant terminology.";
            case "languages" -> "List languages with an honest proficiency level such as B2 or C1.";
            case "achievements" -> "Quantify impact with truthful metrics such as time saved, users supported or percentage improvement.";
            case "length" -> "Keep the CV focused and remove repetition while preserving relevant evidence.";
            default -> "Use clear section headings and consistent formatting.";
        };
    }

    private static ChecklistItem item(String key, String label, int points, int maxPoints, String detail) {
        String status = points >= Math.ceil(maxPoints * 0.8) ? "pass" : points > 0 ? "review" : "missing";
        return new ChecklistItem(key, label, status, detail, points, maxPoints);
    }

    private static int count(Pattern pattern, String text) {
        int count = 0;
        var matcher = pattern.matcher(text);
        while (matcher.find()) count++;
        return count;
    }

    private static String sectionText(Pattern heading, String text) {
        var current = heading.matcher(text);
        if (!current.find()) return "";
        int start = current.end();
        var next = SECTION_HEADING.matcher(text);
        int end = next.find(start) ? next.start() : text.length();
        return text.substring(start, end).trim();
    }

    private static int words(String value) {
        if (value == null || value.isBlank()) return 0;
        return value.trim().split("\\s+").length;
    }

    private static int sectionItemCount(String value) {
        if (value == null || value.isBlank()) return 0;
        return (int) Pattern.compile("[,;|•]|\\R")
                .splitAsStream(value)
                .map(String::trim)
                .filter(item -> !item.isBlank() && words(item) <= 10)
                .count();
    }

    private static Pattern heading(String alternatives) {
        return Pattern.compile("(?im)^[\\p{Zs}\\t]*(?:" + alternatives + ")[\\p{Zs}\\t]*[:|]?[\\p{Zs}\\t]*$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static Map<String, List<String>> aliases() {
        Map<String, List<String>> values = new LinkedHashMap<>();
        values.put("JavaScript", List.of("javascript"));
        values.put("TypeScript", List.of("typescript"));
        values.put("Spring Boot", List.of("spring boot"));
        values.put("Java", List.of("java"));
        values.put("React", List.of("react", "react.js"));
        values.put("Angular", List.of("angular"));
        values.put("Vue", List.of("vue", "vue.js"));
        values.put("Python", List.of("python"));
        values.put("C#", List.of("c#", "c sharp"));
        values.put(".NET", List.of(".net", "dotnet"));
        values.put("Docker", List.of("docker"));
        values.put("Kubernetes", List.of("kubernetes", "k8s"));
        values.put("PostgreSQL", List.of("postgresql", "postgres"));
        values.put("SQL", List.of("sql"));
        values.put("AWS", List.of("aws", "amazon web services"));
        values.put("Azure", List.of("azure"));
        values.put("Git", List.of("git"));
        values.put("Linux", List.of("linux"));
        values.put("REST", List.of("rest", "restful"));
        values.put("JUnit", List.of("junit"));
        values.put("Cypress", List.of("cypress"));
        values.put("Scrum", List.of("scrum"));
        values.put("Project Management", List.of("project management", "projektmanagement"));
        values.put("Product Management", List.of("product management", "produktmanagement"));
        values.put("Sales", List.of("sales", "vertrieb"));
        values.put("Customer Success", List.of("customer success", "kundenbetreuung"));
        values.put("Accounting", List.of("accounting", "buchhaltung"));
        values.put("Finance", List.of("finance", "finanzen"));
        values.put("Recruiting", List.of("recruiting", "personalgewinnung"));
        values.put("Human Resources", List.of("human resources", "personalwesen", "hr"));
        values.put("Logistics", List.of("logistics", "logistik", "lagerlogistik"));
        values.put("Electrical Engineering", List.of("electrical engineering", "elektrotechnik"));
        values.put("Mechanical Engineering", List.of("mechanical engineering", "maschinenbau", "mechanische konstruktion"));
        values.put("PLC", List.of("plc", "sps"));
        values.put("Microsoft 365", List.of("microsoft 365", "m365"));
        values.put("Excel", List.of("excel"));
        return Map.copyOf(values);
    }

    public record BaselineCvReview(
            int completenessScore,
            String summary,
            List<ChecklistItem> checklist,
            List<String> strengths,
            List<String> improvements,
            JobFit jobFit
    ) {
    }
}
