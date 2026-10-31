# Permio Java

Java SDK for the [perms.io](https://perms.io) permissions API: checks, access queries, grants,
groups, and the generated gRPC stubs for everything else.

- Java 11+ (built with JDK 21, published as Java 11 bytecode)
- gRPC over HTTP/2 with TLS (`grpc-netty-shaded`), protobuf 4.x
- Unchecked, typed exceptions; per-attempt timeouts; retries for idempotent reads only

## Install

Gradle:

```kotlin
dependencies {
    implementation("io.perms:permio:0.1.0")
}
```

Maven:

```xml
<dependency>
  <groupId>io.perms</groupId>
  <artifactId>permio</artifactId>
  <version>0.1.0</version>
</dependency>
```

### Android, or another gRPC transport

The SDK depends on `grpc-netty-shaded` at runtime, which suits servers but is large and not meant
for Android. To use another transport (e.g. `grpc-okhttp` on Android), exclude it and add yours;
the client picks up whichever transport is on the classpath:

```kotlin
dependencies {
    implementation("io.perms:permio:0.1.0") {
        exclude(group = "io.grpc", module = "grpc-netty-shaded")
    }
    implementation("io.grpc:grpc-okhttp:1.85.1") // match the SDK's gRPC version
}
```

```xml
<dependency>
  <groupId>io.perms</groupId>
  <artifactId>permio</artifactId>
  <version>0.1.0</version>
  <exclusions>
    <exclusion>
      <groupId>io.grpc</groupId>
      <artifactId>grpc-netty-shaded</artifactId>
    </exclusion>
  </exclusions>
</dependency>
```

## Quick start

```java
import io.perms.Permio;

try (Permio permio = Permio.builder().project("my-app").build()) { // API key from PERMIO_API_KEY
  boolean allowed = permio.check(userId, "docs.read", Permio.resource("docs", docId));
}
```

A `Permio` is thread-safe and owns a gRPC channel: create one at startup, share it, close it at
shutdown. A denied check returns `false`; it is not an error.

## Configuration

```java
Permio permio = Permio.builder()
    .apiKey("perms_...")              // default: the PERMIO_API_KEY environment variable
    .baseUrl(Permio.DEV_BASE_URL)     // default: Permio.DEFAULT_BASE_URL (production)
    .project("my-app")                // default project_name for every helper
    .timeout(Duration.ofSeconds(5))   // per attempt; default 10 s, Duration.ZERO disables it
    .listTimeout(Duration.ofSeconds(30)) // per list page attempt; default: timeout
    .retries(3)                       // idempotent reads only; default 2
    .build();
```

| Option | Default | Notes |
| --- | --- | --- |
| `apiKey` | `$PERMIO_API_KEY` | `perms_...` key, or a legacy JWT. Checked locally (length, alphabet, CRC32 checksum) so a typo fails at `build()` with `PermioConfigException`. |
| `baseUrl` / `endpoint` | `https://api.perms.io` | `https://host[:port]` (TLS) or `http://host[:port]` (plaintext, local testing). |
| `project` | none | Required by every helper except `whoAmI()`. Override per call with `permio.withProject("other")`. |
| `timeout` | 10 s | Per attempt. |
| `listTimeout` | `timeout` | Per attempt, for each list page request (list helpers, iterables, `whoHasAccess`, `listAccess`). |
| `retryPolicy` / `retries` | 2 retries, 100 ms base, 2 s max | See [Retries](#retries). |
| `channel` | built from `baseUrl` | Inject any `io.grpc.Channel` (e.g. in-process for tests). Not closed by the client. |
| `executor` | pool of 8 daemon threads | Runs the `*Async` methods; each holds a thread for the call's duration and further calls queue. Pass your own to size it. Not shut down by the client. |
| `configureChannel` | none | Adjust the `ManagedChannelBuilder` (keepalive, proxy, ...). |

`withProject(name)` and `withCallTimeout(duration)` return views that share the channel.
`withCallTimeout` sets an overall deadline per call, across retries. Closing a view does nothing.

`close()` the client you built when done. After that every call on it, its views and its `raw()`
stubs fails at once with `PermioUnavailableException`, and calls waiting to retry fail instead of
hanging. A channel or executor you passed in is left open.

## API

```java
// Checks
permio.check(principal, "docs.read", "/docs/1");                          // boolean
permio.checkAll(principal, List.of("docs.read", "docs.write"), "/docs/1"); // every permission
permio.checkAny(principal, List.of("docs.read", "docs.admin"), "/docs/1"); // at least one
permio.checkDetailed(principal, perms, resources);   // CheckResult: allowed + what is missing
permio.assertAllowed(principal, "docs.read", "/docs/1"); // throws PermioDeniedException
permio.batchCheck(principal, "docs.read", uris);     // Map<String, Boolean> in request order (>100 split up, 4 chunks in flight, max 10,000 resources)
permio.checkAsync(principal, "docs.read", "/docs/1"); // CompletableFuture<Boolean>
// Checks, access queries, grant/revoke, whoAmI and group writes/gets have *Async variants.

// Access queries (every page is fetched, at most 10,000 results; see "Large results")
AccessScopes access = permio.listAccess(principal, "docs.read", "/docs");
access.coversAll(); access.toPrefixes(); access.toRegex(); access.allows("/docs/1");
List<AccessEntry> who = permio.whoHasAccess("/docs/1", "docs.read", /* expandGroups */ true);

// Streaming access queries (lazy PagedIterable, one page in memory at a time)
for (AccessEntry e : permio.whoHasAccessIterable("/docs/1")) { ... }
permio.listAccessIterable(principal, "docs.read", "/docs").stream()...

// Lists (lazy Iterable, also .stream() and .toList())
for (Permission p : permio.listPermissions()) { ... }
permio.listRoles(ListOptions.defaults().withSearch("edit"));
permio.listPrincipals();
permio.listGrants(principal);                        // alias: listAssignments
```

`withSearch` applies to `listPermissions`, `listRoles`, `listPrincipals` and `groups().list`.
`listGrants`, `groups().listMembers` and `groups().listForPrincipal` cannot search and throw
`IllegalArgumentException` if given a search. A list whose API cursor repeats throws
`PermioException` rather than silently ending early.

### Large results

A resource may have millions of principals with access, so the collect-all `whoHasAccess` and
`listAccess` stop at `maxResults` results (default `Permio.DEFAULT_MAX_RESULTS`, 10,000) and throw
`PermioTooManyResultsException` (with `maxResults()`) instead of filling memory. They fetch at most
`maxResults + 1` items. Either stream with `whoHasAccessIterable` / `listAccessIterable`, which
fetch one page at a time as you iterate and have no cap, or pass a larger cap:

```java
permio.whoHasAccess("/docs/1", null, false, 50_000);   // maxResults
permio.listAccess(principal, "docs.read", "/docs", 50_000);
```

A streamed `listAccessIterable` reports "covers everything under `under`" as a single `SUBTREE`
scope whose URI is `under`.

`batchCheck` / `batchCheckDetailed` accept at most `Permio.DEFAULT_MAX_BATCH_RESOURCES` (10,000)
resources per call, counted as given (before de-duplication); more throws
`PermioValidationException` before any request is sent. Pass `maxResources` to change it:
`permio.batchCheck(principal, List.of("docs.read"), uris, 20_000)`. Requests of 100 resources are
built as they are sent, at most 4 in flight; the first failing one is thrown.

```java
// Grants (never retried automatically)
permio.grant(principal, "docs.read", "/docs");
permio.grant(Grant.to(principal).on("/docs").permissions("docs.read").roles(roleId));
permio.revoke(Grant.to(principal).on("/docs").permissions("docs.read"));

// Identity
WhoAmI me = permio.whoAmI();                          // key ID, organisation, project, access level
```

Resource URIs: build them with `Permio.resource(...)` (or `Resources.resource`), which
percent-encodes each segment exactly like permio-js, so `/`, spaces and other reserved characters
stay inside one segment. Build URIs the same way when granting and checking. A grant on a URI also
applies to everything below it.

## Groups

```java
permio.groups().create("editors", "Can edit documents");
permio.groups().addMembers("editors", List.of("alice", "bob")); // AddMembersResult: added / existing
permio.grant(Grant.to(Permio.group("editors")).on("/docs").permissions("docs.write"));
permio.check("bob", "docs.write", "/docs/1");                  // true, through the group
permio.groups().listMembers("editors");
permio.groups().listForPrincipal("bob");
permio.groups().removeMembers("editors", List.of("bob"));
permio.groups().update("editors", "New description");
permio.groups().delete("editors");                              // also removes its grants
```

Group methods take the slug (`"editors"`) or the principal ID (`"group:editors"`). Groups cannot
contain groups.

## Errors

Every exception is an unchecked `PermioException` with `code()` (an `io.perms.ErrorCode`, if
any; `grpcCode()` gives the `io.grpc.Status.Code`), `metadata()` (response headers and trailers)
and the original exception as the cause.

| Exception | When |
| --- | --- |
| `PermioConfigException` | Malformed API key or base URL, no project. |
| `PermioAuthException` | `UNAUTHENTICATED`: unknown, invalidated or missing key. |
| `PermioPermissionException` | `PERMISSION_DENIED`: the *key* lacks rights; `missing()` lists them when the API says. |
| `PermioValidationException` | `INVALID_ARGUMENT`, or the SDK's own input checks (no request is sent). |
| `PermioNotFoundException` | `NOT_FOUND`. |
| `PermioUnavailableException` | `UNAVAILABLE`: network failure or API down (after retries). |
| `PermioTimeoutException` | `DEADLINE_EXCEEDED`. |
| `PermioRateLimitException` | `RESOURCE_EXHAUSTED`; `retryAfter()` from `RetryInfo` or `retry-after`. |
| `PermioDeniedException` | Thrown by `assertAllowed` when the *principal* is denied; `missing()` lists what. |
| `PermioTooManyResultsException` | `whoHasAccess` / `listAccess` found more than `maxResults` results; `maxResults()`. Use the `*Iterable` form or raise the cap. |

Other codes (e.g. `ALREADY_EXISTS`) are a base `PermioException`. Async methods complete
exceptionally with the same types.

## Retries

Each attempt has its own timeout (default 10 s). Idempotent reads (`Check`, `BatchCheck`,
`WhoAmI`, every `Get*`/`List*`; see `RetryPolicy.isIdempotent`) are retried on `UNAVAILABLE` and
`DEADLINE_EXCEEDED`, up to `maxRetries` times, with jittered exponential backoff (base 100 ms,
doubling, capped at `maxDelay`, 2 s).

A rate-limited read (`RESOURCE_EXHAUSTED`) is retried only when the server says how long to wait
(`google.rpc.RetryInfo` detail, or a `retry-after` header) and that delay is within `maxDelay`;
otherwise `PermioRateLimitException` is thrown with `retryAfter()`. A `retry-after` on
`UNAVAILABLE` is used as the minimum backoff.

Each list page is its own read: a page that times out or is unavailable is retried after the
same backoff (never immediately), and a rate-limited page waits for the server's delay. Pages use
`listTimeout` per attempt.

Writes, including grant and revoke, are sent exactly once: after a timeout the first attempt may
still have landed, and repeating it could undo another caller's change. Retry them yourself if
that is safe for you.

```java
Permio.builder().retryPolicy(RetryPolicy.builder()
    .maxRetries(4).baseDelay(Duration.ofMillis(50)).maxDelay(Duration.ofSeconds(1)).build());
```

## Raw client escape hatch

The generated gRPC stubs for both public services ship in this artifact
(`io.perms.proto.permissions.v1`, `io.perms.proto.organisation.v1`,
`io.perms.proto.pagination.v1`). `permio.raw()` gives stubs that run over the client's channel,
so auth, timeouts and retries apply:

```java
permio.raw().permissions().createPermission(CreatePermissionRequest.newBuilder()
    .setProjectName("my-app").setName("docs.read").setDescription("Read documents").build());
permio.raw().organisations();        // OrganisationService
permio.raw().permissionsFuture();    // ListenableFuture stubs; also *Async (StreamObserver)
permio.raw().channel();              // build any stub yourself
```

Raw requests need `project_name` set explicitly, and raw errors are plain
`StatusRuntimeException`s: convert them with `PermioException.from(e)`. Paginate any list RPC with
`new PagedIterable<>(page -> ..., 100)`.

## Dev vs prod

`Permio.DEFAULT_BASE_URL` (`https://api.perms.io`) is production and the default.
`Permio.DEV_BASE_URL` (`https://dev-api.perms.io`) is the development API. API keys belong to one
project in one environment.

## Integration tests

Unit tests run against in-process gRPC servers:

```bash
./gradlew build                                  # format check, compile, unit tests
./gradlew test -PtestJavaVersion=11              # unit tests on another installed JDK
```

The live suite (`src/test/java/io/perms/LiveIntegrationTest.java`, JUnit tag `integration`)
mirrors permio-js `test/live.test.ts`. It is skipped unless `PERMIO_API_KEY` is set, targets
`PERMIO_BASE_URL` (default: dev) and refuses to run against production. It needs a full-access
key; the project is `PERMIO_PROJECT` or the key's own. It creates and removes fixed IDs prefixed
`sdk-it-java`.

```bash
PERMIO_API_KEY=perms_... ./gradlew integrationTest
```

In CI the `integration` job runs it with the `PERMIO_DEV_API_KEY` secret, one run at a time, and
skips with a notice when the secret is not set.

## Generated code and proto updates

The public protos from [perms-io/proto](https://github.com/perms-io/proto) are vendored in
`src/main/proto/proto/{permissions,organisation,pagination}/v1/` (never `internal.proto`), and the
Java classes are generated at build time by the `com.google.protobuf` Gradle plugin (protoc and
`protoc-gen-grpc-java`, versions pinned in `gradle/libs.versions.toml`). `.proto-version` records
the proto release and commit they came from.

To move to a new proto release:

```bash
git clone git@github.com:perms-io/proto.git /tmp/proto && git -C /tmp/proto checkout v0.0.19
scripts/sync-protos.sh /tmp/proto v0.0.19
./gradlew build
```

The script copies the three files, adds `java_package` / `java_multiple_files` /
`java_outer_classname` options (the upstream files only have `go_package`) and rewrites
`.proto-version`. The protos' imports (`google/api/annotations.proto`, `validate/validate.proto`)
come from the `proto-google-common-protos` and `pgv-java-stub` jars, which also provide their
Java classes. The `Vendored protos` workflow re-runs the script against the pinned commit and fails
if the files were edited by hand (it needs a `PROTO_DEPLOY_KEY` secret with read access to the
proto repo, and is skipped without it).

## Releasing

`.github/workflows/release.yml` publishes to Maven Central (Central Portal) when a `v*` tag that
matches `version` in `gradle.properties` is pushed. One-time setup by the owner:

1. Sign in at [central.sonatype.com](https://central.sonatype.com) and add the namespace
   `io.perms`. Verify it by adding the DNS TXT record it shows to `perms.io`.
2. Generate a user token (Account > Generate User Token).
3. Create a GPG key for signing, publish the public key to a keyserver
   (`gpg --keyserver keys.openpgp.org --send-keys <KEYID>`), and export the private key with
   `gpg --armor --export-secret-keys <KEYID>`.
4. Add these repository (or `maven-central` environment) secrets:
   - `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`: the user token
   - `SIGNING_KEY`: the ASCII-armoured private key
   - `SIGNING_KEY_PASSWORD`: its passphrase
5. Bump `version` in `gradle.properties`, update `CHANGELOG.md`, tag `vX.Y.Z` and push the tag.

The release runs on Blacksmith: Maven Central has no runner requirement. CI secrets:
`PERMIO_DEV_API_KEY` (full-access dev key for the integration job) and `PROTO_DEPLOY_KEY`.

## Licence

Apache-2.0. See [LICENSE](LICENSE).
