package in.simplifymoney.ledgersync.model;

import in.simplifymoney.ledgersync.parse.Dates;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * An unaccounted bank balance difference.
 *
 * Corresponds to the output shape required for reconciliation.json:
 * {
 * "account_last4": "4821",
 * "occurred_at": "2026-07-29T17:06:00+05:30",
 * "amount": "7500.00",
 * "note": "..."
 * }
 */
public record Discrepancy(
        String accountLast4,
        OffsetDateTime occurredAt,
        BigDecimal amount,
        String note) {

    public Discrepancy {
        Objects.requireNonNull(accountLast4, "accountLast4");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(note, "note");

        if (accountLast4.length() != 4 || !accountLast4.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("accountLast4 must be 4 digits: " + accountLast4);
        }
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
        if (amount.scale() != 2) {
            throw new IllegalArgumentException("amount must carry exactly 2 decimal places: " + amount);
        }
        if (note.isBlank()) {
            throw new IllegalArgumentException("note must not be blank");
        }
        if (occurredAt.getOffset() == null) {
            occurredAt = occurredAt.withOffsetSameInstant(Dates.IST);
        }
    }
}
