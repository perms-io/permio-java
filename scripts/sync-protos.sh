#!/usr/bin/env bash
# Refreshes the vendored public protos in src/main/proto from a perms-io/proto
# checkout, then records the release they came from in .proto-version (the same
# format permio-js uses).
#
#   git clone https://github.com/perms-io/proto /tmp/proto && git -C /tmp/proto checkout v0.0.18
#   scripts/sync-protos.sh /tmp/proto v0.0.18
#
# CI (.github/workflows/generated.yml) checks out the commit pinned in
# .proto-version, runs this script and fails if src/main/proto changes.
#
# Only the public API is copied (permissions, organisation, pagination); the
# internal.proto files are never vendored. The upstream protos have no Java
# options, so this inserts java_package / java_multiple_files /
# java_outer_classname after each file's go_package line. Their imports
# (google/api/*.proto, validate/validate.proto) are not vendored: they are read
# from the proto-google-common-protos and pgv-java-stub jars on the classpath,
# which also provide the matching Java classes.
set -euo pipefail

SRC="${1:?path to a perms-io/proto checkout}"
VERSION="${2:?proto release, e.g. v0.0.18}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/src/main/proto"

copy() {
  local rel="$1" pkg="$2" outer="$3"
  mkdir -p "$DEST/$(dirname "$rel")"
  awk -v pkg="$pkg" -v outer="$outer" '
    { print }
    /^option go_package/ {
      print "option java_package = \"" pkg "\";"
      print "option java_multiple_files = true;"
      print "option java_outer_classname = \"" outer "\";"
      done = 1
    }
    END { if (!done) { print "no go_package line to anchor the Java options" > "/dev/stderr"; exit 1 } }
  ' "$SRC/$rel" > "$DEST/$rel"
}

rm -rf "$DEST/proto"
copy proto/permissions/v1/permissions.proto io.perms.proto.permissions.v1 PermissionsProto
copy proto/organisation/v1/organisation.proto io.perms.proto.organisation.v1 OrganisationProto
copy proto/pagination/v1/pagination.proto io.perms.proto.pagination.v1 PaginationProto
COMMIT="$(git -C "$SRC" rev-parse HEAD)"
cat > "$ROOT/.proto-version" <<PIN
# The perms-io/proto release that src/main/proto is copied from.
# Written by scripts/sync-protos.sh; checked by CI (generated.yml).
version=${VERSION}
commit=${COMMIT}
PIN
echo "Vendored perms-io/proto ${VERSION} into src/main/proto"
