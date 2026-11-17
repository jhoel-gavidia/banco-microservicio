# banco-microservicio

Account-service: Spring Boot 4.1, PostgreSQL 18, Flyway y Testcontainers. Se construye paso a paso, con un commit por cada cambio.

## Arrancarlo

```powershell
docker compose up -d
cd account-service
.\mvnw.cmd spring-boot:run
```

El compose levanta la BD en localhost:5432 y la app queda en http://localhost:8081.

Endpoints:

- `POST /api/v1/accounts` → 201 con header `Location`. Body: `customerId`, `number`, `currency`.
- `GET /api/v1/accounts/{id}` → 200 o 404.
- `POST /api/v1/accounts/{id}/deposits` → 201. Body: `amount`, `reference`.
- `POST /api/v1/accounts/{id}/withdrawals` → 201. Body: `amount`, `reference`.
- `GET /api/v1/accounts/{id}/movements` → 200 con el historial.
- `GET /actuator/health`.

`reference` la elige el cliente y es obligatoria. Se guarda en el asiento y `UNIQUE (account_id, reference, type)` evita que el mismo movimiento se registre dos veces; repetirla devuelve 409. Retirar más de lo disponible devuelve 422.

Los errores vienen en RFC 7807. Fíjate en `type`: es un identificador estable, no una URL que se visita. Es lo que el cliente debería usar para decidir qué hacer; el `detail` es para leer, no para programar contra él.

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

## Decisiones

- Dinero siempre `BigDecimal` / `NUMERIC(19,4)`. Nunca `double`.
- Los CHECK, UNIQUE y FK están en la BD, además de la validación del DTO. No nos fiamos solo de Bean Validation.
- `saveAndFlush` dentro del `try`: si no, el error de UNIQUE por concurrencia sale en el commit, fuera del catch, y no se puede traducir a `AccountAlreadyExistsException`.
- `AccountMapper` (MapStruct) solo convierte entidad → DTO. En el pom los annotation processors van Lombok antes que MapStruct, para que MapStruct vea los getters generados.
- `LedgerEntry` no lleva `@Immutable` de Hibernate: en Hibernate 7 no se puede combinar con `@Generated(event = INSERT)` porque el re-read de `created_at` intenta un lock WRITE sobre una entidad read-only y revienta. La inmutabilidad la garantiza el trigger `trg_ledger_immutable` de la V2 y que no existe ningún path de UPDATE/DELETE en el código.
