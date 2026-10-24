# Local IBM MQ broker

A dev-only IBM MQ Advanced for Developers queue manager (`icr.io/ibm-messaging/mq:10.0.1.0-r1`) for the Camel JMS / Citrus experiments. Claude manages this folder; it isn't part of the Quarkus app.

## Start / stop

Run from this folder:

```shell
docker compose up -d      # start (waits ~30s to become healthy)
docker compose ps         # STATUS should say (healthy)
docker compose logs -f    # follow logs
docker compose down       # stop, keep queue data
docker compose down -v    # stop and wipe queue data (named volume mq-data); queues from mqsc/ are recreated on next up
docker compose restart    # re-apply mqsc/*.mqsc after editing it
```

## Connection details

| Setting | Value |
|---|---|
| Host / port | `localhost` / `1414` |
| Queue manager | `QM1` |
| App channel | `DEV.APP.SVRCONN`: user `app`, password `passw0rd` |
| Admin channel | `DEV.ADMIN.SVRCONN`: user `admin`, password `passw0rd` |
| Queues | Project: `DEV.JOBS.REQUEST` (backout after 3 → `DEV.JOBS.BACKOUT`), from `mqsc/30-quarqueueus.mqsc`. Built-in: `DEV.QUEUE.1..3` (no backout); dead letter queue `DEV.DEAD.LETTER.QUEUE` |
| Topic | `DEV.BASE.TOPIC` (topic string `dev/`) |
| Web console | https://localhost:9443/ibmmq/console, `admin` / `passw0rd` (self-signed cert, so accept the browser warning) |

The `app` user may put, get, browse, publish and subscribe on anything matching `DEV.**`, so either use the `DEV.*` queues or name your own queues `DEV.something`. The app user can't create queues. See below.

## Creating queues

Queues are defined with **MQSC** commands, MQ's admin language, run by `runmqsc`. There are two ways to run them. Pick based on whether the queue should be part of the repo.

### A. Ad hoc, interactively

```shell
docker exec -it quarqueueus-ibm-mq runmqsc QM1
DEFINE QLOCAL('DEV.SCRATCH') REPLACE
END
```

The queue lives in the `mq-data` volume. It survives `docker compose down` / `up`, but not `down -v`, and nobody else gets it from git. Fine for poking around. Don't define queues by hand that are also in the `.mqsc` file: the file wins on the next start.

### B. Declaratively, from a file applied at every start (what this repo uses)

The image runs every `/etc/mqm/*.mqsc` file at startup, in name order. `10-dev.mqsc` is the one that creates the `DEV.*` objects (you can see it with `docker exec quarqueueus-ibm-mq ls /etc/mqm`). This repo mounts its own file after it, [`mqsc/30-quarqueueus.mqsc`](mqsc/30-quarqueueus.mqsc). **To add a queue, add a `DEFINE` there and run `docker compose restart`.** How it's wired:

1. The file, in simplified form; the real one also sets `DEFPSIST(YES)`:

   ```
   * Job requests from the REST API to the worker route.
   * After 3 failed (rolled-back) deliveries the JMS client moves the message to the backout queue.
   DEFINE QLOCAL('DEV.JOBS.REQUEST') +
          BOTHRESH(3) BOQNAME('DEV.JOBS.BACKOUT') +
          REPLACE
   DEFINE QLOCAL('DEV.JOBS.BACKOUT') REPLACE
   ```

   MQSC syntax: `*` starts a comment, and a trailing `+` continues the command on the next line. `REPLACE` makes the command safe to re-run on every start. Tested on this broker on 2026-10-24:
   - **Messages on the queue are kept** across restarts. Only persistent ones, though: non-persistent messages are lost whenever the queue manager restarts, whatever the file does.
   - **Every attribute *not* in the file is reset to its default** on each start. It is *not* like `ALTER`. A change made by hand in the console or `runmqsc` (e.g. `DEFPSIST(YES)`) silently disappears on the next restart. Treat the file as the single source of truth and put every attribute you care about in it.

   Persistence: Camel/JMS sends persistent messages by default (`deliveryPersistent=true`), so the queue's `DEFPSIST` only matters for non-JMS senders such as the `amqsputc` sample in the smoke test. Add `DEFPSIST(YES)` to the definition if you want those to survive restarts too.

2. The mount in `docker-compose.yml`:

   ```yaml
       volumes:
         - mq-data:/mnt/mqm
         - ./mqsc/30-quarqueueus.mqsc:/etc/mqm/30-quarqueueus.mqsc:ro
   ```

3. Check that it was applied. The container log also says `Automatic MQSC configuration script has completed, ... 0 had errors`:

   ```shell
   docker exec quarqueueus-ibm-mq sh -c "echo 'DISPLAY QLOCAL(DEV.JOBS.*) BOTHRESH BOQNAME' | runmqsc QM1"
   ```

Edit the file and run `docker compose restart` to apply changes. It's closer to Hibernate's `update` mode than to a Flyway migration: the whole file is re-applied on every boot. Note that removing a `DEFINE` from the file does **not** delete the queue. Use `DELETE QLOCAL(...)` for that, with `PURGE` if it still holds messages.

You can also create queues in the web console (Queue manager QM1 → Create), or through the REST admin API on port 9443. Neither leaves anything in git.

### Permissions

On this image, `app` has an authority record for the generic profile `DEV.**`:

```
PROFILE(DEV.**)  ENTITY(app)  ENTTYPE(PRINCIPAL)  AUTHLIST(BROWSE,GET,INQ,PUT)
```

So any `DEV.*` queue works with no extra setup. For any other name, grant `app` access in the same `.mqsc` file:

```
SET AUTHREC PROFILE('JOBS.**') OBJTYPE(QUEUE) PRINCIPAL('app') AUTHADD(BROWSE,GET,INQ,PUT)
```

### Backout: why your own queues should set `BOTHRESH`

The built-in `DEV.QUEUE.1..3` have `BOTHRESH(0)` and no `BOQNAME`, which means **never back out**. With Camel `transacted: true`, a message that makes the route fail every time is rolled back and redelivered forever. With `BOTHRESH(n)` and `BOQNAME(q)` set, the IBM MQ JMS client moves the message to `q` after `n` failed deliveries. If it can't (no `BOQNAME`, or no PUT authority), the message goes to the dead-letter queue `DEV.DEAD.LETTER.QUEUE`. It's the counterpart of a Spring/ActiveMQ redelivery policy with a DLQ, except here it's configured on the queue, not in the client.

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
