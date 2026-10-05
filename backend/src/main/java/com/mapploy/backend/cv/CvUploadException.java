package com.mapploy.backend.cv;

public class CvUploadException extends RuntimeException {
    public CvUploadException(String message) {
        super(message);
    }

    public CvUploadException(String message, Throwable cause) {
        super(message, cause);
    }
}
