# banco-microservicio

Dos servicios Spring Boot 4.1 sobre PostgreSQL 18, Kafka y Keycloak. `account-service` gestiona cuentas y movimientos; `transfer-service` coordina transferencias entre cuentas. Cada uno tiene su propia base de datos, su JAR y su configuración de seguridad, y comparten el compose, el realm y este README.

## Arrancarlo

```powershell
docker compose up -d

# terminal 1
cd account-service
.\mvnw.cmd spring-boot:run

# terminal 2
cd transfer-service
.\mvnw.cmd spring-boot:run
```

El compose levanta PostgreSQL en localhost:5432, Keycloak en localhost:8080 y Kafka en localhost:9092. `account-service` queda en http://localhost:8081 y `transfer-service` en http://localhost:8083. Cada servicio usa su propia BD (`accounts_db` y `transfers_db`) y su propio usuario con permisos solo sobre esa BD; en producción serían instancias distintas.

### account-service

Endpoints:

- `POST /api/v1/accounts` → 201 con header `Location`. Body: `number`, `currency`. El `customerId` se toma del token, no del body.
- `GET /api/v1/accounts/{id}` → 200 o 404.
- `POST /api/v1/accounts/{id}/deposits` → 201. Body: `amount`, `reference`. Solo `admin` o el service account de `transfer-service` con `account_internal`.
- `POST /api/v1/accounts/{id}/withdrawals` → 201. Body: `amount`, `reference`.
- `GET /api/v1/accounts/{id}/movements` → 200 con el historial.
- `GET /actuator/health`.

Solo las cuentas `ACTIVE` pueden mover saldo.

### transfer-service

- `POST /api/v1/transfers` → 201. Body: `sourceAccountId`, `targetAccountId`, `amount`, `currency`, `reference`. Exige el header `Idempotency-Key`.
- `GET /api/v1/transfers/{id}` → 200 o 404.
- `GET /api/v1/transfers` → 200 con las transferencias del propietario, o todas si es `admin`.
- `GET /actuator/health`. Un depósito o retiro sobre `BLOCKED` o `CLOSED` responde 409 con `type` `https://banco.local/problems/account-not-active`, sin cambiar el saldo ni dejar una clave de idempotencia pendiente.

`reference` la elige el cliente y es obligatoria. Se guarda en el asiento y `UNIQUE (account_id, reference, type)` evita que el mismo movimiento se registre dos veces; repetirla devuelve 409. Retirar más de lo disponible devuelve 422.

Los POST aceptan además el header opcional `Idempotency-Key`. Repetiendo la misma clave con el mismo body se devuelve la respuesta guardada sin insertar otro asiento; con otro body da 409. Solo se guardan respuestas exitosas: si el movimiento falla, la transacción se revierte y la clave queda libre para reintentar.

Todas las rutas exigen un Bearer token de Keycloak. Cómo pedirlo, qué roles hay y cómo se filtran los datos por propietario está en [Seguridad](#seguridad).

Los errores vienen en RFC 7807. Fíjate en `type`: es un identificador estable, no una URL que se visita. Es lo que el cliente debería usar para decidir qué hacer; el `detail` es para leer, no para programar contra él. Los de autenticación y autorización son `https://banco.local/problems/unauthorized` y `https://banco.local/problems/forbidden`, respectivamente.

## Seguridad

El compose levanta también Keycloak en `http://localhost:8080`. El realm `banco` se importa desde `keycloak/realm-banco.json`. Los clientes `account-service` y `transfer-service` tienen Direct Access Grants habilitado solo para desarrollo local. Ambos son resource servers: validan el JWT, no manejan sesiones ni contraseñas propias.

Para pedir un token y llamar a `account-service`:

```powershell
$body = @{
  client_id     = "account-service"
  client_secret = "account-service-secret"
  grant_type    = "password"
  username      = "alice"
  password      = "alice"
  scope         = "openid"
}
$token = (Invoke-RestMethod http://localhost:8080/realms/banco/protocol/openid-connect/token -Body $body).access_token

curl.exe -H "Authorization: Bearer $token" http://localhost:8081/api/v1/accounts
```

Para `transfer-service` se usa el cliente `transfer-service` y el scope decide qué se puede hacer:

```powershell
$body = @{
  client_id     = "transfer-service"
  client_secret = "transfer-service-secret"
  grant_type    = "password"
  username      = "alice"
  password      = "alice"
  scope         = "openid transfer.write"
}
$token = (Invoke-RestMethod http://localhost:8080/realms/banco/protocol/openid-connect/token -Body $body).access_token

curl.exe -H "Authorization: Bearer $token" http://localhost:8083/api/v1/transfers
```

El service account de `transfer-service` pide su propio token con `client_credentials` y ese token solo tiene `account_internal`. Es el que se usa para llamar a `account-service`; el token del cliente nunca se reenvía entre servicios.

Usuarios del realm:

| usuario | claim `customerId` | roles |
| --- | --- | --- |
| `alice` | `11111111-1111-4111-8111-111111111111` | `account_read`, `account_write`, `transfer_read`, `transfer_write` |
| `bob` | `22222222-2222-4222-8222-222222222222` | `account_read`, `account_write`, `transfer_read`, `transfer_write` |
| `carol` | `44444444-4444-4444-8444-444444444444` | `account_read`, `transfer_read` |
| `admin` | `33333333-3333-4333-8333-333333333333` | `admin`, `transfer_read`, `transfer_write` |

`account_read` habilita los GET; `account_write` habilita crear cuentas y retirar, pero **no depositar**. Un depósito lo hace `admin` o el service account con `account_internal`: el cliente no puede acreditarse dinero a sí mismo. `admin` ve todas las cuentas; los demás solo las suyas. La propiedad se comprueba en la capa de datos: los repositorios filtran por `customerId` y una cuenta ajena responde 404, no 403, para no revelar que existe. El `customerId` no se acepta en el body de creación: se toma del token, así nadie puede abrir una cuenta a nombre de otro.

La API de transferencias exige el scope y el rol a la vez: `transfer.read` con `transfer_read` para consultar, y `transfer.write` con `transfer_write` para crear. Tener el scope sin el rol no alcanza: carol puede pedir `transfer.write` pero sigue recibiendo 403. Dentro de `account-service`, `account_internal` permite mover saldo en cualquier cuenta, aunque las lecturas con `X-Customer-Id` siguen filtrando por propietario; por eso el rol interno y `admin` no son lo mismo.

Dos detalles que valen la pena saber:

- Spring Security 7 no lee `realm_access.roles` por defecto: su converter solo mira `scope`/`scp`. `SecurityConfig` registra un `JwtAuthenticationConverter` que transforma esos roles en `ROLE_*` en mayúsculas. Sin eso, todo daría 403 aunque el token fuera válido.
- El `JwtDecoder` se arma con `NimbusJwtDecoder.withJwkSetUri(...)` dentro del bean, en lugar de usar `issuer-uri` directamente, para no descargar el JWKS al arrancar el contexto de los tests. La validación del issuer se añade aparte con `JwtValidators.createDefaultWithIssuer(...)`.

Los 401 y 403 se escriben en `application/problem+json` desde `AuthenticationEntryPoint` y `AccessDeniedHandler`, porque los rechaza la cadena de filtros antes de llegar al controller. El `@RestControllerAdvice` cubre las mismas excepciones si algún día se lanzan dentro de él, reutilizando los mismos `type`.

## Transferencias

`POST /api/v1/transfers` crea la transferencia en `PENDING` y exige `Idempotency-Key`. El hash cubre cliente, cuentas, importe, moneda y referencia: repetir la clave con el mismo cuerpo devuelve la respuesta guardada, y reutilizarla con otro cuerpo da 409. Antes de guardar se comprueba que la cuenta de origen pertenezca al `customerId` del token; una cuenta ajena responde 404.

Un worker revisa periódicamente las transferencias pendientes. La saga hace el débito en la cuenta de origen, el depósito en la de destino y, si el depósito falla, devuelve el importe con un depósito de compensación en la cuenta de origen. Las llamadas usan claves estables `transfer-{id}-debit`, `transfer-{id}-credit` y `transfer-{id}-refund`, así que reintentar después de un corte no duplica cargos. Los estados son `PENDING`, `DEBITED`, `COMPLETING`, `COMPENSATING`, `COMPLETED`, `COMPENSATED` y `FAILED`. Las consultas muestran solo las transferencias propias; `admin` ve todas.

Configuración por entorno: `ACCOUNT_SERVICE_BASE_URL`, `TRANSFER_ORCHESTRATOR_ENABLED`, `TRANSFER_ORCHESTRATOR_POLL_INTERVAL` y `TRANSFER_ORCHESTRATOR_BATCH_SIZE`.

## Eventos

Cada servicio publica en su propio tópico. `account-service` usa `account.movements.v1` con clave de partición `accountId`, así que los eventos de una misma cuenta caen siempre en la misma partición y conservan su orden. Hay dos tipos:

```json
{"eventId":"...","eventType":"ACCOUNT_OPENED","accountId":"...","customerId":"...","number":"...","currency":"EUR","occurredAt":"..."}
{"eventId":"...","eventType":"ACCOUNT_MOVEMENT_RECORDED","accountId":"...","customerId":"...","type":"CREDIT","amount":25.50,"balanceAfter":25.5000,"reference":"...","occurredAt":"..."}
```

`eventType` va en el payload y también en la columna `event_type` del outbox. Cada `eventId` es único y es la clave de deduplicación del consumidor: la entrega es `at-least-once`, así que un mismo evento puede llegar dos veces. Guarda los `eventId` procesados y descarta los repetidos antes de aplicar el efecto.

La escritura del evento y la de la cuenta o el asiento ocurren en la misma transacción de PostgreSQL. El registro queda en `outbox_events` y un publicador periódico reclama un lote con `FOR UPDATE SKIP LOCKED`, publica y marca `published_at`. Si Kafka cae, la fila sigue pendiente con `attempts` y `next_attempt_at`; al volver se reanuda con backoff. Tras agotar `max-attempts` el evento va a `<topic>.dlq` (`account.movements.v1.dlq` o `transfer.events.v1.dlq`) y se marca como publicado para no bloquear la cola. Con varias instancias, cada una toma filas distintas.

Para ver el flujo en local:

```powershell
docker exec banco-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic account.movements.v1 --from-beginning --property print.key=true

docker exec banco-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic transfer.events.v1 --from-beginning --property print.key=true
```

`transfer-service` usa `transfer.events.v1` con clave `transferId`. Emite `TransferInitiated`, `TransferDebited`, `TransferCompleted` y `TransferCompensated`; todos llevan `transferId`, `customerId`, importe y moneda, y el de compensación incluye el motivo. La lógica de outbox, idempotencia y deduplicación está duplicada en cada servicio a propósito: ninguno depende del código del otro.

Configuración por entorno: `KAFKA_BOOTSTRAP_SERVERS`, `OUTBOX_TOPIC`, `OUTBOX_PUBLISHER_ENABLED`, `OUTBOX_BATCH_SIZE`, `OUTBOX_POLL_INTERVAL`, `OUTBOX_MAX_ATTEMPTS` y `OUTBOX_RETRY_BACKOFF`. El productor usa `acks=all` e idempotencia; `max.block.ms` y `delivery.timeout.ms` están acotados para que una caída del broker no deje al publicador esperando un minuto por evento.

## Tests

```powershell
# desde account-service/

# solo unitarios, sin Docker:
.\mvnw.cmd test "-Dtest=AccountServiceImplTest"

# todo junto (unitarios + integración):
.\mvnw.cmd test "-Dtest=AccountServiceImplTest,AccountServiceApplicationTests,AccountRepositoryIT,AccountConcurrencyIT,IdempotencyConcurrencyIT"

# uno solo:
.\mvnw.cmd test "-Dtest=AccountRepositoryIT"
```

Con `mvn test` a secas Surefire corre las clases `*Test` y `*Tests`: entran `AccountServiceImplTest` y `AccountServiceApplicationTests`. Ojo que esta última ya usa Testcontainers, así que **necesita Docker aunque no se llame `*IT`**. Los `*IT` no los corre por defecto; hay que pedirlos con `-Dtest=...`. Cuando se monte CI se añadirá Failsafe para que `mvn verify` los corra solos.

Usamos Testcontainers con `postgres:18-alpine`, la misma imagen del compose. No H2: las regex de los CHECK, `gen_random_uuid()`, el trigger del ledger y el `@Generated` de `createdAt` no se comportan igual. Un test verde en H2 no dice mucho sobre lo que va a pasar en producción.

`PostgresIntegrationTest` arranca un contenedor en un bloque `static { }` y `@DynamicPropertySource` mete `DB_URL`, `DB_USER` y `DB_PASSWORD` con el puerto aleatorio. `application-test.yaml` los lee sin valor por defecto: si el contenedor no arranca, el test falla en vez de conectarse a una BD local. Flyway migra desde cero en cada corrida y `@Sql("/clean-db.sql")` limpia antes de cada test.

Un detalle que me costó un rato: no poner `@Testcontainers`/`@Container` en la base. Esa extensión para el contenedor al terminar cada clase y lo reinicia en otro puerto, pero Spring deja el contexto en caché con la URL anterior → `Connection refused`. Por eso el `static { POSTGRES.start(); }`: se arranca una vez y lo comparten todas las clases.

Cosas de versión que conviene recordar:

- Boot 4 no trae `@AutoConfigureTestDatabase` ni `spring.test.database.replace`.
- `DynamicPropertyRegistry` solo tiene `add()` (no `addFirst()`).
- Testcontainers 2.x: `org.testcontainers.postgresql.PostgreSQLContainer`, y la clase no es genérica (sin `<>`).
- Boot 4 usa Jackson 3: `tools.jackson.databind.ObjectMapper` y `tools.jackson.core.JacksonException`. Los imports `com.fasterxml.jackson.databind.*` no compilan, aunque `jackson-annotations` 2 siga en el classpath.
- `HttpStatus.UNPROCESSABLE_ENTITY` está deprecado; en Spring 7 es `HttpStatus.UNPROCESSABLE_CONTENT`.

## Decisiones

- Dinero siempre `BigDecimal` / `NUMERIC(19,4)`. Nunca `double`.
- `CurrencyCode` es un value object (record) validado contra el registro ISO 4217 del JDK, con `AttributeConverter` a `VARCHAR(3)`: no hay enum que mantener ni hizo falta una migración nueva. Normaliza a mayúsculas y expone `fractionDigits()`. El CHECK de la BD se queda como defensa en profundidad para inserts directos por SQL.
- Los CHECK, UNIQUE y FK están en la BD, además de la validación del DTO. No nos fiamos solo de Bean Validation.
- `saveAndFlush` dentro del `try`: si no, el error de UNIQUE por concurrencia sale en el commit, fuera del catch, y no se puede traducir a `AccountAlreadyExistsException`.
- `AccountMapper` (MapStruct) solo convierte entidad → DTO. En el pom los annotation processors van Lombok antes que MapStruct, para que MapStruct vea los getters generados.
- `LedgerEntry` no lleva `@Immutable` de Hibernate: en Hibernate 7 no se puede combinar con `@Generated(event = INSERT)` porque el re-read de `created_at` intenta un lock WRITE sobre una entidad read-only y revienta. La inmutabilidad la garantiza el trigger `trg_ledger_immutable` de la V2 y que no existe ningún path de UPDATE/DELETE en el código.
- Idempotencia: la PK de `idempotency_keys` es `(customer_id, account_id, idempotency_key)`; el cliente y la cuenta se sacan de la cuenta destino. Así la misma clave en dos cuentas propias son dos operaciones distintas. El hash SHA-256 cubre cuenta + tipo + amount + reference, aplicando `stripTrailingZeros()` para que `100.50` y `100.5` den el mismo valor. Solo se cachean respuestas exitosas. La migración V4 descartó las claves guardadas con el esquema anterior porque no era posible derivar su `account_id`.
- El historial se ordena por `ledger_entries.entry_seq`, un valor de secuencia asignado en el INSERT. `created_at` usa `clock_timestamp()` para reflejar el momento del insert, pero no es la clave de ordenación: `now()` es estable por transacción y dos escrituras serializadas por el `FOR UPDATE` de la cuenta podían quedar con fechas invertidas respecto a `balance_after`.
- Los decimales del importe se validan contra `CurrencyCode.fractionDigits()`: USD y EUR admiten 2, JPY 0, KWD 3. Se cuenta sobre `stripTrailingZeros()`, así que `100.500` es válido en USD pero `10.0001` no. El JDK devuelve `-1` para monedas sin fracción estándar (por ejemplo XAU) y en ese caso se exigen importes enteros. El error es un 422 con `type` `https://banco.local/problems/invalid-amount-scale`. No hace falta migración: `NUMERIC(19,4)` sigue siendo la cota superior del DTO y cubre las monedas ISO con 4 decimales; reducir la escala sería destructivo. La BD normaliza la escala a 4 al guardar.
- La reserva de una `Idempotency-Key` es un `INSERT ... ON CONFLICT DO NOTHING`, no un `saveAndFlush` con `catch`. Usar una violación de PK como control de flujo dejaba la transacción en estado `aborted` en PostgreSQL y, además, el `findById` posterior podía devolver la entidad fallida del persistence context en vez de la fila ganadora: un rival con estado `COMPLETED` se confundía con `IN_PROGRESS`. Con el insert condicional se relee la fila real y se devuelve el replay o el 409 que corresponda. `IdempotencyConcurrencyIT` cubre ambos casos con dos transacciones solapadas.
- `transfer-service` no importa código de `account-service`: cada uno tiene su outbox, su idempotencia y su seguridad. Así se puede desplegar por separado sin acoplar despliegues ni esquemas.
- El service account de `transfer-service` llama a `account-service` con su propio token de `client_credentials`. La cabecera `X-Customer-Id` solo se acepta con `account_internal`; ese rol puede mover saldo en cualquier cuenta, pero las lecturas siguen filtrando por propietario. `admin` es el único que ve todas las cuentas.
- Los scopes no filtran por usuario: la API de transferencias pide scope y rol a la vez. Un token con `transfer.write` pero sin `transfer_write` recibe 403.
- La saga no mantiene una transacción abierta mientras llama a `account-service`. Cada transición de estado y su evento se guardan juntos, y las claves `transfer-{id}-debit`, `-credit` y `-refund` hacen que reintentar una llamada ya exitosa no duplique el cargo.
