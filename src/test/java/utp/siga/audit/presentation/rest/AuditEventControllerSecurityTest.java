package utp.siga.audit.presentation.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import utp.siga.audit.application.dto.AuditEventDto;
import utp.siga.audit.application.dto.AuditPageDto;
import utp.siga.audit.application.port.in.SearchAuditEventsUseCase;
import utp.siga.audit.application.query.SearchAuditEventsQuery;
import utp.siga.audit.infrastructure.security.SecurityConfiguration;

@WebMvcTest(
        controllers = AuditEventController.class,
        properties = {
            "audit.security.jwks-uri=http://127.0.0.1:1/.well-known/jwks.json",
            "audit.security.issuer=siga-identity",
            "audit.security.audience=siga-api"
        })
@Import(SecurityConfiguration.class)
class AuditEventControllerSecurityTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private SearchAuditEventsUseCase searchAuditEventsUseCase;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void requestWithoutJwtReturns401WithProblemDetails() throws Exception {
        String correlationId = UUID.randomUUID().toString();

        mvc.perform(get("/api/v1/audit-events")
                        .header("X-Correlation-Id", correlationId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.correlationId").value(correlationId));
    }

    @Test
    void requestWithJwtLackingAuditReadReturns403WithProblemDetails() throws Exception {
        UUID actor = UUID.randomUUID();
        String correlationId = UUID.randomUUID().toString();

        mvc.perform(get("/api/v1/audit-events")
                        .header("X-Correlation-Id", correlationId)
                        .with(jwtWith(actor, "INVENTORY_READ")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"))
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.correlationId").value(correlationId));
    }

    @Test
    void requestWithAuditReadPermissionReturns200AndAuditPage() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID auditId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-10-14T22:00:00Z");

        AuditEventDto eventDto = new AuditEventDto(
                auditId,
                "MovementConfirmed",
                actor,
                "Movement",
                movementId,
                "MOVEMENT_CONFIRMED",
                "SUCCESS",
                correlationId,
                Map.of("movementCode", "MOV-123"),
                occurredAt);

        AuditPageDto pageDto = new AuditPageDto(List.of(eventDto), 0, 20, 1L);

        when(searchAuditEventsUseCase.search(any())).thenReturn(pageDto);

        mvc.perform(get("/api/v1/audit-events")
                        .with(jwtWith(actor, "AUDIT_READ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(auditId.toString()))
                .andExpect(jsonPath("$.content[0].eventType").value("MovementConfirmed"))
                .andExpect(jsonPath("$.content[0].actorId").value(actor.toString()))
                .andExpect(jsonPath("$.content[0].aggregateType").value("Movement"))
                .andExpect(jsonPath("$.content[0].aggregateId").value(movementId.toString()))
                .andExpect(jsonPath("$.content[0].action").value("MOVEMENT_CONFIRMED"))
                .andExpect(jsonPath("$.content[0].result").value("SUCCESS"))
                .andExpect(jsonPath("$.content[0].correlationId").value(correlationId.toString()))
                .andExpect(jsonPath("$.content[0].details.movementCode").value("MOV-123"))
                .andExpect(jsonPath("$.content[0].occurredAt").value(occurredAt.toString()));

        ArgumentCaptor<SearchAuditEventsQuery> captor = ArgumentCaptor.forClass(SearchAuditEventsQuery.class);
        verify(searchAuditEventsUseCase).search(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(0, captor.getValue().page());
        org.junit.jupiter.api.Assertions.assertEquals(20, captor.getValue().size());
    }

    @Test
    void paginationAcceptsDocumentedBounds() throws Exception {
        UUID actor = UUID.randomUUID();
        when(searchAuditEventsUseCase.search(any()))
                .thenAnswer(invocation -> {
                    SearchAuditEventsQuery query = invocation.getArgument(0);
                    return new AuditPageDto(List.of(), query.page(), query.size(), 0L);
                });

        mvc.perform(get("/api/v1/audit-events")
                        .param("page", "0")
                        .param("size", "1")
                        .with(jwtWith(actor, "AUDIT_READ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1));

        mvc.perform(get("/api/v1/audit-events")
                        .param("page", "1")
                        .param("size", "100")
                        .with(jwtWith(actor, "AUDIT_READ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void invalidPaginationReturns400() throws Exception {
        UUID actor = UUID.randomUUID();

        mvc.perform(get("/api/v1/audit-events")
                        .param("page", "-1")
                        .with(jwtWith(actor, "AUDIT_READ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        mvc.perform(get("/api/v1/audit-events")
                        .param("size", "0")
                        .with(jwtWith(actor, "AUDIT_READ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        mvc.perform(get("/api/v1/audit-events")
                        .param("size", "101")
                        .with(jwtWith(actor, "AUDIT_READ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void requestWithQueryParamsPassesCanonicalFiltersToUseCase() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID filterActor = UUID.randomUUID();
        UUID filterAggregateId = UUID.randomUUID();
        UUID filterCorrelation = UUID.randomUUID();

        when(searchAuditEventsUseCase.search(any()))
                .thenReturn(new AuditPageDto(List.of(), 1, 10, 0L));

        mvc.perform(get("/api/v1/audit-events")
                        .param("actorId", filterActor.toString())
                        .param("aggregateType", "Movement")
                        .param("aggregateId", filterAggregateId.toString())
                        .param("correlationId", filterCorrelation.toString())
                        .param("from", "2026-10-14T00:00:00Z")
                        .param("to", "2026-10-14T23:59:59Z")
                        .param("page", "1")
                        .param("size", "10")
                        .with(jwtWith(actor, "AUDIT_READ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(10));

        ArgumentCaptor<SearchAuditEventsQuery> captor = ArgumentCaptor.forClass(SearchAuditEventsQuery.class);
        verify(searchAuditEventsUseCase).search(captor.capture());

        SearchAuditEventsQuery query = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(filterActor, query.actorId());
        org.junit.jupiter.api.Assertions.assertEquals("Movement", query.aggregateType());
        org.junit.jupiter.api.Assertions.assertEquals(filterAggregateId, query.aggregateId());
        org.junit.jupiter.api.Assertions.assertEquals(filterCorrelation, query.correlationId());
        org.junit.jupiter.api.Assertions.assertEquals(Instant.parse("2026-10-14T00:00:00Z"), query.from());
        org.junit.jupiter.api.Assertions.assertEquals(Instant.parse("2026-10-14T23:59:59Z"), query.to());
        org.junit.jupiter.api.Assertions.assertEquals(1, query.page());
        org.junit.jupiter.api.Assertions.assertEquals(10, query.size());
    }

    private static RequestPostProcessor jwtWith(UUID actor, String authority) {
        return jwt().jwt(builder -> {
            builder.subject(actor.toString()).claim("permissions", List.of(authority));
        }).authorities(new SimpleGrantedAuthority(authority));
    }
}
