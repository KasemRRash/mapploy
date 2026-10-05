package com.mapploy.backend.api;

import com.mapploy.backend.analysis.AnalysisFailedException;
import com.mapploy.backend.job.JobNotFoundException;
import com.mapploy.backend.cv.CvUploadException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(JobNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(JobNotFoundException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Job not found."));
    }

    @ExceptionHandler(AnalysisFailedException.class)
    public ResponseEntity<Map<String, Object>> analysisFailure(AnalysisFailedException error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error.getMessage());
        body.put("fallback", error.getFallback());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    @ExceptionHandler(CvUploadException.class)
    public ResponseEntity<Map<String, Object>> cvUploadFailure(CvUploadException error) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", error.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> cvTooLarge(MaxUploadSizeExceededException error) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("error", "The CV must not exceed 5 MB."));
    }
}
