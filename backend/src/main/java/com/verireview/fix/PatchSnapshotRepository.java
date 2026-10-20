package com.verireview.fix;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PatchSnapshotRepository extends JpaRepository<PatchSnapshot, UUID> {

  List<PatchSnapshot> findByPatchIdOrderByFilePathAsc(UUID patchId);

  void deleteByPatchId(UUID patchId);
}
