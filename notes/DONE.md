# Done

Finished TODO items, newest first, kept for reference. Open work is in [TODO.md](TODO.md).

## 2026-10-24

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
- IBM MQ `ConnectionFactory` producer in `xyz.kajih.mq` (`MqConfig` + `MqConnectionFactoryProducer`), wrapped by `PooledJmsWrapper`, with credentials on the Camel JMS component. → [QnA](QnA.md#how-do-i-provide-the-ibm-mq-connectionfactory-for-camel-jms-with-pooling-and-credentials)
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
