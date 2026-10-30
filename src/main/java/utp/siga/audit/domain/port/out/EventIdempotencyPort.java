package utp.siga.audit.domain.port.out;

import java.util.UUID;

public interface EventIdempotencyPort {

    boolean claim(UUID eventId, String consumer);

    boolean isProcessed(UUID eventId, String consumer);

    void recordProcessed(UUID eventId, String consumer);
}
