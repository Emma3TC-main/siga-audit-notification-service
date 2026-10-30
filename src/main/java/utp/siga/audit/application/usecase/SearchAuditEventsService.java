package utp.siga.audit.application.usecase;

import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import utp.siga.audit.application.dto.AuditPageDto;
import utp.siga.audit.application.port.in.SearchAuditEventsUseCase;
import utp.siga.audit.application.query.SearchAuditEventsQuery;
import utp.siga.audit.domain.port.out.AuditEventRepositoryPort;

@Service
public class SearchAuditEventsService implements SearchAuditEventsUseCase {

    private final AuditEventRepositoryPort auditEventRepository;

    public SearchAuditEventsService(AuditEventRepositoryPort auditEventRepository) {
        this.auditEventRepository = Objects.requireNonNull(auditEventRepository, "auditEventRepository must not be null");
    }

    @Override
    @Transactional(readOnly = true)
    public AuditPageDto search(SearchAuditEventsQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        return auditEventRepository.findByFilters(query);
    }
}
