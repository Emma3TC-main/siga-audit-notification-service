package utp.siga.audit.presentation.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import utp.siga.audit.application.dto.NotificationDto;
import utp.siga.audit.application.dto.NotificationPageDto;
import utp.siga.audit.application.exception.NotificationNotFoundException;
import utp.siga.audit.application.port.in.NotificationUseCase;
import utp.siga.audit.application.query.ListNotificationsQuery;
import utp.siga.audit.infrastructure.security.SecurityConfiguration;
import utp.siga.audit.presentation.error.AuditHttpExceptionHandler;

@WebMvcTest(
        controllers = NotificationController.class,
        properties = {
            "audit.security.jwks-uri=http://127.0.0.1:1/.well-known/jwks.json",
            "audit.security.issuer=siga-identity",
            "audit.security.audience=siga-api"
        })
@Import({SecurityConfiguration.class, AuditHttpExceptionHandler.class})
class NotificationControllerSecurityTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private NotificationUseCase notifications;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void allNotificationEndpointsRequireAuthenticationButNoPermission() throws Exception {
        mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/notifications/unread-count")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/notifications/{id}/read", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());

        UUID subject = UUID.randomUUID();
        when(notifications.list(any())).thenReturn(new NotificationPageDto(List.of(), 0, 20, 0));
        mvc.perform(get("/api/v1/notifications").with(user(subject)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/notifications/unread-count").with(user(subject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));
    }

    @Test
    void listUsesJwtSubjectAndCanonicalDefaults() throws Exception {
        UUID subject = UUID.randomUUID();
        UUID notificationId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        when(notifications.list(any())).thenReturn(new NotificationPageDto(List.of(
                new NotificationDto(notificationId, subject, "MOVEMENT_CONFIRMED", "Movimiento confirmado",
                        "El movimiento MOV-1 (ENTRY) fue confirmado.", "INFO", "Movement", movementId,
                        null, Instant.parse("2026-10-20T12:00:00Z"))), 0, 20, 1));

        mvc.perform(get("/api/v1/notifications").with(user(subject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].recipientUserId").value(subject.toString()))
                .andExpect(jsonPath("$.content[0].referenceType").value("Movement"))
                .andExpect(jsonPath("$.content[0].severity").value("INFO"));

        ArgumentCaptor<ListNotificationsQuery> captor = ArgumentCaptor.forClass(ListNotificationsQuery.class);
        verify(notifications).list(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(subject, captor.getValue().recipientUserId());
        org.junit.jupiter.api.Assertions.assertEquals(0, captor.getValue().page());
        org.junit.jupiter.api.Assertions.assertEquals(20, captor.getValue().size());
    }

    @Test
    void invalidPaginationAndMalformedIdReturn400() throws Exception {
        UUID subject = UUID.randomUUID();
        mvc.perform(get("/api/v1/notifications").param("page", "-1").with(user(subject)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/notifications").param("size", "101").with(user(subject)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/notifications/not-a-uuid/read").with(user(subject)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void markReadReturnsEmpty200AndUsesJwtSubject() throws Exception {
        UUID subject = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        mvc.perform(post("/api/v1/notifications/{id}/read", id).with(user(subject)))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
        verify(notifications).markRead(id, subject);
    }

    @Test
    void nonOwnedAndMissingNotificationHaveSame404() throws Exception {
        UUID subject = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        doThrow(new NotificationNotFoundException()).when(notifications).markRead(id, subject);
        mvc.perform(post("/api/v1/notifications/{id}/read", id).with(user(subject)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
    }

    private static RequestPostProcessor user(UUID subject) {
        return jwt().jwt(builder -> builder.subject(subject.toString()).claim("permissions", List.of()));
    }
}
