package com.hardwarebilling.service;

import com.hardwarebilling.config.SecurityUtil;
import com.hardwarebilling.domain.AuditLog;
import com.hardwarebilling.repo.AuditLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** Writes audit rows inside the caller's transaction, so an audit entry exists iff the change committed. */
@Service
public class AuditService {
    private final AuditLogRepository repo;

    public AuditService(AuditLogRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public void log(String action, String entityType, Object entityId, String details) {
        AuditLog a = new AuditLog();
        a.setUsername(SecurityUtil.username());
        a.setAction(action);
        a.setEntityType(entityType);
        a.setEntityId(entityId == null ? null : String.valueOf(entityId));
        // never log secrets: callers pass only business descriptions
        a.setDetails(details != null && details.length() > 2000 ? details.substring(0, 2000) : details);
        repo.save(a);
    }

    @Transactional(readOnly = true)
    public List<AuditLog> recent(int n) {
        return repo.findAllByOrderByIdDesc(PageRequest.of(0, n));
    }
}
