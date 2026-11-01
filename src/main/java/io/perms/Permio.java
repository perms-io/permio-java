package io.perms;

import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.Channel;
import io.grpc.ChannelCredentials;
import io.grpc.ClientInterceptors;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.TlsChannelCredentials;
import io.perms.proto.permissions.v1.Assignment;
import io.perms.proto.permissions.v1.BatchCheckRequest;
import io.perms.proto.permissions.v1.BatchCheckResponse;
import io.perms.proto.permissions.v1.BatchCheckResult;
import io.perms.proto.permissions.v1.CheckPermissionRequest;
import io.perms.proto.permissions.v1.CheckPermissionResponse;
import io.perms.proto.permissions.v1.GetAllAssignmentsForPrincipalRequest;
import io.perms.proto.permissions.v1.GetAllAssignmentsForPrincipalResponse;
import io.perms.proto.permissions.v1.GrantUserPermissionsAndRolesOnResourceRequest;
import io.perms.proto.permissions.v1.ListAccessRequest;
import io.perms.proto.permissions.v1.ListAccessResponse;
import io.perms.proto.permissions.v1.ListPermissionsRequest;
import io.perms.proto.permissions.v1.ListPermissionsResponse;
import io.perms.proto.permissions.v1.ListPrincipalsRequest;
import io.perms.proto.permissions.v1.ListPrincipalsResponse;
import io.perms.proto.permissions.v1.ListPrincipalsWithAccessRequest;
import io.perms.proto.permissions.v1.ListPrincipalsWithAccessResponse;
import io.perms.proto.permissions.v1.ListRolesRequest;
import io.perms.proto.permissions.v1.ListRolesResponse;
import io.perms.proto.permissions.v1.MissingPermissions;
import io.perms.proto.permissions.v1.PermissionsServiceGrpc;
import io.perms.proto.permissions.v1.PrincipalAccess;
import io.perms.proto.permissions.v1.RevokeUserPermissionsAndRolesOnResourceRequest;
import io.perms.proto.permissions.v1.WhoAmIRequest;
import io.perms.proto.permissions.v1.WhoAmIResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Client for the perms.io API.
 *
 * <pre>{@code
 * try (Permio permio = Permio.builder().apiKey(System.getenv("PERMIO_API_KEY")).project("my-app").build()) {
 *   if (permio.check(userId, "docs.read", Resources.resource("docs", docId))) { ... }
 * }
 * }</pre>
 *
 * <p>A client is thread-safe and holds a gRPC channel: create one and share it. {@link
 * #withProject} and {@link #withCallTimeout} return lightweight views that share the channel;
 * closing a view does nothing, close the client you built.
 *
 * <p>Every call is bounded by a per-attempt timeout (default 10 s). Idempotent reads are retried on
 * {@code UNAVAILABLE} and {@code DEADLINE_EXCEEDED} (default 2 retries); writes, grant and revoke
 * included, are sent once. See {@link RetryPolicy}. Errors are thrown as {@link PermioException}
 * subclasses.
 */
public final class Permio implements AutoCloseable {
  /** Production API. */
  public static final String DEFAULT_BASE_URL = "https://api.perms.io";

  /** Environment variable read when no API key is given. */
  public static final String API_KEY_ENV = "PERMIO_API_KEY";

  /** Default per-attempt timeout. */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

  /** This SDK's version. */
  public static final String VERSION = readVersion();

  /**
   * Threads in the default executor for the {@code *Async} methods. Each async call blocks one of
   * them for its duration; further calls queue. Pass your own with {@link Builder#executor}.
   */
  public static final int DEFAULT_ASYNC_THREADS = 8;

  /**
   * Default cap on the results {@link #whoHasAccess} and {@link #listAccess} collect into memory.
   * Past it they throw {@link PermioTooManyResultsException}; iterate {@link #whoHasAccessIterable}
   * or {@link #listAccessIterable} instead, or pass a larger {@code maxResults}.
   */
  public static final int DEFAULT_MAX_RESULTS = 10_000;

  /**
   * Default cap on the resources one {@link #batchCheck} call accepts. Past it the call throws
   * {@link PermioValidationException} before sending anything.
   */
  public static final int DEFAULT_MAX_BATCH_RESOURCES = 10_000;

  /** At most this many {@code BatchCheck} chunk requests are in flight per batch check. */
  static final int MAX_PARALLEL_CHUNKS = 4;

  private static final int MAX_ITEMS = 100;

  private final Core core;
  private final String project;
  private final Duration callTimeout;
  private final boolean root;
  private final Groups groups;

  /** Shared state of a client and its views. */
  private static final class Core {
    final Channel channel;
    final ManagedChannel ownedChannel;
    final ScheduledExecutorService scheduler;
    final ResilienceInterceptor interceptor;
    final Executor executor;
    final ExecutorService ownedExecutor;
    final ApiKeys.Kind apiKeyKind;
    final String baseUrl;
    final long listTimeoutNanos;

    Core(
        Channel channel,
        ManagedChannel ownedChannel,
        ScheduledExecutorService scheduler,
        ResilienceInterceptor interceptor,
        Executor executor,
        ExecutorService ownedExecutor,
        ApiKeys.Kind apiKeyKind,
        String baseUrl,
        long listTimeoutNanos) {
      this.channel = channel;
      this.ownedChannel = ownedChannel;
      this.scheduler = scheduler;
      this.interceptor = interceptor;
      this.executor = executor;
      this.ownedExecutor = ownedExecutor;
      this.apiKeyKind = apiKeyKind;
      this.baseUrl = baseUrl;
      this.listTimeoutNanos = listTimeoutNanos;
    }
  }

  private Permio(Core core, String project, Duration callTimeout, boolean root) {
    this.core = core;
    this.project = project;
    this.callTimeout = callTimeout;
    this.root = root;
    this.groups = new Groups(this);
  }

  /** A builder; {@link Builder#build()} validates the configuration. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * A production client for {@code apiKey}, without a default project.
   *
   * @throws PermioConfigException if the key is malformed
   */
  public static Permio create(String apiKey) {
    return builder().apiKey(apiKey).build();
  }

  // ---------------------------------------------------------------------------------------------
  // Configuration and views

  /** The default project, if configured. */
  public java.util.Optional<String> project() {
    return java.util.Optional.ofNullable(project);
  }

  /** Whether the configured key is an opaque {@code perms_} key or a legacy JWT. */
  public ApiKeys.Kind apiKeyKind() {
    return core.apiKeyKind;
  }

  /** The API base URL, or null when a channel was injected. */
  public String baseUrl() {
    return core.baseUrl;
  }

  /** A view of this client that uses {@code project} as the default project. */
  public Permio withProject(String project) {
    if (project == null || project.isEmpty()) {
      throw new PermioConfigException("permio: project must not be empty");
    }
    return new Permio(core, project, callTimeout, false);
  }

  /**
   * A view of this client whose calls each have an overall deadline of {@code timeout}, across
   * retries (on top of the per-attempt timeout). List helpers apply it to each page request.
   */
  public Permio withCallTimeout(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative() || timeout.isZero()) {
      throw new PermioConfigException("permio: call timeout must be positive");
    }
    return new Permio(core, project, timeout, false);
  }

  /** Groups of principals: create them, manage members, list them. */
  public Groups groups() {
    return groups;
  }

  /** The generated gRPC stubs, with auth, timeouts and retries applied. */
  public RawClient raw() {
    return new RawClient(core.channel, callTimeout);
  }

  /**
   * Builds a resource URI from raw segments; see {@link Resources#resource(Object...)}.
   *
   * <pre>{@code
   * Permio.resource("projects", projectId, "docs", docId) // "/projects/p1/docs/d1"
   * }</pre>
   */
  public static String resource(Object... segments) {
    return Resources.resource(segments);
  }

  /** The principal ID of a group, {@code "group:<slug>"}; see {@link Groups#principal}. */
  public static String group(String slug) {
    return Groups.principal(slug);
  }

  /**
   * Closes the channel and executors this client created. A channel or executor passed to the
   * builder is left alone. Views ({@link #withProject}, ...) do nothing on close, but stop working
   * when the client they came from is closed.
   *
   * <p>After close, every call (on this client, its views and {@link #raw()} stubs) fails at once
   * with {@link PermioUnavailableException}, and calls waiting to retry fail instead of retrying.
   */
  @Override
  public void close() {
    if (!root) {
      return;
    }
    // Fail calls waiting in retry backoff before stopping the scheduler that would run them.
    core.interceptor.shutdown();
    core.scheduler.shutdownNow();
    if (core.ownedExecutor != null) {
      core.ownedExecutor.shutdown();
    }
    ManagedChannel ch = core.ownedChannel;
    if (ch != null) {
      ch.shutdown();
      try {
        if (!ch.awaitTermination(5, TimeUnit.SECONDS)) {
          ch.shutdownNow();
        }
      } catch (InterruptedException e) {
        ch.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Checks

  /**
   * True when {@code principal} holds {@code permission} on {@code resource}. A denial is a normal
   * result, not an error.
   *
   * @throws PermioPermissionException if the API key lacks {@code permission.check}
   * @throws PermioValidationException for malformed URIs or empty inputs
   */
  public boolean check(String principal, String permission, String resource) {
    return checkDetailed(principal, List.of(nonNull(permission)), List.of(nonNull(resource)))
        .allowed();
  }

  /** True when {@code principal} holds every permission on every resource. */
  public boolean checkAll(
      String principal, Collection<String> permissions, Collection<String> resources) {
    return checkDetailed(principal, permissions, resources).allowed();
  }

  /** True when {@code principal} holds every permission on {@code resource}. */
  public boolean checkAll(String principal, Collection<String> permissions, String resource) {
    return checkAll(principal, permissions, List.of(nonNull(resource)));
  }

  /**
   * True when, on every resource, {@code principal} holds at least one of the permissions. Uses a
   * single {@code Check} call.
   */
  public boolean checkAny(
      String principal, Collection<String> permissions, Collection<String> resources) {
    CheckResult result = checkDetailed(principal, permissions, resources);
    if (result.allowed()) {
      return true;
    }
    if (result.missing().isEmpty()) {
      // Denied without saying what is missing: fail closed.
      return false;
    }
    List<String> wanted = pick(permissions, "permission");
    for (MissingPermission m : result.missing()) {
      boolean holdsOne = false;
      for (String p : wanted) {
        if (!m.permissions().contains(p)) {
          holdsOne = true;
          break;
        }
      }
      if (!holdsOne) {
        return false;
      }
    }
    return true;
  }

  /** True when {@code principal} holds at least one of the permissions on {@code resource}. */
  public boolean checkAny(String principal, Collection<String> permissions, String resource) {
    return checkAny(principal, permissions, List.of(nonNull(resource)));
  }

  /**
   * Checks whether {@code principal} holds every permission on every resource and returns what is
   * missing. At most 100 permissions and 100 resources.
   *
   * @throws PermioPermissionException if the API key lacks {@code permission.check}
   * @throws PermioValidationException for malformed URIs or empty inputs
   */
  public CheckResult checkDetailed(
      String principal, Collection<String> permissions, Collection<String> resources) {
    requirePrincipal(principal);
    List<String> uris = pick(resources, "resource");
    uris.forEach(Resources::assertResourceUri);
    List<String> perms = pick(permissions, "permission");
    String projectName = resolveProject();
    CheckPermissionResponse res =
        call(
            () ->
                stub()
                    .check(
                        CheckPermissionRequest.newBuilder()
                            .setProjectName(projectName)
                            .setPrincipalId(principal)
                            .addAllResourceUris(uris)
                            .addAllPermissions(perms)
                            .build()));
    List<MissingPermission> missing = new ArrayList<>();
    for (MissingPermissions m : res.getMissingPermissionsList()) {
      missing.add(new MissingPermission(m.getResourceUri(), m.getMissingPermissionsList()));
    }
    return new CheckResult(res.getPassed(), missing);
  }

  /** {@link #checkDetailed(String, Collection, Collection)} for one permission and resource. */
  public CheckResult checkDetailed(String principal, String permission, String resource) {
    return checkDetailed(principal, List.of(nonNull(permission)), List.of(nonNull(resource)));
  }

  /**
   * Returns when {@code principal} holds {@code permission} on {@code resource}.
   *
   * @throws PermioDeniedException when it does not, with {@link PermioDeniedException#missing()}
   *     filled in
   */
  public void assertAllowed(String principal, String permission, String resource) {
    assertAllowed(principal, List.of(nonNull(permission)), List.of(nonNull(resource)));
  }

  /**
   * Returns when {@code principal} holds every permission on every resource.
   *
   * @throws PermioDeniedException when it does not
   */
  public void assertAllowed(
      String principal, Collection<String> permissions, Collection<String> resources) {
    CheckResult result = checkDetailed(principal, permissions, resources);
    if (!result.allowed()) {
      throw new PermioDeniedException(principal, result.missing());
    }
  }

  /** Async {@link #check(String, String, String)}. */
  public CompletableFuture<Boolean> checkAsync(
      String principal, String permission, String resource) {
    return async(() -> check(principal, permission, resource));
  }

  /** Async {@link #checkDetailed(String, Collection, Collection)}. */
  public CompletableFuture<CheckResult> checkDetailedAsync(
      String principal, Collection<String> permissions, Collection<String> resources) {
    return async(() -> checkDetailed(principal, permissions, resources));
  }

  /** Async {@link #checkDetailed(String, String, String)}. */
  public CompletableFuture<CheckResult> checkDetailedAsync(
      String principal, String permission, String resource) {
    return async(() -> checkDetailed(principal, permission, resource));
  }

  /** Async {@link #checkAll(String, Collection, Collection)}. */
  public CompletableFuture<Boolean> checkAllAsync(
      String principal, Collection<String> permissions, Collection<String> resources) {
    return async(() -> checkAll(principal, permissions, resources));
  }

  /** Async {@link #checkAll(String, Collection, String)}. */
  public CompletableFuture<Boolean> checkAllAsync(
      String principal, Collection<String> permissions, String resource) {
    return async(() -> checkAll(principal, permissions, resource));
  }

  /** Async {@link #checkAny(String, Collection, Collection)}. */
  public CompletableFuture<Boolean> checkAnyAsync(
      String principal, Collection<String> permissions, Collection<String> resources) {
    return async(() -> checkAny(principal, permissions, resources));
  }

  /** Async {@link #checkAny(String, Collection, String)}. */
  public CompletableFuture<Boolean> checkAnyAsync(
      String principal, Collection<String> permissions, String resource) {
    return async(() -> checkAny(principal, permissions, resource));
  }

  /**
   * Async {@link #assertAllowed(String, String, String)}: completes exceptionally with {@link
   * PermioDeniedException} when denied.
   */
  public CompletableFuture<Void> assertAllowedAsync(
      String principal, String permission, String resource) {
    return async(
        () -> {
          assertAllowed(principal, permission, resource);
          return null;
        });
  }

  /** Async {@link #assertAllowed(String, Collection, Collection)}. */
  public CompletableFuture<Void> assertAllowedAsync(
      String principal, Collection<String> permissions, Collection<String> resources) {
    return async(
        () -> {
          assertAllowed(principal, permissions, resources);
          return null;
        });
  }

  /**
   * Checks each resource separately and returns, per resource URI, whether {@code principal} holds
   * {@code permission} on it. The map has one entry per distinct resource, in request order. Use it
   * to filter a list you already have in memory. More than 100 resources are split into several
   * {@code BatchCheck} calls, built as they are sent, at most 4 in flight at a time; the first
   * failing chunk (in request order) is thrown. At most {@link #DEFAULT_MAX_BATCH_RESOURCES}
   * resources per call (see the {@code maxResources} overloads).
   *
   * @throws PermioValidationException if there are more than {@link #DEFAULT_MAX_BATCH_RESOURCES}
   *     resources
   * @throws PermioPermissionException if the API key lacks {@code permission.check}
   */
  public Map<String, Boolean> batchCheck(
      String principal, String permission, Collection<String> resources) {
    return batchCheck(principal, List.of(nonNull(permission)), resources);
  }

  /**
   * Like {@link #batchCheck(String, String, Collection)}; a resource is allowed only with all of
   * the permissions (at most 100).
   */
  public Map<String, Boolean> batchCheck(
      String principal, Collection<String> permissions, Collection<String> resources) {
    return batchCheck(principal, permissions, resources, DEFAULT_MAX_BATCH_RESOURCES);
  }

  /**
   * Like {@link #batchCheck(String, Collection, Collection)}, accepting at most {@code
   * maxResources} resources (counted as given, before de-duplication) instead of {@link
   * #DEFAULT_MAX_BATCH_RESOURCES}.
   *
   * @throws PermioValidationException if there are more than {@code maxResources} resources
   */
  public Map<String, Boolean> batchCheck(
      String principal,
      Collection<String> permissions,
      Collection<String> resources,
      int maxResources) {
    Map<String, Boolean> out = new LinkedHashMap<>();
    for (BatchCheckEntry e : batchCheckDetailed(principal, permissions, resources, maxResources)) {
      out.put(e.resource(), e.allowed());
    }
    return Collections.unmodifiableMap(out);
  }

  /** Like {@link #batchCheck}, but returns one entry per resource with the missing permissions. */
  public List<BatchCheckEntry> batchCheckDetailed(
      String principal, Collection<String> permissions, Collection<String> resources) {
    return batchCheckDetailed(principal, permissions, resources, DEFAULT_MAX_BATCH_RESOURCES);
  }

  /**
   * Like {@link #batchCheckDetailed(String, Collection, Collection)}, accepting at most {@code
   * maxResources} resources (counted as given, before de-duplication).
   *
   * @throws PermioValidationException if there are more than {@code maxResources} resources, or
   *     {@code maxResources} is not positive
   */
  public List<BatchCheckEntry> batchCheckDetailed(
      String principal,
      Collection<String> permissions,
      Collection<String> resources,
      int maxResources) {
    requirePrincipal(principal);
    if (maxResources <= 0) {
      throw new PermioValidationException("permio: maxResources must be positive");
    }
    if (resources == null) {
      throw new PermioValidationException("permio: resources are required");
    }
    if (resources.size() > maxResources) {
      throw new PermioValidationException(
          "permio: batchCheck accepts at most "
              + maxResources
              + " resources per call, got "
              + resources.size()
              + "; split the list or raise maxResources");
    }
    List<String> uris = new ArrayList<>(new LinkedHashSet<>(resources));
    if (uris.isEmpty()) {
      throw new PermioValidationException("permio: at least one resource is required");
    }
    uris.forEach(Resources::assertResourceUri);
    List<String> perms = pick(permissions, "permission");
    String projectName = resolveProject();

    PermissionsServiceGrpc.PermissionsServiceFutureStub stub = raw().permissionsFuture();
    // Chunks of 100, with at most MAX_PARALLEL_CHUNKS requests in flight.
    ArrayDeque<ListenableFuture<BatchCheckResponse>> inFlight = new ArrayDeque<>();
    Map<String, BatchCheckEntry> byUri = new HashMap<>();
    try {
      int next = 0;
      while (next < uris.size() || !inFlight.isEmpty()) {
        while (next < uris.size() && inFlight.size() < MAX_PARALLEL_CHUNKS) {
          List<String> chunk = uris.subList(next, Math.min(uris.size(), next + MAX_ITEMS));
          next += chunk.size();
          inFlight.add(
              stub.batchCheck(
                  BatchCheckRequest.newBuilder()
                      .setProjectName(projectName)
                      .setPrincipalId(principal)
                      .addAllResourceUris(chunk)
                      .addAllPermissions(perms)
                      .build()));
        }
        for (BatchCheckResult r : await(inFlight.peek()).getResultsList()) {
          byUri.put(
              r.getResourceUri(),
              new BatchCheckEntry(
                  r.getResourceUri(),
                  r.getAllowed() && r.getMissingPermissionsCount() == 0,
                  r.getMissingPermissionsList()));
        }
        inFlight.poll();
      }
    } finally {
      for (ListenableFuture<BatchCheckResponse> f : inFlight) {
        f.cancel(true);
      }
    }
    // Fail closed: every requested resource must have exactly its own answer.
    List<BatchCheckEntry> out = new ArrayList<>(uris.size());
    for (String uri : uris) {
      BatchCheckEntry e = byUri.get(uri);
      if (e == null) {
        throw inconsistentResponse("no BatchCheck result for \"" + uri + "\"");
      }
      out.add(e);
    }
    return Collections.unmodifiableList(out);
  }

  /** Async {@link #batchCheck(String, String, Collection)}. */
  public CompletableFuture<Map<String, Boolean>> batchCheckAsync(
      String principal, String permission, Collection<String> resources) {
    return async(() -> batchCheck(principal, permission, resources));
  }

  /** Async {@link #batchCheck(String, Collection, Collection)}. */
  public CompletableFuture<Map<String, Boolean>> batchCheckAsync(
      String principal, Collection<String> permissions, Collection<String> resources) {
    return async(() -> batchCheck(principal, permissions, resources));
  }

  /** Async {@link #batchCheck(String, Collection, Collection, int)}. */
  public CompletableFuture<Map<String, Boolean>> batchCheckAsync(
      String principal,
      Collection<String> permissions,
      Collection<String> resources,
      int maxResources) {
    return async(() -> batchCheck(principal, permissions, resources, maxResources));
  }

  /** Async {@link #batchCheckDetailed(String, Collection, Collection)}. */
  public CompletableFuture<List<BatchCheckEntry>> batchCheckDetailedAsync(
      String principal, Collection<String> permissions, Collection<String> resources) {
    return async(() -> batchCheckDetailed(principal, permissions, resources));
  }

  /** Async {@link #batchCheckDetailed(String, Collection, Collection, int)}. */
  public CompletableFuture<List<BatchCheckEntry>> batchCheckDetailedAsync(
      String principal,
      Collection<String> permissions,
      Collection<String> resources,
      int maxResources) {
    return async(() -> batchCheckDetailed(principal, permissions, resources, maxResources));
  }

  // ---------------------------------------------------------------------------------------------
  // Access queries

  /**
   * Answers "where may {@code principal} use {@code permission} at or below {@code under}?",
   * following every page, and collects at most {@link #DEFAULT_MAX_RESULTS} scopes. Use the result
   * to filter lists in your database: {@link AccessScopes#coversAll()} means no filter is needed,
   * {@link AccessScopes#toPrefixes()} gives path prefixes, {@link AccessScopes#toRegex()} a regex,
   * and {@link AccessScopes#allows} tests a single URI.
   *
   * @throws PermioTooManyResultsException if there are more scopes than the cap; use {@link
   *     #listAccessIterable} to stream them
   * @throws PermioPermissionException unless the API key has {@code permission.check} on {@code
   *     under} and {@code principal.assignment.get} on the principal
   */
  public AccessScopes listAccess(String principal, String permission, String under) {
    return listAccess(principal, permission, under, DEFAULT_MAX_RESULTS);
  }

  /**
   * Like {@link #listAccess(String, String, String)}, collecting at most {@code maxResults} scopes.
   * At most {@code maxResults + 1} scopes are fetched.
   *
   * @throws PermioTooManyResultsException if there are more than {@code maxResults} scopes
   * @throws PermioValidationException if {@code maxResults} is not positive
   */
  public AccessScopes listAccess(
      String principal, String permission, String under, int maxResults) {
    requireMaxResults(maxResults);
    boolean[] coversAll = {false};
    List<AccessScopes.Scope> scopes =
        collect(
            accessScopes(principal, permission, under, coversAll, (long) maxResults + 1),
            maxResults,
            "listAccess",
            "listAccessIterable");
    return new AccessScopes(under, coversAll[0], scopes);
  }

  /**
   * Streams the scopes of {@link #listAccess(String, String, String)}: "where may {@code principal}
   * use {@code permission} at or below {@code under}?", ordered by URI, fetched one page at a time
   * as you iterate, with nothing buffered beyond one page. When the principal holds the permission
   * on {@code under} itself, the only scope is a {@link AccessScopes.Kind#SUBTREE} scope whose URI
   * is {@code under}.
   *
   * @throws PermioValidationException for malformed inputs (when called)
   */
  public PagedIterable<AccessScopes.Scope> listAccessIterable(
      String principal, String permission, String under) {
    return accessScopes(principal, permission, under, new boolean[1], Long.MAX_VALUE);
  }

  private PagedIterable<AccessScopes.Scope> accessScopes(
      String principal, String permission, String under, boolean[] coversAll, long maxItems) {
    requirePrincipal(principal);
    if (permission == null || permission.isEmpty()) {
      throw new PermioValidationException("permio: permission is required");
    }
    Resources.assertResourceUri(under);
    String projectName = resolveProject();
    return new PagedIterable<>(
        p -> {
          ListAccessResponse res =
              listStub()
                  .listAccess(
                      ListAccessRequest.newBuilder()
                          .setProjectName(projectName)
                          .setPrincipalId(principal)
                          .setPermission(permission)
                          .setUnderUri(under)
                          .setPagination(p)
                          .build());
          if (res.getCoversAll()) {
            coversAll[0] = true;
          }
          List<AccessScopes.Scope> scopes = new ArrayList<>(res.getScopesCount());
          res.getScopesList()
              .forEach(
                  s ->
                      scopes.add(
                          new AccessScopes.Scope(s.getUri(), AccessScopes.Kind.of(s.getScope()))));
          return new PagedIterable.Page<>(scopes, res.getPagination().getNextCursor());
        },
        MAX_ITEMS,
        maxItems);
  }

  /** Async {@link #listAccess(String, String, String)}. */
  public CompletableFuture<AccessScopes> listAccessAsync(
      String principal, String permission, String under) {
    return async(() -> listAccess(principal, permission, under));
  }

  /** Async {@link #listAccess(String, String, String, int)}. */
  public CompletableFuture<AccessScopes> listAccessAsync(
      String principal, String permission, String under, int maxResults) {
    return async(() -> listAccess(principal, permission, under, maxResults));
  }

  /**
   * Answers "who has access to {@code resource}?", following every page and collecting at most
   * {@link #DEFAULT_MAX_RESULTS} entries: the principals (groups included, as {@code group:<slug>})
   * with a grant on the resource, an ancestor of it, or a {@code _} wildcard URI matching it,
   * ordered by principal ID, with the grants that give the access.
   *
   * @throws PermioTooManyResultsException if there are more entries than the cap; use {@link
   *     #whoHasAccessIterable} to stream them
   * @throws PermioPermissionException unless the API key has {@code principal.list} and {@code
   *     principal.assignment.list} on the project
   */
  public List<AccessEntry> whoHasAccess(String resource) {
    return whoHasAccess(resource, null, false);
  }

  /**
   * Like {@link #whoHasAccess(String)}, counting only grants that confer {@code permission} (null
   * for any grant), so a principal is listed exactly when {@link #check} passes for it through its
   * own grants (or, for entries with {@code viaGroup}, through that group). With {@code
   * expandGroups}, every member of each listed group is listed too, once per group.
   */
  public List<AccessEntry> whoHasAccess(String resource, String permission, boolean expandGroups) {
    return whoHasAccess(resource, permission, expandGroups, DEFAULT_MAX_RESULTS);
  }

  /**
   * Like {@link #whoHasAccess(String, String, boolean)}, collecting at most {@code maxResults}
   * entries. At most {@code maxResults + 1} entries are fetched.
   *
   * @throws PermioTooManyResultsException if there are more than {@code maxResults} entries
   * @throws PermioValidationException if {@code maxResults} is not positive
   */
  public List<AccessEntry> whoHasAccess(
      String resource, String permission, boolean expandGroups, int maxResults) {
    requireMaxResults(maxResults);
    return collect(
        accessEntries(resource, permission, expandGroups, (long) maxResults + 1),
        maxResults,
        "whoHasAccess",
        "whoHasAccessIterable");
  }

  /**
   * Streams the entries of {@link #whoHasAccess(String)}, fetched one page at a time as you
   * iterate, with nothing buffered beyond one page. Use it when a resource may have very many
   * principals with access.
   *
   * <pre>{@code
   * for (AccessEntry e : permio.whoHasAccessIterable("/docs/1")) { ... }
   * }</pre>
   */
  public PagedIterable<AccessEntry> whoHasAccessIterable(String resource) {
    return whoHasAccessIterable(resource, null, false);
  }

  /** Streams the entries of {@link #whoHasAccess(String, String, boolean)}. */
  public PagedIterable<AccessEntry> whoHasAccessIterable(
      String resource, String permission, boolean expandGroups) {
    return accessEntries(resource, permission, expandGroups, Long.MAX_VALUE);
  }

  private PagedIterable<AccessEntry> accessEntries(
      String resource, String permission, boolean expandGroups, long maxItems) {
    Resources.assertResourceUri(resource);
    if (permission != null && permission.isEmpty()) {
      throw new PermioValidationException("permio: permission must not be empty");
    }
    String projectName = resolveProject();
    return new PagedIterable<>(
        p -> {
          ListPrincipalsWithAccessRequest.Builder req =
              ListPrincipalsWithAccessRequest.newBuilder()
                  .setProjectName(projectName)
                  .setResourceUri(resource)
                  .setExpandGroups(expandGroups)
                  .setPagination(p);
          if (permission != null) {
            req.setPermission(permission);
          }
          ListPrincipalsWithAccessResponse res = listStub().listPrincipalsWithAccess(req.build());
          List<AccessEntry> entries = new ArrayList<>(res.getPrincipalsCount());
          for (PrincipalAccess pa : res.getPrincipalsList()) {
            List<AccessGrantInfo> via = new ArrayList<>();
            pa.getViaList().forEach(g -> via.add(AccessGrantInfo.of(g)));
            entries.add(
                new AccessEntry(
                    pa.getPrincipalId(),
                    pa.getIsGroup(),
                    pa.getViaGroup().isEmpty() ? null : pa.getViaGroup(),
                    via));
          }
          return new PagedIterable.Page<>(entries, res.getPagination().getNextCursor());
        },
        MAX_ITEMS,
        maxItems);
  }

  /** Async {@link #whoHasAccess(String)}. */
  public CompletableFuture<List<AccessEntry>> whoHasAccessAsync(String resource) {
    return async(() -> whoHasAccess(resource));
  }

  /** Async {@link #whoHasAccess(String, String, boolean)}. */
  public CompletableFuture<List<AccessEntry>> whoHasAccessAsync(
      String resource, String permission, boolean expandGroups) {
    return async(() -> whoHasAccess(resource, permission, expandGroups));
  }

  /** Async {@link #whoHasAccess(String, String, boolean, int)}. */
  public CompletableFuture<List<AccessEntry>> whoHasAccessAsync(
      String resource, String permission, boolean expandGroups, int maxResults) {
    return async(() -> whoHasAccess(resource, permission, expandGroups, maxResults));
  }

  private static void requireMaxResults(int maxResults) {
    if (maxResults <= 0) {
      throw new PermioValidationException("permio: maxResults must be positive");
    }
  }

  /** Collects {@code items}, throwing once more than {@code maxResults} have arrived. */
  private static <T> List<T> collect(
      PagedIterable<T> items, int maxResults, String method, String streamingMethod) {
    List<T> out = new ArrayList<>();
    for (T item : items) {
      if (out.size() == maxResults) {
        throw new PermioTooManyResultsException(method, streamingMethod, maxResults);
      }
      out.add(item);
    }
    return Collections.unmodifiableList(out);
  }

  // ---------------------------------------------------------------------------------------------
  // Lists

  /** Iterates every permission in the project. */
  public PagedIterable<io.perms.proto.permissions.v1.Permission> listPermissions() {
    return listPermissions(ListOptions.defaults());
  }

  /** Iterates the permissions in the project, optionally filtered by name. */
  public PagedIterable<io.perms.proto.permissions.v1.Permission> listPermissions(
      ListOptions options) {
    String projectName = resolveProject();
    return new PagedIterable<>(
        p -> {
          ListPermissionsRequest.Builder req =
              ListPermissionsRequest.newBuilder().setProjectName(projectName).setPagination(p);
          if (options.search() != null) {
            req.setSearch(options.search());
          }
          ListPermissionsResponse res = listStub().listPermissions(req.build());
          return new PagedIterable.Page<>(
              res.getPermissionsList(), res.getPagination().getNextCursor());
        },
        options.pageSize());
  }

  /** Iterates every role in the project. */
  public PagedIterable<io.perms.proto.permissions.v1.Role> listRoles() {
    return listRoles(ListOptions.defaults());
  }

  /** Iterates the roles in the project, optionally filtered by name. */
  public PagedIterable<io.perms.proto.permissions.v1.Role> listRoles(ListOptions options) {
    String projectName = resolveProject();
    return new PagedIterable<>(
        p -> {
          ListRolesRequest.Builder req =
              ListRolesRequest.newBuilder().setProjectName(projectName).setPagination(p);
          if (options.search() != null) {
            req.setSearch(options.search());
          }
          ListRolesResponse res = listStub().listRoles(req.build());
          return new PagedIterable.Page<>(res.getRolesList(), res.getPagination().getNextCursor());
        },
        options.pageSize());
  }

  /** Iterates the IDs of every principal with grants in the project. */
  public PagedIterable<String> listPrincipals() {
    return listPrincipals(ListOptions.defaults());
  }

  /** Iterates the IDs of the principals with grants in the project, optionally filtered. */
  public PagedIterable<String> listPrincipals(ListOptions options) {
    String projectName = resolveProject();
    return new PagedIterable<>(
        p -> {
          ListPrincipalsRequest.Builder req =
              ListPrincipalsRequest.newBuilder().setProjectName(projectName).setPagination(p);
          if (options.search() != null) {
            req.setSearch(options.search());
          }
          ListPrincipalsResponse res = listStub().listPrincipals(req.build());
          return new PagedIterable.Page<>(
              res.getPrincipalsList(), res.getPagination().getNextCursor());
        },
        options.pageSize());
  }

  /** Iterates every grant (resource + permissions + roles) of {@code principal} in the project. */
  public PagedIterable<Assignment> listGrants(String principal) {
    return listGrants(principal, ListOptions.defaults());
  }

  /**
   * Iterates every grant of {@code principal} in the project, with the given page size.
   *
   * @throws IllegalArgumentException if {@code options} has a search filter: this RPC cannot search
   */
  public PagedIterable<Assignment> listGrants(String principal, ListOptions options) {
    requirePrincipal(principal);
    rejectSearch(options, "listGrants");
    String projectName = resolveProject();
    return new PagedIterable<>(
        p -> {
          GetAllAssignmentsForPrincipalResponse res =
              listStub()
                  .getAllAssignmentsForPrincipal(
                      GetAllAssignmentsForPrincipalRequest.newBuilder()
                          .setProjectName(projectName)
                          .setPrincipalId(principal)
                          .setPagination(p)
                          .build());
          return new PagedIterable.Page<>(
              res.getAssignmentsList(), res.getPagination().getNextCursor());
        },
        options.pageSize());
  }

  /**
   * Alias of {@link #listGrants(String)}, named after the {@code Assignment} message as in
   * permio-js.
   */
  public PagedIterable<Assignment> listAssignments(String principal) {
    return listGrants(principal);
  }

  // ---------------------------------------------------------------------------------------------
  // Writes

  /**
   * Grants {@code permission} to {@code principal} on {@code resource}; see {@link #grant(Grant)}.
   */
  public void grant(String principal, String permission, String resource) {
    grant(Grant.to(principal).on(resource).permissions(nonNull(permission)));
  }

  /**
   * Grants permissions and roles to a principal on a resource, and so on every resource below it.
   * Additive: what the principal already holds there is kept, and granting something it already
   * holds changes nothing. Use {@link Groups#principal(String)} as the principal to grant to every
   * member of a group.
   *
   * <p>Like every write, a grant is sent once and never retried automatically.
   *
   * @throws PermioValidationException if a permission or role does not exist in the project, or the
   *     input is malformed
   * @throws PermioPermissionException unless the API key has full access
   */
  public void grant(Grant grant) {
    Prepared g = prepare(grant);
    call(
        () ->
            stub()
                .grantUserPermissionsAndRolesOnResource(
                    GrantUserPermissionsAndRolesOnResourceRequest.newBuilder()
                        .setProjectName(g.project)
                        .setUserId(g.principal)
                        .setResourceUri(g.resource)
                        .addAllPermissions(g.permissions)
                        .addAllRoles(g.roles)
                        .build()));
  }

  /**
   * Revokes {@code permission} from {@code principal} on {@code resource}; see {@link
   * #revoke(Grant)}.
   */
  public void revoke(String principal, String permission, String resource) {
    revoke(Grant.to(principal).on(resource).permissions(nonNull(permission)));
  }

  /**
   * Removes permissions and roles from a principal's grant on exactly the resource. Names the grant
   * does not hold are ignored, so revoking twice is harmless. Grants on ancestor URIs (or {@code _}
   * wildcard URIs that match it) are not touched, so the principal may still pass {@link #check}
   * afterwards; {@link #whoHasAccess} shows where access comes from.
   *
   * <p>Like every write, a revoke is sent once and never retried automatically.
   *
   * @throws PermioPermissionException unless the API key has full access
   */
  public void revoke(Grant grant) {
    Prepared g = prepare(grant);
    call(
        () ->
            stub()
                .revokeUserPermissionsAndRolesOnResource(
                    RevokeUserPermissionsAndRolesOnResourceRequest.newBuilder()
                        .setProjectName(g.project)
                        .setUserId(g.principal)
                        .setResourceUri(g.resource)
                        .addAllPermissions(g.permissions)
                        .addAllRoles(g.roles)
                        .build()));
  }

  /** Async {@link #grant(String, String, String)}. */
  public CompletableFuture<Void> grantAsync(String principal, String permission, String resource) {
    return async(
        () -> {
          grant(principal, permission, resource);
          return null;
        });
  }

  /** Async {@link #revoke(String, String, String)}. */
  public CompletableFuture<Void> revokeAsync(String principal, String permission, String resource) {
    return async(
        () -> {
          revoke(principal, permission, resource);
          return null;
        });
  }

  /** Async {@link #grant(Grant)}. */
  public CompletableFuture<Void> grantAsync(Grant grant) {
    return async(
        () -> {
          grant(grant);
          return null;
        });
  }

  /** Async {@link #revoke(Grant)}. */
  public CompletableFuture<Void> revokeAsync(Grant grant) {
    return async(
        () -> {
          revoke(grant);
          return null;
        });
  }

  // ---------------------------------------------------------------------------------------------
  // Identity

  /**
   * Describes the credential this client uses: for an API key, its ID, its organisation, the
   * project it belongs to and its access level. Useful to verify a key at startup. Needs no
   * project.
   *
   * @throws PermioAuthException if the key is unknown or invalidated
   */
  public WhoAmI whoAmI() {
    WhoAmIResponse res = call(() -> stub().whoAmI(WhoAmIRequest.getDefaultInstance()));
    WhoAmI.PrincipalKind kind;
    switch (res.getPrincipalType()) {
      case PRINCIPAL_TYPE_USER:
        kind = WhoAmI.PrincipalKind.USER;
        break;
      case PRINCIPAL_TYPE_API_KEY:
        kind = WhoAmI.PrincipalKind.API_KEY;
        break;
      case PRINCIPAL_TYPE_SERVICE_ACCOUNT:
        kind = WhoAmI.PrincipalKind.SERVICE_ACCOUNT;
        break;
      default:
        kind = WhoAmI.PrincipalKind.UNKNOWN;
    }
    WhoAmI.ApiKeyAccess access = null;
    if (kind == WhoAmI.PrincipalKind.API_KEY) {
      switch (res.getApiKeyAccess()) {
        case API_KEY_ACCESS_CHECK:
          access = WhoAmI.ApiKeyAccess.CHECK;
          break;
        case API_KEY_ACCESS_FULL:
          access = WhoAmI.ApiKeyAccess.FULL;
          break;
        default:
          access = WhoAmI.ApiKeyAccess.UNKNOWN;
      }
    }
    return new WhoAmI(
        res.getPrincipalId(),
        res.getOrganisationId(),
        kind,
        res.getProjectName().isEmpty() ? null : res.getProjectName(),
        access,
        res.getEmail().isEmpty() ? null : res.getEmail());
  }

  /** Async {@link #whoAmI()}. */
  public CompletableFuture<WhoAmI> whoAmIAsync() {
    return async(this::whoAmI);
  }

  // ---------------------------------------------------------------------------------------------
  // Internals

  PermissionsServiceGrpc.PermissionsServiceBlockingStub stub() {
    return raw().permissions();
  }

  /** {@link #stub()} with the client's list timeout per attempt, for list page requests. */
  PermissionsServiceGrpc.PermissionsServiceBlockingStub listStub() {
    return stub().withOption(ResilienceInterceptor.ATTEMPT_TIMEOUT_NANOS, core.listTimeoutNanos);
  }

  String resolveProject() {
    if (project == null || project.isEmpty()) {
      throw new PermioConfigException(
          "permio: no project given; set it on the builder or use withProject()");
    }
    return project;
  }

  <T> T call(Supplier<T> rpc) {
    try {
      return rpc.get();
    } catch (RuntimeException e) {
      throw PermioException.from(e);
    }
  }

  <T> CompletableFuture<T> async(Supplier<T> work) {
    if (core.interceptor.isShutdown()) {
      return failed(closedException(null));
    }
    try {
      return CompletableFuture.supplyAsync(work, core.executor);
    } catch (RejectedExecutionException e) {
      return failed(core.interceptor.isShutdown() ? closedException(e) : PermioException.from(e));
    } catch (RuntimeException e) {
      return failed(PermioException.from(e));
    }
  }

  private static <T> CompletableFuture<T> failed(Throwable e) {
    CompletableFuture<T> f = new CompletableFuture<>();
    f.completeExceptionally(e);
    return f;
  }

  private static PermioException closedException(Throwable cause) {
    return PermioException.fromStatus(
        ResilienceInterceptor.closedStatus().withCause(cause), null, cause);
  }

  /** Throws when {@code options} sets a search filter on a list RPC that has none. */
  static void rejectSearch(ListOptions options, String method) {
    Objects.requireNonNull(options, "options");
    if (options.search() != null) {
      throw new IllegalArgumentException(
          "permio: " + method + " does not support ListOptions.withSearch");
    }
  }

  private static <T> T await(ListenableFuture<T> f) {
    try {
      return f.get();
    } catch (ExecutionException e) {
      throw PermioException.from(e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PermioException("permio: interrupted", io.grpc.Status.Code.CANCELLED, e, null);
    }
  }

  private static PermioException inconsistentResponse(String what) {
    return new PermioException("permio: inconsistent response from the API: " + what);
  }

  private static String nonNull(String value) {
    // List.of rejects nulls with an NPE; give the SDK's error instead.
    return value == null ? "" : value;
  }

  private static void requirePrincipal(String principal) {
    if (principal == null || principal.isEmpty()) {
      throw new PermioValidationException("permio: principal is required");
    }
  }

  // Distinct values, 1..100 of them, as js pick().
  private static List<String> pick(Collection<String> values, String what) {
    if (values == null) {
      throw new PermioValidationException("permio: at least one " + what + " is required");
    }
    List<String> out = new ArrayList<>(new LinkedHashSet<>(values));
    for (String v : out) {
      if (v == null || v.isEmpty()) {
        throw new PermioValidationException("permio: " + what + "s must be non-empty strings");
      }
    }
    if (out.isEmpty()) {
      throw new PermioValidationException("permio: at least one " + what + " is required");
    }
    if (out.size() > MAX_ITEMS) {
      throw new PermioValidationException("permio: at most 100 " + what + "s per check");
    }
    return out;
  }

  private static List<String> names(Collection<String> values, String what) {
    List<String> out = new ArrayList<>(new LinkedHashSet<>(values));
    for (String v : out) {
      if (v == null || v.isEmpty()) {
        throw new PermioValidationException("permio: " + what + "s must be non-empty strings");
      }
    }
    return out;
  }

  private static final class Prepared {
    String project;
    String principal;
    String resource;
    List<String> permissions;
    List<String> roles;
  }

  private Prepared prepare(Grant grant) {
    Objects.requireNonNull(grant, "grant");
    requirePrincipal(grant.principal());
    Resources.assertResourceUri(grant.resource());
    Prepared p = new Prepared();
    p.permissions = names(grant.permissions(), "permission");
    p.roles = names(grant.roles(), "role");
    if (p.permissions.isEmpty() && p.roles.isEmpty()) {
      throw new PermioValidationException("permio: at least one permission or role is required");
    }
    p.project = resolveProject();
    p.principal = grant.principal();
    p.resource = grant.resource();
    return p;
  }

  private static String readVersion() {
    try (InputStream in = Permio.class.getResourceAsStream("permio-version.properties")) {
      if (in == null) {
        return "unknown";
      }
      Properties props = new Properties();
      props.load(in);
      return props.getProperty("version", "unknown");
    } catch (IOException e) {
      return "unknown";
    }
  }

  private static ThreadFactory daemonThreads(String prefix) {
    AtomicInteger n = new AtomicInteger();
    return r -> {
      Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
      t.setDaemon(true);
      return t;
    };
  }

  // ---------------------------------------------------------------------------------------------

  /**
   * Builds a {@link Permio} client.
   *
   * <pre>{@code
   * Permio permio = Permio.builder()
   *     .apiKey(System.getenv("PERMIO_API_KEY")) // the default when not set
   *     .baseUrl("https://permio.example.test")   // default: production
   *     .project("my-app")
   *     .timeout(Duration.ofSeconds(5))           // per attempt; default 10 s
   *     .retries(3)                               // idempotent reads only; default 2
   *     .build();
   * }</pre>
   */
  public static final class Builder {
    private String apiKey;
    private String baseUrl = DEFAULT_BASE_URL;
    private String project;
    private Duration timeout = DEFAULT_TIMEOUT;
    private Duration listTimeout;
    private RetryPolicy retryPolicy = RetryPolicy.DEFAULT;
    private Channel channel;
    private Executor executor;
    private Consumer<ManagedChannelBuilder<?>> channelConfigurer;

    private Builder() {}

    /**
     * API key from the dashboard ({@code perms_...}), or a legacy JWT key. When not set, the {@code
     * PERMIO_API_KEY} environment variable is used.
     */
    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    /**
     * API base URL: {@code https://host[:port]} (TLS) or {@code http://host[:port]} (plaintext, for
     * local testing). Defaults to production, {@link #DEFAULT_BASE_URL}.
     */
    public Builder baseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
      return this;
    }

    /** Alias of {@link #baseUrl(String)}. */
    public Builder endpoint(String endpoint) {
      return baseUrl(endpoint);
    }

    /**
     * Default {@code project_name} for every helper. Override per call with {@link #withProject}.
     */
    public Builder project(String project) {
      this.project = project;
      return this;
    }

    /** Timeout per attempt. Default 10 s; {@link Duration#ZERO} disables it. */
    public Builder timeout(Duration timeout) {
      Objects.requireNonNull(timeout, "timeout");
      this.timeout = timeout.isNegative() ? Duration.ZERO : timeout;
      return this;
    }

    /**
     * Timeout per attempt for each list page request: the list helpers, the iterables and the
     * collect-all {@link Permio#whoHasAccess} / {@link Permio#listAccess}. Default: the {@link
     * #timeout} (10 s); {@link Duration#ZERO} disables it. A {@link Permio#withCallTimeout}
     * deadline still bounds each page.
     */
    public Builder listTimeout(Duration listTimeout) {
      Objects.requireNonNull(listTimeout, "listTimeout");
      this.listTimeout = listTimeout.isNegative() ? Duration.ZERO : listTimeout;
      return this;
    }

    /** Retries for idempotent reads; shorthand for {@link RetryPolicy#ofMaxRetries}. */
    public Builder retries(int maxRetries) {
      this.retryPolicy = RetryPolicy.ofMaxRetries(maxRetries);
      return this;
    }

    /** Retry settings for idempotent reads. */
    public Builder retryPolicy(RetryPolicy retryPolicy) {
      this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
      return this;
    }

    /**
     * Advanced: a gRPC channel to use instead of building one, e.g. an in-process channel in tests.
     * {@code baseUrl} is then ignored; auth, timeouts and retries still apply. The client does not
     * shut this channel down.
     */
    public Builder channel(Channel channel) {
      this.channel = channel;
      return this;
    }

    /**
     * Executor for the {@code *Async} methods. Each async call runs the blocking call on it, so it
     * holds a thread for the call's duration. Defaults to a pool of {@link
     * Permio#DEFAULT_ASYNC_THREADS} daemon threads owned by the client (further calls queue). An
     * executor passed here is not shut down by {@link Permio#close()}.
     */
    public Builder executor(Executor executor) {
      this.executor = executor;
      return this;
    }

    /**
     * Advanced: adjusts the channel builder before the channel is built (keepalive, proxy,
     * interceptors, ...). Ignored when {@link #channel(Channel)} is set.
     */
    public Builder configureChannel(Consumer<ManagedChannelBuilder<?>> configurer) {
      this.channelConfigurer = configurer;
      return this;
    }

    /**
     * Builds the client.
     *
     * @throws PermioConfigException if the API key or base URL is malformed
     */
    public Permio build() {
      String key = apiKey != null ? apiKey : System.getenv(API_KEY_ENV);
      ApiKeys.Kind kind = ApiKeys.validate(key);
      if (project != null && project.isEmpty()) {
        project = null;
      }

      ManagedChannel owned = null;
      Channel base = channel;
      String url = null;
      if (base == null) {
        Target target = Target.parse(baseUrl);
        url = target.url;
        ManagedChannelBuilder<?> b =
            Grpc.newChannelBuilder(target.authority, target.credentials)
                .userAgent("permio-java/" + VERSION);
        if (channelConfigurer != null) {
          channelConfigurer.accept(b);
        }
        owned = b.build();
        base = owned;
      }

      ScheduledThreadPoolExecutor scheduler =
          new ScheduledThreadPoolExecutor(1, daemonThreads("permio-retry"));
      scheduler.setRemoveOnCancelPolicy(true);
      ExecutorService ownedExecutor = null;
      Executor exec = executor;
      if (exec == null) {
        // Bounded: at most DEFAULT_ASYNC_THREADS blocking calls at once, the rest queue. Idle
        // threads exit after a minute.
        ThreadPoolExecutor pool =
            new ThreadPoolExecutor(
                DEFAULT_ASYNC_THREADS,
                DEFAULT_ASYNC_THREADS,
                60,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                daemonThreads("permio-async"));
        pool.allowCoreThreadTimeOut(true);
        ownedExecutor = pool;
        exec = pool;
      }
      ResilienceInterceptor interceptor =
          new ResilienceInterceptor(key, timeout, retryPolicy, scheduler);
      Channel intercepted = ClientInterceptors.intercept(base, interceptor);
      Core core =
          new Core(
              intercepted,
              owned,
              scheduler,
              interceptor,
              exec,
              ownedExecutor,
              kind,
              url,
              listTimeout == null ? attemptNanos(timeout) : attemptNanos(listTimeout));
      return new Permio(core, project, null, true);
    }
  }

  private static long attemptNanos(Duration timeout) {
    return timeout.isZero() ? 0 : timeout.toNanos();
  }

  /** A parsed base URL. */
  private static final class Target {
    final String url;
    final String authority;
    final ChannelCredentials credentials;

    private Target(String url, String authority, ChannelCredentials credentials) {
      this.url = url;
      this.authority = authority;
      this.credentials = credentials;
    }

    static Target parse(String baseUrl) {
      if (baseUrl == null || baseUrl.trim().isEmpty()) {
        throw new PermioConfigException("permio: baseUrl must not be empty");
      }
      String trimmed = baseUrl.trim().replaceAll("/+$", "");
      URI uri;
      try {
        uri = new URI(trimmed);
      } catch (URISyntaxException e) {
        throw invalid(baseUrl);
      }
      String scheme =
          uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
      boolean tls;
      if (scheme.equals("https")) {
        tls = true;
      } else if (scheme.equals("http")) {
        tls = false;
      } else {
        throw invalid(baseUrl);
      }
      String host = uri.getHost();
      if (host == null
          || (uri.getPath() != null && !uri.getPath().isEmpty())
          || uri.getQuery() != null
          || uri.getFragment() != null
          || uri.getUserInfo() != null) {
        throw invalid(baseUrl);
      }
      int port = uri.getPort() != -1 ? uri.getPort() : (tls ? 443 : 80);
      String hostPart = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
      return new Target(
          trimmed,
          hostPart + ":" + port,
          tls ? TlsChannelCredentials.create() : InsecureChannelCredentials.create());
    }

    private static PermioConfigException invalid(String baseUrl) {
      return new PermioConfigException(
          "permio: baseUrl must be an http(s) URL without a path, e.g. "
              + DEFAULT_BASE_URL
              + ", got \""
              + baseUrl
              + "\"");
    }
  }
}
