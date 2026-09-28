package com.dis.fshipbot.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallenv15.BgeSmallEnV15EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Slf4j
@Configuration
public class VectorStoreConfig {

    @Value("${spring.datasource.url}")
    private String jdbcUrl;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    @Value("${vector.store.table-name:knowledge_base}")
    private String tableName;

    @Value("${vector.store.schema:fship_ai}")  // Add schema configuration
    private String schemaName;

    @Value("${vector.store.dimensions:384}")
    private int dimensions;

    /**
     * ivfflat.probes — how many of the index's `lists` clusters a search scans.
     * PostgreSQL defaults this to 1, so with an index built on lists=50 a search
     * structurally CANNOT see ~98% of the corpus. Pinned per connection below.
     * Default 100 = effectively exact search (probes >= lists), which is the safe
     * choice for a corpus this size; lower it only if vector latency becomes a
     * measured problem.
     */
    @Value("${vector.store.ivfflat-probes:100}")
    private int ivfflatProbes;

    @Value("${vector.store.pool-size:5}")
    private int vectorPoolSize;

    /**
     * Number of IVFFlat clusters the index is built with. Kept in sync with
     * schema.sql (ivfflat WITH (lists = 50)) and must be <= ivfflat-probes for a
     * search to be effectively exact. Only used if index creation is ever enabled.
     */
    @Value("${vector.store.index-list-size:50}")
    private int indexListSize;

    @Value("${vector.store.max-lifetime-ms:600000}")
    private long vectorMaxLifetimeMs;

    @Bean
    @Primary
    public EmbeddingModel embeddingModel() {
        log.info("=".repeat(60));
        log.info("Initializing BGE Small EN v1.5 Embedding Model");
        log.info("  - Dimensions: 384");
        log.info("  - Type: ONNX Runtime (local)");
        log.info("=".repeat(60));

        try {
            return new BgeSmallEnV15EmbeddingModel();
        } catch (UnsatisfiedLinkError e) {
            // JVM-level pinning: the tokenizer/ONNX native lib was already loaded by a
            // PREVIOUS deployment's classloader in this same JVM (typical after a context
            // reload or manager-triggered restart). A native lib loads once per JVM —
            // no code path can recover; only a full JVM restart clears it.
            String restart = restartHint();
            log.error("=".repeat(60));
            log.error("BGE native library is already loaded in this JVM by an earlier deployment.");
            log.error("This is NOT fixable in code — a native library loads once per JVM.");
            log.error("RESTART THE TOMCAT SERVICE, then redeploy: {}", restart);
            log.error("Reloading or re-starting only the webapp context is NOT enough.");
            log.error("To stop this recurring on every redeploy, move the DJL/tokenizers jar");
            log.error("into $CATALINA_HOME/lib so it loads once in the shared classloader.");
            log.error("=".repeat(60));
            throw new IllegalStateException(
                    "BGE/tokenizer native library already loaded by a previous deployment "
                            + "in this JVM. Restart the Tomcat SERVICE (not just the context) "
                            + "and redeploy: " + restart,
                    e);
        }
    }

    /**
     * Platform-appropriate command for restarting Tomcat.
     *
     * <p>Was hard-coded to the Windows {@code net stop/start} form, which does not
     * exist on the Linux host this actually deploys to — the operator gets an
     * error while the real fix sits in the message. Returns a generic hint when
     * the platform is unrecognised rather than guessing.
     */
    public static String restartHint() {
        return restartHint(System.getProperty("os.name", ""));
    }

    public static String restartHint(String osName) {
        String os = osName == null ? "" : osName.toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) {
            return "net stop <service> && net start <service>   (or services.msc)";
        }
        if (os.contains("nux") || os.contains("nix") || os.contains("aix") || os.contains("mac")) {
            return "systemctl restart tomcat   (or: systemctl restart tomcat9 / tomcat10 — "
                    + "check with: systemctl list-units --type=service | grep -i tomcat)";
        }
        return "restart the Tomcat service (systemctl restart tomcat on Linux, "
                + "net stop/start <service> on Windows)";
    }

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        log.info("=".repeat(60));
        log.info("Initializing PGVector Embedding Store");
        log.info("  - Schema: {}", schemaName);
        log.info("  - Table: {}", tableName);
        log.info("  - Dimensions: {}", dimensions);
        log.info("  - ivfflat.probes: {} (clusters searched per query; PG default of 1 discards ~98% of the corpus)",
                ivfflatProbes);
        log.info("  - ivfflat lists: {}", indexListSize);
        log.info("  - Connection pool: {} (LangChain4j default was UNPOOLED — one TCP handshake per query)",
                vectorPoolSize);
        log.info("  - JDBC URL: {}", jdbcUrl);

        // "password authentication failed for user X" is identical whether the
        // password is wrong, empty, or the DNS name resolved somewhere else.
        // Log the target and credential SHAPE (never the values) so the cause is
        // diagnosable from the startup log alone.
        log.info("  - Target: {}", describeTarget(jdbcUrl));
        log.info("  - DB user: {}", (username == null || username.isBlank()) ? "<BLANK>" : username);
        log.info("  - DB password: {}",
                password == null || password.isBlank() ? "<BLANK>" : "set (" + password.length() + " chars)");

        if (username == null || username.isBlank()) {
            throw new IllegalStateException(
                    "spring.datasource.username is blank. Set FSHIP_AI_DB_USR in the environment "
                            + "before starting the app.");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "spring.datasource.password is blank, so PostgreSQL is being asked to "
                            + "authenticate with an empty password. Set FSHIP_AI_DB_PASS in the "
                            + "environment before starting the app. If the value contains '$', "
                            + "export it with SINGLE quotes — double quotes expand it and corrupt "
                            + "the password.");
        }
        log.info("=".repeat(60));

        // Build the fully qualified table name
        String fullTableName = schemaName + "." + tableName;

        // Pooled DataSource with ivfflat.probes pinned on every connection.
        // Separate from the Hikari pool used by JdbcTemplate (repositories) —
        // both are needed, but the vector store no longer opens a physical
        // connection per query.
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(jdbcUrl);
        hc.setUsername(username);
        hc.setPassword(password);
        hc.setMaximumPoolSize(vectorPoolSize);
        hc.setMinimumIdle(Math.max(1, vectorPoolSize / 2));
        hc.setMaxLifetime(vectorMaxLifetimeMs);
        hc.setPoolName("pgvector-pool");
        hc.setConnectionInitSql("SET ivfflat.probes = " + Math.max(1, ivfflatProbes));

        HikariDataSource vectorDataSource;
        try {
            vectorDataSource = new HikariDataSource(hc);
        } catch (Exception e) {
            // Wrap so the operator sees WHICH endpoint refused the connection.
            // The raw Hikari/SQLState chain says only "auth failed", which is
            // indistinguishable from a wrong host or a mangled shell variable.
            throw new IllegalStateException(
                    "Could not connect to " + describeTarget(jdbcUrl) + " as user '" + username
                            + "'. PostgreSQL rejected the credentials. In order of likelihood: "
                            + "(1) FSHIP_AI_DB_PASS was exported with double quotes and the '$' was "
                            + "expanded — use single quotes; (2) the variable is not reaching the JVM "
                            + "(systemd EnvironmentFile, or a launcher that drops the environment); "
                            + "(3) '" + hostOf(jdbcUrl) + "' resolves to a different host from the one "
                            + "your Windows machine reaches. Verify with: "
                            + "PGPASSWORD=\"$FSHIP_AI_DB_PASS\" psql -h " + hostOf(jdbcUrl)
                            + " -U " + username + " -d " + databaseOf(jdbcUrl)
                            + " -c 'select 1'",
                    e);
        }

        return new PooledPgVectorEmbeddingStore(
                vectorDataSource,
                fullTableName,
                dimensions,
                // useIndex=false: only gates CREATE INDEX in initTable (it is not a
                // stored field and does not affect the search SQL — the planner still
                // uses the IVFFlat index, which is exactly why ivfflat.probes matters).
                // The index is owned by schema.sql, so the app must not create it.
                // Passing true here also forces indexListSize validation at startup.
                false,
            indexListSize, // > 0; must match schema.sql's lists = 50
            false,  // createTable — schema.sql owns the table too
            false); // dropTableFirst
    }

    /**
     * Human-readable endpoint for logs and error messages. Never includes
     * credentials — the JDBC URL in this project carries none, but it is parsed
     * defensively so a future {@code ?user=...} cannot leak into a log line.
     */
    public static String describeTarget(String jdbcUrl) {
        String url = jdbcUrl == null ? "<null>" : jdbcUrl;
        int q = url.indexOf('?');
        if (q >= 0) {
            url = url.substring(0, q);
        }
        int creds = url.indexOf("//");
        if (creds >= 0) {
            int at = url.indexOf('@', creds);
            if (at >= 0) {
                url = url.substring(0, creds + 2) + "<redacted>" + url.substring(at);
            }
        }
        return url;
    }

    /** Host component of a JDBC URL, or {@code <unknown>}. */
    public static String hostOf(String jdbcUrl) {
        return component(jdbcUrl, 1);
    }

    /** Database (path) component of a JDBC URL, or {@code <unknown>}. */
    public static String databaseOf(String jdbcUrl) {
        return component(jdbcUrl, 2);
    }

    private static String component(String jdbcUrl, int which) {
        if (jdbcUrl == null) {
            return "<unknown>";
        }
        String rest = jdbcUrl.substring(jdbcUrl.indexOf("//") + 2);
        // Strip any userinfo ("user:pass@host"). Without this the host is
        // reported as the USERNAME, so the diagnostic would send an operator to
        // the wrong machine. Defensive — this project embeds no credentials.
        int at = rest.indexOf('@');
        if (at >= 0) {
            rest = rest.substring(at + 1);
        }
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            if (which == 2) {
                String db = rest.substring(slash + 1);
                int q = db.indexOf('?');
                return q >= 0 ? db.substring(0, q) : db;
            }
            rest = rest.substring(0, slash);
        }
        int colon = rest.indexOf(':');
        return colon >= 0 ? rest.substring(0, colon) : rest;
    }
}