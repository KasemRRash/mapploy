package com.mapploy.backend.analysis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class OllamaStatusService {
    private final RestClient restClient;
    private final String preferredModel;

    public OllamaStatusService(
            @Value("${mapploy.ollama.base-url:http://127.0.0.1:11434}") String baseUrl,
            @Value("${mapploy.ollama.model:gemma3:4b}") String preferredModel) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory(client))
                .build();
        this.preferredModel = preferredModel;
    }

    public OllamaStatus status() {
        try {
            JsonNode payload = restClient.get().uri("/api/tags").retrieve().body(JsonNode.class);
            List<String> models = new ArrayList<>();
            if (payload != null && payload.path("models").isArray()) {
                payload.path("models").forEach(node -> {
                    String name = node.path("name").asText("");
                    if (!name.isBlank()) models.add(name);
                });
            }
            String selected = models.stream()
                    .filter(name -> name.equals(preferredModel) || name.startsWith(preferredModel + ":"))
                    .findFirst()
                    .orElse(models.isEmpty() ? null : models.getFirst());
            return new OllamaStatus(true, selected, null);
        } catch (Exception error) {
            return new OllamaStatus(false, null, rootMessage(error));
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? error.getClass().getSimpleName() : current.getMessage();
    }

    public record OllamaStatus(boolean available, String model, String error) {
    }
}
