package in.simplifymoney.ledgersync.report;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.Discrepancy;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The reports required by the specification:
 *   1. ledger.json — one entry per real transaction
 *   2. summary.json — per-account totals
 *   3. reconciliation.json — anything the ledger cannot account for
 */
public final class Reports {

    private Reports() {}

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static volatile List<Discrepancy> latestDiscrepancies = Collections.emptyList();

    public static void setLatestDiscrepancies(List<Discrepancy> discrepancies) {
        latestDiscrepancies = discrepancies != null ? List.copyOf(discrepancies) : List.of();
    }

    public static Map<String, Object> summary(List<NormalizedTxn> ledger) {
        Map<String, Object> accounts = new LinkedHashMap<>();
        for (String acct : new TreeSet<>(ledger.stream()
                .map(NormalizedTxn::accountLast4).toList())) {

            BigDecimal spend = ZERO;
            BigDecimal income = ZERO;
            long microCount = 0;
            BigDecimal microTotal = ZERO;
            BigDecimal transferredOut = ZERO;
            BigDecimal transferredIn = ZERO;

            for (NormalizedTxn t : ledger) {
                if (!t.accountLast4().equals(acct)) continue;

                switch (t.category()) {
                    case SPEND -> spend = spend.add(t.amount());
                    case INCOME -> income = income.add(t.amount());
                    case MICRO -> {
                        microCount++;
                        microTotal = microTotal.add(t.amount());
                    }
                    case TRANSFER -> {
                        if (t.direction() == Direction.DEBIT) {
                            transferredOut = transferredOut.add(t.amount());
                        } else {
                            transferredIn = transferredIn.add(t.amount());
                        }
                    }
                }
            }

            Map<String, Object> a = new LinkedHashMap<>();
            a.put("spend", spend.toPlainString());
            a.put("income", income.toPlainString());
            a.put("micro_count", microCount);
            a.put("micro_total", microTotal.toPlainString());
            a.put("transferred_out", transferredOut.toPlainString());
            a.put("transferred_in", transferredIn.toPlainString());
            accounts.put(acct, a);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("accounts", accounts);
        return doc;
    }

    public static Map<String, Object> ledgerDocument(List<NormalizedTxn> ledger) {
        List<Object> rows = ledger.stream().map(t -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("account_last4", t.accountLast4());
            r.put("occurred_at", t.occurredAt().toString());
            r.put("direction", t.direction().name().toLowerCase());
            r.put("amount", t.amount().toPlainString());
            r.put("category", t.category().name());
            r.put("merchant", t.merchant());
            r.put("source_message_ids", t.sourceMessageIds());
            return (Object) r;
        }).toList();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("transactions", rows);
        return doc;
    }

    public static Map<String, Object> reconciliation(List<NormalizedTxn> ledger) {
        return reconciliationDocument(latestDiscrepancies);
    }

    public static Map<String, Object> reconciliation(List<NormalizedTxn> ledger, List<Discrepancy> discrepancies) {
        return reconciliationDocument(discrepancies);
    }

    public static Map<String, Object> reconciliationDocument(List<Discrepancy> discrepancies) {
        List<Object> rows = new ArrayList<>();
        if (discrepancies != null) {
            for (Discrepancy d : discrepancies) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("account_last4", d.accountLast4());
                r.put("occurred_at", d.occurredAt().toString());
                r.put("amount", d.amount().toPlainString());
                r.put("note", d.note());
                rows.add(r);
            }
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("discrepancies", rows);
        return doc;
    }

    public static Map<Category, BigDecimal> byCategory(List<NormalizedTxn> ledger) {
        Map<Category, BigDecimal> out = new LinkedHashMap<>();
        for (Category c : Category.values()) out.put(c, ZERO);
        for (NormalizedTxn t : ledger) {
            out.put(t.category(), out.get(t.category()).add(t.amount()));
        }
        return out;
    }
}
