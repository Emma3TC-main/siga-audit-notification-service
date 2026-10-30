package utp.siga.audit.presentation.rest;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import utp.siga.audit.application.dto.AuditPageDto;
import utp.siga.audit.application.port.in.SearchAuditEventsUseCase;
import utp.siga.audit.application.query.SearchAuditEventsQuery;

@RestController
@RequestMapping("/api/v1/audit-events")
public class AuditEventController {

    private final SearchAuditEventsUseCase searchAuditEventsUseCase;

    public AuditEventController(SearchAuditEventsUseCase searchAuditEventsUseCase) {
        this.searchAuditEventsUseCase = Objects.requireNonNull(searchAuditEventsUseCase, "searchAuditEventsUseCase must not be null");
    }

    @GetMapping
    public ResponseEntity<AuditPageDto> searchAuditEvents(
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String aggregateType,
            @RequestParam(required = false) UUID aggregateId,
            @RequestParam(required = false) UUID correlationId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        SearchAuditEventsQuery query = new SearchAuditEventsQuery(
                actorId,
                aggregateType,
                aggregateId,
                correlationId,
                from,
                to,
                page,
                size);

        AuditPageDto result = searchAuditEventsUseCase.search(query);
        return ResponseEntity.ok(result);
    }
}
