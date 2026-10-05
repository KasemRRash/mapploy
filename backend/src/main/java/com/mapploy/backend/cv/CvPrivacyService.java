package com.mapploy.backend.cv;

import com.mapploy.backend.cv.CvModels.AiCvReview;
import com.mapploy.backend.cv.CvModels.JobFit;
import com.mapploy.backend.cv.CvModels.SkillEvidence;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Deterministic, request-local redaction. No CV text or identity dictionary is retained by the service. */
@Service
public class CvPrivacyService {
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;
    private static final Pattern EMAIL = pattern("[\\p{L}\\p{N}._%+\\-]+\\s*@\\s*[\\p{L}\\p{N}.\\-]+\\s*\\.\\s*[\\p{L}]{2,}");
    private static final Pattern URL = pattern("(?:https?://|www\\.)[^\\s<>]+|(?:linkedin\\.com/in/|github\\.com/)[^\\s<>]+");
    private static final Pattern PHONE = pattern("(?<![\\p{L}\\p{N}])\\+?\\d(?:[\\h()./\\-]*\\d){6,14}(?![\\p{L}\\p{N}])");
    private static final Pattern DATE_RANGE = pattern("(?:19|20)\\d{2}\\h*[-–—/]\\h*(?:19|20)\\d{2}");
    private static final Pattern DATE = pattern("(?:\\d{1,2}[./-]){2}(?:\\d{4}|\\d{2})|\\d{4}[-/]\\d{2}[-/]\\d{2}");
    private static final Pattern NAME_LABEL = pattern("(?:^|[|;])\\h*(?:(?:full|first|last|given|family)\\h+name|name|vorname|nachname|nom|prénom|nombre|jméno)\\h*[:=]\\h*(.+)");
    private static final Pattern ADDRESS_LABEL = pattern("(?:^|[|;])\\h*(?:home\\h+address|postal\\h+address|address|adresse|anschrift|wohnort|location|residence)\\h*[:=]\\h*.+");
    private static final String STREET_WORD = "(?:street|st\\.?|road|rd\\.?|avenue|ave\\.?|lane|ln\\.?|drive|dr\\.?|way|boulevard|blvd\\.?|court|ct\\.?)";
    private static final Pattern STREET = pattern("\\b\\d{1,5}[a-z]?\\h+(?:[\\p{L}.'’\\-]+\\h+){1,5}" + STREET_WORD
            + "\\b[^\\r\\n|;]*|\\b[\\p{L}.'’\\-]+(?:straße|strasse|str\\.|weg|allee|platz|gasse)\\h+\\d{1,5}[a-z]?[^\\r\\n|;]*");
    private static final Pattern POSTAL_CITY = pattern("^(?:[A-Z]{1,2}-)?\\d{4,6}\\h+[\\p{L}][\\p{L}\\h.'’\\-]+$|^[A-Z]{1,2}\\d[A-Z\\d]?\\h*\\d[A-Z]{2}(?:\\h+.+)?$");
    private static final Pattern SECTION = pattern("^(?:professional\\h+summary|profile|about\\h+me|summary|profil|kurzprofil|über\\h+mich|"
            + "work\\h+experience|professional\\h+experience|employment|experience|berufserfahrung|beruflicher\\h+werdegang|praxis|"
            + "education|academic\\h+background|ausbildung|studium|bildung|skills|technical\\h+skills|competencies|technologies|"
            + "kenntnisse|fähigkeiten|kompetenzen|languages|language\\h+skills|sprachen|sprachkenntnisse|projects|projekte|"
            + "certifications|zertifikate|references|referenzen)(?:\\h*[:|].*)?$");
    private static final Pattern NON_NAME = pattern("\\b(?:cv|curriculum|vitae|resume|résumé|contact|details|personal|information|name|full|"
            + "developer|engineer|manager|designer|analyst|consultant|student|specialist|assistant|director|"
            + "software|backend|frontend|fullstack|senior|junior|java|python|skills|experience|education|"
            + "gmbh|university|college|bachelor|master|phone|email|address|tel|mobile|portfolio|linkedin|github)\\b");
    private static final Pattern NAME_SHAPE = pattern("[\\p{L}][\\p{L}\\p{M}.'’\\-]*(?:[\\h,]+[\\p{L}][\\p{L}\\p{M}.'’\\-]*){0,5}");
    private static final Pattern PARTICLE = pattern("(?:de|del|der|van|von|da|di|du|la|le|al|bin|mr|mrs|ms|dr|prof)\\.?");

    public RedactedCv redact(String original) {
        String text = normalize(original);
        Map<String, String> identities = new LinkedHashMap<>();
        StringBuilder masked = new StringBuilder();
        boolean header = true;
        int nonEmptyLines = 0;
        for (String line : text.split("\\R", -1)) {
            String trimmed = line.trim();
            if (SECTION.matcher(trimmed).matches()) header = false;
            if (!trimmed.isBlank()) nonEmptyLines++;
            // Unknown document layouts receive conservative header masking as well.
            boolean inHeader = header && nonEmptyLines <= 16;
            boolean address = ADDRESS_LABEL.matcher(trimmed).find() || STREET.matcher(trimmed).find()
                    || POSTAL_CITY.matcher(trimmed).matches();
            var label = NAME_LABEL.matcher(trimmed);
            boolean named = label.find();
            if (named) rememberName(label.group(1).split("[|;]", 2)[0], identities);
            if (inHeader && !address) {
                for (String part : trimmed.split("[|;•]|\\h+-\\h+")) {
                    String candidate = part.trim();
                    var contact = EMAIL.matcher(candidate);
                    if (contact.find()) candidate = candidate.substring(0, contact.start()).trim();
                    var phone = PHONE.matcher(candidate);
                    if (phone.find()) candidate = candidate.substring(0, phone.start()).trim();
                    if (!NON_NAME.matcher(candidate).find() && NAME_SHAPE.matcher(candidate).matches()) {
                        rememberName(candidate, identities);
                    }
                }
            }
            if (address) {
                remember(trimmed.replaceFirst("^[^:]+:\\h*", ""), "[ADDRESS]", identities);
                STREET.matcher(trimmed).results().forEach(match -> remember(match.group(), "[ADDRESS]", identities));
            }
            if (inHeader || address || named) {
                if (!trimmed.isBlank()) masked.append(address ? "[ADDRESS]" : "[PERSONAL DETAILS]");
            } else {
                masked.append(line);
            }
            masked.append('\n');
        }
        // Collect contact values before header removal, including variants returned with different spacing.
        EMAIL.matcher(text).results().forEach(match -> remember(match.group(), "[EMAIL]", identities));
        URL.matcher(text).results().forEach(match -> remember(match.group(), "[LINK]", identities));
        PHONE.matcher(text).results().filter(match -> isPhone(match.group()))
                .forEach(match -> remember(match.group(), "[PHONE]", identities));
        List<Replacement> replacements = identities.entrySet().stream()
                .sorted(Map.Entry.<String, String>comparingByKey(Comparator.comparingInt(String::length)).reversed())
                .map(entry -> new Replacement(literal(entry.getKey()), entry.getValue()))
                .toList();
        return new RedactedCv(masked.toString().strip(), replacements);
    }

    private static void rememberName(String value, Map<String, String> identities) {
        String name = value.trim().replaceFirst("(?iu)^(?:(?:mr|mrs|ms|dr|prof)\\.?)\\h+", "");
        if (!NAME_SHAPE.matcher(name).matches()) return;
        rememberNameVariant(name, identities);
        for (String token : name.split("[\\h,]+")) {
            if (token.length() >= 2 && !PARTICLE.matcher(token).matches()) rememberNameVariant(token, identities);
        }
    }

    private static void rememberNameVariant(String name, Map<String, String> identities) {
        remember(name, "[NAME]", identities);
        // Models sometimes omit accents even when returning otherwise identical names.
        remember(Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", ""), "[NAME]", identities);
        for (String part : name.split("[-']")) {
            if (part.length() >= 2 && !PARTICLE.matcher(part).matches()) remember(part, "[NAME]", identities);
        }
    }

    private static void remember(String value, String replacement, Map<String, String> identities) {
        if (!value.isBlank()) identities.put(value.trim(), replacement);
    }

    private static Pattern literal(String value) {
        String expression = String.join("[\\s\\p{P}]*", Pattern.compile("[\\s\\p{P}]+").splitAsStream(value)
                .filter(part -> !part.isEmpty()).map(Pattern::quote).toList());
        return pattern("(?<![\\p{L}\\p{N}])" + expression + "(?![\\p{L}\\p{N}])");
    }

    private static boolean isPhone(String value) {
        return !DATE_RANGE.matcher(value).matches() && !DATE.matcher(value).matches();
    }

    private static Pattern pattern(String expression) {
        return Pattern.compile(expression, FLAGS);
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("\\p{Cf}", "")
                .replace('’', '\'').replace('–', '-').replace('—', '-');
    }

    private record Replacement(Pattern pattern, String value) { }

    /** Kept only on the analyze call stack; deliberately has no text-bearing toString(). */
    public static final class RedactedCv {
        private final String text;
        private final List<Replacement> replacements;

        private RedactedCv(String masked, List<Replacement> replacements) {
            this.replacements = List.copyOf(replacements);
            this.text = sanitize(masked);
        }

        public String text() {
            return text;
        }

        public String sanitize(String value) {
            if (value == null) return null;
            String result = normalize(value);
            result = EMAIL.matcher(result).replaceAll("[EMAIL]");
            result = URL.matcher(result).replaceAll("[LINK]");
            result = PHONE.matcher(result).replaceAll(match -> isPhone(match.group()) ? "[PHONE]" : match.group());
            for (Replacement replacement : replacements) {
                result = replacement.pattern().matcher(result).replaceAll(replacement.value());
            }
            result = STREET.matcher(result).replaceAll("[ADDRESS]");
            List<String> lines = new ArrayList<>();
            for (String line : result.split("\\R", -1)) {
                lines.add(ADDRESS_LABEL.matcher(line).find() || POSTAL_CITY.matcher(line.trim()).matches()
                        ? "[ADDRESS]" : line);
            }
            return String.join("\n", lines);
        }

        public AiCvReview sanitize(AiCvReview review) {
            return new AiCvReview(sanitize(review.summary()), sanitize(review.strengths()), sanitize(review.improvements()));
        }

        public JobFit sanitize(JobFit fit) {
            if (fit == null) return null;
            return new JobFit(fit.jobId(), sanitize(fit.title()), sanitize(fit.company()), fit.matchScore(),
                    evidence(fit.matchedRequirements()), sanitize(fit.missingRequirements()),
                    evidence(fit.matchedOptionalSkills()), sanitize(fit.explanation()));
        }

        private List<String> sanitize(List<String> values) {
            return values == null ? null : values.stream().map(this::sanitize).toList();
        }

        private List<SkillEvidence> evidence(List<SkillEvidence> values) {
            return values.stream().map(item -> new SkillEvidence(sanitize(item.name()), sanitize(item.evidence()))).toList();
        }
    }
}
