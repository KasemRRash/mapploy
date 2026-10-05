package com.mapploy.backend.cv;

import com.mapploy.backend.analysis.AnalysisJsonService;
import com.mapploy.backend.analysis.AnalysisModels.Analysis;
import com.mapploy.backend.analysis.AnalysisModels.EvidenceItem;
import com.mapploy.backend.analysis.OllamaStatusService;
import com.mapploy.backend.analysis.OllamaStatusService.OllamaStatus;
import com.mapploy.backend.cv.CvChecklistService.BaselineCvReview;
import com.mapploy.backend.cv.CvModels.AiCvReview;
import com.mapploy.backend.cv.CvModels.CvReview;
import com.mapploy.backend.cv.CvPrivacyService.RedactedCv;
import com.mapploy.backend.job.JobEntity;
import com.mapploy.backend.job.JobRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class CvAnalysisPrivacyTests {
    private static final String CV = """
            Alex Example
            alex@example.com | +49 170 12345678
            Rosenstraße 17
            28195 Bremen
            Professional Summary
            Alex Example built Java services for 500 users and improved performance by 35%.
            Work Experience
            Developer, Fiction GmbH | 2021 - 2025
            Built and improved Spring Boot services backed by SQL.
            Skills
            Java, Spring Boot, SQL, Git, Docker
            """;

    private final OllamaStatusService status = mock(OllamaStatusService.class);
    private final LocalCvReviewService local = mock(LocalCvReviewService.class);
    private final CvChecklistService checklist = spy(new CvChecklistService());

    @Test
    void keepsOriginalForChecklistButCleansAiRequestAndEveryApiNarrativeField() {
        when(status.status()).thenReturn(new OllamaStatus(true, "gemma3:4b", null));
        when(local.model()).thenReturn("gemma3:4b");
        when(local.review(any(), isNull(), any())).thenReturn(new AiCvReview(
                "Alex Example: alex@example.com, +49 170 12345678.",
                List.of("Alex has Java skills; alex@example.com; +49 170 12345678."),
                List.of("Example should clarify experience; alex@example.com; +49 170 12345678.")));

        CvReview result = service(local).analyze(upload(), null);

        assertThat(result.engine()).isEqualTo("local-ai");
        assertPrivate(result);
        assertThat(result.checklist()).filteredOn(item -> item.key().equals("contact"))
                .singleElement().satisfies(item -> assertThat(item.points()).isEqualTo(15));
        var raw = ArgumentCaptor.forClass(String.class);
        verify(checklist).review(raw.capture(), anyInt(), isNull(), isNull());
        assertThat(raw.getValue()).contains("Alex Example", "alex@example.com", "+49 170 12345678");
        var sent = ArgumentCaptor.forClass(RedactedCv.class);
        verify(local).review(sent.capture(), isNull(), any());
        assertThat(sent.getValue().text()).doesNotContain("Alex", "Example", "@", "12345678", "Rosenstraße", "Bremen");
    }

    @Test
    void verifiesActualOllamaPromptAndSanitizesReturnedModelResponse() {
        when(status.status()).thenReturn(new OllamaStatus(true, "gemma3:4b", null));
        OllamaChatModel model = mock(OllamaChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("""
                {"summary":"Alex Example has experience; alex@example.com.",
                 "strengths":["Alex developed Java services; +49 170 12345678."],
                 "improvements":["Example should clarify experience; Rosenstraße 17, 28195 Bremen."]}
                """)))));
        CvReview result = service(new LocalCvReviewService(model, "gemma3:4b")).analyze(upload(), null);

        assertThat(result.engine()).isEqualTo("local-ai");
        assertPrivate(result);
        var prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        String sent = prompt.getValue().getInstructions().stream().map(message -> message.getText())
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(sent).doesNotContain("Alex", "Example", "@", "12345678", "Rosenstraße", "Bremen", "private-cv.txt");
        assertThat(sent).contains("Java services for 500 users", "35%", "15/15 points");
    }

    @Test
    void sanitizesSelectedJobContextBeforeTruncationAndPromptAssembly() {
        OllamaChatModel model = mock(OllamaChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "{\"summary\":\"A summary.\",\"strengths\":[],\"improvements\":[]}")))));
        JobEntity job = new JobEntity();
        job.setTitle("Developer for Alex Example");
        job.setCompany("Fiction");
        job.setRawDescription("Java skills required. Contact Alex Example at alex@example.com or +49 170 12345678.");
        BaselineCvReview baseline = new CvChecklistService().review(CV, 80, null, null);
        new LocalCvReviewService(model, "test").review(new CvPrivacyService().redact(CV), job, baseline);
        var prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getContents()).doesNotContain("Alex", "Example", "@", "12345678");
    }

    @Test
    void alsoFiltersOriginalCvEvidenceReturnedByTheRulesOnlyJobMatch() {
        when(status.status()).thenReturn(new OllamaStatus(false, null, "offline"));
        JobEntity job = new JobEntity();
        job.setId("job-privacy");
        job.setTitle("Java Developer");
        job.setCompany("Fiction GmbH");
        job.setAnalysisJson("test-analysis");
        JobRepository repository = mock(JobRepository.class);
        when(repository.findById("job-privacy")).thenReturn(Optional.of(job));
        AnalysisJsonService json = mock(AnalysisJsonService.class);
        when(json.read("test-analysis")).thenReturn(new Analysis(
                "rules", "test", "2026-09-12T00:00:00Z", "Java role", "Unclear", "Unclear", "", false,
                List.of(new EvidenceItem("Java", "Java is required.", true)),
                List.of(), List.of(), List.of(), List.of(), List.of(), "high"));
        CvReview result = new CvAnalysisService(new CvTextExtractor(), checklist, local, status,
                repository, json, new CvPrivacyService()).analyze(upload(), "job-privacy");

        assertThat(result.engine()).isEqualTo("rules");
        assertThat(result.jobFit().matchScore()).isEqualTo(100);
        assertThat(result.jobFit().matchedRequirements()).singleElement().satisfies(item -> {
            assertThat(item.name()).isEqualTo("Java");
            assertThat(item.evidence()).contains("Java services for 500 users").doesNotContain("Alex", "Example");
        });
        verifyNoInteractions(local);
    }

    @Test
    void retainsDeterministicReportWhenOllamaIsOfflineOrFailsOrReturnsEmptyFields() {
        when(status.status()).thenReturn(new OllamaStatus(false, null, "offline"));
        CvReview offline = service(local).analyze(upload(), null);
        verifyNoInteractions(local);
        assertThat(offline.engine()).isEqualTo("rules");
        assertPrivate(offline);

        when(status.status()).thenReturn(new OllamaStatus(true, "gemma3:4b", null));
        when(local.review(any(), isNull(), any())).thenThrow(new IllegalStateException("Malformed model response"));
        CvReview failed = service(local).analyze(upload(), null);
        assertThat(failed.engine()).isEqualTo("rules");
        assertThat(failed.completenessScore()).isEqualTo(offline.completenessScore());
        assertThat(failed.summary()).isEqualTo(offline.summary());
        assertPrivate(failed);

        reset(local);
        when(local.review(any(), isNull(), any())).thenReturn(new AiCvReview(null, Arrays.asList(null, " "), List.of()));
        CvReview empty = service(local).analyze(upload(), null);
        assertThat(empty.summary()).isEqualTo(offline.summary());
        assertThat(empty.strengths()).isEqualTo(offline.strengths());
        assertThat(empty.improvements()).isEqualTo(offline.improvements());
    }

    private CvAnalysisService service(LocalCvReviewService reviewService) {
        return new CvAnalysisService(new CvTextExtractor(), checklist, reviewService, status,
                mock(JobRepository.class), mock(AnalysisJsonService.class), new CvPrivacyService());
    }

    private static MockMultipartFile upload() {
        return new MockMultipartFile("file", "private-cv.txt", "text/plain", CV.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertPrivate(CvReview result) {
        var values = new java.util.ArrayList<>(result.strengths());
        values.addAll(result.improvements());
        values.add(result.summary());
        assertThat(values).allSatisfy(value -> assertThat(value)
                .doesNotContainIgnoringCase("alex").doesNotContainIgnoringCase("example")
                .doesNotContain("@", "12345678", "Rosenstraße", "28195", "Bremen"));
    }
}
