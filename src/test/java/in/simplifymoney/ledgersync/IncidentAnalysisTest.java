package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.Amounts;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class IncidentAnalysisTest {

    @Test
    void analyzeCorpusForIncident() throws IOException {
        List<RawMessage> messages = IngestService.readCorpus(Path.of("fixtures/corpus-a.jsonl"));
        int affectedCount = 0;
        System.out.println("no. of bad messages ");
        for (RawMessage m : messages) {
            String body = m.body();
            BigDecimal extractedFirst = Amounts.first(body);
            BigDecimal statedBal = Amounts.statedBalance(body);
            if (extractedFirst != null && statedBal != null && extractedFirst.compareTo(statedBal) == 0) {
                System.out.println("affected messages: " + m.messageId() + body);
                affectedCount++;
            }
        }
        System.out.println("total affected message: " + affectedCount);
    }
}
