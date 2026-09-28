package com.dis.fshipbot.config;

import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;

import javax.sql.DataSource;

/**
 * Exposes LangChain4j's {@code protected} DataSource-based constructor.
 *
 * <p>The public builder hard-codes {@code PGSimpleDataSource}, which is NOT
 * pooled — every {@code findRelevant} call opened a fresh physical connection
 * (TCP + auth) to the remote Postgres host. Injecting our own pooled DataSource
 * fixes that AND lets us pin {@code ivfflat.probes} on every connection via
 * Hikari's {@code connection-init-sql}.
 *
 * <p>Parameter order verified against {@code PgVectorEmbeddingStoreBuilder}
 * field order (host/port/... excluded, the DataSource overload takes the tail).
 */
class PooledPgVectorEmbeddingStore extends PgVectorEmbeddingStore {

    PooledPgVectorEmbeddingStore(DataSource dataSource,
                                 String table,
                                 Integer dimension,
                                 Boolean useIndex,
                                 Integer indexListSize,
                                 Boolean createTable,
                                 Boolean dropTableFirst) {
        // null MetadataStorageConfig -> the store falls back to
        // DefaultMetadataStorageConfig.defaultConfig() (jsonb), matching the
        // behaviour of the public builder.
        super(dataSource, table, dimension, useIndex, indexListSize,
                createTable, dropTableFirst, null);
    }
}
