# banco-microservicio

Account-service: Spring Boot 4.1, PostgreSQL 18, Flyway y Testcontainers.

## Fases de desarrollo

| # | Fase |
| --- | --- |
| 0 | Esqueleto del proyecto y Maven |
| 1 | PostgreSQL, Docker Compose y Flyway |
| 2 | Feature account: entidad, servicio y API REST |
| 3 | Tests con Testcontainers |
| 4 | Feature ledger: depósitos, retiros e historial |
| 5 | Idempotencia de movimientos |
| 6 | Seguridad con Keycloak |
| 7 | Outbox y Kafka |
| 8 | Transfer Service con saga y compensaciones |
| 9 | Resiliencia y observabilidad |
| 10 | API Gateway |
| 11 | Dockerfile, GitHub Actions y despliegue |

## Arrancarlo

```powershell
docker compose up -d
cd account-service
.\mvnw.cmd spring-boot:run
```

El compose levanta la BD en localhost:5432 y la app queda en http://localhost:8081.

Endpoints:

- `POST /api/v1/accounts` → 201 con header `Location`. Body: `number`, `currency`. El `customerId` se toma del token, no del body.
- `GET /api/v1/accounts/{id}` → 200 o 404.
- `POST /api/v1/accounts/{id}/deposits` → 201. Body: `amount`, `reference`. Solo `admin`; más adelante también un servicio interno con su propio scope.
- `POST /api/v1/accounts/{id}/withdrawals` → 201. Body: `amount`, `reference`.
- `GET /api/v1/accounts/{id}/movements` → 200 con el historial.
- `GET /actuator/health`.

Solo las cuentas `ACTIVE` pueden mover saldo. Un depósito o retiro sobre `BLOCKED` o `CLOSED` responde 409 con `type` `https://banco.local/problems/account-not-active`, sin cambiar el saldo ni dejar una clave de idempotencia pendiente.

`reference` la elige el cliente y es obligatoria. Se guarda en el asiento y `UNIQUE (account_id, reference, type)` evita que el mismo movimiento se registre dos veces; repetirla devuelve 409. Retirar más de lo disponible devuelve 422.

Los POST aceptan además el header opcional `Idempotency-Key`. Repetiendo la misma clave con el mismo body se devuelve la respuesta guardada sin insertar otro asiento; con otro body da 409. Solo se guardan respuestas exitosas: si el movimiento falla, la transacción se revierte y la clave queda libre para reintentar.

Todas las rutas exigen un Bearer token de Keycloak. Cómo pedirlo, qué roles hay y cómo se filtran los datos por propietario está en [Seguridad](#seguridad).

Los errores vienen en RFC 7807. Fíjate en `type`: es un identificador estable, no una URL que se visita. Es lo que el cliente debería usar para decidir qué hacer; el `detail` es para leer, no para programar contra él. Los de autenticación y autorización son `https://banco.local/problems/unauthorized` y `https://banco.local/problems/forbidden`, respectivamente.

## Seguridad

El compose levanta también Keycloak en `http://localhost:8080`. El realm `banco` se importa desde `keycloak/realm-banco.json` y el cliente `account-service` tiene Direct Access Grants habilitado solo para desarrollo local. La app es un resource server: valida el JWT, no maneja sesiones ni contraseñas propias.

Para pedir un token y llamar a la API:

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

Usuarios del realm:

| usuario | claim `customerId` | roles |
| --- | --- | --- |
| `alice` | `11111111-1111-4111-8111-111111111111` | `account_read`, `account_write` |
| `bob` | `22222222-2222-4222-8222-222222222222` | `account_read`, `account_write` |
| `carol` | `44444444-4444-4444-8444-444444444444` | `account_read` |
| `admin` | `33333333-3333-4333-8333-333333333333` | `admin` |

`account_read` habilita los GET; `account_write` habilita crear cuentas y retirar, pero **no depositar**. Un depósito lo hace `admin` o, en el futuro, un servicio interno con su propio scope: el cliente no puede acreditarse dinero a sí mismo. `admin` ve todas las cuentas; los demás solo las suyas. La propiedad se comprueba en la capa de datos: los repositorios filtran por `customerId` y una cuenta ajena responde 404, no 403, para no revelar que existe. El `customerId` no se acepta en el body de creación: se toma del token, así nadie puede abrir una cuenta a nombre de otro.

Dos detalles que valen la pena saber:

- Spring Security 7 no lee `realm_access.roles` por defecto: su converter solo mira `scope`/`scp`. `SecurityConfig` registra un `JwtAuthenticationConverter` que transforma esos roles en `ROLE_*` en mayúsculas. Sin eso, todo daría 403 aunque el token fuera válido.
- El `JwtDecoder` se arma con `NimbusJwtDecoder.withJwkSetUri(...)` dentro del bean, en lugar de usar `issuer-uri` directamente, para no descargar el JWKS al arrancar el contexto de los tests. La validación del issuer se añade aparte con `JwtValidators.createDefaultWithIssuer(...)`.

Los 401 y 403 se escriben en `application/problem+json` desde `AuthenticationEntryPoint` y `AccessDeniedHandler`, porque los rechaza la cadena de filtros antes de llegar al controller. El `@RestControllerAdvice` cubre las mismas excepciones si algún día se lanzan dentro de él, reutilizando los mismos `type`.

## Tests

```powershell
# desde account-service/

# solo unitarios, sin Docker:
.\mvnw.cmd test "-Dtest=AccountServiceImplTest"

# todo junto (unitarios + integración):
.\mvnw.cmd test "-Dtest=AccountServiceImplTest,AccountServiceApplicationTests,AccountRepositoryIT,AccountConcurrencyIT"

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
