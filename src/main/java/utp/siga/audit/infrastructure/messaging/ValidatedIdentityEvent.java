package utp.siga.audit.infrastructure.messaging;

import java.util.UUID;
import utp.siga.audit.application.command.RecordIdentityAuditCommand;

record ValidatedIdentityEvent(UUID eventId, RecordIdentityAuditCommand command) {
}
