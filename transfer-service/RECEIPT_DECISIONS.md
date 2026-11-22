# Decisiones de diseno - comprobantes S3 (#23)

Estado: Bloque 1 cerrado. Este documento fija las decisiones que los Bloques 2 a 6 deben respetar. No implementa persistencia, endpoints ni migraciones.

## 1. Seguridad HTTP

- Se reutilizan las autoridades existentes: `SCOPE_transfer.write` + `ROLE_TRANSFER_WRITE` para POST y `SCOPE_transfer.read` + `ROLE_TRANSFER_READ` para GET.
- No se crea un rol `user`. `admin` no es un bypass HTTP: el token admin del realm ya incluye `transfer_read` y `transfer_write`.
- En `transfer-service` y `api-gateway` se agrega un matcher POST explicito para `/api/v1/transfers/*/receipts` antes de `anyRequest()`.
- Los GET de comprobantes ya caen en el matcher existente `/api/v1/transfers/**`; se mantiene esa regla.

## 2. Propiedad y administrador

- La transferencia se resuelve siempre con `TransferService.getById(transferId, customer)`.
- El `customer_id` del comprobante es `transfer.customerId()`, nunca el `customerId` del administrador que sube el archivo. `TransferResponse` ya expone `customerId`.
- Consulta de comprobante:
  - usuario normal: `id + transfer_id + customer_id`;
  - admin: `id + transfer_id`, porque la propiedad de la transferencia ya fue validada.
- Un comprobante inexistente o ajeno responde 404. Un admin no recibe 404 por propiedad.

## 3. next_attempt_at

- Insercion PENDING: `next_attempt_at = lease_expires_at` (`now() + leaseDuration`).
- PENDING a PENDING: `next_attempt_at = now() + retryBackoff`.
- PENDING a ACTIVE o FAILED: `next_attempt_at = NULL`.
- Todos los calculos usan `now()` de PostgreSQL, no el reloj de la aplicacion.

## 4. Archivo invalido

- El archivo se valida antes de insertar la fila PENDING: tamano maximo, magic bytes, MIME, nombre saneado y SHA-256.
- Si la validacion falla, se responde 400 `receipt-invalid-file` y no se crea fila.
- Si la segunda lectura falla despues del insert, la fila queda PENDING y la resuelve el reconciliador. No se agrega una razon de fallo nueva.

## 5. Contrato POST ante resultado ambiguo

- 201 Created si la fila queda ACTIVE.
- 409 `receipt-failed` si la fila queda FAILED.
- 202 Accepted con `Location` y `ReceiptResponse` PENDING si el resultado de S3 es ambiguo y la fila sigue PENDING. No es un PENDING por defecto: es el contrato explicito para un resultado desconocido.
- 503 `receipt-storage-unavailable` si el fallo de S3 es definitivo; la fila queda PENDING con `next_attempt_at` futuro.
- Si el lease sigue vigente tras la ambiguedad: detener heartbeat, liberar el lease con `next_attempt_at` futuro y responder 202.
- Si al reconsultar la fila ya es ACTIVE o FAILED, responder 201 o 409 respectivamente.

## 6. Integridad en descarga

- Comparar el SHA-256 calculado con `sha256_hex` de la fila y con el checksum de S3 cuando este presente.
- Si no coincide, responder 502 `receipt-integrity-failed` antes de escribir headers o bytes.
- Un comprobante ACTIVE sin checksum verificable no se sirve: 502.

## 7. PutObject replayable

- Usar `RequestBody.fromContentProvider(() -> multipartFile.getInputStream(), size, contentType)` en lugar de `RequestBody.fromInputStream`, para permitir reintentos del SDK.
- Fijar `contentLength` y `checksumSHA256(Base64(rawDigest))`.
- Si el proveedor no permite reabrir el stream, usar un archivo temporal como fallback. No cargar el archivo completo en memoria.

## 8. Reconciliacion y schedulers

- En el Bloque 4 se agregan `transfer.receipts.reconcile.enabled`, `interval` y `batch-size`.
- El claim usa CTE con `FOR UPDATE SKIP LOCKED`. El claim normal incrementa `attempts`; el claim final no.
- El heartbeat y el sweeper de temporales se implementan en el Bloque 5 con un `TaskScheduler` explicito.

## 9. Temporales y ACL

- `temp-dir` es el directorio base; el feature usa un subdirectorio `receipts`.
- POSIX: directorio 0700 y archivo 0600.
- Windows: crear el subdirectorio y aplicar una DACL protegida (solo SID del proceso, Administrators y SYSTEM) antes de validar. No cambiar permisos de `java.io.tmpdir`.
- Si no se puede endurecer o validar la ACL, el arranque falla salvo `allow-unverified-temp-acl=true`.

## 10. MIME y headers

- Tipos permitidos: PDF, PNG y JPEG.
- `Content-Type` se deriva del contenido validado; la extension tambien.
- En descarga: `X-Content-Type-Options: nosniff`, `Content-Disposition: attachment` con RFC 5987 y `Cache-Control: no-store`.

## 11. Multipart

- `MaxUploadSizeExceededException` se mapea a 400 `receipt-invalid-file`.

## 12. Persistencia V6

- La constraint `_receipt_lease_consistency` se llamara `ck_receipt_lease_consistency`.
- `sha256_hex` sera `VARCHAR(64)`, no `CHAR(64)`.
- Al pasar a ACTIVE se limpian `failure_reason` y `last_error`.
- `object_key` mantiene `UNIQUE`.

## 13. Floci

- Los tests de integracion crean el bucket `banco-comprobantes-local` de forma idempotente.
- No se asume que Floci cree el bucket.
- La imagen de compose se fijara a una version concreta cuando se estabilice; no se usara `latest` en CI.

## 14. Timeouts de descarga

- El `apiCallTimeout` global de 10s no cubre un streaming de hasta 30s. En `GetObject` se aplica un override por peticion con `apiCallTimeout = totalSoftDeadline + 5s`.
- `socketReadTimeout` se aplica en el HTTP client de descarga; `responseFirstReadTimeout` y `totalSoftDeadline` se implementan con watchdog y `ResponseInputStream.abort()`.
- La cancelacion es cooperativa: no se promete corte absoluto, pero no se envian headers hasta validar el archivo completo.

## 15. Linea base

- La linea base de 65 tests se conserva. `TransferApiSecurityTest` vive en `AuthenticatedCustomerTest.java`.
- Con los 6 tests de `ReceiptS3ConfigTest`, el total es 71 tests verdes.
- `mvn clean test` no depende de Floci ni de Docker.
