package utp.siga.audit.application.dto;

import java.util.List;

public record AuditPageDto(
        List<AuditEventDto> content,
        int page,
        int size,
        long totalElements) {
}
