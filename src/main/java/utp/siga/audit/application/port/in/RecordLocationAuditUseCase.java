package utp.siga.audit.application.port.in;

import java.util.UUID;
import utp.siga.audit.application.command.RecordLocationAuditCommand;

public interface RecordLocationAuditUseCase {
    boolean recordLocationChanged(UUID eventId, RecordLocationAuditCommand command);
}
