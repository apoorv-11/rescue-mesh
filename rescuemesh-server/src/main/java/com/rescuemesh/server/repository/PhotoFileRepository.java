package com.rescuemesh.server.repository;

import com.rescuemesh.server.domain.PhotoFile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PhotoFileRepository extends JpaRepository<PhotoFile, String> {
}
