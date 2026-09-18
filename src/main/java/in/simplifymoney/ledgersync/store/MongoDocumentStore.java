package in.simplifymoney.ledgersync.store;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.Discrepancy;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Decimal128;

/**
 * MongoDB implementation of DocumentStore and LedgerStore.
 *
 * Implements the three required access patterns with supporting compound and
 * multikey indexes, preserving exact monetary precision using BSON Decimal128,
 * and maintaining full idempotency across saves and backfills.
 */
public final class MongoDocumentStore implements DocumentStore, LedgerStore, AutoCloseable {

    public static final String TRANSACTIONS_COLLECTION = "transactions";
    public static final String DISCREPANCIES_COLLECTION = "discrepancies";

    private final MongoClient client;
    private final MongoDatabase database;
    private final MongoCollection<Document> txns;
    private final MongoCollection<Document> discrepancies;
    private final boolean ownsClient;

    public MongoDocumentStore(MongoConfig config) {
        this(config.createClient(), config.databaseName(), true);
    }

    public MongoDocumentStore(MongoClient client, String databaseName) {
        this(client, databaseName, false);
    }

    public MongoDocumentStore(MongoClient client, String databaseName, boolean ownsClient) {
        this.client = Objects.requireNonNull(client, "client");
        this.database = client.getDatabase(Objects.requireNonNull(databaseName, "databaseName"));
        this.txns = database.getCollection(TRANSACTIONS_COLLECTION);
        this.discrepancies = database.getCollection(DISCREPANCIES_COLLECTION);
        this.ownsClient = ownsClient;

        ensureIndexes();
    }

    /**
     * Creates the required indexes for query performance and idempotency:
     * 1. Q1 index: (account_last4 ASC, occurred_at DESC)
     * 2. Q2 index: (account_last4 ASC, category ASC, amount ASC)
     * 3. Q3 multikey index: (source_message_ids ASC)
     * 4. Idempotency unique index: (txn_key ASC)
     */
    private void ensureIndexes() {
        // Q1 index
        txns.createIndex(
                Indexes.compoundIndex(
                        Indexes.ascending("account_last4"),
                        Indexes.descending("occurred_at")),
                new IndexOptions().name("idx_account_occurred"));

        // Q2 index
        txns.createIndex(
                Indexes.compoundIndex(
                        Indexes.ascending("account_last4"),
                        Indexes.ascending("category"),
                        Indexes.ascending("amount")),
                new IndexOptions().name("idx_account_cat_amt"));

        // Q3 multikey index
        txns.createIndex(
                Indexes.ascending("source_message_ids"),
                new IndexOptions().name("idx_source_msg"));

        // Unique transaction key index
        txns.createIndex(
                Indexes.ascending("txn_key"),
                new IndexOptions().name("idx_txn_key_unique").unique(true));

        // Discrepancies index
        discrepancies.createIndex(
                Indexes.ascending("occurred_at"),
                new IndexOptions().name("idx_disc_occurred"));
    }

    /**
     * Computes the deterministic transaction identity key.
     */
    public static String transactionKey(NormalizedTxn txn) {
        return txn.accountLast4() + "|"
                + txn.occurredAt().toString() + "|"
                + txn.direction().name() + "|"
                + txn.amount().setScale(2, RoundingMode.HALF_UP).toPlainString() + "|"
                + txn.merchant();
    }

    /**
     * Saves a normalized transaction idempotently.
     * If the transaction already exists, merges any new source message IDs.
     */
    @Override
    public void save(NormalizedTxn txn) {
        Objects.requireNonNull(txn, "txn");
        String key = transactionKey(txn);

        Bson filter = Filters.eq("_id", key);
        Bson update = Updates.combine(
                Updates.setOnInsert("txn_key", key),
                Updates.setOnInsert("account_last4", txn.accountLast4()),
                Updates.setOnInsert("occurred_at", txn.occurredAt().toString()),
                Updates.setOnInsert("direction", txn.direction().name()),
                Updates.setOnInsert("amount", new Decimal128(txn.amount())),
                Updates.setOnInsert("category", txn.category().name()),
                Updates.setOnInsert("merchant", txn.merchant()),
                Updates.addEachToSet("source_message_ids", txn.sourceMessageIds())
        );

        txns.updateOne(filter, update, new UpdateOptions().upsert(true));
    }

    /**
     * Q1: One account's transactions for one month, newest first.
     * Supported by compound index: { account_last4: 1, occurred_at: -1 }
     */
    @Override
    public List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) {
        Objects.requireNonNull(accountLast4, "accountLast4");
        Objects.requireNonNull(month, "month");

        // Format month start and next month start in IST (+05:30)
        ZoneOffset ist = ZoneOffset.ofHoursMinutes(5, 30);
        String startStr = month.atDay(1).atStartOfDay().atOffset(ist).toString();
        String endStr = month.plusMonths(1).atDay(1).atStartOfDay().atOffset(ist).toString();

        Bson filter = Filters.and(
                Filters.eq("account_last4", accountLast4),
                Filters.gte("occurred_at", startStr),
                Filters.lt("occurred_at", endStr)
        );

        List<NormalizedTxn> out = new ArrayList<>();
        for (Document doc : txns.find(filter).sort(Sorts.descending("occurred_at"))) {
            out.add(toNormalizedTxn(doc));
        }
        return out;
    }

    /**
     * Q2: Running totals per category for an account, for its whole history.
     * Supported by compound index: { account_last4: 1, category: 1, amount: 1 }
     */
    @Override
    public Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        Objects.requireNonNull(accountLast4, "accountLast4");

        List<Bson> pipeline = Arrays.asList(
                Aggregates.match(Filters.eq("account_last4", accountLast4)),
                Aggregates.group("$category", Accumulators.sum("total", "$amount"))
        );

        Map<Category, BigDecimal> totals = new EnumMap<>(Category.class);
        for (Category c : Category.values()) {
            totals.put(c, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        }

        for (Document doc : txns.aggregate(pipeline)) {
            String catStr = doc.getString("_id");
            if (catStr != null) {
                Category cat = Category.valueOf(catStr);
                Decimal128 sumVal = doc.get("total", Decimal128.class);
                BigDecimal total = (sumVal != null)
                        ? sumVal.bigDecimalValue().setScale(2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                totals.put(cat, total);
            }
        }
        return totals;
    }

    /**
     * Q3: Given a message id, which transaction did it produce?
     * Supported by multikey index: { source_message_ids: 1 }
     */
    @Override
    public Optional<NormalizedTxn> byMessageId(String messageId) {
        Objects.requireNonNull(messageId, "messageId");

        Document doc = txns.find(Filters.eq("source_message_ids", messageId)).first();
        return Optional.ofNullable(doc).map(this::toNormalizedTxn);
    }

    @Override
    public List<NormalizedTxn> all() {
        List<NormalizedTxn> out = new ArrayList<>();
        for (Document doc : txns.find().sort(Sorts.ascending("occurred_at"))) {
            out.add(toNormalizedTxn(doc));
        }
        return out;
    }

    @Override
    public long count() {
        return txns.countDocuments();
    }

    @Override
    public void saveDiscrepancy(Discrepancy d) {
        Objects.requireNonNull(d, "discrepancy");
        Document doc = new Document()
                .append("account_last4", d.accountLast4())
                .append("occurred_at", d.occurredAt().toString())
                .append("amount", new Decimal128(d.amount()))
                .append("note", d.note());
        discrepancies.insertOne(doc);
    }

    @Override
    public List<Discrepancy> discrepancies() {
        List<Discrepancy> out = new ArrayList<>();
        for (Document doc : discrepancies.find().sort(Sorts.ascending("occurred_at"))) {
            Decimal128 amt = doc.get("amount", Decimal128.class);
            out.add(new Discrepancy(
                    doc.getString("account_last4"),
                    OffsetDateTime.parse(doc.getString("occurred_at")),
                    amt != null ? amt.bigDecimalValue().setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2),
                    doc.getString("note")
            ));
        }
        return out;
    }

    /**
     * Clears all collections (useful for test isolation).
     */
    public void drop() {
        txns.drop();
        discrepancies.drop();
        ensureIndexes();
    }

    @Override
    public void close() {
        if (ownsClient) {
            client.close();
        }
    }

    public MongoCollection<Document> getTxnsCollection() {
        return txns;
    }

    private NormalizedTxn toNormalizedTxn(Document doc) {
        Decimal128 amtVal = doc.get("amount", Decimal128.class);
        BigDecimal amount = (amtVal != null)
                ? amtVal.bigDecimalValue().setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        @SuppressWarnings("unchecked")
        List<String> rawMsgIds = (List<String>) doc.get("source_message_ids");
        List<String> msgIds = rawMsgIds != null ? rawMsgIds : List.of();

        return new NormalizedTxn(
                doc.getString("account_last4"),
                OffsetDateTime.parse(doc.getString("occurred_at")),
                Direction.valueOf(doc.getString("direction")),
                amount,
                Category.valueOf(doc.getString("category")),
                doc.getString("merchant"),
                msgIds
        );
    }
}
