# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [0.2.0] - Unreleased

### Removed
- **Breaking:** `Permio.DEV_BASE_URL` is removed. Pass the URL of a non-production environment to
  `Builder.baseUrl(...)` instead.

### Changed
- The live integration tests require `PERMIO_BASE_URL` and are skipped when it is not set. Their
  host allowlist is loopback, `*.localhost`, `*.test` and any `*.perms.io` host except production
  (`api.perms.io`).

## [Unreleased]

Fixes from the pre-release review. The SDK is not published yet, so the API changes below are
made without a deprecation period.

### Fixed
- `close()` no longer leaves calls hanging. Calls waiting in retry backoff now fail with
  `PermioUnavailableException` ("the client is closed") instead of never completing, and every
  call made after `close()` (including through `withProject()` / `withCallTimeout()` views and
  `raw()` stubs) fails at once. A retry the scheduler rejects ends the call with its last error.
  A channel passed to the builder is still left open.
- `checkAny` failed open: a denial with no missing-permission details returned `true`. It now
  returns `false`.
- A repeated pagination cursor silently ended a listing early; it now throws `PermioException`.
- `ListOptions.withSearch` was silently ignored by `listGrants`, `groups().listMembers` and
  `groups().listForPrincipal`, whose RPCs cannot search; they now throw
  `IllegalArgumentException` when given a search.
- The jar no longer ships the `.proto` sources at the classpath root (`proto/...`).
- `AccessScopes.toPrefixes()` javadoc no longer recommends SQL `LIKE p || '/%'` (`_` and `%` are
  `LIKE` wildcards); it recommends a literal starts-with test.

### Changed
- Fuller Maven Central (POM) description; README intro and badges. Release steps moved to `RELEASING.md`.
- `*Async` methods run on a bounded pool of `Permio.DEFAULT_ASYNC_THREADS` (8) daemon threads
  instead of an unbounded cached pool; pass `Builder.executor(...)` to size it yourself.
- `batchCheck` / `batchCheckDetailed` keep at most 4 chunk requests in flight.
- `PermioException.code()` returns the SDK's own `ErrorCode` enum; the gRPC code moved to
  `grpcCode()`.
- The live integration tests run only against an allowlist of hosts (the development API,
  loopback, `*.localhost`, `*.test`), checked on the parsed URL.

### Added
- Async variants: `checkAllAsync`, `checkAnyAsync`, `checkDetailedAsync(String, String, String)`,
  `assertAllowedAsync`, `batchCheckAsync(String, Collection, Collection)`,
  `batchCheckDetailedAsync`, `whoHasAccessAsync(String)`, `grantAsync` / `revokeAsync`
  (principal, permission, resource), and `Groups` `createAsync`, `getAsync`, `updateAsync`,
  `deleteAsync`, `addMembersAsync`, `removeMembersAsync`.
- README: excluding `grpc-netty-shaded` for Android / OkHttp.
- POM: organization and issue tracker.

## [0.1.0] - Unreleased

First release of the Java SDK, matching permio-js (including its grant/revoke/whoAmI and
Retry-After work).

### Added
- `Permio` client with a builder: API key (or `PERMIO_API_KEY`) with local format and checksum
  validation, base URL (`DEFAULT_BASE_URL` and a constant for the development API), default project, per-attempt timeout,
  retry policy, injectable channel and executor.
- `check`, `checkAll`, `checkAny`, `checkDetailed`, `assertAllowed`, `batchCheck`,
  `batchCheckDetailed`, `listAccess` (`AccessScopes`), `whoHasAccess`, `grant`, `revoke`, `whoAmI`,
  with `CompletableFuture` variants.
- Lazy paginated `listPermissions`, `listRoles`, `listPrincipals`, `listGrants`.
- `Groups`: create, get, update, delete, list, add/remove members, list members, list a
  principal's groups; `Permio.group(slug)` / `Groups.principal(slug)`.
- `Resources.resource(...)` URI builder and `assertResourceUri`.
- Typed unchecked exceptions under `PermioException`, including `PermioRateLimitException` with
  `retryAfter()`.
- Retries with jittered exponential backoff for idempotent reads only; `RetryInfo` /
  `retry-after` honoured on `RESOURCE_EXHAUSTED`.
- Generated gRPC stubs for the public API (PrivateJAR/proto v0.0.18), reachable through
  `permio.raw()`.

### Scale guardrails
- Streaming access queries: `whoHasAccessIterable(resource)`,
  `whoHasAccessIterable(resource, permission, expandGroups)` and
  `listAccessIterable(principal, permission, under)`, lazy `PagedIterable`s.
- `maxResults` overloads: `whoHasAccess(resource, permission, expandGroups, maxResults)`,
  `listAccess(principal, permission, under, maxResults)` and their `*Async` variants;
  `PermioTooManyResultsException`.
- `maxResources` overloads: `batchCheck(principal, permissions, resources, maxResources)`,
  `batchCheckDetailed(..., maxResources)` and their `*Async` variants.
- `Builder.listTimeout(Duration)`: per-attempt timeout for list page requests (default: `timeout`).
- **Behaviour change:** `whoHasAccess` and `listAccess` collect at most 10,000 results
  (`Permio.DEFAULT_MAX_RESULTS`) and throw `PermioTooManyResultsException` past that, after
  fetching at most cap + 1 items; they used to follow every page into memory. Use the new
  `*Iterable` forms or pass `maxResults`.
- **Behaviour change:** `batchCheck` / `batchCheckDetailed` accept at most 10,000 resources per
  call (`Permio.DEFAULT_MAX_BATCH_RESOURCES`, counted before de-duplication) and throw
  `PermioValidationException` above it; pass `maxResources` to change it.
