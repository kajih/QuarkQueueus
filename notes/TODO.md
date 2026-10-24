# TODO

Open work, roughly in priority order within each section. Last full rescan: **2026-10-24** (evening), with everything up to that point committed.
Finished items are in [DONE.md](DONE.md), and background explanations in [QnA.md](QnA.md).

## 1. Messaging (current focus)

Code review findings for the new `jobs` package → [QnA: code review](QnA.md#code-review-2026-10-24-code-standard-bad-code-package-structure)

Package layout follow-ups → [QnA: structure round 2](QnA.md#package-structure-review-round-2-2026-10-24)

- [ ] **`JobRequestRoute` has an unused `@Inject JobWorker worker` field** next to `.bean(JobWorker.class, "handle")`. Pick one: `.bean(worker, "handle")`, or drop the field. → [QnA: round 3](QnA.md#package-structure-review-round-3-2026-10-24)
- [ ] `SeedJobSender`: unused `ConfigProperty` import, left over from the removed flag.
- [ ] **Queue message record owned by `execution`** (`JobRequest`, plus the queue URI). Stop sending the REST DTO `CreateJobRequest`, and have `JobWorker.handle` take the record. → [QnA: structure round 2](QnA.md#package-structure-review-round-2-2026-10-24)
- [ ] **Add the `jobs` domain centre** (`Job` + `JobService` with an in-memory store), so the REST adapter doesn't call the messaging adapter directly.
- [ ] **`JobSender` has no scope annotation**, so it isn't a CDI bean. Add `@ApplicationScoped`, drop the `/* ... */` placeholder, and split "create job" (`JobService`) from "send" (sender).
- [ ] Queue URI hard-coded in 3 classes (`JobSender`, `JobRequestRoute`, `SeedJobSender`): one constant or config key, owned by `execution`.
- [ ] `SeedJobSender`: hand-written JSON and a fully qualified `java.util.UUID`. Build the `JobRequest` record, or call `JobService.submit` once it exists.
- [ ] `JobWorker`: body logged twice at INFO; `boom` scaffolding; take the `JobRequest` record instead of `String`.

 `transacted: true` is set on the component, which is enough for rollback. **Don't** add `.transacted()`: it fails at startup on this classpath (earlier advice corrected). → [QnA: Camel JMS listener and sending](QnA.md#how-do-i-write-a-camel-jms-listener-and-how-do-i-send-messages-to-the-queue)
- [ ] **Citrus test for that route.** `citrus-quarkus` + `citrus-jms` (both test scope) are enough: `citrus-quarkus` plugs into `@QuarkusTest` as a Quarkus test resource, and JUnit comes from `quarkus-junit` (checked in the 5.1.0 jar), so **no `citrus-junit-jupiter`** is needed. Import `org.citrusframework.quarkus.CitrusSupport`, not the same-named JUnit one. The broker must be running, since there's no Dev Service for MQ.

## 2. Config and `pom.xml` hygiene

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

- [ ] Add a formatter (Spotless + palantir/google-java-format) to the build; fix mixed CRLF/LF line endings (`.gitattributes`).

## 6. Scaffold leftovers

- [ ] `GreetingResource` + `GreetingResourceTest` are from `quarkus create` (now dev/test-only). Remove them once there are real tests for the jobs API.

---

### Known harmless warnings

So nobody chases them again:

- **`OpenAPI 3.1 support is still in beta`** from the generator: see section 4.
