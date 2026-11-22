# banco-microservicio

Dos servicios Spring Boot 4.1 sobre PostgreSQL 18, Kafka y Keycloak, y un `api-gateway` con Spring Cloud Gateway WebMVC. `account-service` gestiona cuentas y movimientos; `transfer-service` coordina transferencias entre cuentas; el gateway expone un único punto de entrada. Cada servicio tiene su propia base de datos, su JAR y su configuración de seguridad, y comparten el compose, el realm y este README.

## Arrancarlo

```powershell
docker compose up -d

# terminal 1
cd account-service
.\mvnw.cmd spring-boot:run

# terminal 2
cd transfer-service
.\mvnw.cmd spring-boot:run

# terminal 3
cd api-gateway
.\mvnw.cmd spring-boot:run
```

El compose levanta PostgreSQL en localhost:5432, Keycloak en localhost:8080, Kafka en localhost:9092 y Floci (S3 local compatible) en localhost:4566. `account-service` queda en http://localhost:8081, `transfer-service` en http://localhost:8083 y `api-gateway` en http://localhost:8084. Cada servicio usa su propia BD (`accounts_db` y `transfers_db`) y su propio usuario con permisos solo sobre esa BD; en producción serían instancias distintas.

### account-service

Endpoints:

- `POST /api/v1/accounts` → 201 con header `Location`. Body: `number`, `currency`. El `customerId` se toma del token, no del body.
- `GET /api/v1/accounts/{id}` → 200 o 404.
- `POST /api/v1/accounts/{id}/deposits` → 201. Body: `amount`, `reference`. Solo `admin` o el service account de `transfer-service` con `account_internal`.
- `POST /api/v1/accounts/{id}/withdrawals` → 201. Body: `amount`, `reference`.
- `GET /api/v1/accounts/{id}/movements` → 200 con el historial.
- `GET /actuator/health`, `GET /actuator/metrics`, `GET /actuator/prometheus`.

Solo las cuentas `ACTIVE` pueden mover saldo. Un depósito o retiro sobre `BLOCKED` o `CLOSED` responde 409 con `type` `https://banco.local/problems/account-not-active`, sin cambiar el saldo ni dejar una clave de idempotencia pendiente.

### transfer-service

- `POST /api/v1/transfers` → 201. Body: `sourceAccountId`, `targetAccountId`, `amount`, `currency`, `reference`. Exige el header `Idempotency-Key`.
- `GET /api/v1/transfers/{id}` → 200 o 404.
- `GET /api/v1/transfers` → 200 con las transferencias del propietario, o todas si es `admin`.
- `POST /api/v1/transfers/{id}/receipts` → 201/202/409/503. Sube un comprobante (`multipart/form-data`, campo `file`).
- `GET /api/v1/transfers/{id}/receipts` → 200 con los comprobantes de la transferencia.
- `GET /api/v1/transfers/{id}/receipts/{receiptId}/file` → 200 con el archivo verificado, o 404/502.
- `GET /actuator/health`, `GET /actuator/metrics`, `GET /actuator/prometheus`.

`reference` la elige el cliente y es obligatoria. Se guarda en el asiento y `UNIQUE (account_id, reference, type)` evita que el mismo movimiento se registre dos veces; repetirla devuelve 409. Retirar más de lo disponible devuelve 422.

Los POST aceptan además el header opcional `Idempotency-Key`. Repetiendo la misma clave con el mismo body se devuelve la respuesta guardada sin insertar otro asiento; con otro body da 409. Solo se guardan respuestas exitosas: si el movimiento falla, la transacción se revierte y la clave queda libre para reintentar.

Todas las rutas exigen un Bearer token de Keycloak. Cómo pedirlo, qué roles hay y cómo se filtran los datos por propietario está en [Seguridad](#seguridad).

Los errores vienen en RFC 7807. Fíjate en `type`: es un identificador estable, no una URL que se visita. Es lo que el cliente debería usar para decidir qué hacer; el `detail` es para leer, no para programar contra él. Los de autenticación y autorización son `https://banco.local/problems/unauthorized` y `https://banco.local/problems/forbidden`, respectivamente.

## Seguridad

El compose levanta también Keycloak en `http://localhost:8080`. El realm `banco` se importa desde `keycloak/realm-banco.json`. Los clientes `account-service` y `transfer-service` tienen Direct Access Grants habilitado solo para desarrollo local. Los tres componentes son resource servers: validan el JWT, no manejan sesiones ni contraseñas propias.

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

## api-gateway

Punto de entrada único en http://localhost:8084. Es un módulo independiente con Spring Cloud Gateway WebMVC: las rutas son estáticas y viven en `spring.cloud.gateway.server.webmvc.routes` de `api-gateway/src/main/resources/application.yaml`. No hay descubrimiento de servicios ni variante reactiva.

| ruta | destino |
| --- | --- |
| `/api/v1/accounts`, `/api/v1/accounts/**` | `http://localhost:8081` |
| `/api/v1/transfers`, `/api/v1/transfers/**` | `http://localhost:8083` |

El gateway valida el Bearer token contra el mismo realm antes de rutear, con el mismo mapeo de `realm_access.roles` a `ROLE_*` y de `scope` a `SCOPE_*`. Los servicios siguen validando por su cuenta: si alguien los llama directamente, no baja el nivel de seguridad. No se creó un cliente nuevo en Keycloak; sirven los tokens de `account-service` y `transfer-service` descritos arriba.

Las reglas de acceso son: lectura de cuentas con `account_read`, `admin` o `account_internal`; depósitos solo con `admin` o `account_internal`; el resto de escrituras de cuentas con `account_write`, `admin` o `account_internal`; y transferencias con scope y rol a la vez (`transfer.read` con `transfer_read`, `transfer.write` con `transfer_write`). Los 401 y 403 salen en `application/problem+json` con `correlationId`, usando los mismos `type` de los servicios.

No hay reintentos automáticos en los POST: un 500 del servicio destino se devuelve tal cual al cliente. El retry de la saga `transfer-service` → `account-service` sigue viviendo en `transfer-service`, con sus claves de idempotencia; el gateway no duplica esa lógica.

Si el cliente envía `X-Correlation-Id`, se respeta y se propaga; si no, se genera uno. Se guarda en el MDC, se devuelve en la respuesta, se añade a los `ProblemDetail` y viaja al servicio destino en la misma cabecera, incluso cuando el error ocurre durante el ruteo. Además de los tipos de autenticación y autorización, el gateway usa `https://banco.local/problems/invalid-argument`, `not-found`, `upstream-error` e `internal-error`.

`/actuator/health`, `/actuator/health/**` y `/actuator/info` son públicos. `/actuator/metrics` y `/actuator/prometheus` piden token. Al ser WebMVC no existe `/actuator/gateway`; para ver los mappings disponibles usa `/actuator/mappings`.

Configuración por entorno: `KEYCLOAK_ISSUER_URI`, `ACCOUNT_SERVICE_URL` y `TRANSFER_SERVICE_URL`.

## Transferencias

`POST /api/v1/transfers` crea la transferencia en `PENDING` y exige `Idempotency-Key`. El hash cubre cliente, cuentas, importe, moneda y referencia: repetir la clave con el mismo cuerpo devuelve la respuesta guardada, y reutilizarla con otro cuerpo da 409. Antes de guardar se comprueba que la cuenta de origen pertenezca al `customerId` del token; una cuenta ajena responde 404.

Un worker revisa periódicamente las transferencias pendientes. La saga hace el débito en la cuenta de origen, el depósito en la de destino y, si el depósito falla, devuelve el importe con un depósito de compensación en la cuenta de origen. Las llamadas usan claves estables `transfer-{id}-debit`, `transfer-{id}-credit` y `transfer-{id}-refund`, así que reintentar después de un corte no duplica cargos. Los estados son `PENDING`, `DEBITED`, `COMPLETING`, `COMPENSATING`, `COMPLETED`, `COMPENSATED` y `FAILED`. Las consultas muestran solo las transferencias propias; `admin` ve todas.

Configuración por entorno: `ACCOUNT_SERVICE_BASE_URL`, `ACCOUNT_SERVICE_CONNECT_TIMEOUT`, `ACCOUNT_SERVICE_READ_TIMEOUT`, `TRANSFER_ORCHESTRATOR_ENABLED`, `TRANSFER_ORCHESTRATOR_POLL_INTERVAL`, `TRANSFER_ORCHESTRATOR_BATCH_SIZE`, `TRANSFER_ORCHESTRATOR_RETRY_BACKOFF` y `TRANSFER_ORCHESTRATOR_CIRCUIT_OPEN_BACKOFF`.

## Eventos

Cada servicio publica en su propio tópico. `account-service` usa `account.movements.v1` con clave de partición `accountId`, así que los eventos de una misma cuenta caen siempre en la misma partición y conservan su orden. Hay dos tipos:

```json
{"eventId":"...","eventType":"ACCOUNT_OPENED","accountId":"...","customerId":"...","number":"...","currency":"EUR","occurredAt":"...","correlationId":"..."}
{"eventId":"...","eventType":"ACCOUNT_MOVEMENT_RECORDED","accountId":"...","customerId":"...","type":"CREDIT","amount":25.50,"balanceAfter":25.5000,"reference":"...","occurredAt":"...","correlationId":"..."}
```

`eventType` va en el payload y también en la columna `event_type` del outbox. Cada `eventId` es único y es la clave de deduplicación del consumidor: la entrega es `at-least-once`, así que un mismo evento puede llegar dos veces. Guarda los `eventId` procesados y descarta los repetidos antes de aplicar el efecto.

La escritura del evento y la de la cuenta o el asiento ocurren en la misma transacción de PostgreSQL. El registro queda en `outbox_events` y un publicador periódico reclama un lote con `FOR UPDATE SKIP LOCKED`, publica y marca `published_at`. Si Kafka cae, la fila sigue pendiente con `attempts` y `next_attempt_at`; al volver se reanuda con backoff. Tras agotar `max-attempts` el evento va a `<topic>.dlq` (`account.movements.v1.dlq` o `transfer.events.v1.dlq`) y se marca como publicado para no bloquear la cola. Con varias instancias, cada una toma filas distintas.

Para ver el flujo en local:

```powershell
docker exec banco-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic account.movements.v1 --from-beginning --property print.key=true

docker exec banco-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic transfer.events.v1 --from-beginning --property print.key=true
```

`transfer-service` usa `transfer.events.v1` con clave `transferId`. Emite `TransferInitiated`, `TransferDebited`, `TransferCompleted` y `TransferCompensated`; todos llevan `transferId`, `customerId`, `correlationId`, importe y moneda, y el de compensación incluye el motivo. La lógica de outbox, idempotencia y deduplicación está duplicada en cada servicio a propósito: ninguno depende del código del otro.

Configuración por entorno: `KAFKA_BOOTSTRAP_SERVERS`, `OUTBOX_TOPIC`, `OUTBOX_PUBLISHER_ENABLED`, `OUTBOX_BATCH_SIZE`, `OUTBOX_POLL_INTERVAL`, `OUTBOX_MAX_ATTEMPTS`, `OUTBOX_RETRY_BACKOFF`, `OUTBOX_METRICS_REFRESH_INTERVAL` y `OUTBOX_STALE_THRESHOLD`. El productor usa `acks=all` e idempotencia; `max.block.ms` y `delivery.timeout.ms` están acotados para que una caída del broker no deje al publicador esperando un minuto por evento.

## Resiliencia y observabilidad

`transfer-service` llama a `account-service` con `connect-timeout=1s` y `read-timeout=2s`, y envuelve cada llamada en circuit breaker → retry → time limiter → bulkhead, en ese orden. El circuito se abre al 50% de fallos sobre 20 llamadas, espera 30 s y permite 3 llamadas de prueba en half-open. El retry hace 3 intentos totales con backoff exponencial y jitter; no reintenta si el circuito está abierto ni si el bulkhead está lleno. El bulkhead reserva 5 hilos, llega a 10 y admite 20 esperando en cola. El time limiter cancela la llamada a los 3 s.

Un fallo transitorio no marca la transferencia como fallida: queda en `PENDING` con `failure_reason` y `next_attempt_at`, y el worker la reanuda cuando pasa el espaciado. Los fallos de negocio sí avanzan la saga a `FAILED` o inician la compensación. El espaciado del worker se ajusta con `TRANSFER_ORCHESTRATOR_RETRY_BACKOFF` y `TRANSFER_ORCHESTRATOR_CIRCUIT_OPEN_BACKOFF`.

La correlación viaja en `X-Correlation-Id`. Si el cliente no la envía, el servicio la genera; se guarda en el MDC, se devuelve en la respuesta, aparece en los `ProblemDetail` y se incluye en los eventos del outbox. La saga persiste el valor en `transfers.correlation_id` y lo restaura al reanudarse, incluso cuando el bulkhead cambia de hilo. Así se puede seguir una misma operación en logs y en Kafka de extremo a extremo sin depender de un tracing distribuido.

Los endpoints `/actuator/health`, `/actuator/metrics` y `/actuator/prometheus` piden Bearer token en ambos servicios. Además de las métricas de Resilience4j (`resilience4j_*`), hay métricas de dominio:

- `transfers.created`, `transfers.saga.completed`, `transfers.saga.compensated`, `transfers.saga.failed` y `transfers.saga.duration`.
- `transfers.client.retries` por motivo (`circuit_open`, `bulkhead_full`, `unavailable`), `transfers.circuit.state` con etiqueta `state` y `transfers.circuit.transitions` con `from`/`to`.
- `outbox.events.published`, `outbox.events.dlq`, `outbox.events.pending`, `outbox.events.oldest.age.seconds` y `outbox.publish.lag`.
- `account.movements.recorded` con etiqueta `type` (`CREDIT` o `DEBIT`).

`GET /actuator/health` incluye el componente `outbox`. Si el evento pendiente más antiguo supera `OUTBOX_STALE_THRESHOLD` (1 minuto por defecto), el estado general pasa a `DOWN` y el detalle muestra `pending`, `oldestPendingAgeSeconds` y `staleThresholdSeconds`. Esto detecta que el publicador dejó de publicar, no solo que la base de datos responda.

## Comprobantes de transferencia

`transfer-service` permite subir, listar y descargar comprobantes asociados a una transferencia. Los archivos se guardan en un bucket S3 compatible (Floci en local) y su estado se persiste en `transfer_receipts` (migración V6). El diseño detallado está en `transfer-service/RECEIPT_DECISIONS.md`.

### Endpoints

| Método | Ruta | Auth | Resultado |
| --- | --- | --- | --- |
| `POST` | `/api/v1/transfers/{id}/receipts` | `SCOPE_transfer.write` + `ROLE_TRANSFER_WRITE` | 201 ACTIVE; 202 PENDING con `Location` si el resultado S3 es ambiguo; 409 `receipt-failed`; 503 `receipt-storage-unavailable`; 400 `receipt-invalid-file`; 404 transferencia inexistente/ajena |
| `GET` | `/api/v1/transfers/{id}/receipts` | `SCOPE_transfer.read` + `ROLE_TRANSFER_READ` | 200 con lista `ReceiptResponse` |
| `GET` | `/api/v1/transfers/{id}/receipts/{receiptId}/file` | `SCOPE_transfer.read` + `ROLE_TRANSFER_READ` | 200 bytes verificados; 404 no encontrado/ajeno/no ACTIVE; 502 `receipt-integrity-failed` |

La propiedad se resuelve contra la transferencia: el `customer_id` del comprobante es siempre `transfer.customerId()` (nunca el admin). Un usuario normal solo ve/descarga comprobantes de sus transferencias; `admin` puede consultar por transferencia, pero no es bypass HTTP.

### Flujo de carga

- **Validación antes de persistir**: tamaño (<= 5MB por defecto), SHA-256 streaming, magic bytes/MIME permitido (PDF, PNG, JPEG), nombre saneado y coherencia de content-type. Un archivo inválido responde 400 y no crea fila PENDING.
- **PENDING**: se crea con `lease_owner`, `lease_expires_at`, `next_attempt_at` inicial = lease, `attempts=1` y `object_key` determinista `transfers/{transferId}/receipts/{receiptId}/{safeFilename}`.
- **Upload S3**: usa `RequestBody.fromContentProvider(...)` replayable y checksum SHA-256. Un éxito confirma ACTIVE (201). Un fallo definitivo de checksum marca FAILED (409). Un fallo definitivo de storage reprograma retry y responde 503 con `receiptId`. Un timeout/resultado ambiguo libera lease, reconsulta el estado y responde 201/409/202 según corresponda.
- **Heartbeat de lease**: mientras el upload está en curso se renueva el lease por SQL (`now() + leaseDuration`) solo si el receipt sigue PENDING, con el mismo owner y lease vigente.

### Reconciliación

`ReceiptReconciler` corre con `@Scheduled` (habilitable por config) y reclama lotes PENDING con CTE `FOR UPDATE SKIP LOCKED`. Hace `HeadObject` en S3: checksum coincide => ACTIVE; checksum distinto => FAILED; objeto ausente antes de `missingGrace` => reschedule; ausente tras gracia o max attempts => FAILED; 4xx/5xx según clasificación.

### Descarga segura

Solo se sirve un comprobante `ACTIVE`. El archivo se descarga a un temporal endurecido (`temp-dir/receipts`), se validan checksum S3 y SHA-256 contra metadata antes de exponer bytes, y se aplican timeouts/watchdog cooperativo con `ResponseInputStream.abort()`. Headers: `Content-Disposition` attachment, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`.

El directorio temporal intenta ACL/POSIX restrictiva al arrancar; si no puede verificarse, el arranque falla salvo `transfer.receipts.download.allow-unverified-temp-acl=true` (solo dev/test).

### Configuración

Variables principales (valores por defecto en `application.yaml`):

- S3/Floci: `RECEIPT_S3_BUCKET`, `RECEIPT_S3_REGION`, `RECEIPT_S3_ENDPOINT`, `RECEIPT_S3_ACCESS_KEY`, `RECEIPT_S3_SECRET_KEY`, `RECEIPT_S3_PATH_STYLE`, `RECEIPT_S3_CHECKSUM_*`.
- Upload: `RECEIPT_MAX_FILE_SIZE`, `RECEIPT_UPLOAD_MAX_ATTEMPTS`, `RECEIPT_UPLOAD_LEASE_DURATION`, `RECEIPT_UPLOAD_HEARTBEAT_INTERVAL`, `RECEIPT_UPLOAD_RETRY_BACKOFF`, `RECEIPT_UPLOAD_MISSING_GRACE`.
- Download: `RECEIPT_DOWNLOAD_MAX_CONCURRENT`, `RECEIPT_DOWNLOAD_TEMP_DIR`, `RECEIPT_DOWNLOAD_SOCKET_READ_TIMEOUT`, `RECEIPT_DOWNLOAD_FIRST_READ_TIMEOUT`, `RECEIPT_DOWNLOAD_TOTAL_SOFT_DEADLINE`, `RECEIPT_DOWNLOAD_STREAM_BUFFER_SIZE`, `RECEIPT_DOWNLOAD_TEMP_TTL`, `RECEIPT_DOWNLOAD_ALLOW_UNVERIFIED_TEMP_ACL`.
- Reconcile: `RECEIPT_RECONCILE_ENABLED`, `RECEIPT_RECONCILE_INTERVAL`, `RECEIPT_RECONCILE_BATCH_SIZE`.

Para local con el compose: endpoint `http://localhost:4566`, `path-style-access-enabled=true`, bucket `banco-comprobantes-local`, credenciales de prueba Floci.

## Contenedores e imágenes

Los tres módulos tienen `Dockerfile` multi-stage independientes (build Maven, capas Spring Boot layertools, usuario no root) y `.dockerignore` propio. La imagen base es Amazon Corretto 21 Alpine 3.24 fijada por digest.

Requisitos: Docker Engine/Desktop con Docker Compose v2.

### Arranque local con Compose

```powershell
# levantar infraestructura + los tres servicios
docker compose --profile apps up --build -d

# ver logs
docker compose --profile apps logs -f account-service
docker compose --profile apps logs -f transfer-service
docker compose --profile apps logs -f api-gateway

# parar y eliminar contenedores del perfil
docker compose --profile apps down
```

El compose funciona sin `.env` gracias a defaults dev/local. Si necesitas personalizar valores, copia `.env.example` a `.env`; `.env` está ignorado por git y no debe commitearse. Los `.dockerignore` excluyen `.env` y artefactos locales del contexto de build.

Para desarrollo local sin contenedores, sigue valiendo el flujo de `## Arrancarlo` con `mvnw spring-boot:run`.

### Variables relevantes

| Variable | Default | Uso |
| --- | --- | --- |
| `IMAGE_REGISTRY` | `ghcr.io/jhoel-gavidia/banco-microservicio` | Prefijo de imagen para servicios del perfil `apps` |
| `IMAGE_TAG` | `local` | Tag local de las imágenes |
| `ACCOUNT_SERVICE_PORT` / `TRANSFER_SERVICE_PORT` / `API_GATEWAY_PORT` | `8081` / `8083` / `8084` | Puertos host |
| `ACCOUNTS_DB_PORT` / `KAFKA_PORT` / `KEYCLOAK_PORT` / `FLOCI_PORT` | `5432` / `9092` / `8080` / `4566` | Puertos host de infraestructura |
| `ACCOUNTS_DB_*` / `TRANSFERS_DB_*` | ver `.env.example` | Credenciales/nombres BD dev local |
| `KEYCLOAK_ISSUER_URI` | `http://localhost:8080/realms/banco` | Issuer esperado en JWT; en compose se valida ese issuer y se descarga JWKS desde `keycloak:8080` dentro de la red Docker |
| `AWS_REGION` / `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` / `RECEIPT_S3_BUCKET` | `us-east-1` / `test` / `test` / `banco-comprobantes-local` | Config S3 local vía Floci, sin AWS real ni costo |

Variables de servicio dentro de contenedor (ya definidas en `compose.yaml`): `DB_URL`, `DB_USER`, `DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS`, `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI`, `KEYCLOAK_TOKEN_URI`, `ACCOUNT_SERVICE_BASE_URL`, `ACCOUNT_SERVICE_URL`, `TRANSFER_SERVICE_URL`, `RECEIPT_S3_ENDPOINT`.

### Sondas y seguridad

- Públicas sin token: `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`.
- `/actuator/health` no expone detalles sensibles sin autorización (`show-details: when-authorized`).
- `/actuator/metrics` y `/actuator/prometheus` requieren autenticación.
- Las imágenes corren como usuario no root y no incluyen secretos.

### CI / GHCR

Workflow: `.github/workflows/ci-images.yml`. Se ejecuta en `push` y `pull_request` con matriz por módulo (`account-service`, `transfer-service`, `api-gateway`). Cada job hace: `mvn -B -ntp test`, build de imagen Docker, scan Trivy, generación de SBOM y, solo en `push` a `main`, login/push a GHCR con tags `sha-<7>` y `latest`. No hay despliegue automático a cloud.

## Tests

```powershell
# desde account-service/

# solo unitarios, sin Docker:
.\mvnw.cmd test "-Dtest=AccountServiceImplTest"

# todo junto (unitarios + integración):
.\mvnw.cmd test "-Dtest=AccountServiceImplTest,AccountServiceApplicationTests,AccountRepositoryIT,AccountConcurrencyIT,IdempotencyConcurrencyIT"

# uno solo:
.\mvnw.cmd test "-Dtest=AccountRepositoryIT"

# desde transfer-service/

# suite normal (sin Docker/Floci, excluye *IT):
.\mvnw.cmd clean test

# integración de comprobantes con Postgres + Floci (requiere Docker):
.\mvnw.cmd -DskipITs=false failsafe:integration-test failsafe:verify
```

En `api-gateway/` basta `.\mvnw.cmd test`: 15 tests sin Docker. El ruteo se comprueba contra un `HttpServer` embebido, así que no hace falta arrancar los servicios ni Keycloak.

Con `mvn test` a secas Surefire corre las clases `*Test` y `*Tests`. En `account-service`, `AccountServiceApplicationTests` usa Testcontainers, así que **necesita Docker aunque no se llame `*IT`**; los `*IT` no se corren por defecto y se piden con `-Dtest=...`. En `transfer-service`, Surefire excluye `**/*IT.java` y Failsafe los ejecuta solo cuando se habilita explícitamente (`-DskipITs=false`), para que `mvn clean test` no dependa de Docker/Floci.

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
