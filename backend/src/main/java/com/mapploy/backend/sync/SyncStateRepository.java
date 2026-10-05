package com.mapploy.backend.sync;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncStateRepository extends JpaRepository<SyncStateEntity, Integer> {
}
