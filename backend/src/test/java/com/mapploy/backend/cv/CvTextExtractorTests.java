package com.mapploy.backend.cv;

import com.mapploy.backend.cv.CvModels.ExtractedCv;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CvTextExtractorTests {

    @Test
    void extractsPlainTextWithoutPersistingAnything() {
        String content = """
                Professional Summary
                Local-first software developer with experience building Java services.
                Work Experience
                Developer at Example GmbH from 2022 to 2025.
                Skills
                Java, Spring Boot, PostgreSQL and Git.
                Languages
                English C1 and German B2.
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file", "../private-cv.txt", "text/plain", content.getBytes(StandardCharsets.UTF_8));

        ExtractedCv extracted = new CvTextExtractor().extract(file);

        assertThat(extracted.fileName()).isEqualTo("private-cv.txt");
        assertThat(extracted.mediaType()).isEqualTo("text/plain");
        assertThat(extracted.text()).contains("Spring Boot", "German B2");
        assertThat(extracted.wordCount()).isGreaterThan(20);
    }

    @Test
    void rejectsUnsupportedFileTypesBeforeParsing() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "cv.exe", "application/octet-stream", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> new CvTextExtractor().extract(file))
                .isInstanceOf(CvUploadException.class)
                .hasMessageContaining("PDF, DOC, DOCX and TXT");
    }
}
