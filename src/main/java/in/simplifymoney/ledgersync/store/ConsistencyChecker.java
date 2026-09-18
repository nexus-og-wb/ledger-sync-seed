package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Proves the two stores agree, and says precisely where they do not.
 *
 * Checks:
 *  - Missing transactions in documents
 *  - Missing transactions in SQL
 *  - Field-by-field differences (amount, category, direction, merchant, occurredAt, message IDs)
 *  - Message-level traceability in DocumentStore
 */
public final class ConsistencyChecker {

    private final SqlLedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(SqlLedgerStore sql, DocumentStore documents) {
        this.sql = Objects.requireNonNull(sql, "sql");
        this.documents = Objects.requireNonNull(documents, "documents");
    }

    public List<Divergence> check() {
        List<Divergence> divergences = new ArrayList<>();

        // Consolidate SQL transactions (merging legacy duplicate rows into unique transactions)
        Map<String, NormalizedTxn> sqlMap = new LinkedHashMap<>();
        Map<String, Set<String>> sqlMessages = new LinkedHashMap<>();

        for (NormalizedTxn t : sql.all()) {
            String key = MongoDocumentStore.transactionKey(t);
            if (!sqlMap.containsKey(key)) {
                sqlMap.put(key, t);
                sqlMessages.put(key, new LinkedHashSet<>(t.sourceMessageIds()));
            } else {
                sqlMessages.get(key).addAll(t.sourceMessageIds());
            }
        }

        // Rebuild consolidated SQL transactions with merged message IDs
        Map<String, NormalizedTxn> consolidatedSql = new LinkedHashMap<>();
        for (Map.Entry<String, NormalizedTxn> entry : sqlMap.entrySet()) {
            String key = entry.getKey();
            NormalizedTxn base = entry.getValue();
            consolidatedSql.put(key, new NormalizedTxn(
                    base.accountLast4(),
                    base.occurredAt(),
                    base.direction(),
                    base.amount(),
                    base.category(),
                    base.merchant(),
                    new ArrayList<>(sqlMessages.get(key))
            ));
        }

        // Retrieve DocumentStore transactions
        Map<String, NormalizedTxn> docMap = new LinkedHashMap<>();
        if (documents instanceof LedgerStore ledgerStore) {
            for (NormalizedTxn t : ledgerStore.all()) {
                docMap.put(MongoDocumentStore.transactionKey(t), t);
            }
        } else {
            // Fallback: look up by each message ID from SQL
            for (NormalizedTxn s : consolidatedSql.values()) {
                for (String msgId : s.sourceMessageIds()) {
                    Optional<NormalizedTxn> opt = documents.byMessageId(msgId);
                    if (opt.isPresent()) {
                        NormalizedTxn d = opt.get();
                        docMap.put(MongoDocumentStore.transactionKey(d), d);
                        break;
                    }
                }
            }
        }

        // 1. Check for transactions present in SQL but missing in Documents
        for (Map.Entry<String, NormalizedTxn> entry : consolidatedSql.entrySet()) {
            String key = entry.getKey();
            NormalizedTxn s = entry.getValue();
            if (!docMap.containsKey(key)) {
                divergences.add(new Divergence(
                        "MISSING_IN_DOCUMENTS: Transaction " + key,
                        s.toString(),
                        "NOT_FOUND"
                ));
            }
        }

        // 2. Check for transactions present in Documents but missing in SQL
        for (Map.Entry<String, NormalizedTxn> entry : docMap.entrySet()) {
            String key = entry.getKey();
            NormalizedTxn d = entry.getValue();
            if (!consolidatedSql.containsKey(key)) {
                divergences.add(new Divergence(
                        "MISSING_IN_SQL: Transaction " + key,
                        "NOT_FOUND",
                        d.toString()
                ));
            }
        }

        // 3. Field-by-field comparison for matching transactions
        for (Map.Entry<String, NormalizedTxn> entry : consolidatedSql.entrySet()) {
            String key = entry.getKey();
            NormalizedTxn s = entry.getValue();
            NormalizedTxn d = docMap.get(key);
            if (d == null) continue;

            if (s.amount().compareTo(d.amount()) != 0) {
                divergences.add(new Divergence(
                        "FIELD_MISMATCH: amount differs for " + key,
                        s.amount().toPlainString(),
                        d.amount().toPlainString()
                ));
            }

            if (s.category() != d.category()) {
                divergences.add(new Divergence(
                        "FIELD_MISMATCH: category differs for " + key,
                        s.category().name(),
                        d.category().name()
                ));
            }

            if (s.direction() != d.direction()) {
                divergences.add(new Divergence(
                        "FIELD_MISMATCH: direction differs for " + key,
                        s.direction().name(),
                        d.direction().name()
                ));
            }

            if (!Objects.equals(s.merchant(), d.merchant())) {
                divergences.add(new Divergence(
                        "FIELD_MISMATCH: merchant differs for " + key,
                        s.merchant(),
                        d.merchant()
                ));
            }

            if (!s.occurredAt().isEqual(d.occurredAt())) {
                divergences.add(new Divergence(
                        "FIELD_MISMATCH: occurredAt differs for " + key,
                        s.occurredAt().toString(),
                        d.occurredAt().toString()
                ));
            }

            Set<String> sMsgs = new HashSet<>(s.sourceMessageIds());
            Set<String> dMsgs = new HashSet<>(d.sourceMessageIds());
            if (!sMsgs.equals(dMsgs)) {
                divergences.add(new Divergence(
                        "FIELD_MISMATCH: sourceMessageIds differ for " + key,
                        s.sourceMessageIds().toString(),
                        d.sourceMessageIds().toString()
                ));
            }
        }

        // 4. Traceability check: each document's message IDs must resolve via byMessageId
        for (NormalizedTxn d : docMap.values()) {
            for (String msgId : d.sourceMessageIds()) {
                Optional<NormalizedTxn> resolved = documents.byMessageId(msgId);
                if (resolved.isEmpty()) {
                    divergences.add(new Divergence(
                            "TRACEABILITY_FAILURE: messageId " + msgId + " not found via byMessageId",
                            "msgId: " + msgId,
                            "OPTIONAL_EMPTY"
                    ));
                } else if (!MongoDocumentStore.transactionKey(resolved.get()).equals(MongoDocumentStore.transactionKey(d))) {
                    divergences.add(new Divergence(
                            "TRACEABILITY_MISMATCH: messageId " + msgId + " resolved to different transaction",
                            MongoDocumentStore.transactionKey(d),
                            MongoDocumentStore.transactionKey(resolved.get())
                    ));
                }
            }
        }

        return divergences;
    }

    /** One place the two stores disagree. */
    public record Divergence(String what, String inSql, String inDocuments) {}
}
