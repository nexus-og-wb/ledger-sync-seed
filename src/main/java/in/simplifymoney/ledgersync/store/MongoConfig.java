package in.simplifymoney.ledgersync.store;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import java.util.Objects;

/**
 * Configuration and client factory for MongoDB connection.
 *
 * Reads MONGO_URI (default: mongodb://localhost:27017) and
 * MONGO_DB (default: ledger_sync) from environment variables or system properties,
 * falling back to sensible local defaults for development.
 */
public record MongoConfig(String uri, String databaseName) {

    public static final String DEFAULT_URI = "mongodb://localhost:27017";
    public static final String DEFAULT_DATABASE = "ledger_sync";

    public MongoConfig {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(databaseName, "databaseName");
    }

    public static MongoConfig fromEnv() {
        String uri = System.getProperty("mongo.uri",
                System.getenv().getOrDefault("MONGO_URI", DEFAULT_URI));
        String db = System.getProperty("mongo.db",
                System.getenv().getOrDefault("MONGO_DB", DEFAULT_DATABASE));
        return new MongoConfig(uri, db);
    }

    public MongoClient createClient() {
        return MongoClients.create(uri);
    }

    public MongoDatabase getDatabase(MongoClient client) {
        return client.getDatabase(databaseName);
    }
}
