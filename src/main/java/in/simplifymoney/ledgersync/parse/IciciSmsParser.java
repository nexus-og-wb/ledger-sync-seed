package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ICICI Bank SMS.
 *
 * Handles two formats in production:
 * Format 1 (V1):
 *   "Dear Customer, Acct XX9075 is debited/credited with INR ... on 01/07/2026 21:14. Info: merchant. Avl Bal ..."
 * Format 2 (V2):
 *   "ICICI Bank Acct XX9075 Cr/Dr INR ... on 23-Jul-2026 16:52; merchant ref no .... BalAvl ..."
 */
public final class IciciSmsParser implements MessageParser {

    public static final String SENDER = "VM-ICICIB-T";

    // Format 1: "... Acct XX9075 is debited/credited with ... on dd/MM/yyyy HH:mm. Info: merchant."
    private static final Pattern V1 = Pattern.compile(
            "Acct XX(?<acct>\\d{4}) is (?<dir>debited|credited) with .*? "
                    + "on (?<when>\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2})\\. "
                    + "Info: (?<merchant>[^.]+)\\.");

    // Format 2: "ICICI Bank Acct XX9075 Cr/Dr ... on dd-MMM-yyyy HH:mm; merchant ref no ..."
    private static final Pattern V2 = Pattern.compile(
            "ICICI Bank Acct XX(?<acct>\\d{4}) (?<dir>Cr|Dr) .*? "
                    + "on (?<when>\\d{2}-\\w{3}-\\d{4} \\d{2}:\\d{2}); "
                    + "(?<merchant>.+?)\\s+ref no");

    @Override
    public boolean supports(RawMessage m) {
        return "sms".equals(m.channel()) && SENDER.equals(m.sender());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        String body = m.body();

        // Format 1
        Matcher v1 = V1.matcher(body);
        if (v1.find()) {
            Direction dir = "debited".equals(v1.group("dir")) ? Direction.DEBIT : Direction.CREDIT;
            return build(m, v1.group("acct"), v1.group("when"), dir, v1.group("merchant"));
        }

        // Format 2
        Matcher v2 = V2.matcher(body);
        if (v2.find()) {
            Direction dir = "Cr".equals(v2.group("dir")) ? Direction.CREDIT : Direction.DEBIT;
            return build(m, v2.group("acct"), v2.group("when"), dir, v2.group("merchant"));
        }

        return Optional.empty();
    }

    private Optional<ParsedTxn> build(RawMessage m, String acct, String when,
                                      Direction dir, String merchant) {
        BigDecimal amount = Amounts.first(m.body());
        OffsetDateTime at = Dates.ist(when);
        if (amount == null || at == null) {
            return Optional.empty();
        }

        return Optional.of(new ParsedTxn(
                acct,
                at,
                dir,
                amount,
                merchant.trim(),
                Amounts.statedBalance(m.body()),
                m.messageId()
        ));
    }
}
