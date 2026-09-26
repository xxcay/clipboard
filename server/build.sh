#!/bin/sh
# Builds clipd for the GL.iNet Beryl AX (GL-MT3000, aarch64) and packs the
# installer bundle: dist/clipd-mt3000.tar.gz
set -e
cd "$(dirname "$0")"
VERSION="${VERSION:-$(git describe --tags --always --dirty 2>/dev/null || echo dev)}"
GOARCH="${GOARCH:-arm64}"

rm -rf dist
mkdir -p dist/clipd-install
CGO_ENABLED=0 GOOS=linux GOARCH="$GOARCH" go build -trimpath \
	-ldflags="-s -w -X main.version=$VERSION" -o dist/clipd-install/clipd .
cp openwrt/clipd.init openwrt/clipd.config openwrt/install.sh openwrt/uninstall.sh dist/clipd-install/
chmod 755 dist/clipd-install/clipd dist/clipd-install/*.sh dist/clipd-install/clipd.init
tar -C dist -czf dist/clipd-mt3000.tar.gz clipd-install
ls -l dist/clipd-install/clipd dist/clipd-mt3000.tar.gz
