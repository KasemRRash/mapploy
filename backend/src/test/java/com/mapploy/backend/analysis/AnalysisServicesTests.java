package com.mapploy.backend.analysis;

import com.mapploy.backend.analysis.AnalysisModels.AiAnalysis;
import com.mapploy.backend.analysis.AnalysisModels.AiEvidenceItem;
import com.mapploy.backend.analysis.AnalysisModels.Analysis;
import com.mapploy.backend.job.JobEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisServicesTests {

    @Test
    void baselineFindsExplicitRequirementsWithoutInventingYearsOrSalary() {
        JobEntity job = job("Java Developer", """
                [Your profile]
                You work with Java, Spring Boot and PostgreSQL.
                Idealerweise, you also know Docker.
                Fluent German at C1 level is required.
                Hybrid work in Hamburg is possible.
                """);

        Analysis analysis = new BaselineAnalysisService().analyze(job);

        assertThat(analysis.requiredSkills()).extracting("name").contains("Java", "Spring Boot", "PostgreSQL");
        assertThat(analysis.optionalSkills()).extracting("name").contains("Docker");
        assertThat(analysis.languageRequirements()).first().extracting("level").isEqualTo("C1");
        assertThat(analysis.workplaceModel()).isEqualTo("Hybrid");
        assertThat(analysis.unclearInformation()).contains(
                "No salary range was found.",
                "No explicit number of years of experience was found.");
    }

    @Test
    void verifierMarksModelEvidenceThatIsNotInTheAdvertisement() {
        JobEntity job = job("Backend Developer", "Java and Spring Boot are required. Remote work is possible.");
        EvidenceVerificationService verifier = new EvidenceVerificationService(new BaselineAnalysisService());
        AiAnalysis ai = new AiAnalysis(
                "Backend role",
                "Unclear",
                "Remote",
                "Remote work is possible.",
                List.of(new AiEvidenceItem("Kubernetes", "Kubernetes is required.")),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "high");

        Analysis verified = verifier.verify(job, ai, "test-model");

        assertThat(verified.requiredSkills()).first().extracting("evidenceVerified").isEqualTo(false);
        assertThat(verified.confidence()).isEqualTo("medium");
        assertThat(verified.workplaceEvidenceVerified()).isTrue();
    }

    private static JobEntity job(String title, String description) {
        JobEntity job = new JobEntity();
        job.setTitle(title);
        job.setRawDescription(description);
        job.setSeniority("");
        return job;
    }
}
