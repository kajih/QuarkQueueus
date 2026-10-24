# TODO

Open work, roughly in priority order within each section. Last full rescan: **2026-10-24**, with everything up to that point committed.
Finished items are in [DONE.md](DONE.md), and background explanations in [QnA.md](QnA.md).

## 1. Messaging (current focus)

- [ ] **Write a first Camel route** (e.g. `from("jms:queue:DEV.JOBS.REQUEST")`; the queue now exists with backout configured) and run it against `broker/`. Nothing exercises the `ConnectionFactory` yet: `./mvnw test` passes only because Camel has no routes, so it never connects. This is also the moment to confirm the component credentials work (`MQRC 2035` = they don't). `transacted: true` is set on the component; add `.transacted()` to the route so Camel's transaction-aware error handler is used. → [QnA: local transactions](QnA.md#follow-up-do-i-still-get-normal-spring-style-jms-transactions)
- [ ] **Citrus test for that route.** `citrus-quarkus` + `citrus-jms` (both test scope) are enough: `citrus-quarkus` plugs into `@QuarkusTest` as a Quarkus test resource, and JUnit comes from `quarkus-junit` (checked in the 5.1.0 jar), so **no `citrus-junit-jupiter`** is needed. Import `org.citrusframework.quarkus.CitrusSupport`, not the same-named JUnit one. The broker must be running, since there's no Dev Service for MQ.

## 2. Config and `pom.xml` hygiene

- [ ] *(optional)* **Silence Camel's `SECURITY WARNING: [secret] Secret property is configured with plain-text value`** by using Camel placeholders for the JMS credentials. → [QnA: Camel secret warning](QnA.md#what-is-camels-security-warning-secret-secret-property-is-configured-with-plain-text-value)
- [ ] **`native.image.path` was split over two lines** by an IDE reformat in `b9e4bdc`. The value now ends in a newline plus indentation. It only matters for native `@QuarkusIntegrationTest` runs (`-Dnative`), but rejoin it into one line. If your IDE keeps doing this, turn off "wrap text in XML tags" for `pom.xml`.
- [ ] **Misplaced comment:** `<!-- keep in step with citrus-quarkus -->` sits on `com.ibm.mq.jakarta.client`, whose version is unrelated to Citrus. Move it to `citrus-jms`, or drop it once `citrus-bom` is imported.
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

- [ ] **`GET /jobs` response shape doesn't match the Pact contract.** The pact expects `{"jobs": []}`, but the spec and `JexSrv.listJobs` return a bare array. Decide which side is right and fix the other. → [QnA: jobs endpoint](QnA.md#why-is-the-get-jobs-endpoint-wrong-and-how-do-i-fix-it)
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

## 5. Scaffold leftovers

- [ ] `GreetingResource` + `GreetingResourceTest`/`GreetingResourceIT` are from `quarkus create`. Remove them once there are real tests for the jobs API.

---

### Known harmless warnings

So nobody chases them again:

- **`OpenAPI 3.1 support is still in beta`** from the generator: see section 4.
