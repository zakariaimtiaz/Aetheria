package com.dis.fshipbot.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Properties;

/**
 * Regression guard for the startup failure:
 * {@code indexListSize must be greater than zero, but is: null}.
 *
 * <p>langchain4j-pgvector's DataSource constructor reaches
 * {@code ValidationUtils.ensureGreaterThanZero(indexListSize, ...)} inside
 * {@code initTable}, so a null/zero value kills the {@code embeddingStore} bean.
 * The Spring test suite mocks {@code EmbeddingStore}, so nothing else catches it.
 *
 * <p>A full construct test is not possible without a live PostgreSQL connection
 * (the store unwraps a real {@code PGConnection} to register the vector type and
 * runs {@code CREATE EXTENSION IF NOT EXISTS vector}). So this asserts the
 * config invariants that keep the bean constructible and the search exact.
 */
public class VectorStoreConfigArgsTest {

    private Properties props() throws Exception {
        Properties p = new Properties();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("database.properties")) {
            Assertions.assertNotNull(in, "database.properties not on classpath");
            p.load(in);
        }
        return p;
    }

    @Test
    public void indexListSizeIsPositive() throws Exception {
        // Must be > 0: the store validates it during bean creation.
        int lists = Integer.parseInt(props().getProperty("vector.store.index-list-size", "50").trim());
        Assertions.assertTrue(lists > 0,
                "vector.store.index-list-size must be > 0, was " + lists
                        + " — the embeddingStore bean will fail to start");
    }

    @Test
    public void probesCoverAllClustersSoSearchIsExact() throws Exception {
        Properties p = props();
        int probes = Integer.parseInt(p.getProperty("vector.store.ivfflat-probes", "100").trim());
        int lists = Integer.parseInt(p.getProperty("vector.store.index-list-size", "50").trim());
        Assertions.assertTrue(probes >= lists,
                "ivfflat.probes (" + probes + ") < lists (" + lists
                        + ") — searches cannot see the whole corpus");
    }

    @Test
    public void vectorPoolIsConfigured() throws Exception {
        int pool = Integer.parseInt(props().getProperty("vector.store.pool-size", "5").trim());
        Assertions.assertTrue(pool > 0,
                "vector.store.pool-size must be > 0; the store's DataSource is unpooled by default");
    }
}
