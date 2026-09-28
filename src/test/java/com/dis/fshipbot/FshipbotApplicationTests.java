package com.dis.fshipbot;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest(properties = {
        "app.ingestion.run-on-startup=false",
        "spring.datasource.url=jdbc:h2:mem:testdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.default_schema=PUBLIC"
})
class FshipbotApplicationTests {

    @MockBean
    private EmbeddingStore<TextSegment> embeddingStore;

    @MockBean
    private EmbeddingModel embeddingModel;

    // NOTE: no @MockBean for ChatLanguageModel — there are 3 beans (gemini/nim/openai)
    // and a single typeless mock fails with "expected a single matching bean". The real
    // beans construct fine without keys (DUMMY-KEY-FOR-STARTUP, no network at startup),
    // and this test makes no LLM calls.

    @Test
    void contextLoads() {
        // Context loads with H2 + mocked vector store so DB (pgvector) not required for unit test
    }

}
