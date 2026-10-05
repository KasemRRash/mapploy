package com.mapploy.backend.job;

public class JobNotFoundException extends RuntimeException {
    public JobNotFoundException(String id) {
        super("Job not found: " + id);
    }
}
