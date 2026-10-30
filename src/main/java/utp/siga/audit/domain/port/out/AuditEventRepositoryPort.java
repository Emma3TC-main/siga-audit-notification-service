package utp.siga.audit.domain.port.out;

import utp.siga.audit.application.dto.AuditPageDto;
import utp.siga.audit.application.query.SearchAuditEventsQuery;
import utp.siga.audit.domain.model.AuditEvent;

public interface AuditEventRepositoryPort {

    void save(AuditEvent auditEvent);

    AuditPageDto findByFilters(SearchAuditEventsQuery query);
}
