package utp.siga.audit.application.port.in;

import utp.siga.audit.application.dto.AuditPageDto;
import utp.siga.audit.application.query.SearchAuditEventsQuery;

public interface SearchAuditEventsUseCase {

    AuditPageDto search(SearchAuditEventsQuery query);
}
