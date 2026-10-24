# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Quarkus 3.x (Java 25, Maven wrapper) service implementing the "Job Execution API" — an API-first REST service whose server interfaces are generated from an OpenAPI spec and verified against a Pact consumer contract.

## Working rules (learning repo)

This is an experimental repo the owner uses to learn Quarkus. The owner is an experienced Java / Spring Boot 4 developer whose team is migrating to Quarkus, so skip the basics and explain Quarkus in terms of the Spring equivalent and where it behaves differently. **Do not edit anything under `src/` (Java sources, tests, `application.yaml`, the OpenAPI spec, pact files) or `pom.xml`.** The owner makes those changes themselves. `pom.xml` is owner-managed for now; the owner isn't fond of Maven and may revisit this, so ask before assuming otherwise. Other repo files such as this CLAUDE.md are fine to edit when asked. Claude's role is to explain and advise, and to keep notes in `notes/`:

- `notes/TODO.md` — **open items only**, grouped by area, current focus first. Add items you discover and link to the relevant QnA entry. When the owner has fixed something (check the code first), move it to `notes/DONE.md` rather than ticking it in place. Update the "Last full rescan" line when you rescan.
- `notes/DONE.md` — finished items, newest date first, with commit hashes where known.
- `broker/` — **exception: Claude owns this folder and may edit it freely.** It holds the Docker Compose setup for a local IBM MQ developer queue manager (`QM1` on `localhost:1414`); see `broker/README.md` for connection details. Project queues are defined in `broker/mqsc/30-quarqueueus.mqsc`, which is re-applied on every start and resets unlisted attributes. Change queues there, never in the console. It's infrastructure, not part of the app.
- `notes/QnA.md` — answers to the owner's questions, appended as `##` sections (newest at the bottom). Explain the why and give the concrete fix steps/snippets, but don't apply them. Keep the status index at the top in sync (🔴 open / ✅ applied). `TODO.md`, `DONE.md` and the index link to entries by heading anchor, so renaming a QnA heading breaks links.

## Commands

Use `./mvnw` (or `mvnw.cmd` on Windows).

- Dev mode with live reload: `./mvnw quarkus:dev` (Dev UI at http://localhost:8080/q/dev/, runtime spec at `/q/openapi`, Swagger UI at `/q/swagger-ui`)
- Unit tests (surefire, `*Test`): `./mvnw test`
- Single test: `./mvnw test -Dtest=GreetingResourceTest#testHelloEndpoint`
- Unit + integration tests (failsafe, `*IT`): `./mvnw verify`
- Single IT: `./mvnw verify -Dit.test=JobApiPactIT -Dtest=skip -Dsurefire.failIfNoSpecifiedTests=false`
- Package: `./mvnw package` → `target/quarkus-app/quarkus-run.jar`; native: `./mvnw package -Dnative`

No linter/formatter is configured.

## Architecture

**API-first via `quarkus-openapi-generator-server`.** The contract lives in `src/main/resources/openapi/jexapi.yaml`. At build time the generator (configured in `application.yaml`, `base-package: xyz.kajih.jexapi`) emits JAX-RS interfaces and models into `target/generated-sources/quarkus-openapi-generator-server/`:
- `xyz.kajih.jexapi.resources.JobsResource` — the resource interface (paths/annotations come from the spec)
- `xyz.kajih.jexapi.model.*` — DTOs (`Job`, `JobStatus`, `CreateJobRequest`, `JobResult`, `Error`) with fluent setters

To change the API, edit the YAML and rebuild — never edit generated sources. `xyz.kajih.quarqueueus.jobs.rest.JobsResourceImpl` is the hand-written implementation of `JobsResource`. Generated DTOs should stay in the REST adapter.

**Package layout** (by feature, under `xyz.kajih.quarqueueus`; the owner enforces the structure, no tooling does):
- `jobs/` — job lifecycle and REST API: `rest/JobsResourceImpl`, `messaging/JobSender` (sends to the request queue)
- `execution/` — runs jobs: `JobWorker`, plus `messaging/JobRequestRoute` (Camel consumer of `DEV.JOBS.REQUEST`)
- `infra/mq/` — IBM MQ `ConnectionFactory` setup, no domain code
- `dev/` — build-profile-gated helpers: `SeedJobSender` (`@IfBuildProfile("dev")`, sends one job on Camel start), `GreetingResource` (`/hello`, `@IfBuildProfile(anyOf = {"dev", "test"})`, leftover scaffold covered by `GreetingResourceTest`). `@IfBuildProfile` removes the bean at build time, so neither is in the prod jar, and a `@QuarkusIntegrationTest` (prod profile) can't see them.

The planned direction (a message record owned by `execution`, a `jobs` domain core) is in `notes/TODO.md` §1 and the structure reviews in `notes/QnA.md`. After moving packages, run `./mvnw clean`: stale classes in `target/` break test discovery.

**Pact provider verification.** `JobApiPactIT` verifies the service as provider `JexApi` against pact files loaded from the test classpath via `@PactFolder("pacts")` (i.e. `src/test/resources/pacts/`, consumer `quarqueueus_client`). Pacts are matched on the provider name, so a mismatch between `@Provider` and the pact's `provider.name` means no pacts are found. Although it uses `@QuarkusTest`, its `*IT` suffix means it only runs under failsafe (`./mvnw verify`), not `./mvnw test`.

**Queue messaging (in progress).** Camel JMS → IBM MQ (the `broker/` queue manager), to be tested with Citrus. Verified end to end on 2026-10-24: consume, rollback on exception (`camel.component.jms.transacted: true`, **no** `.transacted()` in routes, since there's no `TransactedPolicy` on this classpath), and after 3 failures MQ backout to `DEV.JOBS.BACKOUT`. Backout needs `PASSALL` for `app` (set in the broker's `.mqsc`). `.bean(...)` in routes needs `camel-quarkus-bean` (in the pom); without it startup fails. Camel resolves routes at startup, so only a booting test catches such errors. Every `@QuarkusTest` starts the route; with the broker down it just logs reconnect warnings. The IBM MQ client is a plain library, not a Quarkus extension, so `xyz.kajih.quarqueueus.infra.mq` builds the `MQConnectionFactory` from `MqConfig` (`mq.*` in `application.yaml`) and wraps it with `PooledJmsWrapper`; pooled-jms does not auto-wrap user-produced factories. MQ credentials are set on the Camel JMS component (`camel.component.jms.username/password`), not on the factory, using Camel placeholders (`{{key:default}}`, not Quarkus's `${key:default}`), which Camel Quarkus resolves through Quarkus config. `{{mq.app.password}}` has no default and gets its value from `MQ_APP_PASSWORD` in a gitignored `.env` in the project root (template: `.env.example`), so startup fails without it; the username falls back to `app`. There's no MQ Dev Service: start `broker/` first (on a fresh clone, recreate `broker/secrets/` as described in `broker/README.md`).

**Dependency gotcha.** The `quarkus-camel-bom` import excludes `swagger-models` from `swagger-parser`, which breaks OpenAPI codegen. The explicit `swagger-parser` entry in `<dependencyManagement>` overrides it, so keep it when bumping versions. Use `./mvnw quarkus:dependency-tree`, not `dependency:tree`, to see build-time (deployment) dependencies.

**Current state.** `./mvnw verify` fails only on `JobApiPactIT` (body mismatch on `GET /jobs`). That and the other known gaps between the spec, the pact and `JobsResourceImpl` (stub methods, status codes) are tracked in `notes/TODO.md`. Check there, and verify against the code, before assuming something works.
