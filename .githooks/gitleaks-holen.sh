#!/usr/bin/env bash
# github-publish/gitleaks-holen.sh – stellt eine gepinnte gitleaks-Binary für den CI-Job bereit (Runner `pi`).
#
#   gitleaks-holen.sh            → $HOME/github-publish/bin/gitleaks (nur im Runner-Ordner, keine Systeminstallation)
#
# Lädt das offizielle Release-Archiv nur, wenn die Binary fehlt oder die Version nicht stimmt, und prüft es gegen
# die hier fest eingetragene SHA-256 (aus gitleaks_<v>_checksums.txt). Abweichung → Abbruch, nichts wird entpackt.
# Version erhöhen: VERSION + SHA256_* anpassen (Update-Regel: Feature-Versionen frühestens 7 Tage alt).
set -euo pipefail
VERSION=8.30.1
SHA256_linux_arm64=e4a487ee7ccd7d3a7f7ec08657610aa3606637dab924210b3aee62570fb4b080
SHA256_linux_x64=551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb
DEST=${GH_PUBLISH_STATE:-$HOME/github-publish}/bin

if [ -x "$DEST/gitleaks" ] && [ "$("$DEST/gitleaks" version 2>/dev/null)" = "$VERSION" ]; then
  echo "gitleaks $VERSION vorhanden."
  exit 0
fi
case "$(uname -s)-$(uname -m)" in
  Linux-aarch64|Linux-arm64) arch=linux_arm64; sum=$SHA256_linux_arm64 ;;
  Linux-x86_64) arch=linux_x64; sum=$SHA256_linux_x64 ;;
  *) echo "::error::gitleaks-holen.sh: Plattform $(uname -sm) nicht vorgesehen." >&2; exit 1 ;;
esac
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT
f="gitleaks_${VERSION}_${arch}.tar.gz"
curl -fsSL --proto '=https' -o "$W/$f" "https://github.com/gitleaks/gitleaks/releases/download/v${VERSION}/$f"
got=$(sha256sum "$W/$f" | cut -d' ' -f1)
[ "$got" = "$sum" ] || { echo "::error::SHA-256 von $f stimmt nicht ($got) – Abbruch." >&2; exit 1; }
tar -xzf "$W/$f" -C "$W" gitleaks
mkdir -p "$DEST"
install -m 0755 "$W/gitleaks" "$DEST/gitleaks.new"
mv -f "$DEST/gitleaks.new" "$DEST/gitleaks"
echo "gitleaks $VERSION bereitgestellt ($arch, SHA-256 geprüft)."
