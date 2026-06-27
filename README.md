# ZipURL

Initial Spring Boot setup for the ZipURL service.

## Architecture Flow

```mermaid
flowchart TD
    createRequest["POST /api/urls"] --> validation["Validate URL and alias"]
    validation --> createService["UrlShorteningService"]
    createService --> aliasGenerator["AliasGenerator"]
    createService --> database["short_urls table"]
    database -->|"unique alias constraint"| createService
    createService --> cache["Caffeine alias cache"]
    createService --> createResponse["ShortUrlResponse metadata"]

    redirectRequest["GET /{alias}"] --> redirectController["RedirectController"]
    redirectController --> cacheLookup["Cache lookup"]
    cacheLookup -->|"hit"| accessUpdate["Atomic accessCount increment"]
    cacheLookup -->|"miss"| databaseLookup["Database lookup"]
    databaseLookup --> cache
    databaseLookup --> accessUpdate
    accessUpdate --> redirectResponse["302 Location originalUrl"]

    metadataRequest["GET /api/urls/{alias}"] --> metadataLookup["Database metadata lookup"]
    metadataLookup --> metadataResponse["ShortUrlResponse metadata"]
```

## API

- `POST /api/urls` creates a short URL. `longUrl` must be `http` or `https`; `customAlias` is optional.
- `GET /{alias}` redirects to the original URL and increments `accessCount`.
- `GET /api/urls/{alias}` returns metadata without incrementing `accessCount`.

## Requirements

- Java 21
- Maven 3.9+

## Run

```bash
mvn spring-boot:run
```

## Test

```bash
mvn test
```

## Health Check

```bash
curl http://localhost:8080/health
```
