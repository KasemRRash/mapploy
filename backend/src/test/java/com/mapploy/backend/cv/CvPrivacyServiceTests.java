package com.mapploy.backend.cv;

import com.mapploy.backend.cv.CvModels.AiCvReview;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CvPrivacyServiceTests {
    private final CvPrivacyService service = new CvPrivacyService();

    @Test
    void masksHeaderContactsAndRepeatedNamesWhilePreservingCareerEvidence() {
        String original = """
                Alex Example | alex@example.com | +49 170 12345678
                Rosenstraße 17
                28195 Bremen
                https://linkedin.com/in/alex-example
                Professional Summary
                Alex Example built Java services for 500 users and improved performance by 35%.
                Work Experience
                Developer, Example GmbH | 2021 - 2025
                Education
                Bachelor of Science | 2017 - 2021
                Skills
                Java, Spring Boot, SQL, C++, C#, .NET
                """;
        var redacted = service.redact(original);

        assertThat(redacted.text()).doesNotContain("Alex", "alex", "Example", "@", "12345678", "Rosenstraße", "28195", "Bremen", "linkedin");
        assertThat(redacted.text()).contains("Java services for 500 users", "35%", "2021 - 2025", "2017 - 2021", "Spring Boot, SQL, C++, C#, .NET");
        assertThat(original).contains("alex@example.com", "Alex Example");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Alex Example", "Alex Example | alex@example.com", "Alex Example alex@example.com +49 170 12345678",
            "Full name: Alex Example", "CV\nAlex Example", "Alex Example - CV", "Name: Alex Example\nSoftware Engineer"
    })
    void recognisesCommonNameHeaderLayouts(String header) {
        var redacted = service.redact(header + "\nSkills\nJava and SQL");
        assertThat(redacted.sanitize("ALEX EXAMPLE knows SQL. Alex can clarify experience; contact Example."))
                .doesNotContainIgnoringCase("alex").doesNotContainIgnoringCase("example").contains("SQL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Élodie O’Connor", "Jörg Müller", "He Rui", "María del Río", "张伟"})
    void masksUnicodeNamesAndTheirRepeatedParts(String name) {
        var redacted = service.redact(name + "\nSkills\nJava");
        assertThat(redacted.sanitize(name + " has Java experience.")).isEqualTo("[NAME] has Java experience.");
    }

    @Test
    void filtersNamesWhenTheModelOmitsAccentsOrRepeatsOnlyPartOfACompoundName() {
        var redacted = service.redact("Élodie O'Connor\nSkills\nJava");
        assertThat(redacted.sanitize("Elodie OConnor knows Java; Connor can add dates."))
                .isEqualTo("[NAME] knows Java; [NAME] can add dates.");
    }

    @Test
    void sanitizesAllNarrativeFieldsIncludingNewContactsAndFormattingVariants() {
        var redacted = service.redact("Alex Example\nalex@example.com\n+49 170 12345678\nSkills\nJava");
        AiCvReview result = redacted.sanitize(new AiCvReview(
                "Alex\u200b Example can be contacted at alex @ example.com.",
                List.of("EXAMPLE knows Java; phone +49 (170) 1234-5678.", "Use second@example.org."),
                List.of("Alex should add details. Call 0049 170 7654321.", "Visit https://github.com/alex-example.")));
        assertThat(result.summary()).doesNotContainIgnoringCase("alex").doesNotContainIgnoringCase("example").doesNotContain("@");
        assertThat(result.strengths()).allSatisfy(value -> assertThat(value)
                .doesNotContainIgnoringCase("example").doesNotContain("@", "1234", "5678"));
        assertThat(result.improvements()).allSatisfy(value -> assertThat(value)
                .doesNotContainIgnoringCase("alex").doesNotContain("7654321", "github"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Address: 12 Maple Street, Apt 4", "12 Maple Street", "Musterstraße 12", "12345 Musterstadt", "SW1A 1AA London"})
    void masksPossibleAddressLinesOutsideHeaderAndInOutput(String address) {
        var redacted = service.redact("Alex Example\nSkills\nJava\n" + address);
        assertThat(redacted.text()).doesNotContain(address).contains("Java", "[ADDRESS]");
        assertThat(redacted.sanitize("The CV includes " + address.replace("Address: ", "") + "."))
                .doesNotContain(address.replace("Address: ", ""));
    }

    @Test
    void keepsDatesMetricsAndTechnicalTermsAndDoesNotShareIdentitiesAcrossRequests() {
        var first = service.redact("Alex Example\nSkills\nJava");
        var second = service.redact("Robin Fiction\nSkills\nJava");
        String professional = "Java and SQL; 2020 - 2025; 01.02.2024; 2024-02-01; 35%; 500 users; B2 and C1.";
        assertThat(first.sanitize(professional)).isEqualTo(professional);
        assertThat(second.sanitize("Alex Example knows Java.")).isEqualTo("Alex Example knows Java.");
        assertThat(second.sanitize("Robin Fiction knows Java.")).isEqualTo("[NAME] knows Java.");
    }
}
