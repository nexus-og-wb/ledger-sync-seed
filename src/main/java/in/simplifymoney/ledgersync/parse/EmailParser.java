package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bank transaction alert emails.
 *
 * Supports email transaction alerts from banks such as HDFC Bank (alerts@hdfcbank.net)
 * and ICICI Bank (alerts@icicibank.com).
 *
 * Standard email format:
 *   Date: Wed, 01 Jul 2026 09:02:00 +0530
 *   Subject: Transaction alert on your account
 *   Dear Customer,
 *   Your account ending 4821 has been credited with INR 45,000.
 *   Merchant / Remarks: SALARY CREDIT
 *   Transaction reference: 1597155421
 */
public final class EmailParser implements MessageParser {

    private static final Pattern DATE_PATTERN =
            Pattern.compile("^Date:\\s*(.+)$", Pattern.MULTILINE);

    private static final Pattern ACCT_PATTERN =
            Pattern.compile("account ending (\\d{4})", Pattern.CASE_INSENSITIVE);

    private static final Pattern DIR_PATTERN =
            Pattern.compile("has been (debited|credited) with", Pattern.CASE_INSENSITIVE);

    private static final Pattern MERCHANT_PATTERN =
            Pattern.compile("Merchant / Remarks:\\s*(.+)$", Pattern.MULTILINE);

    private static final DateTimeFormatter[] DATE_FORMATTERS = new DateTimeFormatter[] {
            DateTimeFormatter.RFC_1123_DATE_TIME,
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss Z", Locale.ENGLISH)
    };

    @Override
    public boolean supports(RawMessage m) {
        return "email".equalsIgnoreCase(m.channel());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        String body = m.body();

        Matcher mAcct = ACCT_PATTERN.matcher(body);
        if (!mAcct.find()) {
            return Optional.empty();
        }
        String accountLast4 = mAcct.group(1);

        Matcher mDir = DIR_PATTERN.matcher(body);
        if (!mDir.find()) {
            return Optional.empty();
        }
        Direction direction = "debited".equalsIgnoreCase(mDir.group(1))
                ? Direction.DEBIT
                : Direction.CREDIT;

        BigDecimal amount = Amounts.first(body);
        if (amount == null) {
            return Optional.empty();
        }

        Matcher mMerch = MERCHANT_PATTERN.matcher(body);
        String merchant = mMerch.find() ? mMerch.group(1).trim() : "UNKNOWN";

        OffsetDateTime occurredAt = parseEmailDate(body);
        if (occurredAt == null) {
            // Fallback to message received timestamp converted to IST if no date header
            occurredAt = m.receivedAt().withOffsetSameInstant(Dates.IST);
        }

        return Optional.of(new ParsedTxn(
                accountLast4,
                occurredAt,
                direction,
                amount,
                merchant,
                Amounts.statedBalance(body),
                m.messageId()
        ));
    }

    private static OffsetDateTime parseEmailDate(String body) {
        Matcher mDate = DATE_PATTERN.matcher(body);
        if (!mDate.find()) {
            return null;
        }

        String dateStr = mDate.group(1).trim();
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                OffsetDateTime parsed = OffsetDateTime.parse(dateStr, formatter);
                return parsed.withOffsetSameInstant(Dates.IST);
            } catch (DateTimeParseException ignored) {
                // Try next formatter
            }
        }
        return null;
    }
}
