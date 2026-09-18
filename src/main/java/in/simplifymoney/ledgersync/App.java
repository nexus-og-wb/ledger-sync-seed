package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.report.Reports;
import in.simplifymoney.ledgersync.store.SqlLedgerStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command line entry point.
 *
 *   migrate                  apply db/migration/*.sql
 *   ingest  <corpus.jsonl>   read a corpus into the ledger
 *   report  <out-dir>        write ledger.json, summary.json, reconciliation.json
 */
public final class App {

    private static final Path DB = Path.of("data", "ledger");
    private static final Path MIGRATIONS = Path.of("db", "migration");

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: migrate | ingest <corpus.jsonl> | report <out-dir>");
            System.exit(2);
        }
        Files.createDirectories(DB.getParent());

        switch (args[0]) {
            case "migrate" -> {
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    store.migrate(MIGRATIONS);
                    System.out.println("ledger rows: " + store.count());
                }
            }
            case "ingest" -> {
                if (args.length < 2) throw new IllegalArgumentException("ingest needs a corpus");
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    store.migrate(MIGRATIONS);
                    var stats = new IngestService(new Parsers(), store)
                            .ingestFile(Path.of(args[1]));
                    System.out.println(stats);
                    System.out.println("ledger rows: " + store.count());
                }
            }
            case "report" -> {
                if (args.length < 2) throw new IllegalArgumentException("report needs a directory");
                Path out = Path.of(args[1]);
                Files.createDirectories(out);
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    var ledger = store.all();
                    var discrepancies = store.discrepancies();
                    Files.writeString(out.resolve("ledger.json"),
                            Json.writePretty(Reports.ledgerDocument(ledger)));
                    Files.writeString(out.resolve("summary.json"),
                            Json.writePretty(Reports.summary(ledger)));
                    Files.writeString(out.resolve("reconciliation.json"),
                            Json.writePretty(Reports.reconciliationDocument(discrepancies)));
                    System.out.println("wrote 3 files to " + out);
                }
            }
            case "backfill" -> {
                try (SqlLedgerStore sqlStore = new SqlLedgerStore(DB);
                     in.simplifymoney.ledgersync.store.MongoDocumentStore mongoStore =
                             new in.simplifymoney.ledgersync.store.MongoDocumentStore(
                                     in.simplifymoney.ledgersync.store.MongoConfig.fromEnv())) {
                    in.simplifymoney.ledgersync.store.Backfill backfill =
                            new in.simplifymoney.ledgersync.store.Backfill(sqlStore, mongoStore);
                    var result = backfill.run();
                    System.out.printf("backfill completed: read=%d, written=%d, skipped=%d%n",
                            result.read(), result.written(), result.skipped());
                    System.out.println("mongo transactions: " + mongoStore.count());
                }
            }
            case "check" -> {
                try (SqlLedgerStore sqlStore = new SqlLedgerStore(DB);
                     in.simplifymoney.ledgersync.store.MongoDocumentStore mongoStore =
                             new in.simplifymoney.ledgersync.store.MongoDocumentStore(
                                     in.simplifymoney.ledgersync.store.MongoConfig.fromEnv())) {
                    in.simplifymoney.ledgersync.store.ConsistencyChecker checker =
                            new in.simplifymoney.ledgersync.store.ConsistencyChecker(sqlStore, mongoStore);
                    var divergences = checker.check();
                    if (divergences.isEmpty()) {
                        System.out.println("CONSISTENCY CHECK PASSED: SQL and DocumentStore agree perfectly.");
                    } else {
                        System.err.printf("CONSISTENCY CHECK FAILED: %d divergences found!%n", divergences.size());
                        for (var d : divergences) {
                            System.err.printf("  [%s]%n    in SQL:       %s%n    in Documents: %s%n",
                                    d.what(), d.inSql(), d.inDocuments());
                        }
                        System.exit(1);
                    }
                }
            }
            default -> {
                System.err.println("unknown command: " + args[0]);
                System.exit(2);
            }
        }
    }
}
