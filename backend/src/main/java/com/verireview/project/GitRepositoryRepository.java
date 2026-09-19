package com.verireview.project;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GitRepositoryRepository extends JpaRepository<GitRepository, UUID> {
}
