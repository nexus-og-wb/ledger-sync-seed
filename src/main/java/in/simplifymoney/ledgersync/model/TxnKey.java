package in.simplifymoney.ledgersync.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record TxnKey(
        String accountLast4,
        OffsetDateTime occurredAt,
        Direction direction,
        BigDecimal amount,
        String merchant) {

}
