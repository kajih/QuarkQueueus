# QuarQueueus

A Quarkus learning project: the API-first "Job Execution API" (OpenAPI-generated JAX-RS, Pact provider verification), with job processing over IBM MQ through Camel JMS.

- Architecture and commands: [CLAUDE.md](CLAUDE.md)
- Open work: [notes/TODO.md](notes/TODO.md). Questions and answers: [notes/QnA.md](notes/QnA.md)

## Local setup

1. **Start the IBM MQ broker.** There's no Quarkus Dev Service for MQ. See [broker/README.md](broker/README.md):

   ```shell
   cd broker && docker compose up -d
   ```

2. **Create `.env`** in the project root from the template. Quarkus reads it natively, and it's gitignored:

   ```shell
   cp .env.example .env   # then set MQ_APP_PASSWORD (passw0rd for the local broker)
   ```

   Without it, startup fails: the Camel placeholder `{{mq.app.password}}` resolves from `MQ_APP_PASSWORD` and has no default.

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:

```shell script
./mvnw quarkus:dev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at <http://localhost:8080/q/dev/>.

## Packaging and running the application

The application can be packaged using:

```shell script
./mvnw package
```

It produces the `quarkus-run.jar` file in the `target/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `target/quarkus-app/lib/` directory.

The application is now runnable using `java -jar target/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:

```shell script
./mvnw package -Dquarkus.package.jar.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar target/*-runner.jar`.

## Creating a native executable

> **Note:** the IBM MQ client isn't prepared for native image, so expect native builds to fail until that's dealt with. JVM mode is the supported path for now.

You can create a native executable using:

```shell script
./mvnw package -Dnative
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using:

```shell script
./mvnw package -Dnative -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./target/quarqueueus-1.0.0-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult <https://quarkus.io/guides/maven-tooling>.
