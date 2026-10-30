package utp.siga.audit.application.port.in;

import java.util.UUID;
import utp.siga.audit.application.command.RecordIdentityAuditCommand;

public interface RecordIdentityAuditUseCase {
    boolean record(UUID eventId, RecordIdentityAuditCommand command);
}
