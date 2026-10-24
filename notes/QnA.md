# Q&A

Answers to questions about this repo and Quarkus, newest at the bottom. These are analysis notes: Claude explains and suggests, and the owner applies. Open work lives in [TODO.md](TODO.md), and finished work in [DONE.md](DONE.md).

## Index

| Status | Entry |
|---|---|
| ✅ applied | [Package structure review, round 3: app doesn't start](#package-structure-review-round-3-2026-10-24) |
| 🔴 open | [Package structure review, round 2](#package-structure-review-round-2-2026-10-24) |
| 🔴 open | [Code review 2026-10-24: code standard, bad code, packages](#code-review-2026-10-24-code-standard-bad-code-package-structure) |
| 🔴 open | [How do I run a one-time action at startup (send one job)?](#how-do-i-run-a-one-time-action-at-startup-eg-send-one-job) |
| 🔴 open | [How do I write a Camel JMS listener and send messages?](#how-do-i-write-a-camel-jms-listener-and-how-do-i-send-messages-to-the-queue) |
| 🔴 open | [Why is the `GET /jobs` endpoint wrong?](#why-is-the-get-jobs-endpoint-wrong-and-how-do-i-fix-it) |
| 🔴 open | [How do I return 201 / 404 / 409 from a generated interface?](#how-do-i-return-201--404--409-when-the-generated-interface-returns-a-plain-dto) |
| ✅ applied | [What is Camel's `SECURITY WARNING: … plain-text value`?](#what-is-camels-security-warning-secret-secret-property-is-configured-with-plain-text-value) (+ Camel's masking rule) |
| ✅ applied | [Why does `quarkus.pooled-jms.transaction: xa` do nothing?](#why-does-quarkuspooled-jmstransaction-xa-do-nothing) (+ local vs JTA/XA transactions in Camel) |
| ✅ applied | [How do I keep local secrets out of git (`.env` vs Vault)?](#how-do-i-keep-local-secrets-passwords-credentials-out-of-git-do-i-need-a-env-plugin-or-vault) |
| ✅ applied | [How do I provide the IBM MQ `ConnectionFactory` for Camel JMS?](#how-do-i-provide-the-ibm-mq-connectionfactory-for-camel-jms-with-pooling-and-credentials) |
| ✅ applied | [Build fails with `NoClassDefFoundError: …/media/Schema` after adding Camel](#why-does-the-build-fail-with-noclassdeffounderror-ioswaggerv3oasmodelsmediaschema-after-adding-camel) |

🔴 open means the issue still exists in the code. ✅ applied means the fix is in, and the entry is kept as background reading.

---

## Why is the `GET /jobs` endpoint wrong, and how do I fix it?

**The mismatch.** There are three sources of truth, and they don't agree:

| Source | `GET /jobs` returns |
|---|---|
| `src/main/resources/openapi/jexapi.yaml` | a bare JSON array of `Job`: `[ {...} ]` |
| `JexSrv.listJobs` (via generated `JobsResource`) | `List<Job>` → a bare array, plus one hard-coded job |
| `src/test/resources/pacts/quarqueueus_client-JexApi.json` | an object wrapping an empty array: `{"jobs": []}` |

`JobApiPactIT` replays the pact against the running app, so it sees `[ {job} ]` where it expected `{"jobs": []}` and fails.

In consumer-driven contract testing, the **pact describes what the consumer actually relies on**. So the real question is: what does the `quarqueueus_client` consumer code expect? The pact metadata says it was produced by `pact_rust` / a Rust consumer test, so the source of the pact is some Rust consumer test, not this repo.

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
2. Rebuild (`./mvnw compile` or just let `quarkus:dev` reload). The generator will change `JobsResource.listJobs` to return `JobList`, and `JobsResourceImpl` will stop compiling until you update it to return `new JobList().jobs(...)`. That compile error is the API-first workflow doing its job.
3. You still have the empty-list vs hard-coded-job problem from Option A step 2.

**Coming from Spring:**
- **Code generation:** in Spring you'd run `openapi-generator-maven-plugin` with the `spring` generator and `interfaceOnly=true`, then write a `@RestController` that implements the interface. Here a Quarkus *extension* does the generation during Quarkus's own code-gen step, configured in `application.yaml` instead of the POM. That's why there's no generator plugin in `pom.xml`.
- **Pact test setup:** `JobApiPactIT` is the counterpart of a Spring `@SpringBootTest(webEnvironment = RANDOM_PORT)` provider test. `@State` works the same way, since both use pact-jvm's JUnit 5 support. One difference: Quarkus tests start on a fixed port (`quarkus.http.test-port`, default 8081) rather than a random one.
- **No `@RestController` needed:** `JobsResourceImpl` has no stereotype annotation. Quarkus REST finds it because it implements a `@Path`-annotated interface.

**My recommendation:** Option B plus a provider state. Wrapping is the more future-proof API shape, it makes the existing pact pass without touching the consumer, and wiring up `@State` teaches the most about how Pact provider verification works in Quarkus.

---

## How do I return 201 / 404 / 409 when the generated interface returns a plain DTO?

Because `JobsResource` methods return `Job`/`JobResult` directly:

- **Returning an object** gives 200, so `createJob` currently returns 200 instead of 201.
- **Returning `null`** gives **204 No Content**, which is what the stubs do now. *Spring gotcha:* Spring MVC would give 200 with an empty body here.

Spring → Quarkus REST mapping for this question:

| Spring MVC | Quarkus REST (JAX-RS) |
|---|---|
| `ResponseEntity<T>` | `RestResponse<T>` (typed) or `jakarta.ws.rs.core.Response` (untyped) |
| `@ResponseStatus(HttpStatus.CREATED)` | `@org.jboss.resteasy.reactive.ResponseStatus(201)` |
| `@ExceptionHandler` in a `@ControllerAdvice` | `@ServerExceptionMapper` method in any bean (or a classic JAX-RS `ExceptionMapper<E>` `@Provider`) |
| `ResponseStatusException` | `WebApplicationException` and its subclasses (`NotFoundException`, ...) |

Ways to fix it, without changing the generated code:

- **Errors (404/409):** throw an exception from `JobsResourceImpl` and map it to a response. Quarkus REST lets you write a method annotated `@ServerExceptionMapper` (in any bean) that takes e.g. a custom `JobNotFoundException` and returns `RestResponse<Error>` with status 404 and the spec's `Error` body. You could also throw `jakarta.ws.rs.NotFoundException`, but then the body won't match the `Error` schema.
- **201 on create:** Quarkus REST has `@org.jboss.resteasy.reactive.ResponseStatus(201)`. Put it on `JexSrv.createJob` and verify it takes effect: JAX-RS annotation inheritance rules say that if the implementing method has its own JAX-RS annotations, the interface's are ignored, so check that `@POST`/`@Produces` still apply. A test asserting `.statusCode(201)` will tell you. A proper 201 should also set a `Location: /jobs/{id}` header, which needs `Response`/`RestResponse` as the return type.
- **Generator side:** the cleaner long-term fix is to have the generated interface return `Response`/`RestResponse`, so the implementation controls status and headers. Check the `quarkus-openapi-generator-server` docs for an option to do this. I haven't verified one exists for the version used here (2.23.0).

---

## Why does the build fail with `NoClassDefFoundError: io/swagger/v3/oas/models/media/Schema` after adding Camel?

The error has nothing to do with `jexapi.yaml`. `./mvnw compile` fails in Quarkus's `generate-code` step, inside `quarkus-openapi-generator-server`:

```
Quarkus code generation phase has failed: InvocationTargetException: io/swagger/v3/oas/models/media/Schema
Caused by: java.lang.ClassNotFoundException: io.swagger.v3.oas.models.media.Schema
    at org.openapitools.codegen.CodegenConfigLoader.forName
```

**Root cause: the `quarkus-camel-bom` import.** The generator depends on `org.openapitools:openapi-generator` → `io.swagger.parser.v3:swagger-parser`, which normally brings in `io.swagger.core.v3:swagger-core` and `swagger-models` (the `Schema` class lives in `swagger-models`). The Camel Quarkus BOM manages `swagger-parser` **with exclusions** for `swagger-core` and `swagger-models`. Camel uses the `-jakarta` variants (`swagger-models-jakarta`) and adds them explicitly in its own OpenAPI extensions. Because `quarkus-bom` doesn't manage `swagger-parser` at all, Camel's entry is the one Maven uses, and the two jars silently disappear from the generator's build-time classpath. `./mvnw quarkus:dependency-tree` shows the gap: `swagger-parser-v3` is listed but nothing under it from `io.swagger.core.v3`.

Spring analogy: this is the same problem as importing a second BOM next to `spring-boot-dependencies` and having its `<dependencyManagement>` override a transitive dependency for you. Quarkus makes it slightly harder to spot because the generator runs on the *build-time* classpath (deployment dependencies), which `mvn dependency:tree` doesn't show. Use `./mvnw quarkus:dependency-tree` instead.

**Fix (verified on a scratch copy: compiles and generates `JobsResource`):** a `<dependencyManagement>` entry declared directly in your own pom replaces any imported entry for the same artifact, exclusions included. Add this inside `<dependencyManagement><dependencies>`, after the two BOM imports:

```xml
<!-- quarkus-camel-bom excludes swagger-core/models from swagger-parser,
     which breaks quarkus-openapi-generator-server's codegen -->
<dependency>
    <groupId>io.swagger.parser.v3</groupId>
    <artifactId>swagger-parser</artifactId>
    <version>2.1.45</version>
</dependency>
```

Keep the version in line with what `quarkus:dependency-tree` shows the generator using (2.1.45 for generator extension 2.23.0), and re-check it when you bump either the generator extension or the platform.

Alternatives I'd avoid:

- **Dropping the Camel BOM** and giving `camel-quarkus-jms` an explicit version: this goes against how Quarkus platform members are meant to be aligned.
- **Adding `swagger-models` as a normal dependency:** this works, but it puts a codegen-only library on your runtime classpath.

---

## How do I provide the IBM MQ `ConnectionFactory` for Camel JMS (with pooling and credentials)?

### Spring → Quarkus mapping

| Spring Boot | Quarkus |
|---|---|
| `@Configuration` + `@Bean ConnectionFactory` | a class with a `@Produces` method (CDI producer). Same idea, but *you* pick the scope (`@ApplicationScoped` ≈ singleton) |
| `@ConfigurationProperties(prefix = "mq")` | `@ConfigMapping(prefix = "mq")` on an **interface** (method names map to kebab-case keys) |
| `mq-jms-spring-boot-starter` auto-config | **nothing**: there's no IBM MQ extension, so you write the producer yourself |
| `JmsPoolConnectionFactory` / `CachingConnectionFactory` bean | `quarkus-pooled-jms`, but see below: it doesn't wrap your own bean automatically |
| beans never removed | **ArC removes unused beans at build time.** A bean that only Camel looks up at runtime can be removed, so mark it `@Unremovable` |

### 1. Config mapping

```java
package xyz.kajih.quarqueueus.mq;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "mq")
public interface MqConfig {
    String host();

    int port();

    String queueManager();   // mq.queue-manager

    String channel();
}
```

### 2. Producer

Class names checked against `com.ibm.mq.jakarta.client` 10.0.1.0 and `quarkus-pooled-jms` 2.13.0 in your local repo:

```java
package xyz.kajih.quarqueueus.mq;

import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import io.quarkiverse.messaginghub.pooled.jms.PooledJmsWrapper;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import xyz.kajih.quarqueueus.infra.mq.MqConfig;

@ApplicationScoped
public class MqConnectionFactoryProducer {

    @Produces
    @ApplicationScoped
    @Unremovable
    ConnectionFactory mqConnectionFactory(MqConfig mq, PooledJmsWrapper pool) throws JMSException {
        var cf = new MQConnectionFactory();
        cf.setHostName(mq.host());
        cf.setPort(mq.port());
        cf.setQueueManager(mq.queueManager());
        cf.setChannel(mq.channel());
        cf.setTransportType(WMQConstants.WMQ_CM_CLIENT);   // TCP client, not local bindings
        return pool.wrapConnectionFactory(cf);
    }
}
```

**Why wrap by hand:** `quarkus-pooled-jms` only wraps automatically the factories created by Quarkus JMS *extensions* (Artemis, Qpid), via a build-time SPI (`ConnectionFactoryWrapperBuildItem`). A `ConnectionFactory` you produce yourself is invisible to that SPI. The extension does expose `PooledJmsWrapper` as an injectable synthetic bean, and `wrapConnectionFactory(cf)` returns a pool that picks up the `quarkus.pooled-jms.*` settings, such as `max-connections`. Without the wrap, adding the dependency does nothing, and Camel opens a new MQ connection for every operation. Spring's `JmsTemplate` has the same problem without a caching factory.

**Camel finds it by type.** Camel Quarkus autowires a single `ConnectionFactory` bean from CDI into the `jms` component, so no `@Named` is needed. If you ever have two, give them `@Identifier("...")` and set `camel.component.jms.connection-factory: "#name"`.

### 3. Config

```yaml
mq:
  host: localhost
  port: 1414
  queue-manager: QM1
  channel: DEV.APP.SVRCONN

camel:
  component:
    jms:
      username: app
      password: ${MQ_APP_PASSWORD:passw0rd}

quarkus:
  pooled-jms:
    max-connections: 1   # MQ connections are heavy; one connection, many sessions
```

A route then just uses `from("jms:queue:DEV.QUEUE.1")` / `.to("jms:queue:DEV.QUEUE.2")`.

### 4. Credentials: Camel component vs `ConnectionFactory`

You found that the user and password only worked when set on the Camel JMS component, not on the factory. I didn't know that as a rule, and the documentation says both should work:

- **Camel component** `username`/`password`: Camel wraps the factory in Spring's `UserCredentialsConnectionFactoryAdapter`, which calls `createConnection(user, password)`. Explicit credentials passed on the call always win.
- **On the `MQConnectionFactory`**: `cf.setStringProperty(WMQConstants.USERID, ...)`, `cf.setStringProperty(WMQConstants.PASSWORD, ...)` and `cf.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true)`. These are used when the client calls `createConnection()` *without* arguments.

So if setting them on the factory failed, something specific to your setup is the likely cause, not MQ or Camel in general. Candidates worth checking:

- Camel used a different factory from the one you configured. With pooled-jms, this happens if the pool wraps another instance, or the bean you set up wasn't the one autowired.
- Credentials were set on the Camel component too, possibly left over from an earlier attempt, and overrode the factory's.
- The setters were called on the wrong object. For example, they were set on the pool wrapper rather than the `MQConnectionFactory` itself.

If you still have that code, show it and I'll find out which. Either way, **keeping the credentials on the Camel component is a good choice**: the factory stays pure connection config, and the secret stays in config, where it can come from an env var or a vault. The producer then has nothing to read secrets from.

The `MQRC_NOT_AUTHORIZED` (2035) reason code is what you get from the queue manager when the credentials don't reach it. With the dev image, `app` has to authenticate on `DEV.APP.SVRCONN`.

### Notes

- **JVM mode only:** the IBM MQ client isn't prepared for native image.
- **No Dev Service:** Quarkus won't start MQ for you the way it does for Artemis. Start `broker/` with `docker compose up -d` before `quarkus:dev` or tests.
- **Package:** put the producer in its own package (e.g. `xyz.kajih.quarqueueus.mq`), not in `xyz.kajih.jexapi`, which is the generator's.

---

## How do I keep local secrets (passwords, credentials) out of git? Do I need a `.env` plugin or Vault?

**You don't need a plugin: Quarkus reads `.env` itself.** SmallRye Config (Quarkus's config system, the counterpart of Spring's `Environment`) includes a `.env` config source. A `.env` file in the working directory is read as if its entries were environment variables, and in dev mode (`quarkus:dev` / `quarkusDev`) that means the **project root**. A Gradle `.env` plugin only sets env vars for the Gradle JVM. `quarkusDev` launches the app in a separate JVM, which is probably why the plugin had no effect there.

`.env` is already in this repo's `.gitignore` (and `*.env.local`).

### How keys map

Env var names follow the usual rule: uppercase, and every non-alphanumeric character becomes `_`. It's the same as Spring's relaxed binding, but Quarkus resolves the name *from* the property name, so a placeholder or a mapped property both work:

```properties
# .env (project root, not committed)
MQ_APP_PASSWORD=passw0rd                 # used by ${MQ_APP_PASSWORD:...} placeholders
CAMEL_COMPONENT_JMS_PASSWORD=passw0rd    # or override camel.component.jms.password directly
_DEV_MQ_HOST=localhost                   # profile-specific: %dev.mq.host
```

With the config from the ConnectionFactory entry above, `password: ${MQ_APP_PASSWORD}` (drop the `:passw0rd` default once `.env` exists) picks it up. A missing value then fails at startup instead of silently using a default, which is what you want for secrets. Commit a `.env.example` with the keys and dummy values so others know what to create.

Precedence, highest first: system properties, then real env vars, then `.env`, then `application.yaml`. CI or a container can set real env vars and override `.env` without touching any file.

**Alternative:** `config/application.yaml` in the working directory overrides the packaged one. It's useful for a whole block of local settings, not only secrets. Gitignore it if you use it.

### Where Vault fits

`quarkus-vault` (Quarkiverse) adds HashiCorp Vault as a **config source**. `${...}` properties are resolved from Vault KV secrets at startup, and it also does dynamic database credentials. It has a **Dev Service**: in dev and test mode it starts a Vault container for you, much like Testcontainers does for Spring. It's the right tool for shared or deployed environments, but overkill for a local dev password for a dev broker.

Other secret backends work the same way, as config sources: Kubernetes Secrets (`quarkus-kubernetes-config`), and AWS Secrets Manager / Azure Key Vault (Quarkiverse). Quarkus can also encrypt values inside the config file (`${enc:...}`, `quarkus config encrypt` in the CLI). That keeps a ciphertext in git, but you still have to manage the key somewhere.

**For this repo:** use `.env` locally, real env vars in CI and containers, and Vault or the platform's secret store when the service is deployed somewhere real. The application code and `application.yaml` stay the same in all three, because only the config source changes. Spring Cloud Vault sets up the same split.

---

## Why does `quarkus.pooled-jms.transaction: xa` do nothing?

Found while rescanning on 2026-10-24. `./mvnw test` logs at startup:

```
WARN [io.quarkus.config] Unrecognized configuration key "quarkus.transaction-manager.enable-recovery" was provided; it will be ignored
```

**Why:** both keys assume a JTA transaction manager, and there isn't one. `quarkus.transaction-manager.*` belongs to `quarkus-narayana-jta`, which isn't in the pom, hence the warning. `quarkus-pooled-jms` decides at **build time** whether a TransactionManager exists. Without one, `PooledJmsWrapper.createPool` skips the XA branch and builds a plain `JmsPoolConnectionFactory`, so `transaction: xa` is ignored *without* a warning. I read this in the 2.13.0 source. So today you have plain pooling and no transactions, which happens to be what works.

**If you did add `quarkus-narayana-jta`**, XA would *break* the current producer. The XA pool needs an `XAConnectionFactory`, and `MQConnectionFactory` isn't one. You'd have to build `com.ibm.mq.jakarta.jms.MQXAConnectionFactory` instead, give Camel the transaction manager, and deal with recovery: the object store on disk, plus MQ authorities for the recovery connection.

Spring mapping: this is the `JtaTransactionManager` + Atomikos/Narayana setup versus a plain `JmsTransactionManager`.

**Do you need XA?** Only if one transaction has to span JMS **and** another resource, typically "consume message + write to DB atomically". For JMS only, use local transacted sessions:

```yaml
camel:
  component:
    jms:
      transacted: true   # Camel lazily creates a JmsTransactionManager for the route
```

A message is then only acknowledged when the route completes, and a failure rolls it back to the queue. After the queue's backout threshold, MQ moves it to the backout or dead-letter queue.

**Fix:** remove `quarkus.transaction-manager.enable-recovery` and `quarkus.pooled-jms.transaction`. Use `transacted: true` when you write the consuming route, and revisit XA if a database joins the same unit of work.

### Follow-up: do I still get normal (Spring-style) JMS transactions?

Yes. **`camel-jms` is built on `spring-jms`**: the consumer is Spring's `DefaultMessageListenerContainer`, and the producer is `JmsTemplate`. Setting `transacted: true` makes Camel create a Spring `JmsTransactionManager` lazily. So you get exactly the Spring Boot behaviour you know, inside Quarkus:

- The message is consumed in a transacted session, and **commits when the route completes**.
- **An exception rolls the session back** and the message is redelivered.
- Messages the route *sends* with `to("jms:...")` on the same `ConnectionFactory` join that local transaction, so "consume one, produce another" is atomic without XA.
- ~~Put `.transacted()` in the route.~~ **Corrected:** don't. On this classpath it fails at startup (no `TransactedPolicy`), and rollback works without it. See [Camel JMS listener](#how-do-i-write-a-camel-jms-listener-and-how-do-i-send-messages-to-the-queue).
- **Poison messages:** the IBM MQ JMS client counts redeliveries (`JMSXDeliveryCount` / backout count), and once the queue's `BOTHRESH` is exceeded it moves the message to `BOQNAME`. Check what the dev queues have: in `runmqsc QM1`, run `DISPLAY QLOCAL(DEV.QUEUE.1) BOTHRESH BOQNAME`. If they aren't set, a failing message loops forever.

**What the XA advice in the docs is about:** Quarkus has **no `PlatformTransactionManager` abstraction**. Quarkus's `@Transactional` *always* means JTA (Narayana), unlike Spring, where you pick `JmsTransactionManager`, `DataSourceTransactionManager` or `JtaTransactionManager`. The Camel Quarkus / pooled-jms docs describe how to join *that* JTA world:

| `quarkus.pooled-jms.transaction` | Meaning | Spring analogy |
|---|---|---|
| `disabled` (effectively today) | plain pool; Camel's local JMS tx works | `JmsTransactionManager` |
| `enabled` | JMS sessions enlist in Quarkus `@Transactional` (JTA), as a local resource | `JtaTransactionManager` with a single resource |
| `xa` | JMS joins 2-phase commit with other XA resources (e.g. a DB) | `JtaTransactionManager` + XA |

So enabling XA is the right advice *when JMS must commit together with a database*. `MQXAConnectionFactory` isn't a different way of talking to MQ. It's the same client, with an XA interface on top, so an external coordinator (Narayana) can run prepare/commit on the queue manager as one participant in a 2-phase commit. For JMS-only routes, the local transaction above is simpler, faster, and needs no recovery log.

---

## What is Camel's `SECURITY WARNING: [secret] Secret property is configured with plain-text value`?

Logged at startup for `camel.component.jms.username` and `camel.component.jms.password`.

**Why it appears:** Camel marks some component options as secrets (`username`/`password` on JMS among them). At startup, Camel Main checks whether each secret was configured as a *placeholder* or as a literal value. Our `application.yaml` uses `password: ${MQ_APP_PASSWORD}`, which is a **Quarkus/SmallRye** placeholder. Quarkus resolves it *before* Camel ever reads the property, so Camel only sees the resolved value and concludes it's plain text. `username: app` really is a literal. Nothing is leaked; the warning is about how the config *looks* to Camel.

**Fix: use Camel's placeholder syntax `{{...}}` with a lower-case, dotted key ending in `password`.** Quarkus leaves `{{...}}` alone, so Camel sees the placeholder (no warning) and resolves it itself. Camel Quarkus's property resolver reads Quarkus config, and SmallRye maps `mq.app.password` to the env-style name `MQ_APP_PASSWORD`, so the existing `.env` entry still works unchanged:

```yaml
camel:
  component:
    jms:
      username: "{{mq.app.user:app}}"     # Camel default syntax is key:default
      password: "{{mq.app.password}}"     # resolved from MQ_APP_PASSWORD in .env
```

Quote the values, because `{` starts a flow mapping in YAML.

⚠ **Don't use `{{MQ_APP_PASSWORD}}` (the env-style name) as the key.** I first suggested that, and it **leaks the password into the startup log**. Camel Main logs a "Property-placeholders summary" at INFO with every placeholder it resolved, and it masks only keys it recognises as sensitive. `MQ_APP_PASSWORD` isn't recognised; `mq.app.password` is. Tested on 2026-10-24:

| Config | Security warning | Startup log |
|---|---|---|
| `${MQ_APP_PASSWORD}` (Quarkus placeholder) | yes | `camel.component.jms.password = xxxxxx` |
| `"{{MQ_APP_PASSWORD}}"` | no | **`MQ_APP_PASSWORD = passw0rd`**: leaked |
| `"{{mq.app.password}}"` | no | `mq.app.password = xxxxxx` |

A `@QuarkusTest` reading `JmsComponent.getConfiguration()` confirmed that the last variant resolves the username to `app` and the password to the `.env` value.

**The exact masking rule**, from `org.apache.camel.util.SensitiveUtils.containsSensitive` (camel-util 4.22.1): take the part of the key **after the last `.`**, lowercase it, remove `-`, and check for an **exact match** against a list of 94 keywords: `password`, `secret`, `token`, `user`, `username`, `passphrase`, `accesskey`, …. So case doesn't matter, but the final dotted segment does:

| Key | Last segment, normalised | Masked? |
|---|---|---|
| `mq.app.password` | `password` | ✅ |
| `MQ.APP.PASSWORD` | `password` | ✅ |
| `MQ_APP_PASSWORD` | `mq_app_password` (no `.`, `_` not stripped) | ❌ |
| `mq.app-password` | `apppassword` | ❌ |
| `mq.app.passwd` | `passwd` (not in the list) | ❌ |

Rule of thumb: **name secrets `<something>.password` / `.secret` / `.token`**. It's also the idiomatic Quarkus property style, and SmallRye still finds `MQ_APP_PASSWORD` in `.env` or in the real environment. `user`/`username` are on the list too, which is why the plain `username: app` triggered the security warning.

**Final config chosen (2026-10-24):** the username should be *visible* and the password masked:

```yaml
      username: "{{mq_app_user:app}}"   # no dot → "mq_app_user" not sensitive → printed
      password: "{{mq.app.password}}"   # last segment "password" → masked
```

Tested: without `MQ_APP_USER` in `.env`, the default `app` is used, and the summary lists only `mq.app.password = xxxxxx` (defaults aren't listed). With `MQ_APP_USER=probeuser`, the user resolves to `probeuser` and the summary shows `mq_app_user = probeuser`. SmallRye finds the `.env` entry because its env lookup upper-cases the name after replacing non-alphanumerics with `_`.

Belt and braces: `camel.main.auto-configuration-log-summary: false` turns both summaries off entirely (also tested). That's a reasonable default for any environment where logs are shipped somewhere, because the summary is only useful while debugging config.

**Don't** use `{{env:MQ_APP_PASSWORD}}`, the form the warning text suggests. Camel's `env:` function reads real OS environment variables only, and `.env` entries aren't OS env vars. They're a Quarkus config source.

Spring analogy: it's as if Spring Boot warned about `spring.datasource.password=secret`, but not about `${DB_PASSWORD}`. The difference is that here two placeholder systems are stacked, and only the inner one (Camel's) is visible to the check.

---

## How do I write a Camel JMS listener, and how do I send messages to the queue?

**Spring mapping.** `camel-jms` is a layer over `spring-jms` (`spring-jms-7.0.9` is on the classpath), so most of what you know still applies. Only the programming model is different:

| Spring Boot | Camel Quarkus (this repo) |
|---|---|
| `@JmsListener(destination = "X")` method | a `RouteBuilder` with `from("jms:queue:X")`, which runs on a `DefaultMessageListenerContainer` |
| `JmsTemplate.convertAndSend("X", obj)` | `ProducerTemplate.sendBody("jms:queue:X", body)`, which runs on a `JmsTemplate` |
| `concurrency = "3-5"` | `?concurrentConsumers=3&maxConcurrentConsumers=5` on the endpoint URI |
| `spring.jms.listener.*` / container factory | `camel.component.jms.*` in `application.yaml` (already sets credentials and `transacted: true`) |
| `MessageConverter` | Camel type converters: ask for the body as the type you want (`getBody(String.class)`, or a `String` bean parameter) |

### 1. The listener (consumer route)

Any `RouteBuilder` subclass is discovered at build time. The CDI scope is optional, but with it you can `@Inject` into the route:

```java
package xyz.kajih.quarqueueus.jobs;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import xyz.kajih.quarqueueus.execution.JobWorker;

@ApplicationScoped
public class JobRequestRoute extends RouteBuilder {

    @Override
    public void configure() {
        from("jms:queue:DEV.JOBS.REQUEST")
                .routeId("job-request")
                .log("Job request ${header.JMSMessageID} (delivery ${header.JMSXDeliveryCount}): ${body}")
                .bean(JobWorker.class, "handle");
    }
}
```

```java
package xyz.kajih.quarqueueus.jobs;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class JobWorker {
    public void handle(String body) {          // Camel converts the TextMessage body to String
        if (body.contains("boom")) {
            throw new IllegalStateException("poison: " + body);
        }
        // real work goes here
    }
}
```

Keeping the logic in a plain CDI bean, with the route as just the wiring, is the closest match to a Spring `@JmsListener` method, and you can unit-test the bean without Camel. `.bean(JobWorker.class, ...)` looks the bean up in CDI. You can also `@Inject JobWorker worker` into the route and use `.bean(worker, "handle")`.

⚠ **Correction (2026-10-24):** `.bean(...)` needs the **`camel-quarkus-bean`** extension, which isn't in the pom. Without it the app fails at startup with `No bean-processor-factory service could be found in the classpath. Add camel-bean to classpath.` Either add the dependency, or skip the bean EIP and call the injected bean yourself. That works with what's on the classpath (verified):

```java
@Inject
JobWorker worker;
// ...
        .process(exchange -> worker.handle(exchange.getMessage().getBody(String.class)));
```

An exception thrown from `process` fails the exchange just as a bean would, so the rollback behaviour below is unchanged.

**Transactions and rollback: don't add `.transacted()`.** The earlier [local-transactions follow-up](#follow-up-do-i-still-get-normal-spring-style-jms-transactions) and TODO said to add it. That was wrong for this classpath (checked in the Camel 4.22.1 jars). `.transacted()` looks for a `TransactedPolicy` bean. Failing that, it looks for a `PlatformTransactionManager` bean and wraps it in `SpringTransactionPolicy`, which lives in `camel-spring`. Neither exists here: the `JmsTransactionManager` that `transacted: true` creates belongs to the listener container and isn't registered as a bean. So the route would fail at startup. You don't need it anyway:

1. `transacted: true` makes the listener container consume in a transacted session.
2. If the route fails, Camel's default error handler marks the exchange failed (no Camel-level redelivery by default).
3. Camel's `EndpointMessageListener` sees a failed exchange on a transacted endpoint and **rethrows**, so the container rolls the session back, exactly as a Spring `@JmsListener` does when it throws.
4. MQ redelivers. After `BOTHRESH(3)` failed deliveries, the MQ JMS client moves the message to `DEV.JOBS.BACKOUT` (see `broker/mqsc/30-quarqueueus.mqsc`).

What breaks this is any error handling that **handles** the exception: `onException(...).handled(true)`, `deadLetterChannel(...)` or `doTry/doCatch`. Camel then counts the exchange as successful, and the session **commits**, so the message is gone. Use these only when you want Camel, rather than MQ backout, to decide where failures go. `.transacted()` (JTA, via `camel-quarkus-jta` + Narayana) only becomes relevant once a database joins the unit of work. See [the XA entry](#why-does-quarkuspooled-jmstransaction-xa-do-nothing).

**When the broker isn't running**, the container does *not* fail startup (`testConnectionOnStartup` defaults to false). It logs a connection failure and retries every 5 s. That means `@QuarkusTest` runs (including `GreetingResourceTest`) will start the route and log those warnings when the broker is down. To switch the consumer off in tests, use `.autoStartup("{{jobs.consumer.enabled:true}}")` on the route plus `"%test":` → `jobs.consumer.enabled: false` in `application.yaml`. Turn it back on in the Citrus test.

### 2. Sending

**a) From inside a route:** `.to("jms:queue:DEV.JOBS.RESULT")`. When this runs inside the consumer route above, the send joins the consumer's local transaction, so "consume one, produce another" commits or rolls back as a single unit.

**b) From application code** (e.g. `JexSrv.createJob`): inject `ProducerTemplate`. Camel Quarkus provides it as a CDI bean, and it's the counterpart of `JmsTemplate`:

```java
@Inject
ProducerTemplate producer;

@Inject
ObjectMapper mapper;   // Quarkus' Jackson ObjectMapper (quarkus-rest-jackson)

public Job createJob(CreateJobRequest request) {
    var job = new Job().id(UUID.randomUUID()).status(JobStatus.QUEUED) /* ... */;
    try {
        producer.sendBodyAndHeader("jms:queue:DEV.JOBS.REQUEST",
                mapper.writeValueAsString(request),   // String body → JMS TextMessage
                "JobId", job.getId().toString());     // becomes a JMS string property
    } catch (JsonProcessingException e) {
        throw new IllegalArgumentException(e);
    }
    return job;
}
```

Notes:
- **Send a `String` (JSON), not the DTO.** A non-String object becomes an `ObjectMessage` (Java serialization), which you don't want on a queue. `.marshal().json()` inside a route would need `camel-quarkus-jackson`, which isn't in the pom. Serializing yourself avoids adding it.
- **`sendBody*` is fire-and-forget (InOnly).** `requestBody*` switches to request-reply (InOut): Camel creates a temporary reply queue and **blocks waiting for a reply** (20 s timeout by default). Easy to pick by accident from autocomplete.
- **Header names** have to be valid JMS property identifiers. Use `JobId`, not `job-id`: Camel would encode the dash (as `_HYPHEN_`).
- **Transaction scope:** called outside a route, the transacted `JmsTemplate` commits right after the send. This is **not** tied to a Quarkus `@Transactional` (JTA) on the REST method. Once the job is also stored in a database, "saved but not queued" and the reverse become possible. That's the outbox / XA decision, for later.

**c) Plain Jakarta JMS without Camel** is possible: inject the `ConnectionFactory` from `MqConnectionFactoryProducer` and use `JMSContext`. But the credentials are on the Camel component, not on the factory, so you'd have to pass them yourself (`cf.createContext(user, password)`) or get `MQRC 2035`. Stick with `ProducerTemplate` unless you have a reason to bypass Camel.

**d) By hand, for trying the listener out:** the MQ sample program from the broker smoke test, pointed at the project queue (`amqsputc` sends one message per input line, and an empty line ends it):

```shell
docker exec -e MQSERVER='DEV.APP.SVRCONN/TCP/localhost(1414)' -e MQSAMP_USER_ID=app quarqueueus-ibm-mq sh -c \
  'printf "passw0rd\nhello\nboom\n\n" | /opt/mqm/samp/bin/amqsputc DEV.JOBS.REQUEST QM1'
```

With `./mvnw quarkus:dev` running, `hello` is logged once. `boom` is logged with delivery count 1, 2 and 3, then ends up on the backout queue:

```shell
docker exec quarqueueus-ibm-mq sh -c "echo 'DISPLAY QLOCAL(DEV.JOBS.*) CURDEPTH' | runmqsc QM1"
```

Expect `DEV.JOBS.BACKOUT CURDEPTH(1)`. (Tested 2026-10-24. This needed `PASSALL` for `app` on the backout queue, which was missing at first and is now in `broker/mqsc/30-quarqueueus.mqsc`; see [broker/README.md → Backout](../broker/README.md#backout-why-your-own-queues-should-set-bothresh).) You can also put test messages from the web console (queue → Create message). The automated form of this check is the Citrus test in TODO §1.

**IBM MQ detail:** Camel sends JMS messages with an `MQRFH2` header that carries the JMS properties. JMS consumers never notice. A non-JMS consumer (a C/COBOL program, `amqsgetc`) sees it as junk in front of the body. For such receivers, address the queue as `jms:queue:queue:///DEV.X?targetClient=1` to send a plain MQ message.

---

## How do I run a one-time action at startup (e.g. send one job)?

**Spring mapping:** `CommandLineRunner` / `ApplicationRunner` / `@EventListener(ApplicationReadyEvent.class)` become a **CDI observer method**. Quarkus has no runner interfaces. Startup work is "observe an event".

| Quarkus | When it fires | Use for |
|---|---|---|
| `void x(@Observes StartupEvent e)`, or `@io.quarkus.runtime.Startup` on a bean | app is starting (≈ `ApplicationStartedEvent`) | general init that doesn't need Camel |
| `void x(@Observes CamelEvent.CamelContextStartedEvent e)` | the Camel context and its routes are started (≈ `ApplicationReadyEvent` for Camel) | **sending through Camel**: what you want here |

Prefer the Camel event for sending. It guarantees the `jms` component and your consumer route are up, so you don't depend on the order in which Quarkus and Camel start. The Camel Quarkus event bridge (`CamelManagementEventBridge`, in `camel-quarkus-core`) is switched on automatically when it finds an observer for a Camel event at build time.

```java
package xyz.kajih.quarqueueus.jobs;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.spi.CamelEvent;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class SeedJobSender {

    @Inject
    ProducerTemplate producer;

    @ConfigProperty(name = "jobs.seed.enabled", defaultValue = "false")
    boolean enabled;

    void onCamelStarted(@Observes CamelEvent.CamelContextStartedEvent event) {
        if (!enabled) {
            return;
        }
        producer.sendBodyAndHeader("jms:queue:DEV.JOBS.REQUEST",
                "{\"type\":\"report-generation\",\"parameters\":{}}",
                "JobId", java.util.UUID.randomUUID().toString());
        Log.info("Seed job sent");
    }
}
```

```yaml
"%dev":
  jobs:
    seed:
      enabled: true
```

Gotchas:
- **"One-time" means once per start.** In `quarkus:dev`, every live reload restarts the app, so the observer fires again and you get one message per reload. Every `@QuarkusTest` run also starts the app. That's why the example is off by default and only on in `%dev`. To fire it again on purpose, press `s` (force restart) in the dev-mode console.
- **The broker must be up.** Unlike the consumer, which retries in the background, a failed send throws inside the observer, and that **fails startup**. If you'd rather keep running, wrap it in `try/catch` and log.
- **`@Observes` doesn't make the bean eager.** The observer method itself registers the bean, and an unused observer bean isn't removed, so no `@Startup` or `@Unremovable` is needed.
- **Repeatable or "on demand" instead of at startup:** the most natural trigger is `POST /jobs` once `createJob` sends (see [the previous entry](#how-do-i-write-a-camel-jms-listener-and-how-do-i-send-messages-to-the-queue), 2b). Then `curl -X POST localhost:8080/jobs -H 'Content-Type: application/json' -d '{…}'` is your one-off action. A Camel `timer:` route (`from("timer:seed?repeatCount=1")`) is the Camel-idiomatic alternative, but it needs `camel-quarkus-timer`, which isn't in the pom. Likewise, `@Scheduled` needs `quarkus-scheduler`.

---

## Code review (2026-10-24): code standard, bad code, package structure

Reviewed: everything under `src/` at 2026-10-24, including the uncommitted `jobs` package. Findings are ordered most important first within each section. They're tracked in [TODO.md](TODO.md).

### Bugs and things that will bite

1. **`JobWorker.java` is untracked, but the other three `jobs` files are staged** (`git status`: `AM` vs `??`). A commit now would contain a route that references a class that isn't in the commit, so that commit doesn't compile. Run `git add` on it, or stage the whole package together.
2. **`JobSender` isn't a CDI bean.** It has `@Inject` fields but no scope annotation. In Quarkus, only classes with a bean-defining annotation are discovered. (The exception is classes that declare producers or observers, which get an implicit `@Dependent`; plain `@Inject` fields don't count.) So `@Inject JobSender` would fail the build with an unsatisfied dependency, and `new JobSender()` leaves both fields `null`. Spring's component scan would not have saved you either, since there's no `@Component`. Add `@ApplicationScoped`.
3. **`jobs.seed.enabled: true` is set for every profile**, not under `"%dev"`. So:
   - each `@QuarkusTest` start (`GreetingResourceTest`, `JobApiPactIT`) sends a message;
   - the tests fail at startup when the broker is down, because a failing send in the observer aborts startup;
   - a production build would send a seed job on every start.

   Move it under `"%dev":`. Better still, see structure point 3 below.
4. **`mqjms.log.0` / `mqjms.log.0.lck` in the project root.** The IBM MQ JMS client writes its log to the working directory. `.gitignore` has `*.log`, but that doesn't match `mqjms.log.0`, so it shows up as untracked. Either add `mqjms.log*` to `.gitignore`, or point the client somewhere else with `-Dcom.ibm.msg.client.commonservices.log.outputName=target/mqjms.log`. In dev mode, pass it as `./mvnw quarkus:dev -Djvm.args="-Dcom.ibm.msg.client.commonservices.log.outputName=target/mqjms.log"`.

### Bad code (smaller)

- **`JobSender.createJob`:**
  - It declares `throws JsonProcessingException` but catches it inside, so the `throws` is dead.
  - The `/* ... */` placeholder from my snippet is still in, so the returned `Job` has no `type` or `createdAt`.
  - Its responsibility is muddled: it both *creates* a job and *sends* it. Let `JexSrv.createJob` (the REST layer) build the `Job` and store it, and give the sender a single job, e.g. `void send(UUID jobId, CreateJobRequest request)`.
  - Nothing calls it yet. `JexSrv.createJob` still returns `null`.
- **The queue URI `"jms:queue:DEV.JOBS.REQUEST"` is duplicated in three classes.** Put it in one place:
  - a constant (`JobQueues.REQUEST`), or
  - config: `jobs.request-uri: jms:queue:DEV.JOBS.REQUEST`, then `from("{{jobs.request-uri}}")` in the route. `ProducerTemplate` resolves `{{…}}` in URIs too.

  Config is what you'd do in Spring with `@Value("${…}")` on `@JmsListener(destination=…)`.
- **`SeedJobSender` builds JSON by hand** (`"{\"type\":…}"`) while `JobSender` uses `ObjectMapper` with the generated `CreateJobRequest`. Use the DTO in both, so a spec change breaks the build rather than the consumer. It also uses a fully qualified `java.util.UUID` inline; import it.
- **`JobWorker`:**
  - The `boom` poison check is test scaffolding inside production code. It's fine while experimenting, but give it a `// TODO remove` or move the behaviour into a test.
  - `Log.info(body)` logs the full payload at INFO, and the route's `.log(...)` already logs it. One of them is enough. Payloads (`parameters`) can contain data you don't want in logs; DEBUG is the usual level for bodies.
  - `handle(String)`: once the payload is the `CreateJobRequest` JSON, take the DTO instead. Deserialize with `ObjectMapper`, or add `camel-quarkus-jackson` and `.unmarshal().json(CreateJobRequest.class)` in the route.
- **Leftover tutorial comments** from my snippets (`// Camel converts the TextMessage body to String`, `// becomes a JMS string property`) explain Camel rather than your code. Keep them while learning if they help, but they're noise in a real codebase.
- **Minor inconsistencies:**
  - `JobApiPactIT` is `public`, while `GreetingResourceTest` is package-private. JUnit 5 needs neither.
  - Line endings are mixed (Git warns `CRLF will be replaced by LF` on `application.yaml` and `.env.example`). Set your IDE to LF for this repo, or add `* text=auto eol=lf` to `.gitattributes`.
  - `JobsResourceImpl` uses a hard-coded UUID with mixed-case hex copied from the pact example (already in TODO §3).

### What's already good

- Package-private `@Inject` fields. This is the Quarkus idiom: ArC can inject them without reflection. Spring folks usually write `private` fields; in Quarkus that costs reflection, and native builds warn about it.
- `@ConfigMapping` interface for `mq.*`, the counterpart of `@ConfigurationProperties`.
- The route is thin wiring and the logic lives in a CDI bean.
- No hand-edits in generated code.

### Package structure

What you have:

```
xyz.kajih                 GreetingResource, JexSrv           ← REST impl in the root package
xyz.kajih.jexapi.*        generated (resources, model)       ← target/, never edit
xyz.kajih.quarqueueus.jobs            JobRequestRoute, JobWorker, JobSender, SeedJobSender
xyz.kajih.quarqueueus.mq              MqConfig, MqConnectionFactoryProducer
```

**Package-by-feature (`jobs`) plus an infrastructure package (`mq`) is the right shape.** It's the same thing you'd do in Spring Boot. Suggestions:

1. ✅ *Done (uncommitted, 2026-10-24):* **move the REST implementation into the jobs package** and give it a descriptive name. `JexSrv` became `xyz.kajih.quarqueueus.jobs.rest.JobsResourceImpl`, and `JobApiPactIT` moved alongside it on the test side. (The original wording of this point was mangled by a `JexSrv` → `JobsResourceImpl` find-and-replace.)
2. ✅ *Partly done:* **project-specific root** `xyz.kajih.quarqueueus`. The generator's `base-package` is still `xyz.kajih.jexapi`, which is fine: generated code is a library the REST adapter uses, not part of the hand-written package tree.
3. **Keep dev-only code out of the production build.** Annotate `SeedJobSender` with `@io.quarkus.arc.profile.IfBuildProfile("dev")`. ArC then **removes the bean at build time** in `test` and `prod` builds, so there's no flag to forget and nothing to misconfigure. This has no exact Spring equivalent: `@Profile("dev")` is evaluated at runtime, and the class still ships. With that in place, `jobs.seed.enabled` can go entirely. A `xyz.kajih.dev` package is optional.
4. **Don't split `jobs` into sub-packages yet.** At four classes, `jobs.messaging` / `jobs.api` / `jobs.store` would be premature. Revisit once job storage arrives (TODO §3).
5. `mq` is fine as infrastructure. If more brokers or protocols join, `xyz.kajih.infra.mq` is the usual next step.

### Code standard / tooling

There's no formatter or static analysis, so "code standard" is currently whatever the IDE does (which is how `native.image.path` got split, TODO §2). Since `pom.xml` is yours to decide on:
- **Spotless** with `palantir-java-format` or `google-java-format`, so `./mvnw spotless:apply` / `spotless:check` runs in `verify`. This is the most common choice in Spring shops and is build-tool independent: it's available for Gradle too, if you leave Maven.
- Optionally **Error Prone** or **SpotBugs** later. Nothing here is complex enough to need them yet.

---

## Package structure review, round 2 (2026-10-24)

Layout as staged:

```
xyz.kajih.quarqueueus
├── dev/                         GreetingResource, SeedJobSender
├── execution/                   JobWorker
├── infra/mq/                    MqConfig, MqConnectionFactoryProducer
└── jobs/internal/
    ├── messaging/               JobRequestRoute, JobSender
    └── rest/                    JobsResourceImpl
test: xyz.kajih.quarqueueus      GreetingResourceTest, GreetingResourceIT
      xyz.kajih.quarqueueus.jobs JobApiPactIT
```

Good moves: `infra/mq` is clearly infrastructure, the REST and JMS adapters are separated, and `execution` exists as its own area. Everything is now staged together, including `JobWorker`, so the earlier "commit would break the build" problem is gone. Suggestions, most important first:

1. **`JobRequestRoute` is in the wrong package.** It *consumes* the request queue and calls `execution.JobWorker`, so it's execution's inbound adapter, not jobs'. As it stands, `jobs` both sends to and consumes its own queue, and `jobs` → `execution` is a direct method call. That makes the queue pointless as a boundary. Move it to `execution` (e.g. `execution/messaging/JobRequestRoute`). `jobs` then only sends, `execution` only receives, and neither imports the other.

2. **The queue needs its own message type, owned by `execution`.**
   - `JobSender` currently puts the REST DTO (`CreateJobRequest`) on the queue, and builds a REST `Job` too. So the generated OpenAPI model leaks out of the REST adapter into messaging.
   - Add a record in `execution`, e.g. `public record JobRequest(UUID jobId, String type, Map<String, Object> parameters)`. `JobSender` maps to it and serialises it; `JobWorker.handle(JobRequest)` receives it.
   - Put the queue URI next to it: a constant `JobRequest.QUEUE = "jms:queue:DEV.JOBS.REQUEST"`, or a config key `execution.request-uri` used as `{{execution.request-uri}}`. That also fixes the URI being duplicated in three classes. Then `jobs` depends on `execution`'s contract (one record, one constant), and `execution` depends on nothing.

3. **`jobs` has no centre.** The package has only adapters (`internal/rest`, `internal/messaging`). Once `createJob` does real work, the REST adapter would call `JobSender` directly, adapter to adapter. Add the domain at `jobs/` level now, even if small:
   - `jobs/Job.java`, the domain model rather than the generated DTO;
   - `jobs/JobService.java` (`@ApplicationScoped`) with `submit`, `find` and `list`;
   - a `ConcurrentHashMap` store inside it, or a separate `JobStore`.

   `JobsResourceImpl` then maps DTO ↔ `Job` and calls `JobService`, and `JobService` calls `JobSender`. This is also where TODO §3 "no job storage" gets done.

4. **Pick one convention for `internal/`.** `jobs` has `internal/`, `execution` doesn't. Java doesn't enforce `internal` anyway, so it's only a signal. Either drop it (`jobs/rest`, `jobs/messaging`, `execution/messaging`), which is simpler at this size and my pick, or use it everywhere. The mix makes a reader wonder whether `execution.JobWorker` is meant to be API.

5. **`GreetingResource` doesn't belong in `dev/`.**
   - It's scaffold, not a dev tool, and its tests stayed in the root test package, so test and main no longer mirror each other.
   - If you later put `@IfBuildProfile("dev")` on `dev` beans (the plan for `SeedJobSender`, still open in TODO §1), a habit of doing that package-wide would remove `/hello` from the test build and break `GreetingResourceTest`.
   - Either delete it with its two tests now (TODO §6), or move it back to the root package.

6. **`dev/SeedJobSender` still has the earlier problems:** the `jobs.seed.enabled: true` flag is unprofiled, the JSON is hand-written, and the URI is hard-coded. After point 2 it should build a `JobRequest` record, or better, call `JobService.submit` once that exists (point 3), so the seed goes through the same path as a REST call. The config key `jobs.seed.*` also suggests it belongs to `jobs`. Make the class `@IfBuildProfile("dev")` and drop the key.

7. **Test packages:** `JobApiPactIT` in `jobs` (not `jobs.internal.rest`) is fine. It's a black-box HTTP test of the jobs area, so it sits with the area, not the adapter. Unit tests for `JobService` or `JobWorker` should go in the same package as the class, so they can reach package-private members.

8. **Minor:**
   - `JobSender` still has no scope annotation (not a bean) and still has the `/* ... */` placeholder.
   - `JobWorker` still logs the body twice and still has the `boom` check.
   - All of these are already in TODO §1.

Target after points 1–5:

```
xyz.kajih.quarqueueus
├── jobs/            Job, JobService            (+ rest/JobsResourceImpl, messaging/JobSender)
├── execution/       JobRequest (record + queue URI), JobWorker   (+ messaging/JobRequestRoute)
├── infra/mq/        MqConfig, MqConnectionFactoryProducer
└── dev/             SeedJobSender  (@IfBuildProfile("dev"))
```

Dependencies: `jobs → execution` (only the `JobRequest` record), `dev → jobs`, and every module → `infra` only through CDI (`ConnectionFactory`). Nothing points back.

---

## Package structure review, round 3 (2026-10-24)

**Applied since round 2:**
- `JobRequestRoute` → `execution/messaging`;
- `internal/` dropped (`jobs/rest`, `jobs/messaging`);
- `SeedJobSender` is `@IfBuildProfile("dev")`, and the `jobs.seed.enabled` flag is gone from `application.yaml`.

The package layout itself now looks right.

Two things are broken. I verified both by running `GreetingResourceTest`: first on the real tree, then in a scratch copy of the project with the route patched. Nothing under `src/` was changed.

1. **The app fails at startup**, so every `@QuarkusTest` fails, not just this one. `.bean(JobWorker.class, "handle")` needs `camel-quarkus-bean`:

   ```
   FailedToCreateRouteException: Failed to create route: job-request at: >>> Bean[xyz.kajih.quarqueueus.execution.JobWorker] <<<
   … because: No bean-processor-factory service could be found in the classpath. Add camel-bean to classpath.
   ```

   That's my mistake from the [listener entry](#how-do-i-write-a-camel-jms-listener-and-how-do-i-send-messages-to-the-queue), now corrected there. There are two fixes:
   - add `org.apache.camel.quarkus:camel-quarkus-bean`, version from the Camel BOM;
   - or inject the worker and call it from `.process(...)`:

   ```java
   @Inject
   JobWorker worker;

   @Override
   public void configure() {
       from("jms:queue:DEV.JOBS.REQUEST")
               .routeId("job-request")
               .log("Job request ${header.JMSMessageID} (delivery ${header.JMSXDeliveryCount}): ${body}")
               .process(exchange -> worker.handle(exchange.getMessage().getBody(String.class)));
   }
   ```

   With the second variant the app starts in the scratch copy. I'd pick it: it's a normal compile-checked method call, so a rename in `JobWorker` breaks the build instead of failing at runtime, and it needs no extra extension. Spring analogy: calling the service from your `@JmsListener` method, rather than using a SpEL bean reference.

   *Why didn't the build catch it?* Camel resolves EIPs when it creates the route, at startup, not at build time. Only a test that boots the app sees it. That's one more reason to keep at least one `@QuarkusTest` that boots the app.

2. **`@IfBuildProfile("dev")` on `GreetingResource` removes `/hello` from the test build.** Once startup is fixed, `GreetingResourceTest` fails with `Expected status code <200> but was <404>` (seen in the scratch copy). `GreetingResourceIT`, which runs against the packaged **prod** build, fails for the same reason. Since it's scaffold anyway, delete `GreetingResource` and both tests (TODO §6). If you want to keep it as a smoke test, remove the annotation and move it back to the root package.

Still open from round 2 (TODO §1): the `JobRequest` message record, the `jobs` domain centre (`Job`, `JobService`), `JobSender` scope/placeholder, and the queue URI duplicated in three classes.

**Note on stale build output:** after moving packages, the first `./mvnw test` failed with `Could not load class … GreetingResourceTest`. `./mvnw clean test` fixed it. Old `.class` files from the previous packages stay in `target/` and confuse the incremental build. Run `clean` after package moves.

