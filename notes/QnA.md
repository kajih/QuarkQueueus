# Q&A

Answers to questions about this repo and Quarkus, **grouped by topic**, with each entry written to match the current code. Claude explains and suggests, and the owner applies. Open work lives in [TODO.md](TODO.md), and finished work in [DONE.md](DONE.md).

Cleaned up on 2026-10-25: entries were regrouped, the three code-review rounds were merged into one summary, the three `JobRequest` entries were merged into one, and outdated snippets were replaced with references to the real code. The longer original wording is in git history (`a6f05f5` and later).

## Index

| Status | Topic | Entry |
|---|---|---|
| 🔴 open | REST API | [Why is the `GET /jobs` endpoint wrong?](#why-is-the-get-jobs-endpoint-wrong-and-how-do-i-fix-it) |
| 🔴 open | REST API | [How do I return 201 / 404 / 409 from a generated interface?](#how-do-i-return-201--404--409-when-the-generated-interface-returns-a-plain-dto) |
| ✅ applied | Build | [Build fails with `NoClassDefFoundError: …/media/Schema` after adding Camel](#why-does-the-build-fail-with-noclassdeffounderror-ioswaggerv3oasmodelsmediaschema-after-adding-camel) |
| ✅ applied | Build | [Why does `quarkus:build` warn that `<extensions>true</extensions>` is missing?](#why-does-quarkusbuild-warn-that-extensionstrueextensions-is-missing) (nothing to fix: use `package`) |
| 🔴 open | Build | [How do I remove native (GraalVM) support?](#how-do-i-remove-native-graalvm-support) (one `pom.xml` line left) |
| ✅ applied | Config | [How do I keep local secrets out of git (`.env` vs Vault)?](#how-do-i-keep-local-secrets-passwords-credentials-out-of-git-do-i-need-a-env-plugin-or-vault) |
| ✅ applied | Config | [What is Camel's `SECURITY WARNING: … plain-text value`?](#what-is-camels-security-warning-secret-secret-property-is-configured-with-plain-text-value) (+ Camel's masking rule) |
| ✅ applied | IBM MQ | [How do I provide the IBM MQ `ConnectionFactory` for Camel JMS?](#how-do-i-provide-the-ibm-mq-connectionfactory-for-camel-jms-with-pooling-and-credentials) |
| ✅ applied | IBM MQ | [Why does `quarkus.pooled-jms.transaction: xa` do nothing?](#why-does-quarkuspooled-jmstransaction-xa-do-nothing) (+ local vs JTA/XA transactions) |
| 🔴 open | Camel | [How do I write a Camel JMS listener and send messages?](#how-do-i-write-a-camel-jms-listener-and-how-do-i-send-messages-to-the-queue) (listener done; `JobSender` not wired) |
| ✅ applied | Camel | [The job request message: `JobRequest`, bean binding and headers](#the-job-request-message-jobrequest-bean-binding-and-headers) (+ which annotations to use) |
| 🔴 open | Camel | [How do I run a one-time action at startup (send one job)?](#how-do-i-run-a-one-time-action-at-startup-eg-send-one-job) (`SeedJobSender` leftovers) |
| 🔴 open | Testing | [How do I test the JMS flow with Citrus?](#how-do-i-test-the-jms-flow-with-citrus) |
| 🔴 open | Structure | [Code and structure reviews (2026-10-24)](#code-and-structure-reviews-2026-10-24) (decisions made, plus what's still open) |

🔴 open means part of the entry still isn't in the code. ✅ applied means the fix is in, and the entry is kept as background reading. New entries go at the end of their topic, and the index stays in topic order.

---

# REST API and contract

## Why is the `GET /jobs` endpoint wrong, and how do I fix it?

**The mismatch.** There are three sources of truth, and they don't agree:

| Source | `GET /jobs` returns |
|---|---|
| `src/main/resources/openapi/jexapi.yaml` | a bare JSON array of `Job`: `[ {...} ]` |
| `JobsResourceImpl.listJobs` (via generated `JobsResource`) | `List<Job>` → a bare array, plus one hard-coded job |
| `src/test/resources/pacts/quarqueueus_client-JexApi.json` | an object wrapping an empty array: `{"jobs": []}` |

`JobApiPactIT` replays the pact against the running app, so it sees `[ {job} ]` where it expected `{"jobs": []}` and fails.

In consumer-driven contract testing, the **pact describes what the consumer actually relies on**. So the real question is: what does the `quarqueueus_client` consumer code expect? The pact metadata says it was produced by `pact_rust`, so it comes from a Rust consumer test, not this repo.

### Option A: the consumer is wrong (keep the bare array)

If a bare array is what you want (it's what the spec says):

1. Fix the **consumer test** so it expects an array, and regenerate the pact file. Don't hand-edit the pact unless the consumer side is just a throwaway experiment, in which case hand-editing is fine for learning.
2. **Handle the hard-coded job.** Pact compares arrays strictly unless the pact uses matchers: an expected `[]` against an actual `[job]` still fails. Either:
   - use a **matcher** in the consumer test (e.g. `each_like(job)`, so it means "an array of objects shaped like this"), which is usually what you want, or
   - add a **provider state** (e.g. `"no jobs exist"`) in the consumer test, and in `JobApiPactIT` add a `@State("no jobs exist")` method that clears the job store before the interaction runs. That needs real storage instead of the hard-coded list in `JobsResourceImpl`.

### Option B: the spec is wrong (wrap the list)

If you want `{"jobs": [...]}`, which is a common choice because it leaves room for pagination fields like `nextCursor` and `total` later:

1. In `jexapi.yaml`, add a schema and point the 200 response at it:
   ```yaml
   JobList:
     type: object
     required: [jobs]
     properties:
       jobs:
         type: array
         items:
           $ref: "#/components/schemas/Job"
   ```
2. Rebuild (`./mvnw compile`, or let `quarkus:dev` reload). The generator changes `JobsResource.listJobs` to return `JobList`, and `JobsResourceImpl` stops compiling until you return `new JobList().jobs(...)`. That compile error is the API-first workflow doing its job.
3. You still have the empty-list vs hard-coded-job problem from Option A step 2.

**Coming from Spring:**
- **Code generation:** in Spring you'd run `openapi-generator-maven-plugin` with the `spring` generator and `interfaceOnly=true`, then write a `@RestController` that implements the interface. Here a Quarkus *extension* does the generation during Quarkus's own code-gen step, configured in `application.yaml` instead of the POM. That's why there's no generator plugin in `pom.xml`.
- **Pact test setup:** `JobApiPactIT` is the counterpart of a Spring `@SpringBootTest(webEnvironment = RANDOM_PORT)` provider test. `@State` works the same way, since both use pact-jvm's JUnit 5 support. One difference: Quarkus tests start on a fixed port (`quarkus.http.test-port`, default 8081) rather than a random one.
- **No `@RestController` needed:** `JobsResourceImpl` has no stereotype annotation. Quarkus REST finds it because it implements a `@Path`-annotated interface.

**My recommendation:** Option B plus a provider state. Wrapping is the more future-proof API shape, it makes the existing pact pass without touching the consumer, and wiring up `@State` teaches the most about how Pact provider verification works in Quarkus.

---

## How do I return 201 / 404 / 409 when the generated interface returns a plain DTO?

Because `JobsResource` methods return `Job`/`JobResult` directly:

- **Returning an object** gives 200, so `createJob` returns 200 instead of 201.
- **Returning `null`** gives **204 No Content**, which is what the stubs do now. *Spring gotcha:* Spring MVC would give 200 with an empty body here.

| Spring MVC | Quarkus REST (JAX-RS) |
|---|---|
| `ResponseEntity<T>` | `RestResponse<T>` (typed) or `jakarta.ws.rs.core.Response` (untyped) |
| `@ResponseStatus(HttpStatus.CREATED)` | `@org.jboss.resteasy.reactive.ResponseStatus(201)` |
| `@ExceptionHandler` in a `@ControllerAdvice` | `@ServerExceptionMapper` method in any bean (or a classic JAX-RS `ExceptionMapper<E>` `@Provider`) |
| `ResponseStatusException` | `WebApplicationException` and its subclasses (`NotFoundException`, ...) |

Ways to fix it, without changing the generated code:

- **Errors (404/409):** throw an exception from `JobsResourceImpl` and map it to a response. Write a method annotated `@ServerExceptionMapper` (in any bean) that takes e.g. a custom `JobNotFoundException` and returns `RestResponse<Error>` with status 404 and the spec's `Error` body. You could throw `jakarta.ws.rs.NotFoundException` instead, but then the body won't match the `Error` schema.
- **201 on create:** put `@org.jboss.resteasy.reactive.ResponseStatus(201)` on `JobsResourceImpl.createJob`, and verify it takes effect. JAX-RS annotation inheritance says that if the implementing method has its own JAX-RS annotations, the interface's are ignored, so check that `@POST`/`@Produces` still apply. A test asserting `.statusCode(201)` will tell you. A proper 201 should also set a `Location: /jobs/{id}` header, which needs `Response`/`RestResponse` as the return type.
- **Generator side:** the cleaner long-term fix is to have the generated interface return `Response`/`RestResponse`, so the implementation controls status and headers. Check the `quarkus-openapi-generator-server` docs for an option to do this. I haven't verified one exists for the version used here (2.23.0).

---

# Build and dependencies

## Why does the build fail with `NoClassDefFoundError: io/swagger/v3/oas/models/media/Schema` after adding Camel?

The error has nothing to do with `jexapi.yaml`. `./mvnw compile` fails in Quarkus's `generate-code` step, inside `quarkus-openapi-generator-server`:

```
Quarkus code generation phase has failed: InvocationTargetException: io/swagger/v3/oas/models/media/Schema
Caused by: java.lang.ClassNotFoundException: io.swagger.v3.oas.models.media.Schema
    at org.openapitools.codegen.CodegenConfigLoader.forName
```

**Root cause: the `quarkus-camel-bom` import.** The generator depends on `org.openapitools:openapi-generator` → `io.swagger.parser.v3:swagger-parser`, which normally brings in `swagger-core` and `swagger-models` (`Schema` lives in `swagger-models`). The Camel Quarkus BOM manages `swagger-parser` **with exclusions** for both, because Camel uses the `-jakarta` variants and adds them explicitly in its own OpenAPI extensions. `quarkus-bom` doesn't manage `swagger-parser` at all, so Camel's entry wins, and the two jars silently disappear from the generator's build-time classpath. `./mvnw quarkus:dependency-tree` shows the gap.

Spring analogy: the same problem as importing a second BOM next to `spring-boot-dependencies` and having its `<dependencyManagement>` override a transitive dependency for you. It's harder to spot here because the generator runs on the *build-time* classpath (deployment dependencies), which `mvn dependency:tree` doesn't show. Use `./mvnw quarkus:dependency-tree` instead.

**Fix (applied, in `pom.xml`):** a `<dependencyManagement>` entry declared directly in your own pom replaces any imported entry for the same artifact, exclusions included:

```xml
<dependency>
    <groupId>io.swagger.parser.v3</groupId>
    <artifactId>swagger-parser</artifactId>
    <version>2.1.45</version>
</dependency>
```

Keep the version in line with what `quarkus:dependency-tree` shows the generator using (2.1.45 for generator extension 2.23.0), and re-check it when you bump either the generator extension or the platform. Alternatives I'd avoid: dropping the Camel BOM (goes against how platform members are aligned), or adding `swagger-models` as a normal dependency (puts a codegen-only library on the runtime classpath).

---

## Why does `quarkus:build` warn that `<extensions>true</extensions>` is missing?

The `pom.xml` **does** have `<extensions>true</extensions>`, and the warning is misleading. Reproduced on 2026-10-25 with Maven 3.9.16 and Quarkus 3.40.1.1:

| Command | Warning? |
|---|---|
| `./mvnw package` (runs `quarkus:build (default-build)`) | no |
| `./mvnw quarkus:build` (runs `quarkus:build (default-cli)`) | **yes** |
| `./mvnw io.quarkus.platform:quarkus-maven-plugin:3.40.1.1:build` | **yes** |
| `./mvnw package quarkus:build`: the same goal twice in one session | only on the `default-cli` run |

So it depends on *how the goal is started*, not on the pom. The `[ quarkus ]` packaging in the build header also proves the extension is loaded.

**What triggers it** (from the plugin bytecode): `QuarkusBootstrapMojo` warns when the goal is `build` and its injected `BootstrapSessionListener` reports `isEnabled() == false`. That listener is a Maven lifecycle participant whose `afterProjectsRead` sets `enabled = true` unconditionally, so the `default-cli` mojo has been given a different listener instance from the one Maven called. The likely mechanism is Maven's own: for a plugin with `extensions=true`, Maven reuses the extension class realm for the plugin. A goal typed on the command line is resolved into a fresh plugin reference without the `extensions` flag, so it gets its own realm with its own, never-called components. In Spring terms: two application contexts, each with its own singleton, and you ask the one that never got the startup event.

**Is it harmful?** The warning isn't. But **running `quarkus:build` on its own is a bad habit** anyway: a bare goal runs no lifecycle phases, so there's no code generation, no `compile` and no `process-resources`. It augments whatever is already in `target/classes`, which may be stale.

**What to do:** build with the lifecycle, like `mvn package` rather than `spring-boot:repackage` in Spring Boot. Use `./mvnw package` (add `-DskipTests` to skip tests), which runs `quarkus:build` after compile. In IntelliJ, use *Maven → Lifecycle → package*. `quarkusBuild` is the **Gradle** task name; in Maven, the equivalent is the `package` phase.

---

## How do I remove native (GraalVM) support?

**There are no native packages to remove.** Native in Quarkus isn't a dependency. It's a build mode (`-Dnative`) that every extension supports through build steps in its `-deployment` module, and those steps do nothing without the flag. The closest Spring comparison is Spring AOT / `native-maven-plugin`, except that there's no extra plugin to remove: `quarkus-maven-plugin` handles both modes.

What `quarkus create` left behind:

| Where | Status |
|---|---|
| `pom.xml` native profile, `src/main/docker/Dockerfile.native*` | already gone |
| `.dockerignore` | deleted (2026-10-25) |
| `README.md` native section, `CLAUDE.md` | now say JVM only |
| `pom.xml`, failsafe `<systemPropertyVariables>`: `<native.image.path>…-runner</native.image.path>` | **still there. Delete the line.** `@QuarkusIntegrationTest` only reads it to find a native binary. |

**Keep `<jvmArgs>--enable-native-access=ALL-UNNAMED</jvmArgs>`** on `quarkus-maven-plugin`. Despite the name, it's a plain JDK 22+ flag that allows JNI/FFM "restricted methods" without a warning, for code on the classpath. It only applies to `quarkus:dev`.

Package-private `@Inject` fields stay the idiom in JVM mode too: ArC needs reflection for `private` injection points.

---

# Config and secrets

## How do I keep local secrets (passwords, credentials) out of git? Do I need a `.env` plugin or Vault?

**You don't need a plugin: Quarkus reads `.env` itself.** SmallRye Config (the counterpart of Spring's `Environment`) includes a `.env` config source. A `.env` file in the working directory is read as if its entries were environment variables, and in dev mode that means the **project root**. A Gradle `.env` plugin only sets env vars for the Gradle JVM, and `quarkusDev` launches the app in a separate JVM, which is why such a plugin has no effect there.

This repo: `.env` (gitignored) holds `MQ_APP_PASSWORD`; `.env.example` is the committed template.

### How keys map

Env var names follow the usual rule: uppercase, and every non-alphanumeric character becomes `_`. It's like Spring's relaxed binding, but Quarkus resolves the name *from* the property name, so `mq.app.password` finds `MQ_APP_PASSWORD`:

```properties
# .env (project root, not committed)
MQ_APP_PASSWORD=passw0rd                 # resolves mq.app.password
CAMEL_COMPONENT_JMS_PASSWORD=passw0rd    # or override camel.component.jms.password directly
_DEV_MQ_HOST=localhost                   # profile-specific: %dev.mq.host
```

The current `application.yaml` uses `password: "{{mq.app.password}}"` with no default, so a missing value fails at startup instead of silently using a default, which is what you want for secrets. (Why it's a Camel `{{…}}` placeholder rather than a Quarkus `${…}`: see the next entry.)

Precedence, highest first: system properties, then real env vars, then `.env`, then `application.yaml`. CI or a container can set real env vars and override `.env` without touching any file. `config/application.yaml` in the working directory also overrides the packaged one, which is useful for a whole block of local settings (gitignore it if you use it).

### Where Vault fits

`quarkus-vault` (Quarkiverse) adds HashiCorp Vault as a **config source**: `${...}` properties are resolved from Vault KV secrets at startup, and it also does dynamic database credentials. It has a **Dev Service** that starts a Vault container in dev and test, much like Testcontainers does for Spring. Right for shared or deployed environments, overkill for a local dev password.

Other secret backends work the same way, as config sources: Kubernetes Secrets (`quarkus-kubernetes-config`), AWS Secrets Manager / Azure Key Vault (Quarkiverse). Quarkus can also encrypt values inside the config file (`${enc:...}`, `quarkus config encrypt`), but you still have to manage the key.

**For this repo:** `.env` locally, real env vars in CI and containers, and Vault or the platform's secret store when deployed. The code and `application.yaml` stay the same in all three, as with Spring Cloud Vault.

---

## What is Camel's `SECURITY WARNING: [secret] Secret property is configured with plain-text value`?

Logged at startup for `camel.component.jms.username` and `password`, back when they were set as `password: ${MQ_APP_PASSWORD}` and `username: app`.

**Why:** Camel marks some component options as secrets (`username`/`password` on JMS among them), and at startup checks whether each was configured as a *placeholder* or as a literal. A Quarkus `${…}` placeholder is resolved by Quarkus *before* Camel reads the property, so Camel only sees the resolved value and concludes it's plain text. Nothing was leaked; the warning is about how the config *looks* to Camel. Spring analogy: as if Spring Boot warned about `spring.datasource.password=secret` but not about `${DB_PASSWORD}`, except that here two placeholder systems are stacked, and only the inner one (Camel's) is visible to the check.

**Fix (applied):** use Camel's placeholder syntax `{{...}}`. Quarkus leaves it alone, Camel sees the placeholder (no warning) and resolves it through Quarkus config, so `.env` still works:

```yaml
camel:
  component:
    jms:
      username: "{{mq_app_user:app}}"   # no dot → not sensitive → printed in the summary
      password: "{{mq.app.password}}"   # last segment "password" → masked
```

Quote the values, because `{` starts a flow mapping in YAML. `:app` is Camel's default syntax.

⚠ **The key name decides whether the value is masked in the log.** Camel Main logs a "Property-placeholders summary" at INFO with every placeholder it resolved, and masks only keys it recognises as sensitive. Tested 2026-10-24:

| Config | Security warning | Startup log |
|---|---|---|
| `${MQ_APP_PASSWORD}` (Quarkus placeholder) | yes | `camel.component.jms.password = xxxxxx` |
| `"{{MQ_APP_PASSWORD}}"` | no | **`MQ_APP_PASSWORD = passw0rd`**: leaked |
| `"{{mq.app.password}}"` | no | `mq.app.password = xxxxxx` |

**The masking rule** (`org.apache.camel.util.SensitiveUtils.containsSensitive`, camel-util 4.22.1): take the part of the key **after the last `.`**, lowercase it, remove `-`, and check for an **exact match** against a list of 94 keywords (`password`, `secret`, `token`, `user`, `username`, `passphrase`, `accesskey`, …):

| Key | Last segment, normalised | Masked? |
|---|---|---|
| `mq.app.password` | `password` | ✅ |
| `MQ.APP.PASSWORD` | `password` | ✅ |
| `MQ_APP_PASSWORD` | `mq_app_password` (no `.`, `_` not stripped) | ❌ |
| `mq.app-password` | `apppassword` | ❌ |
| `mq.app.passwd` | `passwd` (not in the list) | ❌ |

Rule of thumb: **name secrets `<something>.password` / `.secret` / `.token`**. That's the idiomatic Quarkus property style too. Defaults aren't listed in the summary; with `MQ_APP_USER=probeuser` set, it shows `mq_app_user = probeuser` (tested).

- `camel.main.auto-configuration-log-summary: false` turns both summaries off (tested). That's a reasonable default wherever logs are shipped somewhere.
- **Don't** use `{{env:MQ_APP_PASSWORD}}`, the form the warning suggests: Camel's `env:` function reads only real OS environment variables, and `.env` entries aren't OS env vars.

---

# IBM MQ and transactions

## How do I provide the IBM MQ `ConnectionFactory` for Camel JMS (with pooling and credentials)?

Applied: `infra/mq/MqConfig.java` and `infra/mq/MqConnectionFactoryProducer.java`, with `mq.*` in `application.yaml`.

| Spring Boot | Quarkus |
|---|---|
| `@Configuration` + `@Bean ConnectionFactory` | a class with a `@Produces` method (CDI producer). *You* pick the scope (`@ApplicationScoped` ≈ singleton) |
| `@ConfigurationProperties(prefix = "mq")` | `@ConfigMapping(prefix = "mq")` on an **interface** (method names map to kebab-case keys: `queueManager()` → `mq.queue-manager`) |
| `mq-jms-spring-boot-starter` auto-config | **nothing**: there's no IBM MQ extension, so you write the producer yourself |
| `JmsPoolConnectionFactory` / `CachingConnectionFactory` bean | `quarkus-pooled-jms`, but it doesn't wrap your own bean automatically (below) |
| beans never removed | **ArC removes unused beans at build time.** A bean only Camel looks up at runtime can be removed, hence `@Unremovable` on the producer |

**Why wrap by hand:** `quarkus-pooled-jms` only wraps automatically the factories created by Quarkus JMS *extensions* (Artemis, Qpid), via a build-time SPI (`ConnectionFactoryWrapperBuildItem`). A factory you produce yourself is invisible to that SPI. The extension does expose `PooledJmsWrapper` as an injectable bean, and `wrapConnectionFactory(cf)` returns a pool that picks up the `quarkus.pooled-jms.*` settings. Without the wrap, Camel opens a new MQ connection for every operation, just as Spring's `JmsTemplate` does without a caching factory.

**Camel finds it by type.** Camel Quarkus autowires a single `ConnectionFactory` bean into the `jms` component, so no `@Named` is needed. With two, give them `@Identifier("...")` and set `camel.component.jms.connection-factory: "#name"`.

**Credentials: on the Camel component, not the factory.** Both work in principle:
- **Camel component** `username`/`password` (this repo): Camel wraps the factory in Spring's `UserCredentialsConnectionFactoryAdapter`, which calls `createConnection(user, password)`.
- **On the `MQConnectionFactory`**: `setStringProperty(WMQConstants.USERID, …)`, `PASSWORD`, and `setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true)`. These are used by `createConnection()` *without* arguments.

The component option keeps the factory pure connection config and the secret in config. The trade-off: **anything that uses the factory without Camel** (plain JMS, Citrus) gets no credentials and fails with `MQRC 2035 NOT_AUTHORIZED`, which is the code the queue manager returns whenever credentials don't reach it. See the Citrus entry for the workaround.

**Notes:** the IBM MQ client is a plain library, so JVM mode only. There's no MQ Dev Service either: start `broker/` (`docker compose up -d`) before `quarkus:dev` or tests.

---

## Why does `quarkus.pooled-jms.transaction: xa` do nothing?

(Found on 2026-10-24; both keys have since been removed.)

**Why:** both keys assumed a JTA transaction manager, and there isn't one. `quarkus.transaction-manager.*` belongs to `quarkus-narayana-jta`, which isn't in the pom, hence the `Unrecognized configuration key` warning. `quarkus-pooled-jms` decides at **build time** whether a TransactionManager exists. Without one, `PooledJmsWrapper.createPool` skips the XA branch and builds a plain `JmsPoolConnectionFactory`, so `transaction: xa` was ignored *without* a warning (read in the 2.13.0 source).

**Adding `quarkus-narayana-jta` would make XA break the producer:** the XA pool needs an `XAConnectionFactory`, and `MQConnectionFactory` isn't one. You'd need `com.ibm.mq.jakarta.jms.MQXAConnectionFactory`, the transaction manager given to Camel, and recovery (an object store on disk, plus MQ authorities for the recovery connection). Spring mapping: `JtaTransactionManager` + Atomikos/Narayana versus a plain `JmsTransactionManager`.

**Do you need XA?** Only if one transaction must span JMS **and** another resource, typically "consume message + write to DB atomically". For JMS only, use local transacted sessions: `camel.component.jms.transacted: true` (applied).

### Follow-up: do I still get normal (Spring-style) JMS transactions?

Yes. **`camel-jms` is built on `spring-jms`**: the consumer is Spring's `DefaultMessageListenerContainer`, and the producer is `JmsTemplate`. `transacted: true` makes Camel create a Spring `JmsTransactionManager` lazily, so you get the Spring Boot behaviour you know:

- The message is consumed in a transacted session, and **commits when the route completes**.
- **An exception rolls the session back**, and the message is redelivered.
- Messages the route *sends* with `to("jms:...")` on the same `ConnectionFactory` join that local transaction, so "consume one, produce another" is atomic without XA.
- **Don't** add `.transacted()` to the route: on this classpath it fails at startup, and rollback works without it (see the listener entry).
- **Poison messages:** the IBM MQ JMS client counts redeliveries (`JMSXDeliveryCount` / backout count), and once the queue's `BOTHRESH` is exceeded it moves the message to `BOQNAME`. `DEV.JOBS.REQUEST` has `BOTHRESH(3)` → `DEV.JOBS.BACKOUT` (`broker/mqsc/30-quarqueueus.mqsc`). Without that, a failing message loops forever.

**What the XA advice in the docs is about:** Quarkus has **no `PlatformTransactionManager` abstraction**. Quarkus's `@Transactional` *always* means JTA (Narayana), unlike Spring, where you pick a transaction manager. The Camel Quarkus / pooled-jms docs describe how to join that JTA world:

| `quarkus.pooled-jms.transaction` | Meaning | Spring analogy |
|---|---|---|
| `disabled` (effectively today) | plain pool; Camel's local JMS tx works | `JmsTransactionManager` |
| `enabled` | JMS sessions enlist in Quarkus `@Transactional` (JTA), as a local resource | `JtaTransactionManager` with a single resource |
| `xa` | JMS joins 2-phase commit with other XA resources (e.g. a DB) | `JtaTransactionManager` + XA |

So XA is the right advice *when JMS must commit together with a database*. `MQXAConnectionFactory` is the same client with an XA interface on top, so that Narayana can run prepare/commit on the queue manager. For JMS-only routes, the local transaction is simpler, faster, and needs no recovery log.

---

# Camel messaging

## How do I write a Camel JMS listener, and how do I send messages to the queue?

**Spring mapping.** `camel-jms` is a layer over `spring-jms`, so most of what you know still applies. Only the programming model differs:

| Spring Boot | Camel Quarkus (this repo) |
|---|---|
| `@JmsListener(destination = "X")` method | a `RouteBuilder` with `from("jms:queue:X")`, which runs on a `DefaultMessageListenerContainer` |
| `JmsTemplate.convertAndSend("X", obj)` | `ProducerTemplate.sendBody("jms:queue:X", body)`, which runs on a `JmsTemplate` |
| `concurrency = "3-5"` | `?concurrentConsumers=3&maxConcurrentConsumers=5` on the endpoint URI |
| `spring.jms.listener.*` / container factory | `camel.component.jms.*` in `application.yaml` (credentials, `transacted: true`) |
| `MessageConverter` | Camel **type converters** for simple types (`String`), plus an explicit **data format** step (`.unmarshal().json(...)`) for JSON → object |

### The listener (applied)

`execution/messaging/JobRequestRoute` consumes `JobRequest.QUEUE`, logs `JobId` and the delivery count, unmarshals the JSON into the `JobRequest` record, and calls `.bean(JobWorker.class, "handle")`. Any `RouteBuilder` subclass is discovered at build time; the CDI scope is optional, but with it you can `@Inject` into the route. Keeping the logic in a plain CDI bean, with the route as just the wiring, is the closest match to a Spring `@JmsListener` method. Details on the body/header binding are in [the next entry](#the-job-request-message-jobrequest-bean-binding-and-headers).

Two things that only fail at **startup**, because Camel resolves routes when it creates them, not at build time (so only a test that boots the app catches them):
- `.bean(...)` needs `camel-quarkus-bean` (in the pom). Without it: `No bean-processor-factory service could be found in the classpath`.
- `.unmarshal().json(...)` needs `camel-quarkus-jackson` (in the pom).

**Transactions and rollback: don't add `.transacted()`.** It looks for a `TransactedPolicy` bean, then for a `PlatformTransactionManager` to wrap in `SpringTransactionPolicy` (from `camel-spring`). Neither exists here: the `JmsTransactionManager` that `transacted: true` creates belongs to the listener container and isn't a bean. So the route would fail at startup (checked in the Camel 4.22.1 jars). Rollback works without it:

1. `transacted: true` makes the listener container consume in a transacted session.
2. If the route fails (an exception in `unmarshal` or in the bean), Camel's default error handler marks the exchange failed. There's no Camel-level redelivery by default.
3. Camel's `EndpointMessageListener` sees a failed exchange on a transacted endpoint and **rethrows**, so the container rolls the session back, exactly as a Spring `@JmsListener` does when it throws.
4. MQ redelivers. After `BOTHRESH(3)`, the MQ JMS client moves the message to `DEV.JOBS.BACKOUT`.

What breaks this is error handling that **handles** the exception: `onException(...).handled(true)`, `deadLetterChannel(...)` or `doTry/doCatch`. Camel then counts the exchange as successful, the session **commits**, and the message is gone. Use those only when you want Camel, rather than MQ backout, to decide where failures go. `.transacted()` (JTA, via `camel-quarkus-jta` + Narayana) only becomes relevant once a database joins the unit of work.

**When the broker isn't running**, the container does *not* fail startup (`testConnectionOnStartup` defaults to false). It logs a connection failure and retries every 5 s, so every `@QuarkusTest` logs those warnings when the broker is down. To switch the consumer off in tests: `.autoStartup("{{jobs.consumer.enabled:true}}")` on the route, plus `"%test":` → `jobs.consumer.enabled: false` in `application.yaml`, and turn it back on in the Citrus test.

### Sending

**a) From inside a route:** `.to("jms:queue:DEV.JOBS.RESULT")`. Inside the consumer route, the send joins the consumer's local transaction, so "consume one, produce another" commits or rolls back as a unit.

**b) From application code:** inject `ProducerTemplate`, the counterpart of `JmsTemplate`. `jobs/messaging/JobSender.send(JobRequest)` (`@ApplicationScoped`) does this. Still open: nothing calls it yet (`JobsResourceImpl.createJob` returns `null`); see TODO §1. Until something injects it, ArC removes it at build time as an unused bean (unlike Spring, which creates every `@Component`), and that's harmless.

- **Send a `String` (JSON), not an object.** A non-String body becomes an `ObjectMessage` (Java serialization), which you don't want on a queue. `JobSender` serialises with Quarkus's `ObjectMapper`. With `camel-quarkus-jackson` now in the pom, `.marshal().json()` in a route is an option too.
- **`sendBody*` is fire-and-forget (InOnly).** `requestBody*` switches to request-reply (InOut): Camel creates a temporary reply queue and **blocks waiting for a reply** (20 s timeout by default). Easy to pick by accident from autocomplete.
- **Header names** must be valid JMS property identifiers: `JobId`, not `job-id` (Camel would encode the dash as `_HYPHEN_`). Values must be `String` or primitives; see the next entry.
- **Transaction scope:** called outside a route, the transacted `JmsTemplate` commits right after the send. This is **not** tied to a Quarkus `@Transactional` (JTA) on the REST method. Once jobs are also stored in a database, "saved but not queued" and the reverse become possible. That's the outbox / XA decision, for later.

**c) Plain Jakarta JMS without Camel** is possible: inject the `ConnectionFactory` and use `JMSContext`. But the credentials are on the Camel component, so you'd pass them yourself (`cf.createContext(user, password)`) or get `MQRC 2035`.

**d) By hand, for trying the listener out:** the MQ sample program, pointed at the project queue (`amqsputc` sends one message per input line, and an empty line ends it). The route now expects JSON, so the first line succeeds, and `boom` (not JSON) fails in `unmarshal`:

```shell
docker exec -e MQSERVER='DEV.APP.SVRCONN/TCP/localhost(1414)' -e MQSAMP_USER_ID=app quarqueueus-ibm-mq sh -c \
  'printf "passw0rd\n{\"jobId\":\"7f9c1b52-6a43-4c1e-9d0e-2b8f6a1c3d44\",\"type\":\"report-generation\",\"parameters\":{}}\nboom\n\n" | /opt/mqm/samp/bin/amqsputc DEV.JOBS.REQUEST QM1'
docker exec quarqueueus-ibm-mq sh -c "echo 'DISPLAY QLOCAL(DEV.JOBS.*) CURDEPTH' | runmqsc QM1"
```

Expect `boom` to be delivered 3 times, then `DEV.JOBS.BACKOUT` to have one more message. (The backout step needs `PASSALL` for `app`, set in `broker/mqsc/30-quarqueueus.mqsc`; see [broker/README.md → Backout](../broker/README.md#backout-why-your-own-queues-should-set-bothresh).) Messages sent with `amqsputc` have no `JobId` property, so the route logs `Job request null`. You can also put test messages from the web console (queue → Create message).

**IBM MQ detail:** Camel sends JMS messages with an `MQRFH2` header that carries the JMS properties. JMS consumers never notice, but a non-JMS consumer (a C/COBOL program, `amqsgetc`) sees it as junk in front of the body. For such receivers, address the queue as `jms:queue:queue:///DEV.X?targetClient=1` to send a plain MQ message.

---

## The job request message: `JobRequest`, bean binding and headers

(Merged from three entries of 2026-10-25: why `JobRequest` shouldn't extend the DTO, `.bean` binding and JMS properties, and which annotations to use.) Applied: `execution/JobRequest`, `JobSender.send(JobRequest)`, and the route with `.unmarshal().json(JobRequest.class).bean(...)`. Smaller `JobWorker` leftovers are in TODO §1.

### Why a separate record, not `extends CreateJobRequest`

**Ownership is about which way the dependencies point, not which package the file sits in.** `execution` defines the queue contract and depends on nothing; `jobs` (the sender) adapts to it. A `JobRequest extends CreateJobRequest` in `execution` would import the generated REST model, so the arrow points the wrong way, and:

1. **The wire format would still be the OpenAPI schema.** A change in `jexapi.yaml` would silently change the JSON on the queue, including for messages already queued or in backout.
2. **It adds nothing**, in particular no `jobId`, which the worker needs and the REST request doesn't have.
3. **It doesn't save the mapping step.** JAX-RS hands you a `CreateJobRequest`; you'd copy it into the subclass anyway, and that copy is the mapping.
4. **The worker would get a mutable, generated type** instead of a small immutable value.

Spring analogy: you wouldn't make a JMS/Kafka event class `extends` your `@RequestBody` DTO either.

The record: `record JobRequest(UUID jobId, String type, Map<String, String> parameters)` plus `QUEUE`. `type` is a `String` rather than the generated `TypeEnum`, to keep generated code out of `execution`. Jackson serialises records without annotations, in component order. Mapping belongs where the DTO is known, in `JobService` once it exists (TODO §1):

```java
jobSender.send(new JobRequest(job.getId(), body.getType().value(), body.getParameters()));
```

The `JobId` JMS property duplicates the body, but keep it: JMS selectors (`JobId = '…'`), log lines and MQ tooling can use it without parsing the body.

### Getting the record into the bean

Verified on 2026-10-25 with a throwaway probe in a scratch copy against `broker/`:

| Route | Result |
|---|---|
| `.bean(Worker.class, "handle")` with `handle(JobRequest)` | ❌ `NoTypeConversionAvailableException: No type converter available to convert from type: java.lang.String to the required type: …JobRequest` |
| `.unmarshal().json(JobRequest.class).bean(Worker.class, "handle")` (+ `camel-quarkus-jackson`) | ✅ `JobRequest[jobId=7f9c…, type=report-generation, parameters={year=2026}]` |

Bean binding converts the body to the parameter type with Camel's type converters, and Camel has no default `String → POJO` JSON converter. (Spring's `@JmsListener` would only do it with a `MappingJackson2MessageConverter` configured.) `unmarshal().json(...)` is an explicit data format step: afterwards the body *is* a `JobRequest`. Invalid JSON fails in `unmarshal`, inside the transacted route, so it goes to backout like any other failure. That failure only shows up per message, not at startup.

The alternative that needs neither `camel-quarkus-bean` nor `camel-quarkus-jackson`: `@Inject JobWorker worker; @Inject ObjectMapper mapper;` and `.process(e -> worker.handle(mapper.readValue(e.getMessage().getBody(String.class), JobRequest.class)))`. It's a compile-checked call, but it hides the parsing inside a lambda. This repo uses `.bean`.

The probe also showed that `.bean(JobWorker.class, …)` **calls the CDI bean**: values set by the route-invoked worker were visible through the bean `@Inject`ed in the test.

### Reading JMS properties (headers)

Camel copies every JMS property onto the message as a **header**: standard ones (`JMSMessageID`, `JMSXDeliveryCount`, …), IBM's (`JMS_IBM_*`), and custom ones (`JobId`). From the probe:

```
JMSMessageID=ID:414d…, JMSXDeliveryCount=1, JMSRedelivered=false, JMSDeliveryMode=2, JMSPriority=4,
JMSDestination=queue:///DEV.QUEUE.1, JMSXUserID=app, JMS_IBM_Format=MQSTR, JMS_IBM_PutDate=20261025, …,
JobId=h3, CamelMessageTimestamp=…
```

Ways to get them into the bean method (1–3 verified in the probe):

1. **Name them in the route:** `.bean(JobWorker.class, "handle(${body}, ${header.JobId})")` with a plain `handle(JobRequest request, String jobId)`. Keeps `JobWorker` free of Camel imports.
2. **Annotate a parameter:** `@Header("JobId") String jobId`, like Spring's `@Header` on a `@JmsListener` method.
3. **The whole map:** `@Headers Map<String, Object> headers`, with the route unchanged. Camel gives the body to the un-annotated (or `@Body`) parameter and the map to the `@Headers` one.
   - The map is the message's live, mutable header map: read it, don't change it (changes carry on down the route). Copy it with `new HashMap<>(headers)` if you keep it; `Map.copyOf` throws on the `null` values (e.g. `JMSType`).
   - Values are `Object`, so you have to cast. For the 1–2 headers you actually need, `@Header("JMSXDeliveryCount") Integer attempt` is clearer.
   - Camel-free variant: `"handle(${body}, ${headers})"` with a plain `Map` parameter (not run in the probe).

`jobId` is already in the body, so the worker doesn't need the `JobId` header. Use headers in the worker for metadata outside the contract, e.g. `JMSXDeliveryCount` to log "attempt 2 of 3".

**Custom property gotchas:**
- Names must be valid Java identifiers. Camel's default key strategy encodes `.` and `-` when sending and decodes them when receiving, so stick to names like `JobId`.
- Values must be `String`, primitives or their boxed types. A `UUID` header is dropped when sending (logged at DEBUG), which is why `JobSender` calls `.toString()`.
- Types survive the round trip: a `String` property comes back as `String`, an `int` as `Integer`.

### Which annotations belong on the bean method

All in `org.apache.camel` (checked in `camel-api-4.22.1`). They only matter when Camel calls the method through `.bean(...)`; a direct Java call ignores them. Spring equivalents are in brackets.

| Annotation | What it does | Verdict |
|---|---|---|
| `@Body` (parameter) | binds the body [`@Payload`] | Use it (`JobWorker` does). Optional, but explicit once there are several parameters. |
| `@Header("X")` (parameter) | one header, converted to the parameter type [`@Header`] | For the headers you actually need. |
| `@Headers` (parameter) | the whole header map [`@Headers`] | Fine for exploring (`JobWorker` uses it now); narrow it down later. |
| `@Handler` (method) | marks the method Camel calls | Optional. With it, the route can be `.bean(JobWorker.class)` without the `"handle"` string. `JobWorker` has it, but the route still names the method, which is harmless but redundant. |
| `@ExchangeProperty` / `@ExchangeProperties`, `@Variable` / `@Variables` | route-internal state set by earlier steps, **not** JMS properties | Not needed here. |
| `@ExchangeException` | the caught exception | For beans in `onException(...)` error handling. |
| `@Consume("jms:…")` | makes the method a consumer without a `RouteBuilder` [closest to `@JmsListener`] | Pick one style; you chose routes (with `log` and `unmarshal` steps). |
| `@Produce` / `@EndpointInject`, `@PropertyInject`, `@BindToRegistry` | producer injection, config injection, registry binding | Quarkus has idioms for all three: `@Inject ProducerTemplate`, `@ConfigProperty` / `@ConfigMapping`, and plain CDI beans, which Camel already sees. |

`@Body` and `@Handler` are standard Camel; unlike `@Header`/`@Headers`, they weren't in the probe. The app compiles and boots with them (`./mvnw verify`, 2026-10-25), but no test has sent a message through them yet.

### Side lesson: public fields on CDI beans

The probe first read a public field on an `@Inject`ed `@ApplicationScoped` bean and always got `null`, even though the route had set it. `@Inject` gives you a **client proxy**, a subclass instance with its own copies of the fields: method calls are forwarded to the real instance, but field reads aren't. Spring's scoped proxies behave the same way. So expose state through methods.

---

## How do I run a one-time action at startup (e.g. send one job)?

**Spring mapping:** `CommandLineRunner` / `ApplicationRunner` / `@EventListener(ApplicationReadyEvent.class)` become a **CDI observer method**. Quarkus has no runner interfaces; startup work is "observe an event".

| Quarkus | When it fires | Use for |
|---|---|---|
| `void x(@Observes StartupEvent e)`, or `@io.quarkus.runtime.Startup` on a bean | app is starting (≈ `ApplicationStartedEvent`) | general init that doesn't need Camel |
| `void x(@Observes CamelEvent.CamelContextStartedEvent e)` | the Camel context and its routes are started (≈ `ApplicationReadyEvent` for Camel) | **sending through Camel** |

Prefer the Camel event for sending: it guarantees the `jms` component and the consumer route are up. The Camel Quarkus event bridge switches on automatically when it finds an observer for a Camel event at build time.

Applied as `dev/SeedJobSender`: `@IfBuildProfile("dev")` plus an observer of `CamelContextStartedEvent` that sends one job. `@IfBuildProfile` **removes the bean at build time** in `test` and `prod` builds, so there's no flag to forget. Spring's `@Profile("dev")` is evaluated at runtime, and the class still ships.

Still open (TODO §1): it hard-codes `"jms:queue:DEV.JOBS.REQUEST"` instead of `JobRequest.QUEUE`, and it sends hand-written JSON without a `jobId` (the route accepts it, with `jobId` `null`). Build a `JobRequest` and serialise it, or call `JobService.submit` once it exists, so the seed goes through the same path as a REST call.

Gotchas:
- **"One-time" means once per start.** In `quarkus:dev`, every live reload restarts the app, so the observer fires again and you get one message per reload. Press `s` (force restart) in the dev-mode console to fire it on purpose.
- **The broker must be up.** Unlike the consumer, which retries in the background, a failed send throws inside the observer and **fails startup**. Wrap it in `try/catch` if you'd rather keep running.
- **`@Observes` doesn't make the bean eager**, but an observer method registers the bean, and an unused observer bean isn't removed. No `@Startup` or `@Unremovable` needed.
- **On demand instead of at startup:** `POST /jobs`, once `createJob` sends. A Camel `timer:` route (`from("timer:seed?repeatCount=1")`) needs `camel-quarkus-timer`; `@Scheduled` needs `quarkus-scheduler`.

---

# Testing

## How do I test the JMS flow with Citrus?

### What Citrus is, in Spring terms

In a `@SpringBootTest` you'd test JMS with `JmsTemplate.convertAndSend`, then `JmsTemplate.receive` and Awaitility. Citrus does the same thing as a declarative test DSL. You define **endpoints** (a queue plus a connection factory). Then a **`TestCaseRunner`** runs **actions**: `send`, `receive` (with timeout, selector, and body/header validation including `@matches(...)@`/`@ignore@` matchers), `purgeQueues`, `sleep`, and so on. Each action runs in order and fails the test with a diff. It's black-box: Citrus talks to the real broker, like an external system would.

### How it plugs into Quarkus (checked against the 5.1.0 jars)

- `@org.citrusframework.quarkus.CitrusSupport` is meta-annotated with `@QuarkusTestResource(CitrusTestResource.class, restrictToAnnotatedClass = true)`. So it's a Quarkus test resource, which is roughly a Spring `@ContextConfiguration` initializer plus a `TestExecutionListener`.
- `CitrusTestResource` boots a Citrus instance and injects fields annotated `@CitrusResource` of type `TestCaseRunner`, `TestContext` or `Citrus`. It also wires `@CitrusEndpoint` and `@BindToRegistry` members of the test class.
- `restrictToAnnotatedClass = true` means a class with `@CitrusSupport` gets **its own Quarkus boot**. Quarkus restarts the app whenever the set of test resources changes, just as Spring builds a new context when the context cache key changes. Group Citrus tests in a few classes.
- JUnit 5 comes from `quarkus-junit`, so **don't** add `citrus-junit-jupiter`. Its `@CitrusSupport` is a different annotation with the same name.
- The test-scope dependencies `citrus-quarkus` and `citrus-jms` are enough to start. `citrus-jms` uses a Spring `JmsTemplate` internally (`spring-jms` is already on the classpath via `camel-jms`).
- **JSON bodies:** without `org.citrusframework:citrus-validation-json`, Citrus logs `fallback to default text equals validation` and compares JSON as an exact string, so field order and whitespace matter. Add it (test scope, same version, or managed by `citrus-bom` from TODO §2) for order-insensitive JSON compare and `@ignore@` inside JSON. It isn't in your local repo yet, so check it resolves.

### The two snags specific to this repo

1. **Credentials aren't on the factory** (see the ConnectionFactory entry), so Citrus would get `MQRC 2035`. Wrap the injected bean with Spring's `UserCredentialsConnectionFactoryAdapter` in the test, as below. The alternative is to move credentials onto the MQ factory, so that every client picks them up, at the cost of changing the current setup.
2. **The app consumes the queue you want to inspect.** In a test that checks what was *sent* to `DEV.JOBS.REQUEST`, the `job-request` route competes with Citrus for the message. Stop the route for that test via `CamelContext.getRouteController()`, as below.

Also, `DEV.JOBS.BACKOUT` may still hold `boom` messages from manual runs. Purge it at the start of a test, or the test receives an old message.

### Test 1: a poison message ends up on the backout queue

This tests the route, the transaction and the broker's `BOTHRESH` together. `"boom"` isn't JSON, so it now fails in the route's `unmarshal` step before it reaches `JobWorker`.

```java
package xyz.kajih.quarqueueus.execution.messaging;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.jms.ConnectionFactory;
import org.citrusframework.TestCaseRunner;
import org.citrusframework.annotations.CitrusResource;
import org.citrusframework.jms.endpoint.JmsEndpoint;
import org.citrusframework.quarkus.CitrusSupport;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jms.connection.UserCredentialsConnectionFactoryAdapter;

import static org.citrusframework.actions.ReceiveMessageAction.Builder.receive;
import static org.citrusframework.actions.SendMessageAction.Builder.send;
import static org.citrusframework.jms.actions.PurgeJmsQueuesAction.Builder.purgeQueues;
import static org.citrusframework.jms.endpoint.JmsEndpoints.jms;

@QuarkusTest
@CitrusSupport
class JobRequestRouteIT {

    @CitrusResource
    TestCaseRunner t;

    @Inject
    ConnectionFactory appFactory;   // the pooled MQ factory from infra.mq, without credentials

    @ConfigProperty(name = "mq_app_user", defaultValue = "app")
    String user;

    @ConfigProperty(name = "mq.app.password")   // from MQ_APP_PASSWORD in .env
    String password;

    ConnectionFactory cf;
    JmsEndpoint requests;
    JmsEndpoint backout;

    @BeforeEach
    void endpoints() {
        var auth = new UserCredentialsConnectionFactoryAdapter();
        auth.setTargetConnectionFactory(appFactory);
        auth.setUsername(user);
        auth.setPassword(password);
        cf = auth;
        requests = jms().asynchronous().connectionFactory(cf).destination("DEV.JOBS.REQUEST").build();
        backout = jms().asynchronous().connectionFactory(cf).destination("DEV.JOBS.BACKOUT").build();
    }

    @Test
    void poisonMessageEndsUpOnBackoutQueue() {
        t.given(purgeQueues().connectionFactory(cf).queueNames("DEV.JOBS.BACKOUT"));
        t.when(send(requests).message().body("boom").header("JobId", "citrus-poison"));
        t.then(receive(backout).timeout(15_000)
                .message().body("boom").header("JobId", "citrus-poison"));
    }
}
```

- The endpoints are built in `@BeforeEach` rather than with `@BindToRegistry`, because they need the injected `appFactory`, and I wouldn't rely on Citrus processing `@BindToRegistry` after Quarkus has injected the test's fields. Passing the `Endpoint` object to `send(...)`/`receive(...)` needs no registry.
- `"boom"` is plain text, so the default text validator is fine here.
- `@ConfigProperty` works because a `@QuarkusTest` class is itself a CDI bean, and `.env` in the project root is a config source in the test profile too.

### Test 2: the sender puts the right message on the queue

`JobSender` is `@ApplicationScoped` now, so the test can inject it. Injecting it is also what keeps ArC from removing it as unused:

```java
    @Inject
    CamelContext camel;

    @Inject
    JobSender sender;

    @Test
    void senderPutsJobRequestOnQueue() throws Exception {
        camel.getRouteController().stopRoute("job-request");   // otherwise the route takes the message first
        try {
            var id = UUID.randomUUID();
            sender.send(new JobRequest(id, "report-generation", Map.of("year", "2026")));
            t.then(receive(requests).timeout(5_000)
                    .selector("JobId = '" + id + "'")
                    .message()
                    .header("JobId", id.toString())
                    .body("{\"jobId\":\"" + id + "\",\"type\":\"report-generation\",\"parameters\":{\"year\":\"2026\"}}"));
        } finally {
            camel.getRouteController().startRoute("job-request");
        }
    }
```

- The selector makes the test ignore leftovers on the queue, such as the dev seed job.
- With `citrus-validation-json` you can write `"jobId":"@ignore@"`, or `@matches('[0-9a-f-]{36}')@`, and stop caring about field order.
- Arguably this belongs in a `jobs` test package. Start with one class, and split when a shared base class or `ApplicationPropertiesSupplier` pays off.

### The happy path: what do you assert?

A valid message currently ends with log lines, which Citrus can't observe. There are two options:

- **Now: mock the worker.** Add `io.quarkus:quarkus-junit-mockito` (test scope, version from the Quarkus BOM), put `@InjectMock JobWorker worker;` in the test, send with Citrus, and check `verify(worker, timeout(5_000)).handle(any(), any())`. `@InjectMock` swaps the CDI bean behind its client proxy, and the probe showed that `.bean(JobWorker.class, …)` calls the CDI bean, so this should work. The mock itself hasn't been tested.
- **Later: assert on an outcome.** Once the worker reports status (job store via `JobService`, or a status/reply queue), the Citrus test can `receive` the status message, or poll `GET /jobs/{id}`. That's the real end-to-end test, and the reason Citrus is worth having over plain mocks.

### Running

- Name the classes `*IT` so that they only run under `./mvnw verify` (failsafe), like `JobApiPactIT`.
- The broker must be running (`broker/`, `docker compose up -d`), and `.env` must exist.
- Single class: `./mvnw verify -Dit.test=JobRequestRouteIT -Dtest=skip -Dsurefire.failIfNoSpecifiedTests=false`.

---

# Code structure

## Code and structure reviews (2026-10-24)

(Merged from the code review and package structure reviews, rounds 2 and 3. Open items live in TODO §1.)

### Where the structure ended up

```
xyz.kajih.quarqueueus
├── jobs/        rest/JobsResourceImpl, messaging/JobSender      (planned: Job, JobService)
├── execution/   JobRequest (record + QUEUE), JobWorker, messaging/JobRequestRoute
├── infra/mq/    MqConfig, MqConnectionFactoryProducer
└── dev/         SeedJobSender (@IfBuildProfile("dev")), GreetingResource (dev + test only)
generated: xyz.kajih.jexapi.{resources,model}   (target/, never edit)
```

Dependencies: `jobs → execution` (only `JobRequest`), `dev → jobs/execution`, and everything → `infra` only through CDI (`ConnectionFactory`). Nothing points back.

### Decisions and why

- **Package by feature plus an infrastructure package**, as you'd do in Spring Boot. The generator's `base-package` stays `xyz.kajih.jexapi`: generated code is a library the REST adapter uses, not part of the hand-written tree.
- **The consumer route lives in `execution`**, not `jobs`: it's execution's inbound adapter. `jobs` only sends, `execution` only receives, so the queue is a real boundary.
- **The queue has its own message type owned by `execution`** (the `JobRequest` entry above).
- **`jobs` needs a centre** (still open): `Job` (domain model, not the DTO) and `JobService` (`submit`/`find`/`list`, in-memory store at first). `JobsResourceImpl` maps DTO ↔ `Job` and calls `JobService`, and `JobService` calls `JobSender`, so the REST adapter never calls the messaging adapter directly.
- **No `internal/` packages**: Java doesn't enforce them, and at this size they're noise.
- **Dev-only code is removed at build time** with `@IfBuildProfile`, not switched off with a runtime flag.
- **Tests:** `JobApiPactIT` sits in the test `jobs` package, since it's a black-box test of the area, not of the adapter. Unit tests for `JobService` or `JobWorker` go in the class's own package, so they can reach package-private members.

### Lessons that still apply

- **A class needs a bean-defining annotation to be a CDI bean.** `@Inject` fields alone don't count. (Classes that declare producers or observers get an implicit `@Dependent`.) `JobSender` was missing one until 2026-10-25: `@Inject JobSender` would have failed the build, and `new JobSender()` leaves the fields `null`. Spring's component scan would need `@Component` too.
- **Unused beans are removed at build time.** An `@ApplicationScoped` bean that nothing injects (like `JobSender` today) is dropped by ArC. That's harmless until something looks it up dynamically (Camel by name/type, `CDI.current()`), which is why the MQ producer has `@Unremovable`.
- **Package-private `@Inject` fields** are the Quarkus idiom: ArC injects them without reflection.
- **Camel route errors show up at startup, not at build time** (the missing `camel-quarkus-bean` broke every `@QuarkusTest`). Keep at least one `@QuarkusTest` that boots the app.
- **`@IfBuildProfile("dev")` also removes beans from the test build.** That's why `GreetingResource` is `anyOf = {"dev", "test"}`, and why no `@QuarkusIntegrationTest` (prod profile) can see it.
- **Run `./mvnw clean` after moving packages**: stale `.class` files in `target/` break test discovery (`Could not load class …`).
- **The IBM MQ client writes `mqjms.log.*` to the working directory** (now gitignored). To move it: `-Dcom.ibm.msg.client.commonservices.log.outputName=target/mqjms.log`, in dev mode via `-Djvm.args=…`.
- **Tutorial comments from Claude's snippets** (e.g. `// Camel converts the TextMessage body to String`) explain Camel, not your code, and go stale. The one in `JobWorker` is now wrong.

### Tooling suggestion (TODO §5)

There's no formatter or static analysis, so the code standard is whatever the IDE does (mixed CRLF/LF line endings are one result). **Spotless** with `palantir-java-format` or `google-java-format`, running `spotless:check` in `verify`, is the common choice in Spring shops, and it's available for Gradle too. Error Prone or SpotBugs can come later.
