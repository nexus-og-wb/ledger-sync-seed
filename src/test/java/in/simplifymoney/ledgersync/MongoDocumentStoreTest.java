package in.simplifymoney.ledgersync;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.Backfill;
import in.simplifymoney.ledgersync.store.ConsistencyChecker;
import in.simplifymoney.ledgersync.store.MongoConfig;
import in.simplifymoney.ledgersync.store.MongoDocumentStore;
import in.simplifymoney.ledgersync.store.SqlLedgerStore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class MongoDocumentStoreTest {

    private static final String TEST_DB = "ledger_sync_test";
    private MongoClient mongoClient;
    private MongoDocumentStore store;

    @BeforeEach
    void setUp() {
        String uri = System.getenv().getOrDefault("MONGO_URI", "mongodb://localhost:27017");
        mongoClient = MongoClients.create(uri);
        store = new MongoDocumentStore(mongoClient, TEST_DB, false);
        store.drop();
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.drop();
        }
        if (mongoClient != null) {
            mongoClient.close();
        }
    }

    private NormalizedTxn sampleTxn(String account, String timeStr, Direction dir, String amt, Category cat, String merchant, List<String> msgs) {
        return new NormalizedTxn(
                account,
                OffsetDateTime.parse(timeStr),
                dir,
                new BigDecimal(amt).setScale(2, RoundingMode.HALF_UP),
                cat,
                merchant,
                msgs
        );
    }

    @Test
    @DisplayName("1. Mongo transaction save and retrieve")
    void testSaveAndRetrieve() {
        NormalizedTxn txn = sampleTxn("4821", "2026-07-04T20:24:00+05:30", Direction.DEBIT, "2499.50", Category.SPEND, "AMAZON PAY", List.of("m-001"));
        store.save(txn);

        assertEquals(1, store.count());
        List<NormalizedTxn> all = store.all();
        assertEquals(1, all.size());
        NormalizedTxn retrieved = all.get(0);
        assertEquals("4821", retrieved.accountLast4());
        assertEquals(new BigDecimal("2499.50"), retrieved.amount());
        assertEquals(Category.SPEND, retrieved.category());
        assertEquals("AMAZON PAY", retrieved.merchant());
        assertEquals(List.of("m-001"), retrieved.sourceMessageIds());
    }

    @Test
    @DisplayName("2. Access Pattern #1: forAccountMonth, newest first")
    void testAccessPattern1_forAccountMonth() {
        NormalizedTxn t1 = sampleTxn("4821", "2026-07-01T10:00:00+05:30", Direction.DEBIT, "100.00", Category.SPEND, "STORE A", List.of("m-1"));
        NormalizedTxn t2 = sampleTxn("4821", "2026-07-15T15:30:00+05:30", Direction.DEBIT, "250.00", Category.SPEND, "STORE B", List.of("m-2"));
        NormalizedTxn t3 = sampleTxn("4821", "2026-07-31T23:59:00+05:30", Direction.CREDIT, "500.00", Category.INCOME, "SALARY", List.of("m-3"));
        NormalizedTxn diffMonth = sampleTxn("4821", "2026-08-01T09:00:00+05:30", Direction.DEBIT, "50.00", Category.SPEND, "STORE C", List.of("m-4"));
        NormalizedTxn diffAccount = sampleTxn("9075", "2026-07-10T12:00:00+05:30", Direction.DEBIT, "75.00", Category.SPEND, "STORE D", List.of("m-5"));

        store.save(t1);
        store.save(t2);
        store.save(t3);
        store.save(diffMonth);
        store.save(diffAccount);

        List<NormalizedTxn> julyTxns = store.forAccountMonth("4821", YearMonth.of(2026, 7));
        assertEquals(3, julyTxns.size());

        // Newest first order
        assertEquals("2026-07-31T23:59+05:30", julyTxns.get(0).occurredAt().toString());
        assertEquals("2026-07-15T15:30+05:30", julyTxns.get(1).occurredAt().toString());
        assertEquals("2026-07-01T10:00+05:30", julyTxns.get(2).occurredAt().toString());
    }

    @Test
    @DisplayName("3. Access Pattern #2: running totals per category for an account")
    void testAccessPattern2_categoryTotals() {
        store.save(sampleTxn("4821", "2026-07-01T10:00:00+05:30", Direction.DEBIT, "1500.00", Category.SPEND, "STORE A", List.of("m-1")));
        store.save(sampleTxn("4821", "2026-07-02T10:00:00+05:30", Direction.DEBIT, "500.50", Category.SPEND, "STORE B", List.of("m-2")));
        store.save(sampleTxn("4821", "2026-07-03T10:00:00+05:30", Direction.CREDIT, "5000.00", Category.INCOME, "EMPLOYER", List.of("m-3")));
        store.save(sampleTxn("4821", "2026-07-04T10:00:00+05:30", Direction.DEBIT, "35.00", Category.MICRO, "UPI/TEA", List.of("m-4")));
        store.save(sampleTxn("4821", "2026-07-05T10:00:00+05:30", Direction.DEBIT, "1000.00", Category.TRANSFER, "SELF", List.of("m-5")));

        // Another account to ensure filtering works
        store.save(sampleTxn("9075", "2026-07-01T10:00:00+05:30", Direction.DEBIT, "999.00", Category.SPEND, "STORE X", List.of("m-6")));

        Map<Category, BigDecimal> totals = store.categoryTotals("4821");
        assertEquals(new BigDecimal("2000.50"), totals.get(Category.SPEND));
        assertEquals(new BigDecimal("5000.00"), totals.get(Category.INCOME));
        assertEquals(new BigDecimal("35.00"), totals.get(Category.MICRO));
        assertEquals(new BigDecimal("1000.00"), totals.get(Category.TRANSFER));
    }

    @Test
    @DisplayName("4. Access Pattern #3: given message ID, find transaction")
    void testAccessPattern3_byMessageId() {
        NormalizedTxn txn = sampleTxn("4821", "2026-07-04T20:24:00+05:30", Direction.DEBIT, "2499.50", Category.SPEND, "AMAZON PAY", List.of("m-001", "m-002"));
        store.save(txn);

        Optional<NormalizedTxn> byFirst = store.byMessageId("m-001");
        assertTrue(byFirst.isPresent());
        assertEquals("4821", byFirst.get().accountLast4());
        assertEquals(new BigDecimal("2499.50"), byFirst.get().amount());

        Optional<NormalizedTxn> bySecond = store.byMessageId("m-002");
        assertTrue(bySecond.isPresent());
        assertEquals(byFirst.get(), bySecond.get());

        Optional<NormalizedTxn> byUnknown = store.byMessageId("m-unknown");
        assertTrue(byUnknown.isEmpty());
    }

    @Test
    @DisplayName("5. Idempotent save: duplicate transactions merge message IDs without duplication")
    void testIdempotentSave() {
        NormalizedTxn first = sampleTxn("4821", "2026-07-04T20:24:00+05:30", Direction.DEBIT, "2499.50", Category.SPEND, "AMAZON PAY", List.of("m-001"));
        NormalizedTxn second = sampleTxn("4821", "2026-07-04T20:24:00+05:30", Direction.DEBIT, "2499.50", Category.SPEND, "AMAZON PAY", List.of("m-002"));

        store.save(first);
        store.save(second);

        assertEquals(1, store.count());
        NormalizedTxn found = store.all().get(0);
        assertTrue(found.sourceMessageIds().containsAll(List.of("m-001", "m-002")));
    }

    @Test
    @DisplayName("6. Correct money precision with Decimal128")
    void testMoneyPrecision() {
        NormalizedTxn txn = sampleTxn("4821", "2026-07-04T20:24:00+05:30", Direction.DEBIT, "92213.10", Category.SPEND, "MERCHANT", List.of("m-1"));
        store.save(txn);

        NormalizedTxn retrieved = store.all().get(0);
        assertEquals(2, retrieved.amount().scale());
        assertEquals("92213.10", retrieved.amount().toPlainString());
    }

    @Test
    @DisplayName("7. Correct occurred_at handling and timezone preservation")
    void testOccurredAtPreservation() {
        OffsetDateTime expectedTime = OffsetDateTime.parse("2026-07-04T20:24:00+05:30");
        NormalizedTxn txn = new NormalizedTxn("4821", expectedTime, Direction.DEBIT, new BigDecimal("100.00"), Category.SPEND, "TEST", List.of("m-1"));
        store.save(txn);

        NormalizedTxn retrieved = store.all().get(0);
        assertEquals(expectedTime, retrieved.occurredAt());
        assertEquals(expectedTime.getOffset(), retrieved.occurredAt().getOffset());
    }

    @Test
    @DisplayName("8. Backfill from SQL to Mongo is idempotent")
    void testBackfillIdempotency() throws Exception {
        Path tempDb = Files.createTempDirectory("ledger-h2-test").resolve("ledger");
        Path migrations = Path.of("db", "migration");

        try (SqlLedgerStore sqlStore = new SqlLedgerStore(tempDb)) {
            sqlStore.migrate(migrations);
            long initialSqlCount = sqlStore.count();
            assertTrue(initialSqlCount > 0);

            Backfill backfill = new Backfill(sqlStore, store);
            Backfill.Result firstRun = backfill.run();

            assertEquals(initialSqlCount, firstRun.read());
            assertTrue(firstRun.written() > 0);
            assertEquals(store.count(), firstRun.written());

            // Re-run backfill
            Backfill.Result secondRun = backfill.run();
            assertEquals(initialSqlCount, secondRun.read());
            assertEquals(0, secondRun.written());
            assertEquals(initialSqlCount, secondRun.skipped());
            assertEquals(firstRun.written(), store.count());
        }
    }

    @Test
    @DisplayName("9. ConsistencyChecker detects missing transaction in Documents")
    void testConsistencyChecker_DetectsMissing() throws Exception {
        Path tempDb = Files.createTempDirectory("ledger-h2-test2").resolve("ledger");
        Path migrations = Path.of("db", "migration");

        try (SqlLedgerStore sqlStore = new SqlLedgerStore(tempDb)) {
            sqlStore.migrate(migrations);
            Backfill backfill = new Backfill(sqlStore, store);
            backfill.run();

            // Verify initial consistency
            ConsistencyChecker checker = new ConsistencyChecker(sqlStore, store);
            List<ConsistencyChecker.Divergence> divInitial = checker.check();
            assertTrue(divInitial.isEmpty(), "Initially consistent");

            // Now delete a document from Mongo
            store.getTxnsCollection().deleteOne(new Document());

            List<ConsistencyChecker.Divergence> divAfter = checker.check();
            assertFalse(divAfter.isEmpty());
            assertTrue(divAfter.stream().anyMatch(d -> d.what().startsWith("MISSING_IN_DOCUMENTS")));
        }
    }

    @Test
    @DisplayName("10. ConsistencyChecker detects extra transaction in Documents")
    void testConsistencyChecker_DetectsExtra() throws Exception {
        Path tempDb = Files.createTempDirectory("ledger-h2-test3").resolve("ledger");
        Path migrations = Path.of("db", "migration");

        try (SqlLedgerStore sqlStore = new SqlLedgerStore(tempDb)) {
            sqlStore.migrate(migrations);
            Backfill backfill = new Backfill(sqlStore, store);
            backfill.run();

            // Insert an extra transaction directly into Mongo
            store.save(sampleTxn("4821", "2026-12-31T23:59:00+05:30", Direction.DEBIT, "999.99", Category.SPEND, "EXTRA", List.of("m-extra")));

            ConsistencyChecker checker = new ConsistencyChecker(sqlStore, store);
            List<ConsistencyChecker.Divergence> divergences = checker.check();
            assertFalse(divergences.isEmpty());
            assertTrue(divergences.stream().anyMatch(d -> d.what().startsWith("MISSING_IN_SQL")));
        }
    }

    @Test
    @DisplayName("11. ConsistencyChecker detects field mismatch")
    void testConsistencyChecker_DetectsFieldMismatch() throws Exception {
        Path tempDb = Files.createTempDirectory("ledger-h2-test4").resolve("ledger");
        Path migrations = Path.of("db", "migration");

        try (SqlLedgerStore sqlStore = new SqlLedgerStore(tempDb)) {
            sqlStore.migrate(migrations);
            Backfill backfill = new Backfill(sqlStore, store);
            backfill.run();

            // Tamper with one document's category in Mongo
            store.getTxnsCollection().updateOne(
                    new Document("account_last4", "4821"),
                    new Document("$set", new Document("category", "INCOME"))
            );

            ConsistencyChecker checker = new ConsistencyChecker(sqlStore, store);
            List<ConsistencyChecker.Divergence> divergences = checker.check();
            assertFalse(divergences.isEmpty());
            assertTrue(divergences.stream().anyMatch(d -> d.what().contains("FIELD_MISMATCH")));
        }
    }
}
