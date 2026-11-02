#!/usr/bin/env bash
# Scans each module's resolved dependencies (test scope included) for known vulnerabilities
# with OSV-Scanner, and exits non-zero if it finds any. Extra arguments go to Maven, for
# example -Dspring-boot.version=4.1.2 to scan the dependencies of another Spring Boot line.
set -euo pipefail

version="${OSV_SCANNER_VERSION:?set OSV_SCANNER_VERSION, for example v2.7.0}"
base="https://github.com/google/osv-scanner/releases/download/${version}"
bin_dir="${RUNNER_TEMP:-$(mktemp -d)}"

mvn --batch-mode --no-transfer-progress cyclonedx:makeBom "$@"

(
  cd "$bin_dir"
  curl -sSfL -o osv-scanner_linux_amd64 "$base/osv-scanner_linux_amd64"
  curl -sSfL -o osv-scanner_SHA256SUMS "$base/osv-scanner_SHA256SUMS"
  grep ' osv-scanner_linux_amd64$' osv-scanner_SHA256SUMS | sha256sum --check --strict
  chmod +x osv-scanner_linux_amd64
)

args=()
for bom in */target/bom.cdx.json; do args+=(-L "$bom"); done
"$bin_dir/osv-scanner_linux_amd64" scan source "${args[@]}"
