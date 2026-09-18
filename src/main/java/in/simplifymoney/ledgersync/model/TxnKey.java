package in.simplifymoney.ledgersync.model;

import in.simplifymoney.ledgersync.parse.Dates;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;

public record TxnKey(
        String accountLast4,
        OffsetDateTime occurredAt,
        Direction direction,
        BigDecimal amount,
        String merchant) {

    public TxnKey {
        if (occurredAt != null) {
            occurredAt = occurredAt.withOffsetSameInstant(Dates.IST);
        }
        if (merchant != null) {
            merchant = merchant.trim().toUpperCase();
        }
        if (amount != null) {
            amount = amount.setScale(2, RoundingMode.HALF_UP);
        }
    }
}
