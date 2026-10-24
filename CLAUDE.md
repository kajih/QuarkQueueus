# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Quarkus 3.x (Java 25, Maven wrapper) service implementing the "Job Execution API" — an API-first REST service whose server interfaces are generated from an OpenAPI spec and verified against a Pact consumer contract.

## Working rules (learning repo)

This is an experimental repo the owner uses to learn Quarkus. The owner is an experienced Java / Spring Boot 4 developer whose team is migrating to Quarkus, so skip the basics and explain Quarkus in terms of the Spring equivalent and where it behaves differently. **Do not edit anything under `src/` (Java sources, tests, `application.yaml`, the OpenAPI spec, pact files) or `pom.xml`.** The owner makes those changes themselves. `pom.xml` is owner-managed for now; the owner isn't fond of Maven and may revisit this, so ask before assuming otherwise. Other repo files such as this CLAUDE.md are fine to edit when asked. Claude's role is to explain and advise, and to keep notes in `notes/`:

- `notes/TODO.md` — checklist of what needs doing. Add items you discover, link to the relevant QA entry, and tick items off when the owner has fixed them (check the code first).
- `broker/` — **exception: Claude owns this folder and may edit it freely.** It holds the Docker Compose setup for a local IBM MQ developer queue manager (`QM1` on `localhost:1414`); see `broker/README.md` for connection details. It's infrastructure, not part of the app.
- `notes/QA.md` — answers to the owner's questions, appended as `##` sections (newest at the bottom). Explain the why and give the concrete fix steps/snippets, but don't apply them. `TODO.md` links to these by heading anchor, so renaming a QA heading breaks links.

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

To change the API, edit the YAML and rebuild — never edit generated sources. `JexSrv` (`src/main/java/xyz/kajih/JexSrv.java`) is the hand-written implementation of `JobsResource`.

`GreetingResource` (`/hello`) is leftover Quarkus scaffold, covered by `GreetingResourceTest` and re-run against the packaged app by `GreetingResourceIT` (`@QuarkusIntegrationTest`).

**Pact provider verification.** `JobApiPactIT` verifies the service as provider `JexApi` against pact files loaded from the test classpath via `@PactFolder("pacts")` (i.e. `src/test/resources/pacts/`, consumer `quarqueueus_client`). Pacts are matched on the provider name, so a mismatch between `@Provider` and the pact's `provider.name` means no pacts are found. Although it uses `@QuarkusTest`, its `*IT` suffix means it only runs under failsafe (`./mvnw verify`), not `./mvnw test`.

**Planned: queue messaging.** The next step is Camel JMS → IBM MQ (the `broker/` queue manager), tested with Citrus. The IBM MQ client is a plain library rather than a Quarkus extension, so its `ConnectionFactory` will need a CDI producer. Setup steps are in `notes/TODO.md`.

**Current state.** Known gaps between the spec, the pact and `JexSrv` (stub methods, response-shape mismatch on `GET /jobs`, status codes) are tracked in `notes/TODO.md`. Check there, and verify against the code, before assuming something works.
