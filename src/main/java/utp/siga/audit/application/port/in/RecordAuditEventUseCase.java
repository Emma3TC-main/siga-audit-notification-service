package utp.siga.audit.application.port.in;

import java.util.UUID;
import utp.siga.audit.application.command.RecordAuditEventCommand;

public interface RecordAuditEventUseCase {

    boolean recordMovementConfirmed(UUID eventId, RecordAuditEventCommand command);
}
