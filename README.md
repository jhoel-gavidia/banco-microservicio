# banco-microservicio

Microservicio bancario (account-service) construido paso a paso con Spring Boot 4.1, PostgreSQL 18, Flyway y Testcontainers.

## Requisitos

- **JDK 21**
- **Docker** en ejecución (Testcontainers levanta PostgreSQL de forma efímera; no hace falta tener `accounts-db` arrancado para los tests).
- Maven mediante el wrapper incluido (`./mvnw` / `mvnw.cmd`). No requiere Maven instalado.

## Estructura

```
compose.yaml                         PostgreSQL local para desarrollo
account-service/
  src/main/java/com/banco/account_service/
    account/                         agregado Account (entidad, DTOs, service, mapper, repositorio)
      web/                           AccountController + GlobalExceptionHandler (ProblemDetail)
    support/                         (solo tests) infraestructura compartida
  src/main/resources/
    application.yaml                 configuración de producción/local
    db/migration/                    V1 accounts, V2 ledger_entries, V3 idempotency_keys
  src/test/resources/
    application-test.yaml            perfil 'test' (placeholders DB_URL/DB_USER/DB_PASSWORD)
    clean-db.sql                     TRUNCATE ... RESTART IDENTITY CASCADE
  src/test/java/.../support/
    PostgresIntegrationTest.java     base con el PostgreSQLContainer
```

## Ejecutar la aplicación

```powershell
docker compose up -d                      # levanta accounts-db en localhost:5432
cd account-service
.\mvnw.cmd spring-boot:run                # arranca en http://localhost:8081
```

Endpoints:

| Método | Ruta | Descripción |
|---|---|---|
| POST | `/api/v1/accounts` | Crear cuenta (201 + header `Location`) |
| GET | `/api/v1/accounts/{id}` | Consultar cuenta (200 / 404) |
| GET | `/actuator/health` | Health check |

Los errores siguen **RFC 7807 (ProblemDetail)**: `type`, `title`, `status`, `detail`, `instance`. El campo `type` es un identificador estable del error (no una URL que se visita); el cliente debe programar contra él, no contra el texto de `detail`.

## Tests

### ¿Por qué Testcontainers y no H2?

H2 es una BD en memoria distinta de PostgreSQL. En un sistema bancario eso es peligroso:

| Característica | PostgreSQL 18 (real) | H2 |
|---|---|---|
| `CHECK (currency ~ '^[A-Z]{3}$')` | regex nativa | sintaxis distinta |
| `gen_random_uuid()` | nativo | no existe |
| `TIMESTAMPTZ`, `NUMERIC(19,4)` | exactos | pueden diferir |
| trigger PL/pgSQL (ledger inmutable) | soportado | no existe |
| `@Generated` de Hibernate | `SELECT` post-insert real | comportamiento distinto |

Un test verde en H2 puede fallar en producción. Testcontainers usa el **mismo** `postgres:18-alpine` del `compose.yaml`, así que lo que se prueba es lo que se despliega.

### Comandos

Desde `account-service/`:

```powershell
# Todos los tests (unitarios + integración) en una sola JVM:
.\mvnw.cmd test "-Dtest=AccountServiceImplTest,AccountServiceApplicationTests,AccountRepositoryIT,AccountConcurrencyIT"

# Solo unitarios (rápidos, sin Docker):
.\mvnw.cmd test "-Dtest=AccountServiceImplTest"

# Solo un test de integración:
.\mvnw.cmd test "-Dtest=AccountRepositoryIT"
```

> **Nota sobre Surefire / Failsafe**: Surefire (que usa `mvn test`) ejecuta por defecto las clases `*Test` / `*Tests`. Las clases `*IT` se invocan explícitamente con `-Dtest=...` como arriba. Cuando se configure CI (Fase 11), se añadirá Failsafe para que `mvn verify` las ejecute automáticamente.

### Inventario de tests (11)

| Clase | Tipo | Tests | Qué cubre |
|---|---|---|---|
| `AccountServiceImplTest` | Unitario (Mockito) | 5 | lógica del service: cuándo llama al mapper, cuándo lanza excepciones |
| `AccountServiceApplicationTests` | Integración | 1 | el contexto arranca y `ddl-auto: validate` casa entidad ↔ migraciones |
| `AccountRepositoryIT` | Integración | 4 | `createdAt` generado, `CHECK`, `UNIQUE`, trigger de inmutabilidad del ledger |
| `AccountConcurrencyIT` | Integración | 1 | 8 hilos, mismo número → exactamente 1 gana, 7 reciben `AccountAlreadyExistsException` |

**Pirámide**: la base son tests unitarios rápidos; los de integración se reservan para lo que solo la BD real puede confirmar (constraints, triggers, concurrencia).

### Cómo funciona el PostgreSQL de test

1. `PostgresIntegrationTest` arranca **un único** `PostgreSQLContainer` en un bloque `static { }`.
2. `@DynamicPropertySource` inyecta `DB_URL`, `DB_USER`, `DB_PASSWORD` con el puerto **aleatorio** del contenedor.
3. `application-test.yaml` (perfil `test`) lee esos placeholders **sin valores por defecto**: si el contenedor no arranca, el context falla en vez de conectarse a una BD local.
4. Flyway migra V1→V2→V3 desde `<< Empty Schema >>` en cada ejecución.
5. `@Sql("/clean-db.sql")` con `TRUNCATE ... RESTART IDENTITY CASCADE` limpia antes de cada test.

#### ⚠️ Gotcha: el contenedor compartido entre clases

No usar `@Testcontainers` + `@Container` en la base si varias clases la extienden. Esa extensión gestiona el ciclo de vida **por clase**: para el contenedor al terminar la clase A y lo reinicia en la B con otro puerto, mientras Spring reutiliza el `ApplicationContext` cacheado apuntando al puerto viejo → `Connection refused`.

Por eso el contenedor se arranca una vez con `static { POSTGRES.start(); }`. Ryuk lo elimina al terminar la JVM.

### Hallazgos de versión (Boot 4.1 / Testcontainers 2.x)

- En Boot 4 **no existe** `@AutoConfigureTestDatabase` ni `spring.test.database.replace` (era de Boot 3). No hay reemplazo automático por H2 que desactivar.
- En Spring Framework 7.0 `DynamicPropertyRegistry` solo expone `add(...)` (no `addFirst()`).
- Testcontainers 2.x: artefacto `testcontainers-postgresql`, paquete `org.testcontainers.postgresql.PostgreSQLContainer`, y la clase **no es genérica** (se usa `PostgreSQLContainer`, sin `<>`).

## Decisiones de diseño destacadas

- **Dinero**: `NUMERIC(19,4)` en BD y `BigDecimal` en Java. Nunca `double`.
- **Última línea de defensa**: `CHECK`, `UNIQUE` y FK en la BD, además de la validación Bean Validation en el DTO.
- **`saveAndFlush` dentro del `try`**: fuerza el `INSERT` dentro de la transacción para poder traducir la violación `UNIQUE` (carrera concurrente) a `AccountAlreadyExistsException`. Con un `save` simple, el error aparecería en el commit, fuera del `try`.
- **Mapper con MapStruct**: `AccountMapper` convierte la entidad JPA en `AccountResponse`. Los processors se declaran Lombok → MapStruct en el `pom.xml` para que MapStruct vea los getters generados.
