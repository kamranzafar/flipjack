# Runnable Spring Boot example

This standalone application demonstrates class-level and method-level routing, a toggled
step inside a service, per-user evaluation, runtime changes, and the read-only
`/actuator/flipjack` endpoint. It is intentionally outside the root Maven reactor, so the
project's release build does not publish it to Maven Central. The app binds to loopback and
its unauthenticated toggle route is for local demos only.

From the repository root, install the current Flipjack snapshot and start the app:

```sh
mvn -pl flipjack-spring-boot-starter -am install -DskipTests
cd examples/flipjack-examples
mvn spring-boot:run
```

In another terminal, compare Alice's personalized greeting with Bob's fallback. The sample
uses the `X-Demo-User` header as its request context:

```sh
curl -H 'X-Demo-User: alice' 'http://localhost:8080/demo/greeting?name=Sam'
curl -H 'X-Demo-User: bob' 'http://localhost:8080/demo/greeting?name=Sam'
```

Try the method-level fallback and the `either` tax step:

```sh
curl 'http://localhost:8080/demo/shipping?order=order-123'
curl 'http://localhost:8080/demo/tax?region=EU'
```

Change a toggle at runtime. The demo-only endpoint is deliberately unauthenticated for
local exploration; do not expose it in a deployed application.

```sh
curl -X POST 'http://localhost:8080/demo/toggles/express-shipping?enabled=true'
curl 'http://localhost:8080/demo/shipping?order=order-123'
curl -X POST 'http://localhost:8080/demo/toggles/regional-tax?enabled=false'
curl 'http://localhost:8080/demo/tax?region=EU'
```

Inspect the effective states. The actuator endpoint is read-only; toggle changes are made
through the local demo route above.

```sh
curl 'http://localhost:8080/actuator/flipjack'
curl 'http://localhost:8080/actuator/flipjack/premium-greeting?subject=alice'
```

The example uses a small in-memory feature source and fixed user targeting to keep the
application self-contained. Replace both with your own store and request/security context
in a real application.
