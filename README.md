<div align="center">

# Aetheria

**A retrieval-augmented knowledge assistant that turns an organisation's documents into cited, verifiable answers.It is an intelligent RAG bot transforming unstructured enterprise data into instant action. Powered by high-performance vector retrieval, LLM synthesis, and **JEV by Typesafe AI** for maximum precision, Aetheria searches technical specs and corporate records to deliver accurate, citation-backed answers in seconds.**

[![Java 11](https://img.shields.io/badge/Java-11-blue.svg)](https://www.oracle.com/java/technologies/javase/javase11-archive-downloads.html)
[![Spring Boot 2.7](https://img.shields.io/badge/Spring%20Boot-2.7.18-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![LangChain4j 0.35](https://img.shields.io/badge/LangChain4j-0.35.0-purple.svg)](https://docs.langchain4j.dev/)
[![PGVector](https://img.shields.io/badge/vector%20store-PGVector-3164F4.svg)](https://github.com/pgvector/pgvector)

</div>

---

Aetheria ingests an organisation's documents — Markdown, Word, PDF, PowerPoint, Excel, and live web pages — embeds them locally, and answers staff questions with citations back to the source passage. Retrieval is hybrid (vector + lexical), results are re-ranked and then checked for grounding before the user ever sees them, and every prompt is editable at runtime from an admin panel without a redeploy.

Originally built for Friendship NGO as `FShipBot`; the Maven artifact is still `com.dis:fshipbot`.

---

## Table of Contents

- [Features](#features)
- [Tech Stack](#tech-stack)
- [How the RAG Pipeline Works](#how-the-rag-pipeline-works)
- [Project Layout](#project-layout)
- [Prerequisites](#prerequisites)
- [Database Setup](#database-setup)
- [Configuration](#configuration)
- [Build & Run](#build--run)
- [API Reference](#api-reference)
- [Admin Panel](#admin-panel)
- [The Prompt System](#the-prompt-system)
- [Ingestion](#ingestion)
- [Testing](#testing)
- [Operational Notes](#operational-notes)
- [Security](#security)
- [License](#license)

---

## Features

**Grounded answers with sources**
Every answer carries the document names, chunk indices, and similarity scores it was built from. When the context genuinely doesn't contain the answer, the model is told to say so rather than fall back on general knowledge.

**Hybrid retrieval**
Semantic vector search finds passages that *mean* the same thing; a PostgreSQL full-text + trigram lexical path catches exact codes, amounts, and names that embeddings routinely blur. The two are fused with Reciprocal Rank Fusion. Lexical degrades to `ILIKE` rather than erroring if the tsvector column is missing.

**Retrieval quality passes**
Deduplication of overlapping chunks, per-section and per-file caps, rarity-weighted keyword ranking, position-aware fill, and coverage repair so every half of a compound question ("X and Y?") gets evidence. Table-of-contents chunks are dropped outright — they reference content without containing it.

**Answer guards**
A foul-language filter and a casual-chat detector both short-circuit before the LLM is called. Post-generation, the answer is checked for degeneration loops, reasoning leaks, and mid-sentence truncation, and retried once before falling back to a safe message.

**Grounding validation (optional)**
`rag.grounding-provider` accepts `none | llm | jev | jev,llm`. The `jev` mode uses JEV by Typesafe AI to judge whether the answer is actually supported by the retrieved context. It short-circuits the cascade and **fails open** — a validator outage never blocks a user.

**JEV re-ranking (optional)**
One Noul score per candidate chunk in a single call, re-ordering the context before generation. Also dark by default and fails open.

**Every prompt is DB-driven**
All 14 LLM prompts live in `fship_ai.app_prompt` and are editable from the admin panel at runtime. The compiled-in registry is only a fallback, so an unreachable database degrades to defaults rather than breaking chat.

**Multi-format ingestion**
Table-aware chunking for Markdown and Word, deterministic sheet→markdown conversion for Excel (no LLM quota spent), URL fetching for web pages, and a Document Tools page for converting, uploading, re-ingesting, and running retrieval diagnostics.

**Swappable LLM provider**
NVIDIA NIM (default), Google Gemini, or OpenAI — switchable from the admin panel without a restart.

---

## Tech Stack

| Layer | Choice |
|---|---|
| Language | Java 11 target, built with JDK 17 |
| Framework | Spring Boot 2.7.18 (Web, Thymeleaf, Validation, Actuator) |
| AI orchestration | LangChain4j 0.35.0 |
| Embeddings | BAAI BGE Small EN v1.5 (ONNX, local, 384-dim) — no embedding API calls |
| Vector store | PostgreSQL + pgvector, 384-dim COSINE, IVFFLAT (`lists=50`) |
| LLM providers | NVIDIA NIM · Google Gemini 2.5 Flash Lite · OpenAI GPT-4o-mini |
| Ranking & grounding | JEV by Typesafe AI (System One Noul judgments) |
| Document parsing | Apache Tika 2.9.2 |
| Database | PostgreSQL (raw `JdbcTemplate`, no ORM for the hot path) |
| Packaging | WAR — self-contained, or external Tomcat 8.5 |
| Build | Maven 3.9.x |

---

## How the RAG Pipeline Works

`ChatService.answerQuestion` is the single entry point. Stages in order:

1. **Foul-language guard** — abuse returns a canned deflection and never reaches RAG, the LLM, or conversation memory.
2. **Casual detection** — greetings and small talk bypass retrieval entirely.
3. **Query rewrite** — a local, zero-LLM-quota heuristic folds conversation history into the embedded query so follow-ups like *"What about the probation period?"* resolve.
4. **Query expansion** *(optional, off by default)* — an LLM rewrite. There is deliberately no static synonym map.
5. **Embedding** — BGE Small EN v1.5 via ONNX. The asymmetric model requires the `Represent this sentence for searching relevant passages:` prefix on the **query side only**; document embeddings must not carry it.
6. **Hybrid retrieval** — 300 candidates from vector search, 100 from lexical, fused by RRF. A low-recall retry relaxes the threshold by 0.10 if fewer than 5 hits come back.
7. **Deduplication** — character-trigram Jaccard > 0.85 on same-file chunks collapses overlap neighbours.
8. **Source diversity** — TOC filtering, phrase and keyword priority, rarity weighting, per-section cap of 5, fair-share position-aware fill, and sub-question coverage repair.
9. **JEV re-rank** *(optional)* — one Noul judgment per candidate; silent fallback to heuristic order.
10. **Context building** — up to `rag.max-context-length` (14 000 chars).
11. **Generation** — `prompt.system`, or `prompt.no_context` when retrieval came back empty. Cut-off, empty, degenerate, and reasoning-leak answers each trigger one targeted retry.
12. **Grounding validation** — the ordered cascade. A rejection replaces the answer with a safe "cannot confirm" message.

Per-stage timings are logged on every query, and the admin panel exposes a retrieval diagnostics view.

### Two non-obvious constraints

The JEV grounding criteria are **deliberate complements**. A universal quantifier on the `true` side ("every claim is supported") paired with an existential one on the `false` side makes the verdict a function of answer *length* — long answers then get rejected no matter how well grounded. `GroundingJudgeTest` enforces this.

The judge grades the **whole answer**, so `MAX_ANSWER_CHARS` must comfortably exceed a typical answer. A silent truncation grades a fragment while the user reads the full text.

---

## Project Layout

```
src/main/java/com/dis/fshipbot/
├── Application.java              Entry point
├── config/
│   ├── ChatModelConfig           Gemini / OpenAI / NIM beans
│   ├── VectorStoreConfig         PGVector store, pooled connections, URI fixup
│   ├── WebMvcConfig              Interceptor registration
│   ├── PageAccessInterceptor     Session gate for /admin/**
│   ├── GlobalViewAdvice          Injects appName / appVersion into every view
│   ├── PooledPgVectorEmbeddingStore
│   └── NoCacheFilter
├── controller/
│   ├── ChatController            /api/ask, /api/health, /api/validate-key
│   ├── HomeController            /, /chat, /admin/login, /app-config.xml
│   ├── BotSettingsController     Prompts, settings, suggestions, provider
│   └── DocumentToolsController   Convert, upload, ingest, diagnostics
├── service/
│   ├── ChatService               The RAG pipeline
│   ├── HybridFusionService       RRF fusion of vector + lexical
│   ├── JevRerankService          JEV re-rank and grounding
│   ├── PromptService             DB prompt cache, render, repair
│   ├── IngestionService          CommandLineRunner entry point
│   ├── AppConfigService          Settings and XML builder
│   ├── ProviderService           Active provider / model
│   └── RetrievalDiagnosticsService
├── ingestion/
│   ├── DocumentProcessor         Walk, hash, split, embed, insert
│   ├── DocumentSplitter          Table-aware chunking
│   ├── ExcelContentProcessor     Deterministic sheet → markdown
│   ├── WebContentProcessor       URL fetch, parse, boilerplate strip
│   └── DocNoiseStripper
├── repository/                   Raw JDBC against the fship_ai schema
├── model/                        ChatRequest, ChatResponse, AppSetting, AppPrompt, …
└── util/
    ├── PromptDefaults            Registry: key, group, label, default text
    ├── FileHashUtil
    └── ContentHashUtil

src/main/resources/
├── application.properties        Server, RAG, ingestion, chunking
├── database.properties           Datasource, Hikari, PGVector
├── models.properties             Providers, models, API keys, JEV
├── logging.properties
├── db/schema.sql                 Tables, extensions, indexes
├── db/data.sql                   Idempotent seed
├── templates/                    Thymeleaf (index + admin)
└── static/css/
```

---

## Prerequisites

| Requirement | Notes |
|---|---|
| **JDK 17** | To build. The bytecode target is Java 11, so the server runs on JDK 11+. |
| **Maven 3.9+** | Or use the bundled `mvnw`. |
| **PostgreSQL 12+ with pgvector** | `CREATE EXTENSION vector` needs superuser or a pgvector-enabled image. |
| **An LLM API key** | At least one of `NVIDIA_API_KEY`, `GEMINI_API_KEY`, `OPENAI_API_KEY`. |
| **Optional: `TYPESAFE_API_KEY`** | Enables JEV re-ranking and grounding. Without it both are skipped silently. |
| **Optional: MarkItDown CLI** | Only for the admin Document Tools *convert* action. |

> **Open in IntelliJ:** File → Open → `pom.xml` → *Open as Project*. Do not open the parent directory — IntelliJ will not build the Maven project correctly.

---

## Database Setup

Create the schema, then seed it. **Run `schema.sql` first** — it owns the `vector`, `pgcrypto`, and `pg_trgm` extensions, which `data.sql` assumes exist.

```bash
psql -h <host> -U <user> -d <database> -f src/main/resources/db/schema.sql
psql -h <host> -U <user> -d <database> -f src/main/resources/db/data.sql
```

`schema.sql` creates:

| Table | Purpose |
|---|---|
| `fship_ai.knowledge_base` | 384-dim pgvector embeddings with file metadata, chunk info, versioning, and a generated `content_tsv` column |
| `fship_ai.app_prompt` | Admin-editable LLM prompts |
| `fship_ai.app_setting` | Key/value chat copy, display flags, category-grouped |
| `fship_ai.app_suggestion` | Suggested questions shown in the chat UI |

Index notes:

- The IVFFLAT index is built with `lists = 50`, so `vector.store.ivfflat-probes` is set to **100** (≥ `lists`, i.e. effectively exact search). PostgreSQL defaults this to `1`, which hides ~98 % of the corpus and silently produces low-recall answers. Lower it only if vector latency is actually measured as a problem.
- The application **never creates or alters** the knowledge base table or its indexes. `schema.sql` owns them.
- A guarded `DO $$` block renames a legacy `friendship_knowledge_base` to `knowledge_base` before the `CREATE TABLE`. It runs first on purpose — otherwise an existing deployment gets a brand-new empty table and silently loses every chunk. It is idempotent, so `schema.sql` is safe to re-run.
- A generated column takes **no** `NULL`/`NOT NULL` clause. A trailing `NULL` on `content_tsv` is a syntax error that aborts the whole script, leaving later tables uncreated.

`data.sql` seeds idempotently (`WHERE NOT EXISTS …`) so re-running it never overwrites admin edits.

---

## Configuration

Configuration is split across four files under `src/main/resources`, merged via `spring.config.import`. Every credential is read from the environment — **do not commit keys.**

### Environment variables

> **Before you go further:** the properties files in this repository currently contain
> **hard-coded credentials** — a PostgreSQL password in `database.properties` and API
> keys for Gemini, NVIDIA NIM, and Typesafe in `models.properties`. Only
> `openai.api-key` uses an env placeholder. See [Security](#security) below.

| Variable | Required | Maps to |
|---|---|---|
| `SPRING_DATASOURCE_USERNAME` | yes | `spring.datasource.username` |
| `SPRING_DATASOURCE_PASSWORD` | yes | `spring.datasource.password` |
| `SPRING_DATASOURCE_URL` | optional | Point at a different PostgreSQL instance |
| `GEMINI_API_KEY` | if `chat.provider=gemini` | `gemini.api-key` |
| `OPENAI_API_KEY` | if `chat.provider=openai` | `openai.api-key` *(already wired)* |
| `NVIDIA_API_KEY` | if `chat.provider=nim` | `nim.api-key` |
| `TYPESAFE_API_KEY` | optional | `jev.api-key` — enables JEV re-rank + grounding |
| `DOCS_DIR` | optional | `docs.directory` |
| `FSHIP_AI_LOG_DIR` | optional | Log output directory |

Two wiring styles are in play. The intended style is an explicit placeholder, as used for
OpenAI in `models.properties`:

```properties
openai.api-key=${OPENAI_API_KEY:}
```

Spring Boot's relaxed binding also lets any property be overridden by its uppercase,
underscored environment name — so `SPRING_DATASOURCE_PASSWORD` works against
`spring.datasource.password` with no code change. The names `FSHIP_AI_DB_USR`,
`FSHIP_AI_DB_PASS`, and `FSHIP_AI_LOG_DIR` appear in comments in the properties files, but
except for the log directory **they are not currently wired to placeholders** and will not
take effect. Use the relaxed-binding names above, or add `${...}` placeholders.

The app starts with dummy keys, but `/api/ask` fails until the **active** provider's key
is present. `web.user-agent` and `docs.directory` are likewise Windows-path defaults;
change them for your platform.

### Security

Before publishing this repository:

1. **Rotate every credential** that is currently committed — the PostgreSQL password, the
   Gemini key, the NVIDIA NIM key, and the Typesafe/Jev key. Treat them as compromised;
   they are in the git history regardless of what the working tree says.
2. **Purge them from history** with `git filter-repo` or BFG, then force-push.
3. **Convert the properties files to env placeholders** using the `openai.api-key` pattern
   above, so the values never reappear in a commit.

Note that `FOLLOW`/history rewriting changes every commit hash, so coordinate it before
anyone else branches.

### Retrieval (`application.properties`)

| Property | Default | Meaning |
|---|---|---|
| `rag.top-k` | `20` | Chunks placed into the final context |
| `rag.similarity-threshold` | `0.50` | Minimum cosine score; a low-recall retry relaxes this by 0.10 |
| `rag.max-context-length` | `14000` | Character budget for the assembled context |
| `rag.hybrid.enabled` | `true` | Fuse lexical results into vector results via RRF |
| `rag.hybrid.vector-limit` / `lexical-limit` | `100` | Per-path candidate caps |
| `rag.hybrid.rrf-k` | `60` | RRF smoothing constant |
| `rag.hybrid.table-boost` | `0.08` | Ranking nudge for table chunks on list-style questions |
| `rag.query-expansion` | `false` | LLM query rewrite (costs a call) |
| `rag.query-rewrite.enabled` | `true` | Local history-aware rewrite (free) |
| `rag.rerank-provider` | `jev` | `jev` or `none` |
| `rag.grounding-provider` | `jev` | `none \| llm \| jev \| jev,llm` |
| `conversation.memory.size` | `4` | Prior messages carried into the prompt |

### Chunking and ingestion

| Property | Default | Meaning |
|---|---|---|
| `docs.directory` | `E:/DBX/docs/markup` | Source directory; point this at your own corpus |
| `docs.supported-extensions` | `.pdf,.doc,.docx,.txt,.xlsx,.pptx,.md,.markdown` | Ingested types |
| `docs.chunk-size` / `chunk-overlap` | `1000` / `200` | Prose chunking |
| `docs.table-max-chars` | `1800` | Table rows stay atomic up to this budget (clamped in code — BGE's 512-token ceiling) |
| `app.ingestion.run-on-startup` | `true` | Set `false` when no database is available |
| `app.ingestion.excel-enabled` | `true` | Deterministic Excel ingestion |
| `web.urls` | *(sample list)* | Comma-separated URLs ingested into the same vector table — **use the full `https://` form**; `https:example.com` parses as an opaque URL with an empty host and is rejected at startup |
| `web.min-content-chars` | `300` | A page yielding less cleaned text is reported as FAILED — the signature of a JS-rendered page |

### Vector store (`database.properties`)

`vector.store.table-name` must match the table `schema.sql` creates. It is read in two places (`VectorStoreConfig` and `RetrievalDiagnosticsService`) whose `@Value` defaults must stay in sync, or `KnowledgeBaseRenameTest` fails.

---

## Build & Run

```bash
# Self-contained WAR (default)
mvn clean package -DskipTests

# External Tomcat 8.5 — natives live in the shared loader
mvn clean package -Ptomcat-deploy
```

Artifacts land in `target/fshipbot.war` (or `target/fshipbot-tomcat.war`).

> **`pom.xml` `<name>` drives the UI branding.** `application.title` is Maven-filtered from `@project.name@` and exposed to every template as `${appName}`. To rebrand the admin panel, change `<name>` — never hard-code a product name in a template, or `AdminBrandingTest` fails.

**Self-contained WAR** — drop `fshipbot.war` into Tomcat and start it. `onnxruntime` and `tokenizers` are unpacked into `WEB-INF/classes` at build time with only the `win-x64` and `linux-x64` natives and no debug symbols, which cuts roughly 100 MB of dead weight.

**External Tomcat** — build with `-Ptomcat-deploy`, copy the WAR as `fshipbot.war`, and copy `target/tomcat-lib/*.jar` into `tomcat/lib`. DJL/ONNX natives load once per JVM; in the common classloader they survive manager reloads instead of being re-pinned on every redeploy. Only a full service restart re-loads them.

Access is then:

| URL | |
|---|---|
| `http://<host>:8080/fshipbot/` | Chat UI |
| `http://<host>:8080/fshipbot/admin/login` | Admin login |

> **Do not run the app from an automated agent session.** It connects to a shared database and ingestion can overwrite real data. Verify with `mvn test` only.

---

## API Reference

Base path: `/fshipbot`

### Public

| Method | Path | Body / Notes |
|---|---|---|
| `POST` | `/api/ask` | `{question, sessionId?, includeSources?}` → `ChatResponse` |
| `GET` | `/api/health` | Returns `OK` |
| `POST` | `/api/validate-key` | `{key}` where key is `yyyyMMdd` → `{valid}` |
| `POST` | `/api/logout` | Invalidates the session |
| `GET` | `/` · `/chat` | Chat UI — no admin affordances |
| `GET` | `/app-config.xml` | DB-driven XML config (the `prompt` category is excluded) |
| `GET` | `/config-mode` | Returns `EDITABLE` |

```bash
curl -X POST http://localhost:8080/fshipbot/api/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"What is the annual leave entitlement?","includeSources":true}'
```

```json
{
  "question": "What is the annual leave entitlement?",
  "answer": "Full-time employees are entitled to 20 days of annual leave…",
  "processingTimeMs": 2140,
  "model": "Nim / nvidia/nemotron-3-super-120b-a12b + BGE Small EN v1.5",
  "hasSources": true,
  "sessionId": "default",
  "conversationSize": 2,
  "confidence": 0.78,
  "sources": [
    {
      "documentName": "hr-handbook.md",
      "documentType": "markdown",
      "chunkIndex": 42,
      "totalChunks": 180,
      "similarity": 0.89
    }
  ]
}
```

`sources` is omitted entirely unless `includeSources` is true **and** the `app.references.enabled` flag is on in the database. That flag is DB-only by design — there is no `application.properties` fallback — so toggling it takes effect without a restart and the two places can never disagree.

### Admin (session required)

| Method | Path | Purpose |
|---|---|---|
| `GET`/`POST` | `/admin/login` | Key = today's date as `yyyyMMdd` |
| `GET` | `/admin` · `/admin/prompts` · `/admin/settings` · `/admin/documents` | Pages |
| `POST` | `/admin/logout` | Ends the session |
| `GET`/`POST` | `/admin/api/prompts` | List (grouped) / save `{key, content}` |
| `POST` | `/admin/api/prompts/reload` | Re-read from the database |
| `POST` | `/admin/api/prompts/reset` · `/reset-all` | Revert one / all prompts to defaults |
| `GET`/`POST` | `/admin/api/settings` | Chat copy and display settings |
| `GET`/`POST`/`PUT`/`DELETE` | `/admin/api/suggestions/*` | Suggested questions CRUD + reorder |
| `GET`/`POST` | `/admin/api/provider` · `/provider/model` | Switch LLM provider / model |
| `GET` | `/admin/api/config.xml` | XML export |
| `POST` | `/admin/documents/convert` | Convert a file via MarkItDown |
| `POST` | `/admin/documents/upload-md` | Upload a `.md` file |
| `POST` | `/admin/documents/ingest` | Trigger ingestion (`force=true` for a full re-embed) |
| `GET` | `/admin/documents/ingest/status` | Ingestion progress |
| `GET`/`POST` | `/admin/documents/api/display` | References toggle |
| `GET` | `/admin/documents/api/diagnostics/retrieval` · `/recall` | Retrieval diagnostics |

`PageAccessInterceptor` gates everything under `/admin/**` except `/admin/login`. XHR requests receive **401 rather than a redirect**, so the frontend never tries to parse the login page as JSON.

---

## Admin Panel

Four pages behind the date-key gate, sharing a Thymeleaf shell (`templates/fragments/admin.html`) and `static/css/admin.css`.

- **Prompts** — a grid of every prompt grouped by area, Edit only.
- **Settings** — chat copy, welcome message, footer, suggested questions, the references toggle.
- **Documents** — convert, upload, ingest, retrieval diagnostics.
- **Login** — the key is today's date as `yyyyMMdd`, stored in the session as `pageAccessGranted`.

The chat UI at `/` has **no** admin entry point at all; the panel is reached only by typing `/admin/login`.

Two Thymeleaf constraints are load-bearing:

- Never use `/*[[@{/}]]*/` inline JS without `th:inline="javascript"` on the exact element. Without it the expression is left as a JS comment and the fallback literal silently wins. The context path is published as `<meta name="admin-ctx">` instead.
- Never resolve a collection with `th:with` + `th:if`. Thymeleaf evaluates `th:if` before `th:with`, so the map lookup is always null and the page renders empty. Build view models in the controller via `PromptService.listSections()`.

---

## The Prompt System

Every LLM prompt is a row in `fship_ai.app_prompt` under the `prompt.` key prefix. `util/PromptDefaults` is the **registry** — default text, group, label, description, and placeholders for all 14. `PromptService` caches database values in a `ConcurrentHashMap` and falls back to the registry when a row is missing, so an unreachable database degrades to defaults instead of breaking chat.

| Group | Keys |
|---|---|
| `chat` | `system` · `no_context` · `conversation_grounding` · `casual` |
| `guard` | `foul_language` |
| `grounding` | `grounding_validation` |
| `retrieval` | `query_expansion` · `query_expansion_system` |
| `jev` | `jev_rerank_instructions` · `jev_rerank_criteria_true` · `jev_rerank_criteria_false` · `jev_grounding_instructions` · `jev_grounding_criteria_true` · `jev_grounding_criteria_false` |

Placeholders use `{token}` and are substituted by `PromptService.render`.

**The application never inserts prompt rows.** `PromptService.save()` refuses an unseeded key rather than silently losing the edit, and `isDbBacked()` surfaces the reason on the Prompts page.

### Seed path vs. upgrade path

`db/data.sql` is guarded by `WHERE NOT EXISTS … prompt_key` so that admin edits survive a re-run. The consequence is that a **corrected default never reaches an already-seeded row**. There are two ways to ship a fixed default:

1. A `superseded(...)` entry in `PromptDefaults`, which is auto-repaired on load and matches only the exact known-bad text — so an admin's own wording is left untouched.
2. An explicit `UPDATE` migration.

`PromptSeedSyncTest` pins `data.sql` to the registry; any drift fails the build.

`AppPromptRepository.findByKey` swallows **only** `EmptyResultDataAccessException`. Other failures propagate — collapsing them into `null` makes an unreachable database look like an unseeded one.

---

## Ingestion

`IngestionService` implements `CommandLineRunner` and runs on startup when `app.ingestion.run-on-startup=true`.

| Source | Handling |
|---|---|
| **Local documents** | `DocumentProcessor` walks `docs.directory`, detects changes with a SHA-256 content hash, splits, batch-embeds, and inserts. Unchanged files are skipped. |
| **Excel** | `ExcelContentProcessor` converts each sheet to deterministic markdown tables — no LLM quota spent. |
| **Web pages** | `WebContentProcessor` fetches and parses each `web.urls` entry, strips boilerplate, and tracks changes by content hash. |

`DocumentSplitter` is table-aware: a table's header row travels with each row group up to `docs.table-max-chars`, so "list these" and "compare these" questions see whole tables. Prose uses `chunk-size` with overlap.

Force a full re-embed after changing the chunker, the stripper, or the embedding model — the change-detection hash covers file *content* only, so those changes are invisible to it. The Document Tools page exposes a **Force full re-embed** checkbox for this.

---

## Testing

```bash
mvn test                                          # full suite
mvn test -Dtest=PromptServiceTest                 # single class
mvn clean package -DskipTests                     # build only
```

**148 tests across 23 classes, all fully offline** — H2 with `@MockBean` for `EmbeddingStore` and `EmbeddingModel`. No test touches a live database.

Conventions worth knowing before you add tests:

- Never mock `DataSource`; it causes a `getMetaData()` NPE.
- Any `@SpringBootTest` that instantiates the vector store needs both `@MockBean`s, or the pgvector store fails on H2 (`SET ivfflat.probes`).
- Repositories are additionally exercised against a real H2 table (`PromptPersistenceIT`) — mocks cannot catch a table that was never created, a wrong column name, or a write that reads back blank.
- Several tests assert on **text**, guarding failure modes that compile and pass normally: `KnowledgeBaseRenameTest` (partial rename), `PromptSeedSyncTest` (seed drift), `GroundingJudgeTest` (length-biased criteria), `ReasoningLeakTest`, `CutOffGuardTest`, `AdminBrandingTest`, `AdminAccessControlTest`.
- Template tests assert on `data-group` / `data-key` attributes, not rendered titles — titles are HTML-escaped (`&` → `&amp;`).

---

## Operational Notes

Things that will bite you, gathered from past incidents:

- **Do not start the application from an agent or CI session.** It connects to the shared production database and ingestion can overwrite real data.
- **Do not set `spring.mvc.view.prefix/suffix`** — it shadows the Thymeleaf configuration.
- **Do not write raw SQL against `knowledge_base`** without a matching `vector.store.table-name`.
- **Do not hard-code a prompt default and assume it reaches the database** — see [Seed path vs. upgrade path](#seed-path-vs-upgrade-path).
- **Do not upgrade past Boot 2.7 / Java 11** without also flipping `javax.*` → `jakarta.*`.
- `VectorStoreConfig` strips `?currentSchema=` before calling `new URI()`, or the `database` component parses incorrectly. The table is fully qualified as `fship_ai.knowledge_base` with `createTable=false`.
- Repositories use raw `jdbcTemplate` and rely on `search_path=fship_ai`, which is why `schema.sql` must create the schema first.
- `DocumentRepository.hasContentTsvColumn()` filters `information_schema` on `table_name`. If that name drifts, the lexical path silently downgrades to `ILIKE` with no error.
- The build filters `application.properties` with `@` delimiters **only**, so templates and static assets stay unfiltered — their `${...}` Thymeleaf expressions would not survive `${}`-delimiter filtering.
- `AGENTS.md` in the repository root is the canonical record of these invariants. Read it before making structural changes.

---

## License

No license file is present in this repository. Add one before publishing — for example `LICENSE` with the MIT text, and reference it from `pom.xml` and this section.

Third-party components retain their own licenses: Spring Boot (Apache 2.0), LangChain4j (Apache 2.0), Apache Tika (Apache 2.0), pgvector (PostgreSQL License), BAAI BGE models (MIT), and the NVIDIA, Google, OpenAI, and Typesafe AI services are subject to their respective commercial terms.
