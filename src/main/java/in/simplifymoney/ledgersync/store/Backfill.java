package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Moves everything already in the SQL store into the document store.
 *
 * Handles:
 *  - Unclean SQL history (merging duplicates and consolidating source_message_ids)
 *  - Idempotency across partial failures and repeated runs
 */
public final class Backfill {

    private final SqlLedgerStore source;
    private final DocumentStore target;

    public Backfill(SqlLedgerStore source, DocumentStore target) {
        this.source = Objects.requireNonNull(source, "source");
        this.target = Objects.requireNonNull(target, "target");
    }

    public Result run() {
        List<NormalizedTxn> sqlTxns = source.all();
        long read = sqlTxns.size();
        long written = 0;
        long skipped = 0;

        // Group by natural transaction identity to merge historical duplicate rows
        Map<String, NormalizedTxn> consolidated = new LinkedHashMap<>();
        Map<String, Set<String>> consolidatedMessages = new LinkedHashMap<>();

        for (NormalizedTxn t : sqlTxns) {
            String key = MongoDocumentStore.transactionKey(t);
            if (!consolidated.containsKey(key)) {
                consolidated.put(key, t);
                consolidatedMessages.put(key, new LinkedHashSet<>(t.sourceMessageIds()));
            } else {
                consolidatedMessages.get(key).addAll(t.sourceMessageIds());
                // Duplicate row within SQL batch
                skipped++;
            }
        }

        // For each consolidated transaction, check if already present in target
        for (Map.Entry<String, NormalizedTxn> entry : consolidated.entrySet()) {
            String key = entry.getKey();
            NormalizedTxn base = entry.getValue();
            Set<String> msgIds = consolidatedMessages.get(key);

            NormalizedTxn consolidatedTxn = new NormalizedTxn(
                    base.accountLast4(),
                    base.occurredAt(),
                    base.direction(),
                    base.amount(),
                    base.category(),
                    base.merchant(),
                    new ArrayList<>(msgIds)
            );

            // Check if already in target (e.g. by checking any of its message IDs)
            boolean alreadyPresent = false;
            for (String msgId : msgIds) {
                if (target.byMessageId(msgId).isPresent()) {
                    alreadyPresent = true;
                    break;
                }
            }

            if (alreadyPresent) {
                // Already in target from a previous run or partial failure
                target.save(consolidatedTxn); // ensures message IDs are merged
                skipped++;
            } else {
                target.save(consolidatedTxn);
                written++;
            }
        }

        // Also copy over discrepancies if target supports LedgerStore
        if (target instanceof LedgerStore ledgerTarget) {
            var discList = source.discrepancies();
            for (var d : discList) {
                ledgerTarget.saveDiscrepancy(d);
            }
        }

        return new Result(read, written, skipped);
    }

    public record Result(long read, long written, long skipped) {}
}
