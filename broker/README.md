# Local IBM MQ broker

A dev-only IBM MQ Advanced for Developers queue manager (`icr.io/ibm-messaging/mq:10.0.1.0-r1`) for the Camel JMS / Citrus experiments. Claude manages this folder; it isn't part of the Quarkus app.

## Start / stop

Run from this folder:

```shell
docker compose up -d      # start (waits ~30s to become healthy)
docker compose ps         # STATUS should say (healthy)
docker compose logs -f    # follow logs
docker compose down       # stop, keep queue data
docker compose down -v    # stop and wipe queue data (named volume mq-data)
```

## Connection details

| Setting | Value |
|---|---|
| Host / port | `localhost` / `1414` |
| Queue manager | `QM1` |
| App channel | `DEV.APP.SVRCONN`: user `app`, password `passw0rd` |
| Admin channel | `DEV.ADMIN.SVRCONN`: user `admin`, password `passw0rd` |
| Queues | `DEV.QUEUE.1`, `DEV.QUEUE.2`, `DEV.QUEUE.3`; dead letter queue `DEV.DEAD.LETTER.QUEUE` |
| Topic | `DEV.BASE.TOPIC` (topic string `dev/`) |
| Web console | https://localhost:9443/ibmmq/console, `admin` / `passw0rd` (self-signed cert, so accept the browser warning) |

The `app` user may put, get, browse, publish and subscribe on anything matching `DEV.**`, so either use the `DEV.*` queues or name your own queues `DEV.something`. The app user can't create queues. Use the console or `runmqsc` as admin for that:

```shell
docker exec -it quarqueueus-ibm-mq runmqsc QM1
DEFINE QLOCAL('DEV.QUARQUEUEUS.JOBS') REPLACE
```

## Passwords

The passwords are passed as Docker secrets (env-var passwords are deprecated since MQ 9.4), read from `secrets/mqAdminPassword` and `secrets/mqAppPassword`. That folder is git-ignored. On a fresh clone, recreate it, with no trailing newline:

```shell
mkdir -p secrets && printf 'passw0rd' > secrets/mqAdminPassword && printf 'passw0rd' > secrets/mqAppPassword
```

## Smoke test

Put a message on a queue and read it back over the app channel, using MQ's sample programs inside the container:

```shell
docker exec -e MQSERVER='DEV.APP.SVRCONN/TCP/localhost(1414)' -e MQSAMP_USER_ID=app quarqueueus-ibm-mq sh -c \
  'printf "passw0rd\nhello\n\n" | /opt/mqm/samp/bin/amqsputc DEV.QUEUE.1 QM1; printf "passw0rd\n" | /opt/mqm/samp/bin/amqsgetc DEV.QUEUE.1 QM1'
```
