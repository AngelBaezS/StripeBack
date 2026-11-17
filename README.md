# PaymentClean — Pasarela de Pagos Stripe + Facturación CFDI 4.0 (Backend)

API REST desarrollada con **Java 21** y **Spring Boot 4.1.1** bajo arquitectura hexagonal (puertos y adaptadores). Integra autenticación stateless con **JWT en cookies `HttpOnly`**, gestión de catálogo y carritos, procesamiento de pagos con **Stripe** (Elements, Checkout Session y transferencia bancaria **SPEI** con ficha PDF), timbrado electrónico **CFDI 4.0** mediante **CFDI Express** y notificaciones por correo con **Mailtrap Java SDK**.

> [!IMPORTANT]
> Este repositorio **no incluye** archivos `.env` ni credenciales reales. Para ejecutarlo localmente, utiliza la plantilla `.env.example`.

---

## Requisitos Previos

- **Java Development Kit (JDK) 21** o superior
- **MySQL 8.0+** (corriendo localmente o en contenedor)
- **Maven** (incluido vía wrapper `mvnw` / `mvnw.cmd`)
- Cuenta en modo pruebas (*Test Mode*) de **Stripe** (`pk_test_...`, `sk_test_...`)
- *(Opcional para webhooks en tiempo real)* **Stripe CLI**
- *(Opcional para timbrado CFDI 4.0)* Cuenta en **CFDI Express** (`sk_test_...`) con un emisor/CSD cargado
- *(Opcional para correos)* Token de **Mailtrap** (`MAILTRAP_TOKEN`)

---

## Configuración Inicial (`.env`)

1. Crea la base de datos en MySQL:
   ```sql
   CREATE DATABASE IF NOT EXISTS payment_stripe_db;
   ```
   *(Spring Data JPA / Hibernate con `spring.jpa.hibernate.ddl-auto=update` creará y actualizará automáticamente todas las tablas al iniciar).*

2. Copia la plantilla `.env.example` a `.env` en la raíz del proyecto `PaymentClean`:
   ```powershell
   Copy-Item .env.example .env
   ```
   *(En Linux/macOS: `cp .env.example .env`)*

3. Edita `.env` con tus propias credenciales:

| Variable | Descripción | Ejemplo / Valor por defecto |
| :--- | :--- | :--- |
| `DB_URL` | Cadena JDBC de MySQL | `jdbc:mysql://localhost:3306/payment_stripe_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC` |
| `DB_USERNAME` | Usuario de MySQL | `root` |
| `DB_PASSWORD` | Contraseña de MySQL | `tu_password` |
| `STRIPE_PUBLIC_KEY` | Clave pública de Stripe (`pk_test_...`) | — |
| `STRIPE_SECRET_KEY` | Clave secreta de Stripe (`sk_test_...`) | — |
| `STRIPE_WEBHOOK_SECRET` | Secreto del webhook de Stripe (`whsec_...`) | — |
| `CFDI_EXPRESS_API_KEY` | API Key de CFDI Express (`sk_test_...` / `sk_live_...`) | — |
| `CFDI_EXPRESS_MERCHANT_ID` | ID del emisor en CFDI Express *(si se omite, intenta autodescubrir el primero activo)* | — |
| `EMITTER_ZIP_CODE` | Código postal de expedición del emisor (5 dígitos) | `42501` |
| `MAILTRAP_TOKEN` | API Token de Mailtrap (`io.mailtrap:mailtrap-java:1.3.0`) | — |
| `MAILTRAP_SANDBOX` | `false` para Email Sending API, `true` para Mailtrap Sandbox | `false` |
| `MAILTRAP_INBOX_ID` | ID del Inbox (solo si `MAILTRAP_SANDBOX=true`) | — |
| `MAILTRAP_FROM_EMAIL` | Remitente verificado en Mailtrap | `hello@demomailtrap.com` |
| `MAILTRAP_FROM_NAME` | Nombre visible del remitente | `Pasarela de Pagos` |
| `MAILTRAP_OVERRIDE_TO` | *(Opcional)* Redirige todos los envíos a un único correo de pruebas | — |
| `JWT_SECRET` | Clave HMAC para firmar JWT (mín. 32 caracteres) | — |
| `FRONTEND_URL` | URL base del frontend para CORS y `return_url` de Stripe | `http://localhost:4200` |

---

## Cómo Arrancar el Backend

Desde la carpeta `PaymentClean`:

**Windows (PowerShell / CMD):**
```powershell
.\mvnw.cmd spring-boot:run
```

**Linux / macOS:**
```bash
./mvnw spring-boot:run
```

El servidor quedará disponible en:
- **API Base**: `http://localhost:8080`
- **Documentación interactiva (Scalar UI)**: `http://localhost:8080/scalar`
- **Especificación OpenAPI 3 JSON**: `http://localhost:8080/v3/api-docs`

---

## Webhooks Locales de Stripe *(Opcional)*

El sistema sincroniza automáticamente el estado de pagos exitosos al regresar a `/cart/resultado` (`POST /api/v1/order/{orderId}/sync-payment`), pero para probar webhooks en tiempo real (como acreditación asíncrona de SPEI o fallos tardíos) ejecuta en otra terminal:

```bash
stripe listen --forward-to localhost:8080/api/webhooks/stripe
```

Copia el `whsec_...` que muestra el CLI en `STRIPE_WEBHOOK_SECRET` dentro de `.env` y reinicia el backend.

---

## Cómo Ejecutar las Pruebas

Ejecutar toda la suite de pruebas (requiere conexión a MySQL para el test de contexto `PaymentCleanApplicationTests`):
```powershell
.\mvnw.cmd test
```

Ejecutar únicamente las pruebas unitarias y de controladores WebMvc sin levantar el contexto completo de BD:
```powershell
.\mvnw.cmd test "-Dtest=!PaymentCleanApplicationTests"
```

---

## Funcionalidades y Endpoints Principales

| Módulo | Endpoints clave | Descripción |
| :--- | :--- | :--- |
| **Autenticación & Perfil** | `POST /api/v1/auth/register`, `login`, `refresh`, `logout`<br>`GET /api/v1/auth/me`<br>`PUT /api/v1/auth/me/username`, `password` | Autenticación con roles `CLIENT` / `ADMIN` y cookie JWT `HttpOnly` (`auth_token`). |
| **Catálogo y Admin** | `GET /api/v1/products`<br>`POST / PUT / PATCH /api/v1/admin/products/**` | Consulta pública de productos y CRUD administrativo sincronizado con Stripe Products/Prices. |
| **Carrito** | `GET / POST / PUT / DELETE /api/v1/cart/**` | Gestión del carrito activo (`ACTIVE`) antes de bloquearlo (`LOCKED`) al crear la orden. |
| **Órdenes y Pagos Stripe** | `GET /api/v1/order?page=0&size=10&status=...`<br>`POST /api/v1/order/direct-payment`, `spei-payment`, `payment-link`<br>`GET /api/v1/order/{orderId}/spei-pdf`<br>`POST /api/v1/order/{orderId}/sync-payment`, `cancel` | Listado paginado (10 por página), Checkout Sessions (Elements / Hosted), ficha SPEI en PDF y cancelación manual o automática tras 48h. |
| **Webhooks Stripe** | `POST /api/webhooks/stripe` | Verificación de firma `Stripe-Signature`, idempotencia por `event_id`, descuento de stock y confirmación. |
| **Facturación CFDI 4.0** | `POST /api/v1/invoices/order/{orderId}` (checkout)<br>`POST /api/v1/invoices/order/{orderId}/issue` (post-compra)<br>`GET /api/v1/invoices/order/{orderId}/pdf` y `/xml`<br>`POST /api/v1/invoices/order/{orderId}/resend-email` | Registro fiscal en checkout (timbrado automático al acreditarse el pago) o emisión única post-compra ante CFDI Express y envío PDF/XML por Mailtrap. |
