package io.perms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.perms.proto.permissions.v1.CreatePermissionRequest;
import io.perms.proto.permissions.v1.DeletePermissionRequest;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Integration test against a real deployment. Skipped unless both PERMIO_API_KEY and
 * PERMIO_BASE_URL are set, and it refuses to run against production because it writes. The key
 * needs full access to its project (PERMIO_PROJECT, default: the key's project from whoAmI):
 *
 * <pre>
 *   PERMIO_API_KEY=perms_... PERMIO_BASE_URL=https://... ./gradlew integrationTest
 * </pre>
 *
 * <p>Everything it creates uses fixed IDs prefixed "sdk-it-java" and is removed before and after
 * the run, so an interrupted run is cleaned up by the next one. Runs sharing a project must not
 * overlap (CI serialises them).
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LiveIntegrationTest {

  private static final String ALICE = "sdk-it-java-alice";
  private static final String BOB = "sdk-it-java-bob";
  private static final String GROUP = "sdk-it-java-editors";
  private static final String READ = "sdk-it-java.read";
  private static final String WRITE = "sdk-it-java.write";
  private static final String ROOT = "/sdk-it-java";
  private static final String DOCS = ROOT + "/docs";
  private static final String DOC1 = DOCS + "/1";
  private static final String OTHER = ROOT + "/other";

  private final String apiKey = System.getenv("PERMIO_API_KEY");
  private final String baseUrl = System.getenv("PERMIO_BASE_URL");
  private Permio permio;
  private String project;

  /**
   * The test writes, so it runs only against an allowlist of non-production hosts: loopback, the
   * reserved {@code .localhost} / {@code .test} names, and {@code *.perms.io} hosts other than
   * production ({@code api.perms.io}). The URL is parsed, so tricks like {@code
   * https://api.perms.io#x.perms.io} or {@code https://x.perms.io@api.perms.io} do not pass.
   */
  static boolean isAllowedTarget(String baseUrl) {
    URI uri;
    try {
      uri = new URI(baseUrl.trim());
    } catch (URISyntaxException e) {
      return false;
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("https") && !scheme.equals("http")) {
      return false;
    }
    if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
      return false;
    }
    String path = uri.getRawPath();
    if (path != null && !path.isEmpty() && !path.equals("/")) {
      return false;
    }
    String host = uri.getHost();
    if (host == null) {
      return false;
    }
    host = host.toLowerCase(Locale.ROOT);
    if (host.endsWith(".")) {
      host = host.substring(0, host.length() - 1);
    }
    return host.equals("localhost")
        || host.equals("127.0.0.1")
        || host.equals("[::1]")
        || host.equals("::1")
        || (host.endsWith(".localhost") && host.length() > ".localhost".length())
        || (host.endsWith(".test") && host.length() > ".test".length())
        || (host.endsWith(".perms.io")
            && host.length() > ".perms.io".length()
            && !host.equals("api.perms.io"));
  }

  @BeforeAll
  void setUp() {
    assumeTrue(apiKey != null && !apiKey.isEmpty(), "PERMIO_API_KEY is not set");
    assumeTrue(baseUrl != null && !baseUrl.isEmpty(), "PERMIO_BASE_URL is not set");
    assumeTrue(
        isAllowedTarget(baseUrl),
        "refusing to run the integration tests against "
            + baseUrl
            + ": only localhost, *.localhost / *.test and non-production *.perms.io hosts are"
            + " allowed");
    try (Permio probe = Permio.builder().apiKey(apiKey).baseUrl(baseUrl).build()) {
      String fromEnv = System.getenv("PERMIO_PROJECT");
      project =
          fromEnv != null && !fromEnv.isEmpty()
              ? fromEnv
              : probe.whoAmI().project().orElse("default");
    }
    permio = Permio.builder().apiKey(apiKey).baseUrl(baseUrl).project(project).build();
    cleanUp();
    for (String name : List.of(READ, WRITE)) {
      permio
          .raw()
          .permissions()
          .createPermission(
              CreatePermissionRequest.newBuilder()
                  .setProjectName(project)
                  .setName(name)
                  .setDescription("permio-java integration test")
                  .build());
    }
  }

  @AfterAll
  void tearDown() {
    if (permio != null) {
      try {
        cleanUp();
      } finally {
        permio.close();
      }
    }
  }

  // Deleting the permissions also removes every grant that uses them.
  private void cleanUp() {
    permio.groups().delete(GROUP);
    for (String name : List.of(READ, WRITE)) {
      try {
        permio
            .raw()
            .permissions()
            .deletePermission(
                DeletePermissionRequest.newBuilder().setProjectName(project).setName(name).build());
      } catch (RuntimeException e) {
        if (!(PermioException.from(e) instanceof PermioNotFoundException)) {
          throw e;
        }
      }
    }
  }

  @Test
  @Order(1)
  void whoAmIDescribesTheApiKey() {
    WhoAmI me = permio.whoAmI();
    assertThat(me.kind()).isEqualTo(WhoAmI.PrincipalKind.API_KEY);
    assertThat(me.project()).contains(project);
    assertThat(me.principalId()).isNotEmpty();
    assertThat(me.organisationId()).isNotEmpty();
    assertThat(me.apiKeyAccess()).contains(WhoAmI.ApiKeyAccess.FULL);
  }

  @Test
  @Order(2)
  void grantThenCheckIsAllowedBelowTheGrantedUri() {
    assertThat(permio.check(ALICE, READ, DOC1)).isFalse();
    permio.grant(ALICE, READ, DOCS);
    assertThat(permio.check(ALICE, READ, DOC1)).isTrue();
    assertThat(permio.check(ALICE, WRITE, DOC1)).isFalse();
  }

  @Test
  @Order(3)
  void batchCheckAnswersEachResource() {
    Map<String, Boolean> result = permio.batchCheck(ALICE, READ, List.of(DOC1, OTHER, DOCS));
    assertThat(List.copyOf(result.entrySet()))
        .containsExactly(Map.entry(DOC1, true), Map.entry(OTHER, false), Map.entry(DOCS, true));
  }

  @Test
  @Order(4)
  void listAccessReturnsTheGrantedSubtree() {
    AccessScopes access = permio.listAccess(ALICE, READ, ROOT);
    assertThat(access.coversAll()).isFalse();
    assertThat(access.toPrefixes()).containsExactly(DOCS);
    assertThat(access.allows(DOC1)).isTrue();
    assertThat(access.allows(OTHER)).isFalse();
  }

  @Test
  @Order(5)
  void revokeThenCheckIsDenied() {
    permio.revoke(ALICE, READ, DOCS);
    assertThat(permio.check(ALICE, READ, DOC1)).isFalse();
    // Revoking what is no longer held is harmless.
    permio.revoke(ALICE, READ, DOCS);
  }

  @Test
  @Order(6)
  void aGroupGrantAppliesToItsMembersUntilTheGroupIsDeleted() {
    permio.groups().create(GROUP, "permio-java integration test");
    AddMembersResult added = permio.groups().addMembers(GROUP, List.of(BOB));
    assertThat(added.added()).containsExactly(BOB);
    assertThat(added.existing()).isEmpty();
    permio.grant(Grant.to(Groups.principal(GROUP)).on(DOCS).permissions(WRITE));
    assertThat(permio.check(BOB, WRITE, DOC1)).isTrue();
    assertThat(permio.check(ALICE, WRITE, DOC1)).isFalse();

    permio.groups().delete(GROUP);
    assertThat(permio.check(BOB, WRITE, DOC1)).isFalse();
  }

  @Test
  @Order(7)
  void rejectsAnUnknownKeyWithAuthException() {
    try (Permio bad =
        Permio.builder()
            .apiKey("perms_0123456789ABCDEFGHIJKLMNOPQRSTUV1ggZdL")
            .baseUrl(baseUrl)
            .project(project)
            .build()) {
      assertThatThrownBy(() -> bad.check(ALICE, READ, DOC1))
          .isInstanceOf(PermioAuthException.class);
      assertThatThrownBy(bad::whoAmI).isInstanceOf(PermioAuthException.class);
    }
  }
}
