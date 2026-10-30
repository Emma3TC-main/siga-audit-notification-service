package utp.siga.audit.application.dto;

import java.util.List;

public record NotificationPageDto(
        List<NotificationDto> content,
        int page,
        int size,
        long totalElements) {
}
