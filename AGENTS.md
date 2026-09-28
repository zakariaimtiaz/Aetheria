# AGENTS.md — FShipBot

## Project Overview
FShipBot is a **RAG-powered AI assistant** for Friendship NGO staff. It ingests organizational documents (markdown, Excel, web pages), stores vector embeddings in PGVector, and answers staff questions via a chat interface backed by Gemini, OpenAI, or NVIDIA NIM. A session-gated **admin panel** edits prompts, chat copy, and the knowledge base at runtime — no redeploy.

## Stack & Entry Points
- Single Maven module `com.dis:fshipbot` — `pom.xml` is source of truth (**Spring Boot 2.7.18**, packaging `war`)
- **Java 11 target** (build with JDK 17) | **LangChain4j 0.35.0** | **BGE Small EN v1.5** (384-dim ONNX) | **PGVector** | **NVIDIA NIM** (default) / Gemini 2.5 Flash Lite / OpenAI GPT-4o-mini
- Entrypoint `src/main/java/com/dis/fshipbot/Application.java:1` (`@SpringBootApplication`)
- Server: port `8080`, context path `/fshipbot`
- `application.title` / `application.version` are Maven-filtered from `@project.name@` / `@project.version@` — change `<name>` in `pom.xml` to rebrand the admin panel

### Key Packages
| Package | Responsibility |
|---------|---------------|
| `config` | `ChatModelConfig` (provider switch), `VectorStoreConfig` (PGVector + BGE), `WebMvcConfig` (admin interceptor registration), `PageAccessInterceptor` (session gate), `GlobalViewAdvice` (appName/appVersion model attributes) |
| `controller` | `ChatController` (`/api/ask`, `/api/health`, `/api/validate-key`, `/api/logout`), `HomeController` (`/`, `/chat`, `/admin/login`, `/app-config.xml`), `BotSettingsController` (`/admin/prompts`, `/admin/settings`, prompt + settings + suggestions CRUD), `DocumentToolsController` (`/admin/documents`) |
| `service` | `ChatService` (RAG pipeline), `PromptService` (DB-backed prompt cache, render, repair), `JevRerankService` (TypeSafe Noul rerank + grounding), `IngestionService` (`CommandLineRunner`), `AppConfigService` (settings/suggestions + XML builder), `ProviderService` |
| `ingestion` | `DocumentProcessor`, `DocumentSplitter` (table-aware), `ExcelContentProcessor` (deterministic sheet→markdown, no LLM quota), `WebContentProcessor` |
| `repository` | `DocumentRepository` (raw JDBC for `knowledge_base`), `AppSettingRepository`, `AppPromptRepository` (read/update only — never inserts), `AppSuggestionRepository` |
| `model` | `ChatRequest`/`ChatResponse` (with `SourceInfo`), `AppSetting`, `AppPrompt`/`PromptSection`, `AppSuggestion` |
| `util` | `PromptDefaults` (prompt registry: keys, groups, default + superseded text), `FileHashUtil`, `ContentHashUtil` |

### Database Tables (schema `fship_ai`)
- `knowledge_base` — pgvector embeddings (384 dims, COSINE, IVFFLAT), file metadata, chunk info, versioning. Renamed from `friendship_knowledge_base`; `schema.sql` carries a guarded `ALTER TABLE ... RENAME TO` so existing data survives.
- `app_setting` — key/value config store (category-grouped)
- `app_prompt` — admin-editable LLM prompts, seeded by `db/data.sql`. The app **never** inserts these rows.
- `app_suggestion` — suggested questions for the chat UI

## Required Toolchain
- **Build with JDK 17** (`C:\Program Files\Java\jdk-17`). `java.version=11` is the *target*; the server runs JDK 11+.
- **Maven 3.9.15** at `C:\apache-maven-3.9.15`. Wrapper jar git-ignored — prefer local Maven.
- **PostgreSQL pgvector** remote `jdbc:postgresql://devs2.apps.friendship.ngo:5432/fship_ai_1_1?currentSchema=fship_ai`. Credentials via env `FSHIP_AI_DB_USR` / `FSHIP_AI_DB_PASS`. No Docker in repo.
- **API keys** via env: `NVIDIA_API_KEY` (default `chat.provider=nim`), `GEMINI_API_KEY`, `OPENAI_API_KEY`, `TYPESAFE_API_KEY` (Jev re-rank/grounding). App starts with dummy keys but `/api/ask` fails without the active provider's.
- **MarkItDown** CLI (optional) — only for `/admin/documents/convert`.

## Commands (PowerShell, JDK 17)
```powershell
$env:JAVA_HOME="C:\Program Files\Java\jdk-17"; $env:Path="C:\Program Files\Java\jdk-17\bin;$env:Path"
mvn -version              # must show 17.x
mvn clean compile -q      # BUILD SUCCESS = deps ok
mvn test -q               # full suite (~128 tests, H2 + MockBean, no live DB)
mvn test -Dtest=PromptServiceTest -q   # single test class
mvn clean package -DskipTests -q       # STOP HERE — verification ends at `mvn test`
```
- **NEVER start the application from an agent session** — no `java -jar`, no `mvn spring-boot:run`, no `Start-Process`, no curl smoke tests. It connects to the **shared production database** and can ingest or overwrite real data. Verification stops at `mvn test`. The user runs and deploys the app themselves.
- Do not use `mvn spring-boot:run` with `JAVA_HOME=11`.

## API Endpoints
| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/api/ask` | none | RAG chat — `{question, sessionId?, includeSources?}` |
| `GET` | `/api/health` | none | Returns `OK` |
| `POST` | `/api/validate-key` | none | `{key}` where key = `yyyyMMdd` → `{valid}` |
| `GET` | `/` | none | Chat UI — **no admin affordances** |
| `GET`/`POST` | `/admin/login` | none | Admin login; key = today's date `yyyyMMdd` |
| `GET` | `/admin` | session | Redirects to `/admin/prompts` |
| `GET` | `/admin/prompts` | session | Prompts — grid of all prompts, **Edit only** |
| `GET` | `/admin/settings` | session | Chat copy + suggested questions |
| `GET` | `/admin/documents` | session | Document Tools |
| `POST` | `/admin/logout` | session | Invalidates session → `/admin/login` |
| `GET`/`POST` | `/admin/api/prompts` | session | List (grouped) / save `{key, content}` |
| `POST` | `/admin/api/prompts/reload` | session | Re-read from DB ("Check again") |
| `GET`/`POST` | `/admin/api/settings` | session | Settings CRUD |
| `GET`/`POST`/`PUT`/`DELETE` | `/admin/api/suggestions/*` | session | Suggestions CRUD + reorder |
| `POST` | `/admin/documents/convert` | session | Convert via MarkItDown CLI |
| `POST` | `/admin/documents/ingest` | session | Trigger ingestion |
| `GET` | `/app-config.xml` | none | XML config (DB-driven, `prompt` category excluded) |

## Admin Panel
- Shell: `templates/fragments/admin.html` (`adminHead`, `adminMenu`, `adminScripts`). Pages: `admin-login.html`, `admin-prompts.html`, `admin-settings.html`, `admin-documents.html`. Styles: `static/css/admin.css`.
- **Access:** `PageAccessInterceptor` gates `/admin/**` except `/admin/login`. The key is today's date as `yyyyMMdd`, stored in session as `pageAccessGranted`. XHR gets 401 (not a redirect) so it doesn't parse login HTML as JSON.
- **Never** use `/*[[@{/}]]*/` inline JS — it needs `th:inline="javascript"` on the exact element, and when missing the expression is left as a JS comment so the fallback literal silently wins. The context path is published as `<meta name="admin-ctx">` and read via `document.querySelector('meta[name="admin-ctx"]').content`.
- **Never** resolve a collection with `th:with` + `th:if` — Thymeleaf evaluates `th:if` *before* `th:with`, so the map lookup is always null and the page renders empty. Build view models in the controller (`PromptService.listSections()`).
- **Branding is never hard-coded** — templates use `${appName}` from `GlobalViewAdvice`. `AdminBrandingTest` fails on any literal brand name.
- Assert on `data-group` / `data-key` in template tests, not on rendered titles — titles are HTML-escaped (`&` → `&amp;`).

## Prompt System
- Every LLM prompt is DB-driven and admin-editable at runtime. Keys are `prompt.*` in `fship_ai.app_prompt`.
- `util/PromptDefaults` is the **registry**: default text, group, label, description, placeholders. `PromptService` caches DB values in a `ConcurrentHashMap` and falls back to the registry when a row is missing — so an unreachable DB degrades to defaults instead of breaking chat.
- **Seed path vs upgrade path.** `db/data.sql` seeds rows for a *fresh* database and is guarded by `WHERE NOT EXISTS ... prompt_key` — by design, so admin edits survive. It therefore **never** delivers a corrected default to an already-seeded row. Changing a default requires either a `superseded(...)` entry in `PromptDefaults` (auto-repaired on load, matching only the exact known-bad text so admin wording is untouched) or an explicit `UPDATE` migration.
- `PromptSeedSyncTest` pins `data.sql` to the registry — a mismatch fails the build.
- The app never inserts prompt rows. `PromptService.save()` refuses an unseeded key rather than silently losing the edit, and `isDbBacked()` / the Prompts page banner surface "not editable" with the reason.
- `AppPromptRepository.findByKey` swallows **only** `EmptyResultDataAccessException`. Other failures propagate — collapsing them into null makes an unreachable DB look like an unseeded one.

## RAG Pipeline (ChatService)
1. **Foul-language guard** — abuse gets the canned `prompt.foul_language` reply, never reaches RAG/LLM/memory
2. **Casual detection** — greetings/small-talk bypass RAG, use `prompt.casual`
3. **Query expansion** (optional, off by default) — `prompt.query_expansion`; no static synonym map by design
4. **Embedding** — BGE Small EN v1.5 (ONNX, local, 384 dims)
5. **Retrieval** — PGVector cosine + hybrid lexical (RRF), `topK=20`, `similarity-threshold=0.50`
6. **Deduplication** — trigram similarity on overlapping chunks
7. **Source diversity** — phrase cap + per-file fair share
8. **Jev re-rank** (optional) — one Noul score per chunk in a single `systemone` call; silent fallback to heuristic order
9. **Context building** — up to `rag.max-context-length=14000`
10. **LLM generation** — `prompt.system` (or `prompt.no_context`); all prompt text via `PromptService`
11. **Grounding validation** (`rag.grounding-provider`: `none|llm|jev|jev,llm`) — fail open throughout
12. **Response** — answer + sources + confidence + per-stage timings in log

### Grounding judge — two traps
- **Jev criteria must be complements.** The `true` side grades the answer **as a whole** and must never demand that *every* fact be supported. A universal quantifier paired with an existential one makes the verdict a function of answer **length**, so broad questions (long answers) get rejected no matter how well grounded. Enforced by `GroundingJudgeTest`.
- **Judge the whole answer.** `MAX_ANSWER_CHARS` must comfortably exceed a typical answer; a silent truncation grades a fragment while the user sees the full text. Truncation is logged when it happens.

## Ingestion Pipeline
- **DocumentProcessor** — walks `docs.directory`, SHA-256 change detection, splits via `DocumentSplitter`, batch embeds + inserts
- **DocumentSplitter** — table-aware: keeps header with each row group, overlap for prose
- **ExcelContentProcessor** — deterministic sheet→markdown tables, no LLM quota
- **WebContentProcessor** — URL fetching + Tika parsing, content-hash change detection
- `.md` filenames stored directly in `file_name`

## IntelliJ IDEA Quirks
- **Open correctly:** File → Open → `E:\DBX\fshipbot\pom.xml` → Open as Project (not `E:\DBX`).
- **Maven tool missing:** View → Tool Windows → Maven → right-click `pom.xml` → Add as Maven Project → Reload All.
- **SDK fix:** File → Project Structure → SDK 17; Settings → Build Tools → Maven → JDK for importer = 17.
- **IDE files** (`.iml`, `.idea/*`) are recreated after `mvn clean`.

## Architecture Notes
- `VectorStoreConfig` strips `?currentSchema=` before `new URI()` — else `database` parses incorrectly. Table is fully qualified `fship_ai.knowledge_base`, `createTable=false`. Name comes from `vector.store.table-name`; the `@Value` defaults in `VectorStoreConfig` and `RetrievalDiagnosticsService` must match `database.properties` or `KnowledgeBaseRenameTest` fails.
- `DocumentRepository.hasContentTsvColumn()` filters `information_schema` on `table_name` — if that name drifts, the lexical path silently downgrades to ILIKE with no error.
- `application.properties` must **not** set `spring.mvc.view.prefix/suffix` — shadows Thymeleaf.
- Repositories use raw `jdbcTemplate`, relying on `search_path=fship_ai`. `schema.sql` must create the schema first.
- A generated column takes **no** `NULL`/`NOT NULL` clause. A trailing `NULL` on `content_tsv ... STORED NULL` is a syntax error that **aborts the whole script**, so later tables never get created.

## Config & Env Gotchas
- `docs.directory=${DOCS_DIR:E:/DBX/docs/markup}` — override via `DOCS_DIR`.
- `app.ingestion.run-on-startup=true` by default; set `false` when no DB is available.
- Logging to `E:/DBX/logs/fshipbot/fshipbot.log` (`FSHIP_AI_LOG_DIR`).
- `spring.config.import` splits config across `logging/database/models.properties`.

## Testing
- 128 tests, all offline: H2 + `@MockBean` for `EmbeddingStore`/`EmbeddingModel`. No live DB.
- Do not mock `DataSource` (causes `getMetaData() NPE`).
- Any `@SpringBootTest` that instantiates the vector store needs those two `@MockBean`s or the pgvector store fails on H2 (`SET ivfflat.probes`).
- Repositories are exercised against a **real** H2 table (`PromptPersistenceIT`) as well as mocks — mocks cannot catch a table that was never created, a wrong column name, or a write that reads back blank.
- Text-level tests guard the failure modes that compile and pass normally: `KnowledgeBaseRenameTest` (partial rename), `PromptSeedSyncTest` (seed drift), `GroundingJudgeTest` (length-biased criteria).

## What NOT to do
- **Don't run the app from an agent session** — it hits the shared production database and can ingest/overwrite real data. Verify with `mvn test` only.
- **Don't hard-code a prompt default and assume it reaches the database** — see "Prompt System" above.
- Don't upgrade `pom.xml` past Boot 2.7 / Java 11 without also flipping `javax.*` → `jakarta.*`.
- Don't open `E:\DBX` as project root.
- Don't re-add Docker files — removed per request; remote DB only.
- Don't set `spring.mvc.view.prefix/suffix`.
- Don't mock `DataSource` in tests.
- Don't write raw SQL against `knowledge_base` without a matching `vector.store.table-name`.
- Don't delete `AGENTS.md` — it is the only record of the invariants above.
