package com.mapploy.backend.analysis;

import com.mapploy.backend.analysis.AnalysisModels.AiAnalysis;
import com.mapploy.backend.analysis.AnalysisModels.Analysis;
import com.mapploy.backend.job.JobEntity;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class LocalAiAnalysisService {

    public static final String SYSTEM_PROMPT = """
            You analyse a job advertisement for an evidence-grounded student prototype.

            Rules:
            - Return English labels and summaries.
            - Evidence must be copied exactly from the original German or English advertisement.
            - Never infer a language level, number of years, skill or work model that is not explicit.
            - Separate mandatory skills from optional or desirable skills.
            - Put missing or ambiguous information in unclearInformation.
            - A contradiction is a genuine tension, not merely missing information.
            """;

    private final OllamaChatModel chatModel;
    private final EvidenceVerificationService verifier;
    private final String model;

    public LocalAiAnalysisService(
            OllamaChatModel chatModel,
            EvidenceVerificationService verifier,
            @Value("${mapploy.ollama.model:gemma3:4b}") String model) {
        this.chatModel = chatModel;
        this.verifier = verifier;
        this.model = model;
    }

    public Analysis analyze(JobEntity job) {
        BeanOutputConverter<AiAnalysis> converter = new BeanOutputConverter<>(AiAnalysis.class);
        OllamaChatOptions.Builder options = OllamaChatOptions.builder();
        options.model(model);
        options.temperature(0.0);
        options.numCtx(4096);
        options.outputSchema(converter.getJsonSchema());
        options.disableThinking();

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage(userPrompt(job))), options.build());

        String response = chatModel.call(prompt).getResult().getOutput().getText();
        AiAnalysis supplied = converter.convert(response);
        if (supplied == null) {
            throw new IllegalStateException("The local model returned no structured analysis.");
        }
        return verifier.verify(job, supplied, model);
    }

    private static String userPrompt(JobEntity job) {
        String description = job.getRawDescription() == null ? "" : job.getRawDescription();
        if (description.length() > 12_000) description = description.substring(0, 12_000);
        return """
                Job title: %s
                Company: %s
                Location: %s
                Source seniority: %s

                ADVERTISEMENT:
                %s
                """.formatted(
                job.getTitle(),
                job.getCompany(),
                job.getLocation(),
                job.getSeniority() == null || job.getSeniority().isBlank() ? "not supplied" : job.getSeniority(),
                description);
    }
}
