# TODO

Open work, roughly in priority order within each section. Last full rescan: **2026-10-25**, with everything up to that point committed; `./mvnw verify` fails only on `JobApiPactIT`.
Finished items are in [DONE.md](DONE.md), and background explanations in [QnA.md](QnA.md).

## 1. Messaging (current focus)

Background: [QnA: code and structure reviews](QnA.md#code-and-structure-reviews-2026-10-24), [QnA: the JobRequest message](QnA.md#the-job-request-message-jobrequest-bean-binding-and-headers).

- [ ] **Add the `jobs` domain centre** (`Job` + `JobService` with an in-memory store). Nothing calls `JobSender` yet: `JobsResourceImpl.createJob` still returns `null`. `JobService.submit` creates the `Job`, maps to `JobRequest` (`new JobRequest(id, body.getType().value(), body.getParameters())`) and calls `JobSender.send`, so the REST adapter doesn't call the messaging adapter directly.
- [ ] `SeedJobSender`: last place with the hard-coded `"jms:queue:DEV.JOBS.REQUEST"` (use `JobRequest.QUEUE`). Its hand-written JSON has no `jobId` (the route accepts it, with `jobId` `null`), and it uses a fully qualified `java.util.UUID`. Build a `JobRequest` and serialise it, or call `JobService.submit` once it exists.
- [ ] `JobWorker`: `boom` scaffolding (`type.contains("boom")` also NPEs if a message has no `type`); logs the whole header map and the request at INFO on every message (DEBUG, or log only `jobId`/`JMSXDeliveryCount`); the comment `// Camel converts the TextMessage body to String` is now wrong. `@Handler` is in, so the route's `"handle"` argument is redundant (`.bean(JobWorker.class)` is enough). → [QnA: bean annotations](QnA.md#the-job-request-message-jobrequest-bean-binding-and-headers)

Note: `transacted: true` is set on the component, which is enough for rollback. **Don't** add `.transacted()`: it fails at startup on this classpath (earlier advice corrected). → [QnA: Camel JMS listener and sending](QnA.md#how-do-i-write-a-camel-jms-listener-and-how-do-i-send-messages-to-the-queue)

- [ ] **Citrus test for that route** (`JobRequestRouteIT`): start with the poison → `DEV.JOBS.BACKOUT` test. Two snags: Citrus needs credentials wrapped around the app's `ConnectionFactory`, and the route must be stopped while asserting what was sent. Add `citrus-validation-json` before you compare JSON bodies. → [QnA: Citrus](QnA.md#how-do-i-test-the-jms-flow-with-citrus)
- [ ] **Broker-free flow test** (`JobFlowTest`, runs under `./mvnw test`): `JobRequest.QUEUE = "{{jobs.request.uri}}"`, a `%test` `direct:` URI plus the dummy JMS password, `camel-quarkus-direct` and `quarkus-junit-mockito` in test scope. Verified recipe → [QnA: no broker at all](QnA.md#no-broker-at-all-the-verified-recipe)
- [ ] **Decide how the remaining broker tests get a broker.** Citrus still needs one. Recommended: a Testcontainers test resource running the IBM MQ image, so `verify` doesn't depend on a manually started `broker/`. → [QnA: Citrus and the broker](QnA.md#doesnt-citrus-decouple-the-test-from-the-mq-broker)

## 2. Config and `pom.xml` hygiene

- [ ] **Native leftover:** `pom.xml`: delete `<native.image.path>` from the failsafe `<systemPropertyVariables>`. Keep `--enable-native-access` (it's a JDK flag, not GraalVM). → [QnA: native](QnA.md#how-do-i-remove-native-graalvm-support)
- [ ] **Misplaced comment, take 2:** `<!-- keep in step with citrus-quarkus -->` moved off `com.ibm.mq.jakarta.client`, but now sits on `rest-assured`'s `<scope>test</scope>`, which is also unrelated to Citrus (rest-assured's version comes from the Quarkus BOM). It belongs on `citrus-jms`'s `<version>`, or drop it once `citrus-bom` is imported.
- [ ] **Import `citrus-bom`** so all `citrus-*` modules share one version (verified on Maven Central: 5.1.0 exists and manages `citrus-quarkus`, `citrus-jms`, `citrus-junit-jupiter`, …). Add to `<dependencyManagement>`, then drop the `<version>` from each `citrus-*` dependency:
  ```xml
  <dependency>
      <groupId>org.citrusframework</groupId>
      <artifactId>citrus-bom</artifactId>
      <version>5.1.0</version>
      <type>pom</type>
      <scope>import</scope>
  </dependency>
  ```
  Put it **after** the Quarkus and Camel BOMs. When two imported BOMs manage the same artifact, the first one wins, so the platform BOMs keep precedence for shared libraries such as Jackson. This is the same rule that caused the swagger problem.

## 3. Jobs API: contract and behaviour

- [ ] **`GET /jobs` response shape doesn't match the Pact contract.** The pact expects `{"jobs": []}`, but the spec and `JobsResourceImpl.listJobs` return a bare array. Decide which side is right and fix the other. → [QnA: jobs endpoint](QnA.md#why-is-the-get-jobs-endpoint-wrong-and-how-do-i-fix-it)
- [ ] **The pact expects an empty list, but `listJobs` returns a hard-coded job.** Even once the shape matches, `[]` vs `[job]` fails. Needs provider states or matchers in the pact (same QnA entry).
- [ ] **`POST /jobs` returns 200, but the spec says 201.** → [QnA: status codes](QnA.md#how-do-i-return-201--404--409-when-the-generated-interface-returns-a-plain-dto)
- [ ] **Stub methods return `null`, which is sent as `204 No Content`.** `getJob`/`getJobResult` should return 404 (and 409 for unfinished results) with an `Error` body (same QnA entry).
- [ ] **Request validation isn't enforced.** The generated `CreateJobRequest` has no `@NotNull` and the parameter has no `@Valid`, so `required: [type, parameters]` is ignored even though `quarkus-hibernate-validator` is present. Check the generator extension for a bean-validation option.
- [ ] `listJobs` ignores the `status` query parameter.
- [ ] **No job storage.** Start with an `@ApplicationScoped` bean around a `ConcurrentHashMap`, then Panache/Hibernate later.
- [ ] **No async execution.** Jobs never move QUEUED → RUNNING → COMPLETED. This is where the queue comes in: `createJob` sends to MQ, a Camel route consumes it and updates the job.

## 4. Spec cleanup

- [ ] `CreateJobRequest.type` is an enum (`image-analysis`, `data-import`, `report-generation`), but `Job.type` is a free-form string. Probably should be a shared `$ref` enum.
- [ ] `GET /jobs/{jobId}/result` has no response for failed jobs (`FAILED` status).
- [ ] *(low)* The spec is `openapi: 3.1.0`, and the generator warns that 3.1 support is beta. Nothing is broken now; if generated code looks odd later, try `3.0.3` first.

## 5. Structure and tooling

- [ ] Add a formatter (Spotless + palantir/google-java-format) to the build. → [QnA: reviews](QnA.md#code-and-structure-reviews-2026-10-24)
- [ ] *(low)* The IDE writes CRLF (Git warns `CRLF will be replaced by LF`). Harmless, because `.gitattributes` already has `* text=auto eol=lf` and Git normalises on commit, but setting the IDE to LF silences the warnings. Several files also lack a final newline.

## 6. Scaffold leftovers

- [ ] `GreetingResource` + `GreetingResourceTest` are from `quarkus create` (now dev/test-only). Remove them once there are real tests for the jobs API.

---

### Known harmless warnings

So nobody chases them again:

- **`OpenAPI 3.1 support is still in beta`** from the generator: see section 4.
- **`The Maven extensions for the Quarkus Maven plugin are not enabled`** when running the bare `quarkus:build` goal: misleading, and `<extensions>true</extensions>` is set. Use `./mvnw package`. → [QnA](QnA.md#why-does-quarkusbuild-warn-that-extensionstrueextensions-is-missing)
- **Connection-failure warnings from the JMS listener** in every `@QuarkusTest` when `broker/` is down: the route retries in the background. → [QnA: listener](QnA.md#how-do-i-write-a-camel-jms-listener-and-how-do-i-send-messages-to-the-queue)
