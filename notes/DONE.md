# Done

Finished TODO items, newest first, kept for reference. Open work is in [TODO.md](TODO.md).

## 2026-10-25

- `JobSender` is `@ApplicationScoped`, so it's a CDI bean now. → [QnA](QnA.md#code-and-structure-reviews-2026-10-24)

**Notes cleanup** (by Claude): `QnA.md` regrouped by topic; the three review rounds merged into [one entry](QnA.md#code-and-structure-reviews-2026-10-24) and the three `JobRequest` entries into [another](QnA.md#the-job-request-message-jobrequest-bean-binding-and-headers); outdated snippets replaced with references to the current code. Verified again: `./mvnw verify` (scratch copy) fails only on `JobApiPactIT`.

**Queue message record, consumer side**
- `JobWorker.handle(@Body JobRequest, @Headers Map<String, Object>)` with `@Handler`.
- `camel-quarkus-jackson` added; the route does `.unmarshal().json(JobRequest.class)` before `.bean(JobWorker.class, "handle")`, and `JobWorker.handle(JobRequest, @Headers Map<String, Object>)` takes the record. → [QnA](QnA.md#the-job-request-message-jobrequest-bean-binding-and-headers)

**Queue message record, producer side** (verified with `./mvnw verify` in a scratch copy: only `JobApiPactIT` fails, as before)
- `execution/JobRequest` is now a standalone `record JobRequest(UUID jobId, String type, Map<String, String> parameters)` with `QUEUE`, instead of `extends CreateJobRequest`. `execution` no longer imports generated code. → [QnA](QnA.md#the-job-request-message-jobrequest-bean-binding-and-headers)
- `JobSender.send(JobRequest)` replaces `createJob(CreateJobRequest)`: it no longer builds the REST `Job` (the `/* ... */` placeholder is gone) and doesn't import `xyz.kajih.jexapi.model`.
- `JobSender` and `JobRequestRoute` use `JobRequest.QUEUE`; only `SeedJobSender` still hard-codes the URI.
- The route log line shows `JobId` and delivery count instead of the full body, so the body is no longer logged twice.

- `JobRequestRoute`: unused `@Inject JobWorker worker` field removed; the route keeps `.bean(JobWorker.class, "handle")`.
- `SeedJobSender`: unused `ConfigProperty` import removed.
- `.dockerignore` deleted. → [QnA](QnA.md#how-do-i-remove-native-graalvm-support)
- Native: `README.md` section on native executables replaced by "JVM mode only", and `CLAUDE.md` updated (by Claude). → [QnA](QnA.md#how-do-i-remove-native-graalvm-support)

## 2026-10-24

**End-to-end run against `broker/`** (by Claude, 2026-10-24, `quarkus:dev` + `amqsputc`)
- Credentials work (no `MQRC 2035`). The `SeedJobSender` job, `hello` and `after-fix` were consumed and logged by `JobWorker`.
- `boom`: delivered 3 times with rollback, then moved to `DEV.JOBS.BACKOUT`, but only after a broker fix. The JMS client needs `PASSALL` on the backout queue and the DLQ (`AMQ8077W … passall`, `JMSWMQ1080`/`JMSWMQ1079`), and without it the listener looped every 5 s. Fixed in `broker/mqsc/30-quarqueueus.mqsc` with `SET AUTHREC … AUTHADD(BROWSE,GET,INQ,PUT,PASSALL)` on `DEV.JOBS.BACKOUT` and `DEV.DEAD.LETTER.QUEUE`. → [broker/README.md → Backout](../broker/README.md#backout-why-your-own-queues-should-set-bothresh)
- After a broker restart, the listener reconnected on its own.
- `.gitignore`: `mqjms.log*` (the IBM MQ client's log and lock file in the working directory).
- `DEV.JOBS.BACKOUT` now holds the `boom` message. Clear it with `CLEAR QLOCAL(DEV.JOBS.BACKOUT)` in `runmqsc` when you no longer need it.

**App starts again** (uncommitted). Verified with `./mvnw clean verify`, both in a scratch copy and on the real tree: `GreetingResourceTest` passes, and the only failure is `JobApiPactIT` "has a matching body" (known, TODO §3).
- Added `camel-quarkus-bean`, so `.bean(...)` in `JobRequestRoute` resolves. → [QnA](QnA.md#code-and-structure-reviews-2026-10-24)
- `GreetingResource` is `@IfBuildProfile(anyOf = {"dev", "test"})`; `GreetingResourceIT` deleted, because the prod jar has no `/hello`.
- `pom.xml`: `native.image.path` rejoined onto one line; misplaced comment removed from `com.ibm.mq.jakarta.client`.

**Package restructure** (uncommitted)
- Root package `xyz.kajih.quarqueueus`, with `dev/`, `execution/`, `infra/mq/` and `jobs/internal/{rest,messaging}`. `JexSrv` → `jobs.internal.rest.JobsResourceImpl`; `JobApiPactIT` → test `jobs` package. `JobWorker` is now staged along with the rest. The generator `base-package` stays `xyz.kajih.jexapi`.
- Round 2: `JobRequestRoute` moved to `execution/messaging`; `internal/` dropped (`jobs/rest`, `jobs/messaging`); `SeedJobSender` is `@IfBuildProfile("dev")`, and the `jobs.seed.enabled` flag is gone. → [QnA](QnA.md#code-and-structure-reviews-2026-10-24) → [QnA: code review](QnA.md#code-and-structure-reviews-2026-10-24)

**Broker queues** (by Claude, in `broker/`)
- `broker/mqsc/30-quarqueueus.mqsc` is mounted into `/etc/mqm/` and re-applied on every start. It defines `DEV.JOBS.REQUEST` (`BOTHRESH(3)` → `DEV.JOBS.BACKOUT`, `DEFPSIST(YES)`) and `DEV.JOBS.BACKOUT`. Verified: the script runs with 0 errors, and `app` can put and get. How-to: [broker/README.md → Creating queues](../broker/README.md#creating-queues).

**Transactions and secrets**
- Camel JMS credentials via Camel placeholders: `"{{mq_app_user:app}}"` (printed) and `"{{mq.app.password}}"` (masked). This fixes both the Camel security warning and the brief password leak in the startup log. `.env.example` lists `MQ_APP_USER` and `MQ_APP_PASSWORD`. → [QnA](QnA.md#what-is-camels-security-warning-secret-secret-property-is-configured-with-plain-text-value)
- `citrus-quarkus` back, now in `<scope>test</scope>`.
- Removed the no-op XA config (`quarkus.transaction-manager.enable-recovery`, `quarkus.pooled-jms.transaction: xa`). The startup warning is gone. → [QnA](QnA.md#why-does-quarkuspooled-jmstransaction-xa-do-nothing)
- `camel.component.jms.transacted: true`: local, Spring-style JMS transactions. → [QnA](QnA.md#follow-up-do-i-still-get-normal-spring-style-jms-transactions)
- `.env.example` with `MQ_APP_PASSWORD=changeme`.
- Removed `citrus-quarkus` (follow-up in TODO: the Citrus tests still need a JUnit integration).

**Messaging setup** (commit `b9e4bdc`)
- IBM MQ `ConnectionFactory` producer in `xyz.kajih.quarqueueus.mq` (`MqConfig` + `MqConnectionFactoryProducer`), wrapped by `PooledJmsWrapper`, with credentials on the Camel JMS component. → [QnA](QnA.md#how-do-i-provide-the-ibm-mq-connectionfactory-for-camel-jms-with-pooling-and-credentials)
- `.env` with `MQ_APP_PASSWORD`, read by Quarkus natively (no plugin), gitignored. `application.yaml` uses `${MQ_APP_PASSWORD}` without a default. → [QnA](QnA.md#how-do-i-keep-local-secrets-passwords-credentials-out-of-git-do-i-need-a-env-plugin-or-vault)
- `quarkus-maven-plugin` `jvmArgs: --enable-native-access=ALL-UNNAMED` (silences Java 25 native-access warnings in dev mode).
- `broker/docker-compose.yml`: `restart: "no"`, so the broker no longer starts with Docker.

**Dependencies**
- `camel-quarkus-jms` + `quarkus-camel-bom` import.
- `com.ibm.mq.jakarta.client` 10.0.1.0 (plain library, no Quarkus extension; JVM mode only).
- `citrus-quarkus` 5.1.0 (later removed; see above), `citrus-jms` 5.1.0 (test).
- `quarkus-pooled-jms` 2.13.0.
- Build fix: `swagger-parser` 2.1.45 override in `<dependencyManagement>`. The Camel BOM had excluded `swagger-models` and broke OpenAPI codegen. → [QnA](QnA.md#why-does-the-build-fail-with-noclassdeffounderror-ioswaggerv3oasmodelsmediaschema-after-adding-camel)

**Repo**
- Git history reset and initial commit `54e0167`, with a new `.gitignore` and `.gitattributes` (LF line endings, executable `mvnw`).
- Local IBM MQ broker in `broker/` (`QM1` on `localhost:1414`; see `broker/README.md`).
