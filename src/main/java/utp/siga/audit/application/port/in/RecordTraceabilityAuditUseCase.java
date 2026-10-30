package utp.siga.audit.application.port.in;

import java.util.UUID;
import utp.siga.audit.application.command.RecordTraceabilityAuditCommand;

public interface RecordTraceabilityAuditUseCase {
    boolean record(UUID eventId, String consumer, RecordTraceabilityAuditCommand command);
}
