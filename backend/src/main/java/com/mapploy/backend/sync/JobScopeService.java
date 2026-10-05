package com.mapploy.backend.sync;

import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
public class JobScopeService {
    private static final Pattern LOCATIONS = Pattern.compile("bremen|hamburg|remote", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public boolean isInScope(ParsedJob job) {
        return LOCATIONS.matcher(value(job.location())).find();
    }

    private static String value(String input) {
        return input == null ? "" : input;
    }
}
