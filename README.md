# ZipURL

Initial Spring Boot setup for the ZipURL service.

## Architecture

### High-Level System

```mermaid
flowchart LR
    subgraph clients [Clients]
        browser["Browser"]
        apiClient["API client"]
    end

    subgraph apiLayer [Spring Boot API Layer]
        urlController["UrlController\nPOST /api/urls\nGET /api/urls/{alias}"]
        redirectController["RedirectController\nGET /{alias}"]
        exceptionHandler["ApiExceptionHandler\n400, 404, 409, 503"]
    end

    subgraph serviceLayer [Service Layer]
        urlService["UrlShorteningService\ncreate, resolve, metadata"]
        aliasGenerator["AliasGenerator\nBase62 aliases"]
        accessCounter["AccessCountService\nDB or Valkey mode"]
    end

    subgraph cacheLayer [Cache And Counters]
        caffeine["Caffeine\nalias to originalUrl"]
        valkey["Valkey optional\nbatched access counters"]
        countFlusher["Scheduled flusher\nValkey to Postgres"]
    end

    subgraph storageLayer [Storage]
        postgres["Postgres\nshort_urls table\nunique alias index"]
    end

    browser --> redirectController
    apiClient --> urlController
    urlController --> urlService
    redirectController --> urlService
    urlService --> aliasGenerator
    urlService --> caffeine
    urlService --> accessCounter
    urlService --> postgres
    accessCounter --> postgres
    accessCounter --> valkey
    valkey --> countFlusher
    countFlusher --> postgres
    apiLayer --> exceptionHandler
```

Low-level details:

- `POST /api/urls` creates aliases and persists canonical URL state in Postgres.
- `GET /{alias}` resolves aliases through Caffeine plus Postgres and records access counts.
- `GET /api/urls/{alias}` reads metadata from Postgres and adds pending Valkey counts when Valkey mode is enabled.
- Postgres remains the source of truth for aliases, original URLs, creation time, and persisted access counts.
- Valkey is an optional write buffer for access counts, not the canonical URL store.

### Creation Flow

```mermaid
flowchart LR
    subgraph request [Request Boundary]
        createRequest["POST /api/urls"]
        requestDto["CreateShortUrlRequest\nlongUrl, customAlias"]
        validation["Bean validation\nvalid URL, alias pattern"]
    end

    subgraph creationService [Creation Service]
        urlService["UrlShorteningService.createShortUrl"]
        customBranch{"Custom alias present?"}
        reservedCheck["Reserved alias check\napi, health, h2-console"]
        aliasGenerator["AliasGenerator\nsecure random Base62"]
        retryLoop["Generated alias retry loop\nbounded attempts"]
    end

    subgraph persistence [Persistence]
        repository["ShortUrlRepository"]
        shortUrls["short_urls table"]
        uniqueIndex["Unique alias constraint"]
    end

    subgraph response [Response]
        cacheWarm["Warm Caffeine cache"]
        responseDto["ShortUrlResponse\nmetadata"]
    end

    createRequest --> requestDto --> validation --> urlService
    urlService --> customBranch
    customBranch -->|"yes"| reservedCheck --> repository
    customBranch -->|"no"| aliasGenerator --> retryLoop --> repository
    repository --> shortUrls --> uniqueIndex
    uniqueIndex -->|"success"| cacheWarm --> responseDto
    uniqueIndex -->|"collision"| retryLoop
    uniqueIndex -->|"custom collision"| response409["409 Conflict"]
```

Low-level details:

- `CreateShortUrlRequest.longUrl` must be a valid URL.
- `customAlias` is optional and limited to letters, numbers, `_`, and `-`.
- The app does a fast `existsByAlias` check for custom aliases, but the Postgres unique constraint is the real race-condition guard.
- Generated alias collisions are retried with a fresh alias.
- Custom alias collisions return `409 Conflict`.

### Redirect Flow

```mermaid
flowchart LR
    subgraph request [Request Boundary]
        redirectRequest["GET /{alias}"]
        aliasValidation["Path alias validation"]
        redirectController["RedirectController"]
    end

    subgraph resolver [Resolver]
        urlService["UrlShorteningService.resolveOriginalUrl"]
        caffeine["Caffeine atomic get\nprevents cache stampede"]
        loader["Cache loader\nfindByAlias"]
    end

    subgraph storage [Storage]
        postgres["Postgres short_urls"]
    end

    subgraph counting [Access Counting]
        accessCounter["AccessCountService"]
        dbMode["DB mode\nsingle row increment"]
        valkeyMode["Valkey mode\nINCR plus dirty set"]
    end

    subgraph response [Response]
        redirect302["302 Found\nLocation originalUrl"]
        notFound["404 Not Found"]
    end

    redirectRequest --> aliasValidation --> redirectController --> urlService
    urlService --> caffeine
    caffeine -->|"hit"| accessCounter
    caffeine -->|"miss"| loader --> postgres
    postgres -->|"found"| caffeine
    postgres -->|"missing"| notFound
    accessCounter --> dbMode
    accessCounter --> valkeyMode
    accessCounter --> redirect302
```

Low-level details:

- Caffeine uses atomic loading for cache misses, which avoids many concurrent requests stampeding Postgres for the same hot alias.
- Redirects return only `302` plus the `Location` header. Metadata is available through the API endpoint instead.
- If the alias disappears from Postgres while still cached, the access-count service can reject the update and the local cache entry is invalidated.

### Access Count Flow

```mermaid
flowchart LR
    subgraph caller [Caller]
        redirectFlow["Successful redirect"]
        metadataFlow["Metadata lookup"]
    end

    subgraph abstraction [AccessCountService]
        interface["recordAccess\npendingAccessCount"]
        mode{"Configured mode"}
    end

    subgraph dbMode [DB Mode]
        dbIncrement["UPDATE short_urls\naccess_count = access_count + 1"]
    end

    subgraph valkeyMode [Valkey Mode]
        incr["INCR zipurl:access:{alias}"]
        dirty["SADD zipurl:access:dirty alias"]
        pendingRead["GET zipurl:access:{alias}"]
        flusher["Scheduled flusher"]
        drain["GETDEL pending count"]
        batchUpdate["UPDATE short_urls\naccess_count = access_count + delta"]
    end

    subgraph storage [Storage]
        postgres["Postgres"]
        valkeyStore["Valkey"]
    end

    redirectFlow --> interface --> mode
    metadataFlow --> interface
    mode -->|"db"| dbIncrement --> postgres
    mode -->|"valkey"| incr --> valkeyStore
    incr --> dirty --> valkeyStore
    metadataFlow --> pendingRead --> valkeyStore
    flusher --> dirty
    flusher --> drain --> valkeyStore
    drain --> batchUpdate --> postgres
```

Low-level details:

- Default mode is `db`, which writes every redirect count directly to Postgres.
- `valkey` mode reduces redirect-time database writes by buffering counts in Valkey.
- Metadata returns persisted Postgres count plus pending Valkey count.
- Valkey batching is eventually consistent. If counts become billing-critical or must be lossless, use direct DB writes or a durable event stream.
- Current Valkey keys:
  - `zipurl:access:{alias}` stores the pending count.
  - `zipurl:access:dirty` stores aliases with pending counts.

### Metadata Flow

```mermaid
flowchart LR
    subgraph request [Request Boundary]
        metadataRequest["GET /api/urls/{alias}"]
        aliasValidation["Path alias validation"]
        urlController["UrlController"]
    end

    subgraph service [Service Layer]
        getShortUrl["UrlShorteningService.getShortUrl"]
        pendingCount["AccessCountService.pendingAccessCount"]
    end

    subgraph stores [Stores]
        postgres["Postgres persisted metadata"]
        valkey["Valkey pending count\nonly in valkey mode"]
    end

    subgraph response [Response]
        responseDto["ShortUrlResponse\npersisted plus pending count"]
    end

    metadataRequest --> aliasValidation --> urlController
    urlController --> getShortUrl --> postgres
    urlController --> pendingCount --> valkey
    postgres --> responseDto
    valkey --> responseDto
```

Low-level details:

- Metadata lookup does not redirect and does not increment `accessCount`.
- The response includes `alias`, `shortUrl`, `originalUrl`, `createdAt`, and `accessCount`.
- In Valkey mode, `accessCount` includes both flushed and unflushed counts.

## API

- `POST /api/urls` creates a short URL. `longUrl` must be a valid URL; `customAlias` is optional.
- `GET /{alias}` redirects to the original URL and increments `accessCount`.
- `GET /api/urls/{alias}` returns metadata without incrementing `accessCount`.

## Requirements

- Java 21
- Maven 3.9+

## Run

```bash
mvn spring-boot:run
```

## Run With DigitalOcean Postgres

```bash
export ZIPURL_DB_PASSWORD='<database-password>'
export ZIPURL_VALKEY_PASSWORD='<valkey-password>'
SPRING_PROFILES_ACTIVE=postgres mvn spring-boot:run
```

The `postgres` profile uses:

- Host: `zipurl-do-user-39324437-0.a.db.ondigitalocean.com`
- Port: `25060`
- Database: `defaultdb`
- Username: `doadmin`
- SSL mode: `require`

Valkey access-count batching uses:

- Host: `zipurl-valkey-do-user-39324437-0.a.db.ondigitalocean.com`
- Port: `25061`
- Username: `default`
- SSL: enabled

Set `ZIPURL_ACCESS_COUNT_MODE=db` to bypass Valkey and write access counts directly to Postgres.

## Test

```bash
mvn test
```

## Health Check

```bash
curl http://localhost:8080/health
```
