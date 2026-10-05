package com.mapploy.backend.sync;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "sync_state")
@Getter
@Setter
@NoArgsConstructor
public class SyncStateEntity {
    @Id
    private Integer id;
    private Instant lastSyncAt;
    @Column(columnDefinition = "text")
    private String summaryJson;
    @Column(columnDefinition = "text")
    private String syncError;
}
