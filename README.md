# Ledger Sync Seed

A financial transaction ingestion, normalization, and ledger persistence engine built in plain Java. The service reads raw bank SMS and email messages from user devices, converts them into canonical financial transactions, categorizes and deduplicates them, reconciles running balances against stated bank balances, and provides persistence across relational (SQL/H2) and document-oriented (MongoDB) storage.

---

## Overview

The processing pipeline operates in five phases:

1. **Messages**: Ingests raw SMS and email uploads (`fixtures/corpus-a.jsonl`), preserving raw message identifiers and arrival timestamps.
2. **Parsing & Normalization**: Format-specific parsers (HDFC SMS, ICICI SMS, and bank email) extract the bank-stated transaction timestamp (`occurred_at` in IST), account identifier, transaction direction (`DEBIT`/`CREDIT`), exact monetary amount, and merchant name into an immutable `NormalizedTxn` record.
3. **Ledger Engine & Deduplication**: Groups multi-channel alerts referencing the same underlying real-world event into a single canonical transaction, consolidating all evidencing message IDs. Identifies transfers between the user's own accounts and isolates micro-spends (UPI debits <= ₹100).
4. **Persistence**: Saves transactions and discrepancies to the active storage engine via the `LedgerStore` abstraction. The service supports an in-memory store for isolated verification, an embedded SQL/H2 store via JDBC, and a MongoDB document store.
5. **Reconciliation**: Evaluates running account balances against bank-stated balances in parsed messages, surfacing any unaccounted gaps as explicit `Discrepancy` records.

---

## Architecture

### System Flow Diagram

```
 Raw Messages (SMS / Email)
             │
             ▼
          Parsers (HDFC, ICICI, Email)
             │
             ▼
        ParsedTxn
             │
             ▼
       IngestService (Deduplication, Categories, Self-Transfers)
             │
             ▼
       NormalizedTxn (Frozen Domain Record)
             │
             ▼
       ReconciliationService (Balance Gap Detection)
             │
             ▼
        LedgerStore (Storage Abstraction)
        ├── InMemoryLedgerStore (Zero-dependency testing)
        ├── SqlLedgerStore      (H2 via JDBC)
        └── MongoDocumentStore  (MongoDB 7.0 via Official Driver)
```

### SQL to MongoDB Migration & Consistency Architecture

```
         H2 Database (data/ledger)
                     │
                     ▼
              SqlLedgerStore
                     │
                     ▼
           Backfill Process (Consolidation & Migration)
                     │
                     ▼
             MongoDocumentStore
                     │
                     ▼
         MongoDB (ledger_sync collection: transactions)
                     │
                     ▼
   ConsistencyChecker (Bidirectional field-level validation)
   [Checks: Missing in Mongo | Missing in SQL | Field Mismatches | Traceability]
```

---

## Tech Stack

* **Java 21**: Core application language, utilizing records, pattern matching, and sealed types.
* **Gradle 9.2.0**: Build tool, dependency management, and execution harness (wrapper included).
* **H2 Database 2.2.224**: Embedded SQL database loaded via standard `java.sql` / JDBC.
* **MongoDB 7.0**: Document database deployed via Docker Compose.
* **Official MongoDB Java Driver (Sync) 5.3.1**: Official driver (`org.mongodb:mongodb-driver-sync` and `org.mongodb:bson`) for connection management, BSON document modeling, and index creation.
* **JUnit 5.10.2**: Testing framework for unit, integration, and contract test suites.
* **Testcontainers 1.20.4**: Integration test container management.

---

## Prerequisites

* **Java Development Kit (JDK) 21** or higher.
* **Docker & Docker Compose** (v2.0+) for running MongoDB.
* **Git Bash / Bash** (for executing `./verify.sh` on Unix or Windows).
* No global Gradle installation required (repository includes `gradlew` and `gradlew.bat`).

---

## Project Structure

```
ledger-sync-seed/
├── docker-compose.yml              # MongoDB 7.0 container specification
├── build.gradle                    # Gradle build file and dependencies
├── gradlew, gradlew.bat            # Gradle wrapper scripts
├── verify.sh                       # Pure-JDK compilation and self-check script
├── fixtures/
│   ├── corpus-a.jsonl              # 522 raw bank messages (SMS + Email)
│   └── corpus-a-totals.json        # Checkpoint totals and account expectations
├── db/migration/
│   ├── V1__initial.sql             # Base SQL ledger schema
│   └── V2__seed.sql                # Legacy historical seed data with duplicates
├── src/main/java/in/simplifymoney/ledgersync/
│   ├── App.java                    # CLI entry point: migrate | ingest | report | backfill | check
│   ├── SelfCheck.java              # In-memory smoke test against fixtures
│   ├── model/
│   │   ├── NormalizedTxn.java      # FROZEN: Canonical output contract for transactions
│   │   ├── Category.java           # FROZEN: SPEND, INCOME, MICRO, TRANSFER
│   │   ├── Direction.java          # DEBIT, CREDIT
│   │   ├── Discrepancy.java        # Balance discrepancy record
│   │   ├── RawMessage.java         # Raw uploaded message model
│   │   └── TxnKey.java             # Natural transaction identity key
│   ├── parse/
│   │   ├── Parsers.java            # Parser registry and dispatcher
│   │   ├── Amounts.java            # Decimal sanitization and parsing utilities
│   │   ├── HdfcSmsParser.java      # HDFC debit/credit SMS parser
│   │   ├── IciciSmsParser.java     # ICICI debit/credit SMS parser
│   │   ├── EmailParser.java        # HTML/Text bank email notification parser
│   │   └── ParsedTxn.java          # Intermediate parser output
│   ├── ingest/
│   │   └── IngestService.java      # Ingestion pipeline, deduplication, categorization
│   ├── reconcile/
│   │   └── ReconciliationService.java # Bank stated balance vs running balance auditor
│   ├── store/
│   │   ├── LedgerStore.java        # Core ledger persistence interface
│   │   ├── SqlLedgerStore.java     # SQL/H2 JDBC implementation
│   │   ├── InMemoryLedgerStore.java# In-memory List-based implementation
│   │   ├── DocumentStore.java      # Interface defining the 3 required document access patterns
│   │   ├── MongoConfig.java        # MongoDB connection configuration (reads MONGO_URI, MONGO_DB)
│   │   ├── MongoDocumentStore.java # MongoDB implementation of DocumentStore & LedgerStore
│   │   ├── Backfill.java           # SQL to MongoDB idempotent migration tool
│   │   └── ConsistencyChecker.java # Bidirectional field-level consistency validator
│   ├── report/
│   │   └── Reports.java            # Generators for ledger.json, summary.json, reconciliation.json
│   └── json/
│       └── Json.java               # Dependency-free JSON parser and writer
└── src/test/java/in/simplifymoney/ledgersync/
    ├── AmountsTest.java            # Numerical precision and currency format tests
    ├── EmailParserTest.java        # Email parsing test suite
    ├── IncidentAnalysisTest.java   # Regression test for incident INC-2026-09-11
    ├── NormalizedTxnContractTest.java # FROZEN: Output contract test suite
    ├── ReconciliationTest.java     # Balance reconciliation gap tests
    └── MongoDocumentStoreTest.java # MongoDB persistence, access patterns, backfill & consistency tests
```

---

## Running the Project

### 1. Start MongoDB
Start the containerized MongoDB instance:
```bash
docker compose up -d
```
To verify the container is running:
```bash
docker compose ps
```

### 2. Dependency-Free Pipeline Self-Check (`verify.sh`)
Verifies parsing, categorization, deduplication, and reconciliation in memory against `corpus-a.jsonl`:
```bash
./verify.sh
```

### 3. Run Automated Tests
Run the entire JUnit test suite (36 tests, including MongoDB document store tests):
```bash
./gradlew test
```
To run only the MongoDB document store tests:
```bash
./gradlew test --rerun --tests in.simplifymoney.ledgersync.MongoDocumentStoreTest
```

### 4. Migrate & Ingest into SQL Store
Apply SQL migrations:
```bash
./gradlew run --args="migrate"
```
Ingest the raw message corpus:
```bash
./gradlew run --args="ingest fixtures/corpus-a.jsonl"
```

### 5. Generate Output Reports
Generate `ledger.json`, `summary.json`, and `reconciliation.json` into a target directory:
```bash
./gradlew run --args="report submission/"
```

### 6. Run SQL to MongoDB Backfill
Migrate historical transactions and discrepancies from H2 into MongoDB:
```bash
./gradlew run --args="backfill"
```
*Note: Safe to run repeatedly; subsequent runs detect existing records and skip them.*

### 7. Run Consistency Checker
Verify that SQL and MongoDB are synchronized across all fields:
```bash
./gradlew run --args="check"
```

### 8. Stop MongoDB
When finished, stop the container:
```bash
docker compose down
```

---

## Document Store Details (Task 4)

### 1. Document Model (`transactions` Collection)

```json
{
  "_id": "4821|2026-07-04T20:24:00+05:30|DEBIT|2499.50|AMAZON PAY",
  "txn_key": "4821|2026-07-04T20:24:00+05:30|DEBIT|2499.50|AMAZON PAY",
  "account_last4": "4821",
  "occurred_at": "2026-07-04T20:24:00+05:30",
  "direction": "DEBIT",
  "amount": NumberDecimal("2499.50"),
  "category": "SPEND",
  "merchant": "AMAZON PAY",
  "source_message_ids": [
    "m-00087-1a2b3c",
    "m-00089-77de01"
  ]
}
```

* **Monetary Precision (`Decimal128`)**: Binary floating-point representations (`double`) introduce precision errors. MongoDB's BSON `Decimal128` maps to Java's `BigDecimal` and stores values with 2 decimal places to the paisa.
* **Timestamp & Offset (`OffsetDateTime`)**: Preserved as an ISO-8601 string (`YYYY-MM-DDTHH:mm:ss+HH:MM`) in IST (`+05:30`), allowing chronological string comparisons and indexing.

### 2. The Three Required Access Patterns & Supporting Indexes

`DocumentStore` declares the three specific queries the service requires:

1. **Q1: One account's transactions for one month, newest first**
   * **Method**: `List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month)`
   * **Supporting Index**: Compound index `{ "account_last4": 1, "occurred_at": -1 }` (`idx_account_occurred`)
   * **Behavior**: The compound index supports filtering by account and occurred_at while maintaining the requested timestamp ordering.

2. **Q2: Running totals per category for an account**
   * **Method**: `Map<Category, BigDecimal> categoryTotals(String accountLast4)`
   * **Supporting Index**: Compound index `{ "account_last4": 1, "category": 1, "amount": 1 }` (`idx_account_cat_amt`)
   * **Behavior**: Supports the aggregation pipeline matching on account and grouping by category with amount summation.

3. **Q3: Transaction produced by a message ID**
   * **Method**: `Optional<NormalizedTxn> byMessageId(String messageId)`
   * **Supporting Index**: Multikey index `{ "source_message_ids": 1 }` (`idx_source_msg`)
   * **Behavior**: Uses the `source_message_ids` index to locate transactions by message ID.

4. **Idempotency Constraint**
   * **Supporting Index**: Unique index `{ "txn_key": 1 }` (`idx_txn_key_unique`)
   * **Behavior**: Enforces uniqueness on the computed natural transaction key.

### 3. Observed Explain Statistics (at 100,000 Transactions)

The following values were observed using MongoDB explain execution statistics at 100,000 transactions:

| Access Pattern | Method | `totalDocsExamined` | `nReturned` | Supporting Index |
|---|---|---|---|---|
| **Q1** | `forAccountMonth` | **2,976** | **2,976** | `{ "account_last4": 1, "occurred_at": -1 }` |
| **Q2** | `categoryTotals` | **0** | **20,000** | `{ "account_last4": 1, "category": 1, "amount": 1 }` |
| **Q3** | `byMessageId` | **1** | **1** | `{ "source_message_ids": 1 }` |

---

## Engineering Decisions & Trade-offs

### SQL → MongoDB Migration
The original implementation stored transactions in a relational SQL table (`ledger`) in H2. In the relational schema, `source_message_ids` was represented as a denormalized comma-separated string (`VARCHAR(500)`). Querying which transaction was produced by a given message ID required table scans with string pattern matching (`LIKE '%msgId%'`).

Migrating to MongoDB provided a document representation where `source_message_ids` is stored as an array of strings. With a multikey index on this field, lookups by message ID are resolved through index entries rather than scanning document text. In addition, document storage allows grouping multiple evidencing message IDs directly within the transaction document without requiring a separate relational join table.

### Persistence Abstraction
The architecture separates domain logic from storage mechanisms:
* **`LedgerStore`**: Persistence interface (`save`, `all`, `count`, `saveDiscrepancy`, `discrepancies`). Implemented by `InMemoryLedgerStore`, `SqlLedgerStore`, and `MongoDocumentStore`.
* **`DocumentStore`**: Interface declaring the three access patterns (`forAccountMonth`, `categoryTotals`, `byMessageId`, `save`).
* `MongoDocumentStore` implements both `DocumentStore` and `LedgerStore` (as well as `AutoCloseable`). This allows MongoDB to serve both document-specific query patterns and general ledger ingestion without duplicating business logic.

### MongoDB Document Model
The domain contract `NormalizedTxn` is frozen and remains unaltered. The MongoDB document fields map to the domain model as follows:
* `_id` and `txn_key`: Compound identity string `accountLast4|occurredAt|direction|amount|merchant`.
* `account_last4`: String.
* `occurred_at`: ISO-8601 string (`YYYY-MM-DDTHH:mm:ss+HH:MM`).
* `direction`: String enum name (`DEBIT` or `CREDIT`).
* `amount`: BSON `Decimal128`.
* `category`: String enum name (`SPEND`, `INCOME`, `MICRO`, `TRANSFER`).
* `merchant`: String.
* `source_message_ids`: BSON array of strings.

### Monetary Precision
Monetary calculations cannot use binary floating-point representations (`float`/`double`), which introduce rounding anomalies (e.g., `0.1 + 0.2 != 0.3`).
* In SQL, precision was maintained using `DECIMAL(14, 2)`.
* In MongoDB, precision is maintained using BSON `Decimal128` (IEEE 754-2008 decimal floating-point format). This maps directly to Java's `BigDecimal` and preserves scale to two decimal places.

### Indexing Strategy
Indexes were created to support the required access patterns:
1. **`idx_account_occurred` on `{ account_last4: 1, occurred_at: -1 }`**: Supports `forAccountMonth`. The compound index supports filtering by account and occurred_at while maintaining the requested timestamp ordering.
2. **`idx_account_cat_amt` on `{ account_last4: 1, category: 1, amount: 1 }`**: Supports `categoryTotals`. MongoDB explain statistics for the benchmark reported `totalDocsExamined: 0`.
3. **`idx_source_msg` on `{ source_message_ids: 1 }`**: Supports `byMessageId`. Uses the `source_message_ids` index to locate transactions by message ID.
4. **`idx_txn_key_unique` on `{ txn_key: 1 }`**: Enforces uniqueness on the transaction key.

### Idempotency
Ingestion and backfill pipelines prevent duplicate documents upon repeated runs:
* A transaction's natural identity is computed from `(account_last4, occurred_at, direction, amount, merchant)`.
* `MongoDocumentStore.save()` uses an upsert operation:
  * Filter: `{ _id: txnKey }`
  * Updates: `$setOnInsert` for transaction fields, combined with `$addEachToSet` on `source_message_ids`.
* If an evidencing message for an existing transaction is processed again, the message ID is appended to `source_message_ids` via set accumulation without creating a new document.

### Backfill Strategy
The `Backfill` component migrates data from `SqlLedgerStore` to `DocumentStore`:
* Historical SQL data contains duplicate rows (e.g., `V2__seed.sql` where identical records or multiple alerts for the same event were inserted as separate rows).
* `Backfill.run()` consolidates SQL rows in memory by natural transaction identity, combining unique message IDs into a set.
* Each consolidated transaction is saved to `DocumentStore.save()`.
* The backfill tracks `read`, `written`, and `skipped`. On the initial run, duplicate rows within SQL are skipped. On subsequent runs, existing documents are recognized and skipped (`written = 0, skipped = read`), avoiding duplicate inserts.

### Consistency Checking
The `ConsistencyChecker` compares `SqlLedgerStore` and `DocumentStore`:
* **Missing in Documents**: Identifies transactions present in SQL but absent in MongoDB (`MISSING_IN_DOCUMENTS`).
* **Missing in SQL**: Identifies transactions present in MongoDB but absent in SQL (`MISSING_IN_SQL`).
* **Field-Level Differences**: For matching transaction keys, compares `amount`, `category`, `direction`, `merchant`, `occurred_at`, and `source_message_ids` (`FIELD_MISMATCH`).
* **Traceability Check**: Verifies that each message ID in a document resolves back to that transaction through `byMessageId`.

---

## Known Limitations & Unimplemented Features

### Reconciliation Gap
When evaluating the provided corpus (`fixtures/corpus-a.jsonl`) against expected checkpoint totals (`fixtures/corpus-a-totals.json`):
* The totals file expects **257 transactions**; the message corpus contains evidence for **256 transactions**.
* Specifically, on account `**4821`, between consecutive bank alerts on **2026-07-29T17:06+05:30**, the bank-stated available balance drops by **₹7,500.00** without an evidencing SMS or email in the corpus.
* **Handling**: The system reports this in `reconciliation.json` as:
  ```json
  {
    "account_last4": "4821",
    "occurred_at": "2026-07-29T17:06:00+05:30",
    "amount": "7500.00",
    "note": "Bank stated balance dropped by 7500.00 without an evidencing transaction"
  }
  ```
  The implementation intentionally reports this as a reconciliation discrepancy rather than inventing a ledger transaction.

### System Scope Limitations
* **Batch Ingestion Model**: The service processes static message batches from `.jsonl` files. Live streaming ingestion (e.g., Kafka, webhook endpoints, or real-time mobile sync) is not implemented.
* **Local Docker Deployment**: MongoDB runs via Docker Compose in standalone mode for local development and testing. Production deployments would typically require a managed replica set with TLS and access controls.
* **No Direct Core Banking Integration**: The service operates by parsing consumer device notifications (SMS and email). It does not interface directly with core banking networks, UPI switches, or payment gateway APIs.
* **Parser Coverage**: Parsers are implemented for the formats present in the corpus (HDFC SMS, ICICI SMS, and bank email templates). Other bank notification templates are not supported.

---

## AI Assistance

AI tools were used as development assistance during the implementation.

Specifically, AI assistance was used for:
* Reviewing MongoDB Java Driver Sync APIs and query syntax.
* Drafting templates for BSON document mapping and index creation.
* Checking cross-platform classpath handling in bash scripts on Windows.
* Suggesting test cases for access patterns and divergence detection.
* Reviewing and refining documentation.

All generated code, queries, and logic were reviewed, adapted to project constraints, verified against the frozen domain contracts, and validated using local test suites and Docker Compose. Business logic and reconciliation decisions were reviewed rather than accepting generated suggestions without evaluation.

---

## Final Verification

The repository verification suites confirm the following results:

* **Automated Unit & Integration Tests**: **36 tests passed**, **0 failures**, **0 errors** across 6 test suites:
  * `AmountsTest`: 9 passed
  * `EmailParserTest`: 4 passed
  * `IncidentAnalysisTest`: 1 passed
  * `NormalizedTxnContractTest`: 6 passed
  * `ReconciliationTest`: 5 passed
  * `MongoDocumentStoreTest`: 11 passed
* **Corpus Ingestion & Pipeline (`verify.sh`)**:
  * **522 messages read**
  * **256 transactions written**
  * **43 messages skipped** (non-transactional notifications)
  * **1 reconciliation discrepancy** (the ₹7,500.00 balance drop on account `**4821`)
* **Category Totals**:
  * `SPEND`: ₹142,567.64
  * `INCOME`: ₹142,791.16
  * `MICRO`: ₹4,443.85
  * `TRANSFER`: ₹62,000.00
* **Consistency Check**:
  * **0 divergences found** between SQL and MongoDB (`CONSISTENCY CHECK PASSED: SQL and DocumentStore agree perfectly.`).

---

## Submission Notes

For reviewers evaluating this submission:

1. **Start MongoDB**:
   ```bash
   docker compose up -d
   ```
2. **Run Dependency-Free Verification**:
   ```bash
   ./verify.sh
   ```
3. **Run Full Test Suite**:
   ```bash
   ./gradlew test
   ```
4. **Run SQL Ingestion & Report Generation**:
   ```bash
   ./gradlew run --args="migrate"
   ./gradlew run --args="ingest fixtures/corpus-a.jsonl"
   ./gradlew run --args="report submission/"
   ```
5. **Run SQL to MongoDB Backfill**:
   ```bash
   ./gradlew run --args="backfill"
   ```
6. **Run Bidirectional Consistency Checker**:
   ```bash
   ./gradlew run --args="check"
   ```
7. **Tear Down MongoDB**:
   ```bash
   docker compose down
   ```
