package com.mapploy.backend.cv;

import com.mapploy.backend.analysis.AnalysisModels.Analysis;
import com.mapploy.backend.analysis.AnalysisModels.EvidenceItem;
import com.mapploy.backend.analysis.AnalysisModels.LanguageRequirement;
import com.mapploy.backend.cv.CvChecklistService.BaselineCvReview;
import com.mapploy.backend.job.JobEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CvChecklistServiceTests {

    @Test
    void calculatesDifferentScoresForDifferentCvContent() {
        String detailedCv = """
                Alex Example | alex@example.com | +49 170 12345678
                Professional Summary
                Backend developer focused on maintainable Java services and measurable product improvements.
                Work Experience
                Software Developer, Example GmbH | 2021 - 2025
                Built and improved Spring Boot services for 500 users and reduced processing time by 35%.
                Education
                Bachelor of Science, Example University | 2017 - 2021
                Skills
                Java, Spring Boot, PostgreSQL, Docker, Git, REST
                Languages
                English C1, German B2
                """;
        String sparseCv = """
                Alex Example | alex@example.com
                Work Experience
                Developer | 2024
                Education
                Example University
                """;

        CvChecklistService service = new CvChecklistService();
        BaselineCvReview detailed = service.review(detailedCv, detailedCv.split("\\s+").length, null, null);
        BaselineCvReview sparse = service.review(sparseCv, sparseCv.split("\\s+").length, null, null);

        assertThat(detailed.completenessScore()).isGreaterThan(sparse.completenessScore());
        assertThat(detailed.completenessScore()).isNotEqualTo(sparse.completenessScore());
        assertThat(detailed.checklist()).allSatisfy(item -> {
            assertThat(item.points()).isBetween(0, item.maxPoints());
            assertThat(item.maxPoints()).isPositive();
        });
    }

    @Test
    void createsTransparentChecklistAndMatchesOnlyExplicitCvEvidence() {
        String cv = """
                Marian Example
                marian@example.com | +49 170 12345678

                Professional Summary
                Backend developer focused on reliable business applications.

                Work Experience
                Software Developer, Example GmbH | 2022 - 2025
                Built Java and Spring Boot REST services backed by PostgreSQL.
                Reduced processing time by 35% for 500 users.

                Education
                Bachelor of Science, Example University | 2018 - 2022

                Skills
                Java, Spring Boot, PostgreSQL, REST, Git

                Languages
                English C1, German C1
                """;
        JobEntity job = new JobEntity();
        job.setId("job-1");
        job.setTitle("Java Developer");
        job.setCompany("Example GmbH");

        Analysis analysis = new Analysis(
                "rules", "test", "2026-09-12T00:00:00Z", "Java role", "Unclear",
                "Unclear", "", false,
                List.of(
                        new EvidenceItem("Java", "Java is required.", true),
                        new EvidenceItem("Spring Boot", "Spring Boot is required.", true),
                        new EvidenceItem("Kubernetes", "Kubernetes is required.", true)),
                List.of(new EvidenceItem("PostgreSQL", "PostgreSQL is helpful.", true)),
                List.of(new LanguageRequirement("German", "C1", "German C1 is required.", true)),
                List.of(), List.of(), List.of(), "high");

        BaselineCvReview review = new CvChecklistService().review(
                cv, cv.split("\\s+").length, job, analysis);

        assertThat(review.checklist())
                .filteredOn(item -> item.status().equals("pass"))
                .extracting("key")
                .contains("contact", "summary", "experience", "education", "skills", "languages", "achievements", "structure");
        assertThat(review.checklist())
                .filteredOn(item -> item.key().equals("achievements"))
                .first()
                .extracting("status", "points", "maxPoints")
                .containsExactly("pass", 10, 10);
        assertThat(review.completenessScore()).isGreaterThanOrEqualTo(85);
        assertThat(review.jobFit().matchedRequirements()).extracting("name")
                .containsExactly("Java", "Spring Boot", "German C1");
        assertThat(review.jobFit().missingRequirements()).containsExactly("Kubernetes");
        assertThat(review.jobFit().matchedOptionalSkills()).extracting("name").containsExactly("PostgreSQL");
        assertThat(review.jobFit().explanation()).contains("not a hiring decision");
    }
}
