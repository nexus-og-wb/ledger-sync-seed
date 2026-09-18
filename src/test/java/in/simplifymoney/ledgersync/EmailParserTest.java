package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.*;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.Dates;
import in.simplifymoney.ledgersync.parse.EmailParser;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmailParserTest {

    private final EmailParser parser = new EmailParser();

    @Test
    @DisplayName("parses HDFC credit alert email")
    void parsesHdfcCreditAlert() {
        RawMessage msg = new RawMessage(
                "m-00002-69e4cd",
                "email",
                "alerts@hdfcbank.net",
                OffsetDateTime.parse("2026-07-01T09:47:00+05:30"),
                "dev-34aed0f15820",
                """
                Date: Wed, 01 Jul 2026 09:02:00 +0530
                Subject: Transaction alert on your account

                Dear Customer,

                Your account ending 4821 has been credited with INR 45,000.
                Merchant / Remarks: SALARY CREDIT
                Transaction reference: 1597155421

                This is a system generated email.
                """
        );

        assertTrue(parser.supports(msg));
        Optional<ParsedTxn> parsed = parser.parse(msg);
        assertTrue(parsed.isPresent());

        ParsedTxn txn = parsed.get();
        assertEquals("4821", txn.accountLast4());
        assertEquals(OffsetDateTime.parse("2026-07-01T09:02:00+05:30"), txn.occurredAt());
        assertEquals(Direction.CREDIT, txn.direction());
        assertEquals(new BigDecimal("45000.00"), txn.amount());
        assertEquals("SALARY CREDIT", txn.merchant());
        assertEquals("m-00002-69e4cd", txn.sourceMessageId());
        assertEquals(Dates.IST, txn.occurredAt().getOffset());
    }

    @Test
    @DisplayName("parses ICICI debit alert email")
    void parsesIciciDebitAlert() {
        RawMessage msg = new RawMessage(
                "m-00236-6f7c84",
                "email",
                "alerts@icicibank.com",
                OffsetDateTime.parse("2026-08-03T11:07:00+05:30"),
                "dev-34aed0f15820",
                """
                Date: Mon, 03 Aug 2026 08:07:00 +0530
                Subject: Transaction alert on your account

                Dear Customer,

                Your account ending 9075 has been debited with Rs.1,999.01.
                Merchant / Remarks: INDIAN OIL
                Transaction reference: 3908415756

                This is a system generated email.
                """
        );

        assertTrue(parser.supports(msg));
        Optional<ParsedTxn> parsed = parser.parse(msg);
        assertTrue(parsed.isPresent());

        ParsedTxn txn = parsed.get();
        assertEquals("9075", txn.accountLast4());
        assertEquals(OffsetDateTime.parse("2026-08-03T08:07:00+05:30"), txn.occurredAt());
        assertEquals(Direction.DEBIT, txn.direction());
        assertEquals(new BigDecimal("1999.01"), txn.amount());
        assertEquals("INDIAN OIL", txn.merchant());
        assertEquals("m-00236-6f7c84", txn.sourceMessageId());
    }

    @Test
    @DisplayName("normalizes UTC email date to IST")
    void normalizesUtcToIst() {
        RawMessage msg = new RawMessage(
                "m-00131-cd229b",
                "email",
                "alerts@hdfcbank.net",
                OffsetDateTime.parse("2026-07-19T00:26:00+05:30"),
                "dev-34aed0f15820",
                """
                Date: Sat, 18 Jul 2026 18:50:00 +0000
                Subject: Transaction alert on your account

                Dear Customer,

                Your account ending 4821 has been debited with INR 412.67.
                Merchant / Remarks: UBER INDIA
                Transaction reference: 4190129089

                This is a system generated email.
                """
        );

        Optional<ParsedTxn> parsed = parser.parse(msg);
        assertTrue(parsed.isPresent());

        ParsedTxn txn = parsed.get();
        // 18:50 UTC + 05:30 = 00:20 on July 19th IST
        assertEquals(OffsetDateTime.parse("2026-07-19T00:20:00+05:30"), txn.occurredAt());
        assertEquals(Dates.IST, txn.occurredAt().getOffset());
        assertEquals("4821", txn.accountLast4());
        assertEquals(new BigDecimal("412.67"), txn.amount());
    }

    @Test
    @DisplayName("deduplicates SMS and Email alerts for the same transaction in ingest flow")
    void deduplicatesSmsAndEmail() throws Exception {
        InMemoryLedgerStore store = new InMemoryLedgerStore();
        IngestService ingest = new IngestService(new Parsers(), store);
        ingest.ingestFile(Path.of("fixtures/corpus-a.jsonl"));

        // Find the transaction for SALARY CREDIT 45000.00 on 2026-07-01
        List<NormalizedTxn> matches = store.all().stream()
                .filter(t -> "4821".equals(t.accountLast4())
                        && t.occurredAt().equals(OffsetDateTime.parse("2026-07-01T09:02:00+05:30"))
                        && t.amount().compareTo(new BigDecimal("45000.00")) == 0
                        && "SALARY CREDIT".equals(t.merchant()))
                .toList();

        // Exactly one transaction should be created
        assertEquals(1, matches.size(), "Should have exactly 1 deduplicated transaction");

        NormalizedTxn txn = matches.get(0);
        // Both SMS (m-00001-31eb24) and Email (m-00002-69e4cd) should be cited in source_message_ids
        assertTrue(txn.sourceMessageIds().contains("m-00001-31eb24"), "Should contain SMS message ID");
        assertTrue(txn.sourceMessageIds().contains("m-00002-69e4cd"), "Should contain Email message ID");
    }
}
