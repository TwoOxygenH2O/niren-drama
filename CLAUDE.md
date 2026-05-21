# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Niren Drama (泥人剧场) is a full-stack AI-powered short drama production platform. It automates the pipeline from a one-line creative idea → screenplay generation → storyboard decomposition → AI image/video generation → voice acting (TTS) → video composition → final export. The platform targets vertical (9:16) short dramas for platforms like 红果短剧 and 抖音短剧.

- **Backend**: Spring Boot 3.2 + Java 17 + Maven + MyBatis-Plus + MySQL 8.0 + Redis 7.0
- **Frontend**: Vue 3 + TypeScript + Vite + Element Plus + Pinia + Axios
- **All API responses** use the `Result<T>` wrapper: `{ code: 200, message: "success", data: ... }`

## Commands

```bash
# Backend (run from backend/)
mvn spring-boot:run                          # Start backend on :8080
mvn clean package -DskipTests                # Build JAR

# Frontend (run from frontend/)
npm install                                   # Install dependencies
npm run dev                                   # Dev server on :5173, proxies /api to :8080
npm run build                                 # Production build (vue-tsc type-check + vite build)

# Docker (run from root)
docker-compose up -d                          # Full stack: MySQL, Redis, backend, frontend (nginx on :80)
```

The backend context-path is `/api`. API docs (Knife4j/Swagger) at `http://localhost:8080/api/doc.html`.

## Architecture

### Backend Layered Structure

```
controller/  →  service/  →  mapper/  →  MySQL (MyBatis-Plus)
                     ↓
                  ai/  (AiProviderFactory → Text/Image/Tts/Video provider impls)
```

- **Controllers** (11 modules): `Auth`, `Project`, `Script`, `Storyboard`, `Character`, `Scene`, `Asset`, `AiConfig`, `Task`, `Video`, `CostEstimation`
- **Services** contain the business logic. Long-running AI operations are annotated with `@Async("aiTaskExecutor")` and record progress in `drama_task_record` via `TaskRecordMapper`.
- **Mappers** extend MyBatis-Plus `BaseMapper<T>`. No XML mapper files for standard CRUD; entities use `@TableName` to map to `drama_*` tables.

### AI Provider Layer (key pattern)

`AiProviderFactory` is the central factory. It resolves AI configuration with this precedence:
1. User's per-type default config from `drama_ai_config` table (keyed by `user_id + config_type + is_default=1`)
2. Application-wide defaults from `application.yml` (`niren.ai.*`)
3. Hardcoded defaults per provider (base URLs, models)

Four provider interfaces: `TextAiProvider`, `ImageAiProvider`, `TtsProvider`, `VideoAiProvider`. All text generation goes through `OpenAiTextProvider` (OpenAI-compatible API). Image providers have separate implementations for Aliyun/DashScope, external, and OpenAI-compatible. TTS providers have Aliyun (CosyVoice), OpenAI-compatible, and a Mock fallback.

### Schema Migration

`SchemaMigrationRunner` runs at application startup and idempotently adds missing columns/indexes using `information_schema` checks. No Flyway/Liquibase — just inline ALTER TABLE DDL. When adding a new entity field, add a migration block here.

### Security

Spring Security with JWT stateless authentication. `JwtAuthenticationFilter` extracts the Bearer token and sets `SecurityContext`. CSRF is disabled. Public endpoints (no auth required): `/auth/**`, `/doc.html`, `/swagger-ui/**`, `/v3/api-docs/**`, `/webjars/**`, `/files/**`. Use `CurrentUserHelper.getUserId(UserDetails)` to resolve the current user's DB ID.

### Async Task System

- `TaskRecord` (table `drama_task_record`) tracks async task state: `taskType`, `status` (PENDING/RUNNING/SUCCESS/FAILED), `progress` (0-100), `result` (JSON).
- `TaskService` provides query APIs with diagnostics: elapsed time, step durations, failure type distribution, external API error ratio.
- Thread pool: `aiTaskExecutor` (4-8 threads, 100 queue capacity, CallerRunsPolicy on rejection).
- Poll scheduler: `aiPollScheduler` (2 threads) for polling external async video/image task status.

### Frontend Architecture

- **Router** (`router/index.ts`): All authenticated routes are children of `MainLayout`. Route guard checks JWT expiry on every navigation and redirects to `/login`.
- **API layer** (`api/request.ts`): Shared Axios instance with base URL `/api`, JWT injection via request interceptor, 401/403 auto-redirect to login via response interceptor.
- **Store** (`stores/user.ts`): Pinia store persisting `token` and `userInfo` to localStorage.
- **Views** are lazy-loaded via `() => import(...)`. Each major module (project, script, storyboard, character, scene, asset, settings, synthesis) has its own view component.

### Project Type System (`ProjectStyleSupport`)

Centralized rule engine that adapts all AI prompts based on two axes:
- **Project type**: 真人短剧 (live-action) vs 漫画短剧 (comic)
- **Genre**: 都市言情, 玄幻奇幻, 悬疑惊悚, 古装历史, etc.

This class generates type-specific and genre-specific rules for text creation, visual creation, audio performance, negative prompt terms, and drama dialogue constraints. Used by `ScriptService` and `StoryboardService` when constructing prompts.

### Video Composition

FFmpeg-based video composition is configured extensively in `application.yml` under `niren.compose.*` and `niren.ffmpeg.*`. Key sub-modules: transitions, audio (TTS alignment, BGM with ducking, loudnorm), subtitles (burnt-in ASS), rhythm (auto micro-motion for static shots), segment compositing, and export profiles (preview vs publish). The `VideoCompositionService` orchestrates this.

## Key Files

| File | Role |
|------|------|
| `backend/src/main/resources/application.yml` | All configuration (DB, AI, FFmpeg, compose, COS) with env var overrides |
| `backend/src/main/java/com/niren/drama/ai/AiProviderFactory.java` | AI provider resolution and factory |
| `backend/src/main/java/com/niren/drama/config/SchemaMigrationRunner.java` | Idempotent DB schema migration on startup |
| `backend/src/main/java/com/niren/drama/security/SecurityConfig.java` | Spring Security configuration |
| `backend/src/main/java/com/niren/drama/common/ProjectStyleSupport.java` | Genre/type prompt adaptation rules |
| `backend/src/main/java/com/niren/drama/exception/GlobalExceptionHandler.java` | Unified exception handling with Chinese error messages |
| `frontend/src/router/index.ts` | Route definitions + auth guard |
| `frontend/src/api/request.ts` | Axios instance with JWT and error interceptors |
| `frontend/src/stores/user.ts` | Auth state (token, userInfo) |
| `.env.example` | Environment variable reference for Docker Compose |

## Caveats

- Redis is configured but commented out in `application.yml`. The project currently does not use Redis at runtime.
- The Docker image includes FFmpeg in the backend container for video composition.
- `mvn clean package` uses `-DskipTests` in the Dockerfile — there are currently no active tests.
- `TencentCosConfig` contains hardcoded credentials in the default YAML values (COS secret-id/secret-key). These should be overridden via environment variables in production.


## CCTO Token Optimization

CCTO is active in this project. 200 files indexed, 0 symbol outlines extracted.

### MCP Tools Available

Use these tools instead of reading files directly to save tokens:

- **`semantic_search`** — Find relevant code by description (e.g. `semantic_search("authentication middleware")`)
- **`smart_read`** — Read a file outline first, then fetch specific sections
- **`project_outline`** — Get a condensed project tree with language tags
- **`memory_recall`** — Search past session summaries

### Workflow

1. Start with `project_outline` for a new task
2. Use `semantic_search` to find relevant code before reading files
3. Use `smart_read filepath` to see a file's outline before fetching specific sections
4. Re-index after large changes: `ccto index --incremental`
