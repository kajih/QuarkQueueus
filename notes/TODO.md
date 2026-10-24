# TODO

Things that need doing in this repo, roughly in priority order. Each one points to an answer in [QA.md](QA.md) when there is one.

## Broken / contract issues

- [x] **Build is broken: OpenAPI codegen fails with `NoClassDefFoundError: io/swagger/v3/oas/models/media/Schema`.** Caused by the `quarkus-camel-bom` import excluding `swagger-models` from `swagger-parser`. Fix with a `swagger-parser` override in `pom.xml`. → [QA: swagger Schema error](QA.md#why-does-the-build-fail-with-noclassdeffounderror-ioswaggerv3oasmodelsmediaschema-after-adding-camel)
- [ ] **`GET /jobs` response shape doesn't match the Pact contract.** The pact expects `{"jobs": []}`, but the spec and `JexSrv.listJobs` return a bare array. Decide which side is right and fix the other. → [QA: jobs endpoint](QA.md#why-is-the-get-jobs-endpoint-wrong-and-how-do-i-fix-it)
- [ ] **The pact expects an empty list, but `listJobs` returns a hard-coded job.** Even once the shape matches, `[]` vs `[job]` fails verification. Needs either provider states or matchers in the pact (same QA entry).
- [ ] **`POST /jobs` returns 200, but the spec says 201.** The generated interface returns `Job`, so Quarkus REST defaults to 200. → [QA: status codes](QA.md#how-do-i-return-201--404--409-when-the-generated-interface-returns-a-plain-dto)
- [ ] **Stub methods return `null`, which Quarkus REST sends as `204 No Content`.** `getJob`/`getJobResult` should return 404 (and 409 for unfinished results) with an `Error` body, as the spec says. (Same QA entry.)

## Missing behaviour

- [ ] Request validation: the generated `CreateJobRequest` has no `@NotNull`, and the interface parameter has no `@Valid`, so `required: [type, parameters]` in the spec isn't enforced even though `quarkus-hibernate-validator` is on the classpath. Check the generator extension's config for a bean-validation option.
- [ ] `listJobs` ignores the `status` query parameter.
- [ ] No job storage. Something has to hold jobs between `createJob` and `getJob`, e.g. an in-memory `ConcurrentHashMap` in an `@ApplicationScoped` bean to start, then Panache/Hibernate later.
- [ ] No actual async execution. Jobs never move QUEUED → RUNNING → COMPLETED. Options to explore: `@Scheduled`, `ManagedExecutor`, Vert.x event bus, or SmallRye Reactive Messaging.

## Queue messaging: Camel JMS + IBM MQ + Citrus

Run from the repo root. Quarkus CLI 3.40.1 is installed. Extension names were checked against `quarkus ext list --installable` on 2026-10-24, and versions against Maven Central on the same day.

- [x] **Camel JMS** (platform extension ✬, version managed for you):
  ```shell
  quarkus ext add camel-quarkus-jms
  ```
  Because Camel Quarkus is part of the Quarkus platform, this should also add the `io.quarkus.platform:quarkus-camel-bom` import to `<dependencyManagement>`, the counterpart of Spring Boot's dependency management for starters. Check the POM diff afterwards to confirm.

- [ ] **Citrus** (a Quarkiverse/registry extension, not part of the platform, so the CLI writes an explicit version):
  ```shell
  quarkus ext add citrus-quarkus
  ```
  ⚠ The CLI adds it in **compile** scope. Move it to `<scope>test</scope>` in `pom.xml` afterwards, because it's test-only. It gives you `@CitrusSupport` to put on a `@QuarkusTest`.
  **Status:** added (5.1.0), but still in compile scope, so the remaining step is to move it to `<scope>test</scope>`.
- [ ] **Small `pom.xml` fix:** the `<!-- keep in step with citrus-quarkus -->` comment landed on `com.ibm.mq.jakarta.client`. It belongs on `citrus-jms`, because the IBM MQ client is versioned independently of Citrus.

- [x] **IBM MQ Jakarta client: no CLI command.** It's a plain library, not a Quarkus extension (no `ibm`/`mq` hit in the registry), so add it to `pom.xml` by hand:
  ```xml
  <dependency>
      <groupId>com.ibm.mq</groupId>
      <artifactId>com.ibm.mq.jakarta.client</artifactId>
      <version>10.0.1.0</version>
  </dependency>
  ```
  It runs in JVM mode. Don't expect `-Dnative` builds to work with it without a lot of reflection config.

- [x] **Citrus JMS module: no CLI command either.** `citrus-quarkus` only gives the Quarkus integration. To send and receive JMS messages from Citrus tests you also need:
  ```xml
  <dependency>
      <groupId>org.citrusframework</groupId>
      <artifactId>citrus-jms</artifactId>
      <version>5.1.0</version> <!-- keep in step with citrus-quarkus -->
      <scope>test</scope>
  </dependency>
  ```
  Citrus also publishes `org.citrusframework:citrus-bom`. Importing it in `<dependencyManagement>` keeps all `citrus-*` versions aligned.

- [x] **Optional: connection pooling:** `quarkus ext add quarkus-pooled-jms` (Messaginghub pooled-jms, the counterpart of Spring's `CachingConnectionFactory`/`JmsPoolConnectionFactory`).

- [x] Local IBM MQ broker to connect to: `broker/` (run `docker compose up -d` there). The queue manager is `QM1` on `localhost:1414`, channel `DEV.APP.SVRCONN`, user `app` / `passw0rd`, queues `DEV.QUEUE.1..3`. Details in `broker/README.md`.
- [ ] After adding: provide an IBM MQ `ConnectionFactory` as a CDI bean (`@Produces @ApplicationScoped`), the counterpart of a Spring `@Bean`, so Camel's `jms:` component can find it. Note that there's no IBM MQ Spring Boot starter-style auto-config in Quarkus. → [QA: IBM MQ ConnectionFactory](QA.md#how-do-i-provide-the-ibm-mq-connectionfactory-for-camel-jms-with-pooling-and-credentials)

- [ ] **Local secrets via `.env`** (already gitignored): put `MQ_APP_PASSWORD=...` in a root `.env`, commit a `.env.example` with dummy values, and use `${MQ_APP_PASSWORD}` without a default in `application.yaml`. No Vault/Consul. → [QA: local secrets](QA.md#how-do-i-keep-local-secrets-passwords-credentials-out-of-git-do-i-need-a-env-plugin-or-vault)

## Spec cleanup

- [ ] `CreateJobRequest.type` is an enum (`image-analysis`, `data-import`, `report-generation`), but `Job.type` is a free-form string. Probably should be a shared `$ref` enum.
- [ ] `GET /jobs/{jobId}/result` has no response for failed jobs (`FAILED` status).

## Scaffold leftovers

- [ ] `GreetingResource` + `GreetingResourceTest`/`GreetingResourceIT` are from `quarkus create`. Remove them once there are real tests for the jobs API.
