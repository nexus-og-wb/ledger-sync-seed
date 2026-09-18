package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.model.TxnKey;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.LedgerStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads a corpus of raw messages and puts transactions in the ledger.
 *
 * This is the naive version. It parses each message on its own and saves
 * whatever comes back. It does not ask whether two messages describe the same
 * transaction, and it decides the category from the direction alone.
 */
public final class IngestService {

    private final Parsers parsers;
    private final LedgerStore store;

    public IngestService(Parsers parsers, LedgerStore store) {
        this.parsers = parsers;
        this.store = store;
    }

    public Stats ingestFile(Path corpus) throws IOException {
        List<RawMessage> messages = readCorpus(corpus);
        int skipped = 0;

        Map<TxnKey, List<ParsedTxn>> grouped = new LinkedHashMap<>();
        Map<String, ParsedTxn> parsedByMessageId = new LinkedHashMap<>();
        Set<String> cardAccounts = new HashSet<>();

        for (RawMessage m : messages) {
            Optional<ParsedTxn> p = parsers.parse(m);
            if (p.isEmpty()) {
                skipped++;
                continue;
            }
            ParsedTxn t = p.get();
            parsedByMessageId.put(m.messageId(), t);
            if (m.body().contains("Avl Limit") || m.body().contains("Card x") || m.body().contains("Bank Card")) {
                cardAccounts.add(t.accountLast4());
            }

            TxnKey key = new TxnKey(t.accountLast4(), t.occurredAt(), t.direction(), t.amount(), t.merchant());
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        }

        List<NormalizedTxn> writtenTxns = new ArrayList<>();
        int written = 0;
        for (Map.Entry<TxnKey, List<ParsedTxn>> entry : grouped.entrySet()) {
            List<ParsedTxn> txns = entry.getValue();
            ParsedTxn first = txns.get(0);

            List<String> messageIds = txns.stream()
                    .map(ParsedTxn::sourceMessageId)
                    .distinct()
                    .toList();

            String merchant = txns.stream()
                    .map(ParsedTxn::merchant)
                    .filter(m -> m != null && !m.isBlank())
                    .findFirst()
                    .orElse(first.merchant());

            Category category = determineCategory(first, grouped.keySet());

            NormalizedTxn normalized = new NormalizedTxn(
                    first.accountLast4(),
                    first.occurredAt(),
                    first.direction(),
                    first.amount(),
                    category,
                    merchant,
                    messageIds);
            store.save(normalized);
            writtenTxns.add(normalized);
            written++;
        }

        // Reconciliation: detect balance gaps and save discrepancies
        in.simplifymoney.ledgersync.reconcile.ReconciliationService reconciler =
                new in.simplifymoney.ledgersync.reconcile.ReconciliationService();
        List<in.simplifymoney.ledgersync.model.Discrepancy> discrepancies =
                reconciler.reconcile(writtenTxns, parsedByMessageId, cardAccounts);
        for (in.simplifymoney.ledgersync.model.Discrepancy d : discrepancies) {
            store.saveDiscrepancy(d);
        }

        return new Stats(messages.size(), written, skipped);
    }

    private Category determineCategory(ParsedTxn p, java.util.Set<TxnKey> allKeys) {
        String merchantUpper = p.merchant() != null ? p.merchant().toUpperCase() : "";

        if (isSelfTransfer(p, allKeys, merchantUpper)) {
            return Category.TRANSFER;
        }

        if (p.direction() == Direction.CREDIT) {
            return Category.INCOME;
        }

        boolean isUpi = merchantUpper.startsWith("UPI/") || merchantUpper.contains("UPI");
        if (isUpi && p.amount().compareTo(new java.math.BigDecimal("100.00")) <= 0) {
            return Category.MICRO;
        }

        return Category.SPEND;
    }

    private boolean isSelfTransfer(ParsedTxn p, java.util.Set<TxnKey> allKeys, String merchantUpper) {
        Direction oppositeDirection = p.direction() == Direction.DEBIT ? Direction.CREDIT : Direction.DEBIT;
        String pName = cleanHolderName(p.merchant());

        for (TxnKey other : allKeys) {
            if (!other.accountLast4().equals(p.accountLast4())
                    && other.direction() == oppositeDirection
                    && other.amount().compareTo(p.amount()) == 0) {

                String otherName = cleanHolderName(other.merchant());

                if (!pName.isEmpty() && pName.equalsIgnoreCase(otherName)) {
                    long secondsDiff = Math
                            .abs(java.time.Duration.between(p.occurredAt(), other.occurredAt()).getSeconds());
                    if (secondsDiff <= 300) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String cleanHolderName(String merchant) {
        if (merchant == null)
            return "";
        String cleaned = merchant.trim().toUpperCase();
        return cleaned.replaceAll("^(IMPS/P2A/|IMPS/|NEFT/|UPI/P2P/|UPI/)", "").trim();
    }

    public static List<RawMessage> readCorpus(Path corpus) throws IOException {
        List<RawMessage> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(corpus)) {
            for (String line : (Iterable<String>) lines.filter(s -> !s.isBlank())::iterator) {
                Map<String, Object> o = Json.parseObject(line);
                out.add(new RawMessage(
                        (String) o.get("message_id"),
                        (String) o.get("channel"),
                        (String) o.get("sender"),
                        OffsetDateTime.parse((String) o.get("received_at")),
                        (String) o.get("device_id"),
                        (String) o.get("body")));
            }
        }
        return out;
    }

    public record Stats(int messagesRead, int transactionsWritten, int messagesSkipped) {
    }
}
