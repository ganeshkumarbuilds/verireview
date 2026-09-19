package com.verireview.fix;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FixRequestRepository extends JpaRepository<FixRequest, UUID> {
}
