# Releasing

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
