package com.mapploy.backend.job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "jobs")
@Getter
@Setter
@NoArgsConstructor
public class JobEntity {

    @Id
    private String id;

    @Column(nullable = false)
    private String sourceKey;
    @Column(nullable = false)
    private String sourceName;
    @Column(nullable = false)
    private String sourceType;
    @Column(nullable = false, length = 1000)
    private String sourceFeedUrl;
    @Column(nullable = false, length = 1000)
    private String sourceUrl;
    @Column(nullable = false)
    private String externalJobId;
    @Column(nullable = false, length = 500)
    private String title;
    @Column(nullable = false, length = 500)
    private String company;
    @Column(nullable = false, length = 500)
    private String location;
    @Column(nullable = false, length = 100)
    private String category;
    private Double longitude;
    private Double latitude;
    @Column(length = 500)
    private String department;
    private String employmentType;
    private String seniority;
    private String schedule;
    private String sourceCreatedAt;
    @Column(nullable = false, columnDefinition = "text")
    private String rawDescription;
    @Column(nullable = false, length = 64)
    private String contentHash;
    @Column(nullable = false)
    private Instant firstSeenAt;
    @Column(nullable = false)
    private Instant lastSeenAt;
    @Column(nullable = false)
    private Instant lastCheckedAt;
    @Column(nullable = false)
    private boolean active;
    @Column(nullable = false)
    private boolean inScope;
    @Column(nullable = false)
    private int consecutiveMissingCount;
    @Column(nullable = false, columnDefinition = "text")
    private String baselineJson;
    @Column(nullable = false, columnDefinition = "text")
    private String analysisJson;
}
