package com.verireview.audit;

import com.verireview.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thin append-only audit writer (SECURITY_DESIGN T12). There is intentionally
 * no update/delete path — {@code audit_logs} rows are immutable.
 */
@Service
public class AuditService {

  private final AuditLogRepository auditLogs;

  public AuditService(AuditLogRepository auditLogs) {
    this.auditLogs = auditLogs;
  }

  @Transactional
  public void record(User actor, String action, String entityType, String entityId) {
    record(actor, action, entityType, entityId, null);
  }

  @Transactional
  public void record(
      User actor, String action, String entityType, String entityId, String metadataJson) {
    AuditLog entry = new AuditLog(action, entityType, entityId);
    entry.setActor(actor);
    entry.setMetadata(metadataJson);
    auditLogs.save(entry);
  }
}
