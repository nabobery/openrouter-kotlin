#!/usr/bin/env bash
# Install a pinned oasdiff (v1.29.1) into build/tools/oasdiff/ and print its path.
#
# Security: the archive is downloaded to a TEMP FILE and its sha256 is verified against the release
# checksums.txt BEFORE anything is extracted — the download is never piped into tar. checksums.txt itself is
# pinned by sha256 here, so a tampered checksum file is caught too.
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="1.29.1"
BASE="https://github.com/oasdiff/oasdiff/releases/download/v${VERSION}"
# sha256 of the release's checksums.txt (pinned; a tampered checksums.txt is rejected).
CHECKSUMS_SHA256="cae36078bb5a2b3cfbee89ac627d0e45e7ec2ad487130f89d0307c582a11f785"

DEST="build/tools/oasdiff"
BIN="$DEST/oasdiff"
if [ -x "$BIN" ] && "$BIN" --version 2>/dev/null | grep -q "$VERSION"; then
  echo "$BIN"; exit 0
fi

os="$(uname -s)"; arch="$(uname -m)"
case "$os" in
  Darwin) asset="oasdiff_${VERSION}_darwin_all.tar.gz" ;;
  Linux)
    case "$arch" in
      x86_64|amd64) asset="oasdiff_${VERSION}_linux_amd64.tar.gz" ;;
      aarch64|arm64) asset="oasdiff_${VERSION}_linux_arm64.tar.gz" ;;
      *) echo "unsupported linux arch: $arch" >&2; exit 1 ;;
    esac ;;
  *) echo "unsupported OS: $os (install oasdiff manually)" >&2; exit 1 ;;
esac

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 "$BASE/$asset" -o "$tmp/$asset"
curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 "$BASE/checksums.txt" -o "$tmp/checksums.txt"

got_checksums="$(shasum -a 256 "$tmp/checksums.txt" | cut -d' ' -f1)"
[ "$got_checksums" = "$CHECKSUMS_SHA256" ] || {
  echo "checksums.txt sha256 mismatch: expected $CHECKSUMS_SHA256, got $got_checksums" >&2; exit 1; }

# Verify the archive against the (now-trusted) checksums.txt before extracting.
( cd "$tmp" && grep " $asset\$" checksums.txt | shasum -a 256 -c - ) >/dev/null || {
  echo "archive $asset failed checksum verification" >&2; exit 1; }

mkdir -p "$DEST"
tar -xzf "$tmp/$asset" -C "$DEST" oasdiff
chmod +x "$BIN"
echo "$BIN"
