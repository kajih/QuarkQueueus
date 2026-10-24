# Q&A

Answers to questions about this repo and Quarkus. Newest at the bottom. These are analysis notes; nothing here has been applied to the code.

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
   - add a **provider state** (e.g. `"no jobs exist"`) in the consumer test, and in `JobApiPactIT` add a `@State("no jobs exist")` method that clears the job store before the interaction runs. That needs real storage instead of the hard-coded list in `JexSrv`.

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
2. Rebuild (`./mvnw compile` or just let `quarkus:dev` reload). The generator will change `JobsResource.listJobs` to return `JobList`, and `JexSrv` will stop compiling until you update it to return `new JobList().jobs(...)`. That compile error is the API-first workflow doing its job.
3. You still have the empty-list vs hard-coded-job problem from Option A step 2.

**Coming from Spring:**
- **Code generation:** in Spring you'd run `openapi-generator-maven-plugin` with the `spring` generator and `interfaceOnly=true`, then write a `@RestController` that implements the interface. Here a Quarkus *extension* does the generation during Quarkus's own code-gen step, configured in `application.yaml` instead of the POM. That's why there's no generator plugin in `pom.xml`.
- **Pact test setup:** `JobApiPactIT` is the counterpart of a Spring `@SpringBootTest(webEnvironment = RANDOM_PORT)` provider test. `@State` works the same way, since both use pact-jvm's JUnit 5 support. One difference: Quarkus tests start on a fixed port (`quarkus.http.test-port`, default 8081) rather than a random one.
- **No `@RestController` needed:** `JexSrv` has no stereotype annotation. Quarkus REST finds it because it implements a `@Path`-annotated interface.

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

- **Errors (404/409):** throw an exception from `JexSrv` and map it to a response. Quarkus REST lets you write a method annotated `@ServerExceptionMapper` (in any bean) that takes e.g. a custom `JobNotFoundException` and returns `RestResponse<Error>` with status 404 and the spec's `Error` body. You could also throw `jakarta.ws.rs.NotFoundException`, but then the body won't match the `Error` schema.
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
package xyz.kajih.mq;

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
package xyz.kajih.mq;

import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import io.quarkiverse.messaginghub.pooled.jms.PooledJmsWrapper;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;

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
- **Package:** put the producer in its own package (e.g. `xyz.kajih.mq`), not in `xyz.kajih.jexapi`, which is the generator's.

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
