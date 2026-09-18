package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.*;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.model.Discrepancy;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.report.Reports;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReconciliationTest {

        private static final OffsetDateTime WHEN = OffsetDateTime.parse("2026-07-29T17:06:00+05:30");

        @Test
        @DisplayName("Discrepancy record validates account last 4 digits")
        void validatesAccountLast4() {
                assertThrows(IllegalArgumentException.class,
                                () -> new Discrepancy("482", WHEN, new BigDecimal("7500.00"), "note"));
                assertThrows(IllegalArgumentException.class,
                                () -> new Discrepancy("XX21", WHEN, new BigDecimal("7500.00"), "note"));
                assertDoesNotThrow(() -> new Discrepancy("4821", WHEN, new BigDecimal("7500.00"), "note"));
        }

        @Test
        @DisplayName("Discrepancy record validates amount scale and sign")
        void validatesAmount() {
                assertThrows(IllegalArgumentException.class,
                                () -> new Discrepancy("4821", WHEN, new BigDecimal("-7500.00"), "note"));
                assertThrows(IllegalArgumentException.class,
                                () -> new Discrepancy("4821", WHEN, new BigDecimal("0.00"), "note"));
                assertThrows(IllegalArgumentException.class,
                                () -> new Discrepancy("4821", WHEN, new BigDecimal("7500.0"), "note"));
                assertThrows(IllegalArgumentException.class,
                                () -> new Discrepancy("4821", WHEN, new BigDecimal("7500"), "note"));
                assertDoesNotThrow(() -> new Discrepancy("4821", WHEN, new BigDecimal("7500.00"), "note"));
        }

        @Test
        @DisplayName("Discrepancy record validates note is not blank")
        void validatesNote() {
                assertThrows(IllegalArgumentException.class,
                                () -> new Discrepancy("4821", WHEN, new BigDecimal("7500.00"), "   "));
        }

        @Test
        @DisplayName("Detects the 7500 discrepancy on account 4821 in corpus-a")
        void detectsCorpusADiscrepancy() throws Exception {
                InMemoryLedgerStore store = new InMemoryLedgerStore();
                IngestService ingest = new IngestService(new Parsers(), store);
                ingest.ingestFile(Path.of("fixtures/corpus-a.jsonl"));

                List<Discrepancy> discrepancies = store.discrepancies();
                assertNotNull(discrepancies);
                assertEquals(1, discrepancies.size(), "Corpus-a should have exactly 1 discrepancy");

                Discrepancy d = discrepancies.get(0);
                assertEquals("4821", d.accountLast4());
                assertEquals(new BigDecimal("7500.00"), d.amount());
                assertEquals(WHEN, d.occurredAt());
                assertTrue(d.note().contains("7500.00"));
        }

        @Test
        @DisplayName("reconciliationDocument produces the schema required by the specification")
        void formatsReconciliationDocument() {
                Discrepancy d = new Discrepancy("4821", WHEN, new BigDecimal("7500.00"),
                                "Bank stated balance dropped by 7500.00 without an evidencing transaction");

                Map<String, Object> doc = Reports.reconciliationDocument(List.of(d));
                assertTrue(doc.containsKey("discrepancies"));

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rows = (List<Map<String, Object>>) doc.get("discrepancies");
                assertEquals(1, rows.size());

                Map<String, Object> row = rows.get(0);
                assertEquals("4821", row.get("account_last4"));
                assertEquals("2026-07-29T17:06+05:30", row.get("occurred_at"));
                assertEquals("7500.00", row.get("amount"));
                assertEquals("Bank stated balance dropped by 7500.00 without an evidencing transaction",
                                row.get("note"));
        }
}
