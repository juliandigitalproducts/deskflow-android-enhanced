#!/usr/bin/env bash
# github-publish/trufflehog-holen.sh – stellt eine gepinnte trufflehog-Binary bereit (CI-Runner `pi`, Pre-Push auf Linux).
#
#   trufflehog-holen.sh          → ${GH_PUBLISH_STATE:-$HOME/github-publish}/bin/trufflehog (keine Systeminstallation)
#
# Lädt das offizielle Release-Archiv nur, wenn die Binary fehlt oder die Version nicht stimmt, und prüft es gegen
# die hier fest eingetragene SHA-256 (aus trufflehog_<v>_checksums.txt). Abweichung → Abbruch, nichts wird entpackt.
# Version erhöhen: VERSION + SHA256_* anpassen (Update-Regel: Feature-Versionen frühestens 7 Tage alt).
# Auf dem Mac reicht meist das trufflehog aus Homebrew (secrets-scan.sh nimmt sonst das aus PATH).
set -euo pipefail
VERSION=3.100.1
SHA256_linux_arm64=017b4f2124199c164579caff85c07839be1c79f6e5908b0f1dd3bccc775c6452
SHA256_linux_amd64=b2460688116f29ce33f1afe16d14fef3fa3db928e74ba55a6c9fed392c60ad69
SHA256_darwin_arm64=baf7600403c029adcaabbc7efd262c5e26b7c654fb88d7334eb56682aaa2f000
DEST=${GH_PUBLISH_STATE:-$HOME/github-publish}/bin

if [ -x "$DEST/trufflehog" ] && [ "$("$DEST/trufflehog" --version 2>&1)" = "trufflehog $VERSION" ]; then
  echo "trufflehog $VERSION vorhanden."
  exit 0
fi
case "$(uname -s)-$(uname -m)" in
  Linux-aarch64|Linux-arm64) arch=linux_arm64; sum=$SHA256_linux_arm64 ;;
  Linux-x86_64) arch=linux_amd64; sum=$SHA256_linux_amd64 ;;
  Darwin-arm64) arch=darwin_arm64; sum=$SHA256_darwin_arm64 ;;
  *) echo "::error::trufflehog-holen.sh: Plattform $(uname -sm) nicht vorgesehen." >&2; exit 1 ;;
esac
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT
f="trufflehog_${VERSION}_${arch}.tar.gz"
curl -fsSL --proto '=https' -o "$W/$f" "https://github.com/trufflesecurity/trufflehog/releases/download/v${VERSION}/$f"
if command -v sha256sum >/dev/null 2>&1; then got=$(sha256sum "$W/$f" | cut -d' ' -f1); else got=$(shasum -a 256 "$W/$f" | cut -d' ' -f1); fi
[ "$got" = "$sum" ] || { echo "::error::SHA-256 von $f stimmt nicht ($got) – Abbruch." >&2; exit 1; }
tar -xzf "$W/$f" -C "$W" trufflehog
mkdir -p "$DEST"
install -m 0755 "$W/trufflehog" "$DEST/trufflehog.new"
mv -f "$DEST/trufflehog.new" "$DEST/trufflehog"
echo "trufflehog $VERSION bereitgestellt ($arch, SHA-256 geprüft)."
