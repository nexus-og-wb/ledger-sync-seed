package in.simplifymoney.ledgersync.reconcile;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.Discrepancy;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Reconciles running ledger transactions against bank-stated balances.
 *
 * Scans evidence from bank alerts to identify any unexplained jumps or drops
 * in available balance that occurred without an evidencing transaction.
 */
public final class ReconciliationService {

    public List<Discrepancy> reconcile(
            List<NormalizedTxn> ledger,
            Map<String, ParsedTxn> parsedByMessageId) {
        return reconcile(ledger, parsedByMessageId, Collections.emptySet());
    }

    public List<Discrepancy> reconcile(
            List<NormalizedTxn> ledger,
            Map<String, ParsedTxn> parsedByMessageId,
            Set<String> cardAccounts) {
        List<Discrepancy> discrepancies = new ArrayList<>();

        // Group transactions by account
        Map<String, List<NormalizedTxn>> byAccount = new HashMap<>();
        for (NormalizedTxn t : ledger) {
            byAccount.computeIfAbsent(t.accountLast4(), k -> new ArrayList<>()).add(t);
        }

        for (Map.Entry<String, List<NormalizedTxn>> entry : byAccount.entrySet()) {
            String accountLast4 = entry.getKey();
            if (cardAccounts.contains(accountLast4)) {
                // Credit card limits fluctuate with payments/authorizations, not a bank balance
                // ledger
                continue;
            }

            List<NormalizedTxn> txns = entry.getValue();
            txns.sort(Comparator.comparing(NormalizedTxn::occurredAt));

            BigDecimal runningBalance = null;

            for (NormalizedTxn t : txns) {
                // Find stated balance from source messages, if any
                BigDecimal stated = null;
                for (String mid : t.sourceMessageIds()) {
                    ParsedTxn pt = parsedByMessageId.get(mid);
                    if (pt != null && pt.statedBalance() != null) {
                        stated = pt.statedBalance();
                        break;
                    }
                }

                if (stated != null) {
                    if (runningBalance == null) {
                        // Baseline opening balance from first stated balance
                        runningBalance = stated;
                    } else {
                        BigDecimal expectedBal = (t.direction() == Direction.DEBIT)
                                ? runningBalance.subtract(t.amount())
                                : runningBalance.add(t.amount());

                        BigDecimal diff = expectedBal.subtract(stated);
                        if (diff.compareTo(BigDecimal.ZERO) != 0) {
                            BigDecimal absDiff = diff.abs().setScale(2, RoundingMode.HALF_UP);
                            String note = diff.signum() > 0
                                    ? "Bank stated balance dropped by " + absDiff.toPlainString()
                                            + " without an evidencing transaction"
                                    : "Bank stated balance increased by " + absDiff.toPlainString()
                                            + " without an evidencing transaction";

                            discrepancies.add(new Discrepancy(
                                    accountLast4,
                                    t.occurredAt(),
                                    absDiff,
                                    note));

                            // Re-align running balance to the bank's stated balance
                            runningBalance = stated;
                        } else {
                            runningBalance = expectedBal;
                        }
                    }
                } else {
                    if (runningBalance != null) {
                        runningBalance = (t.direction() == Direction.DEBIT)
                                ? runningBalance.subtract(t.amount())
                                : runningBalance.add(t.amount());
                    }
                }
            }
        }

        discrepancies.sort(Comparator.comparing(Discrepancy::occurredAt));
        return discrepancies;
    }
}
