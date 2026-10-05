package com.mapploy.backend.cv;

import com.mapploy.backend.cv.CvModels.ExtractedCv;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Locale;
import java.util.Set;

@Service
public class CvTextExtractor {
    static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    private static final int MAX_EXTRACTED_CHARACTERS = 50_000;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "doc", "docx", "txt");
    private static final Set<String> ALLOWED_MEDIA_TYPES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/plain");

    private final Tika tika = new Tika();

    public ExtractedCv extract(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new CvUploadException("Choose a CV file to analyse.");
        if (file.getSize() > MAX_FILE_BYTES) throw new CvUploadException("The CV must not exceed 5 MB.");

        String fileName = safeFileName(file.getOriginalFilename());
        String extension = extension(fileName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new CvUploadException("Supported CV formats are PDF, DOC, DOCX and TXT.");
        }

        try (InputStream input = file.getInputStream()) {
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
            String mediaType = tika.detect(input, metadata).toLowerCase(Locale.ROOT);
            if (!ALLOWED_MEDIA_TYPES.contains(mediaType)) {
                throw new CvUploadException("The uploaded file content is not a supported CV format.");
            }
        } catch (CvUploadException error) {
            throw error;
        } catch (Exception error) {
            throw new CvUploadException("The CV format could not be verified.", error);
        }

        try (InputStream input = file.getInputStream()) {
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
            String text = tika.parseToString(input, metadata, MAX_EXTRACTED_CHARACTERS)
                    .replace('\u0000', ' ')
                    .replaceAll("[\\t\\x0B\\f\\r]+", " ")
                    .replaceAll("[ ]{2,}", " ")
                    .replaceAll("\\n{3,}", "\n\n")
                    .trim();
            if (text.length() < 80) {
                throw new CvUploadException("No readable CV text was found. Scanned PDFs need OCR before upload.");
            }
            String mediaType = tika.detect(fileName);
            int wordCount = text.split("\\s+").length;
            return new ExtractedCv(fileName, mediaType, text, wordCount);
        } catch (CvUploadException error) {
            throw error;
        } catch (Exception error) {
            throw new CvUploadException("The CV could not be read locally.", error);
        }
    }

    private static String safeFileName(String input) {
        String value = input == null || input.isBlank() ? "cv" : input.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1).replaceAll("[\\r\\n]", "").trim();
        return value.length() > 180 ? value.substring(value.length() - 180) : value;
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
