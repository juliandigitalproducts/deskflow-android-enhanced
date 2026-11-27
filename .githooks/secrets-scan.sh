#!/usr/bin/env bash
# github-publish/secrets-scan.sh – gemeinsame Secret-Prüfung (gitleaks + trufflehog) für publish.sh, Pre-Push-Hook und CI.
#
#   secrets-scan.sh baum <dir>                        Dateibaum prüfen (gitleaks dir + trufflehog filesystem, ohne .git/)
#   secrets-scan.sh historie <bereich|--all> [repo]   Commits prüfen (gitleaks git + trufflehog git), repo Standard "."
#       <bereich> = git-rev-list-Argumente als EIN Argument, durch Leerzeichen getrennt, z. B. "v1.2.3" (alles, was vom
#       Tag erreichbar ist), "<alt>..<neu>" oder "<neu> --not --remotes=origin"; --all = alle Refs. Bare-Repos gehen auch.
#   secrets-scan.sh canary                            nur Selbsttest
#
# Canary (vor JEDEM Scan, in allen Modi): zur Laufzeit erzeugte Fake-Werte in einem Temp-Ordner – Telegram-Bot-URL mit
#   34 und mit 35 Zeichen nach ':', Private-Key-Header, password=<16 Zufallszeichen> in einer .properties (alle mit
#   gitleaks und derselben Config) sowie ein GitHub-Token-Muster (trufflehog). Wird einer nicht gefunden → Exit 1.
# Config: SECRETS_SCAN_CONFIG (Datei) > .gitleaks.toml des Ordners/Repos (enthält die Basis, gitleaks-config-sync.sh)
#   > gitleaks-basis.toml neben diesem Skript. Fehlt alles → Exit 1.
# Ausnahmen: .gitleaksignore des Ordners/Repos + SECRETS_SCAN_IGNORE (Datei). Fingerprints wie bei gitleaks
#   (<commit>:<datei>:<regel>:<zeile>, im Baum <datei>:<regel>:<zeile>); trufflehog-Funde mit Regel
#   "trufflehog-<Detektor>". Inline-Ausnahmen (gitleaks:allow, trufflehog:ignore) gelten hier bewusst NICHT.
# Ausgabe: nur Regel/Detektor, Datei, Zeile, Commit (kurz) – nie Werte. SECRETS_SCAN_FINGERPRINTS=<datei>: Fingerprints
#   der Funde zusätzlich dorthin (zum Prüfen von Altlasten – nie ungeprüft nach .gitleaksignore übernehmen).
# Werkzeuge: GITLEAKS / TRUFFLEHOG aus der Umgebung, sonst ${GH_PUBLISH_STATE:-$HOME/github-publish}/bin/<tool>,
#   sonst PATH. Fehlt eins → Exit 1; SECRETS_SCAN_OHNE_TRUFFLEHOG=1 erlaubt nur-gitleaks (mit Warnung).
#   trufflehog läuft immer mit --no-verification: Funde werden nie an Dritte geschickt.
# Exit: 0 = sauber, 2 = Fund(e), 1 = Werkzeug-/Canary-/Aufruffehler.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

# gp_log/gp_die wie in lib.sh – eigene Kopie, damit das Skript auch allein in .githooks/ läuft.
gp_log() { printf '%s\n' "$*" >&2; }
gp_die() {
  local code=1
  case "${1:-}" in [0-9]|[0-9][0-9]) code=$1; shift ;; esac
  printf '::error::%s\n' "$*" >&2
  exit "$code"
}
AUFRUF="Aufruf: secrets-scan.sh baum <dir> | historie <bereich|--all> [repo-dir] | canary"

# Umgebung aus git-Hooks nicht übernehmen: maßgeblich ist allein das übergebene Repo.
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_OBJECT_DIRECTORY GIT_ALTERNATE_OBJECT_DIRECTORIES GIT_COMMON_DIR \
  GIT_PREFIX GIT_NAMESPACE GIT_QUARANTINE_PATH

MODUS=${1:-}
case "$MODUS" in
  baum)
    [ $# -eq 2 ] || gp_die "$AUFRUF"
    [ -d "$2" ] || gp_die "Ordner fehlt: $2"
    ZIEL=$(cd "$2" && pwd) ;;
  historie)
    { [ $# -ge 2 ] && [ $# -le 3 ] && [ -n "$2" ]; } || gp_die "$AUFRUF"
    BEREICH=$2
    REPO_ARG=${3:-.}
    [ -d "$REPO_ARG" ] || gp_die "Repo-Ordner fehlt: $REPO_ARG" ;;
  canary) [ $# -eq 1 ] || gp_die "$AUFRUF" ;;
  *) gp_die "$AUFRUF" ;;
esac

W=$(mktemp -d "${TMPDIR:-/tmp}/secscan.XXXXXX")
chmod 700 "$W"
trap 'rm -rf "$W"' EXIT
mkdir "$W/leer" "$W/repo"
: > "$W/leer.ignore"

# ---- Werkzeuge ------------------------------------------------------------------------------------------------------
BIN="${GH_PUBLISH_STATE:-$HOME/github-publish}/bin"
werkzeug() { # name wert-aus-umgebung → Pfad oder leer
  local n=$1 v=$2
  if [ -n "$v" ]; then command -v "$v" 2>/dev/null || true
  elif [ -x "$BIN/$n" ]; then printf '%s\n' "$BIN/$n"
  else command -v "$n" 2>/dev/null || true
  fi
}
GL=$(werkzeug gitleaks "${GITLEAKS:-}")
[ -n "$GL" ] || gp_die "gitleaks nicht gefunden (GITLEAKS, $BIN/gitleaks oder PATH) – gitleaks-holen.sh ausführen bzw. brew install gitleaks."
TH=$(werkzeug trufflehog "${TRUFFLEHOG:-}")
if [ -z "$TH" ]; then
  [ "${SECRETS_SCAN_OHNE_TRUFFLEHOG:-}" = 1 ] \
    || gp_die "trufflehog nicht gefunden (TRUFFLEHOG, $BIN/trufflehog oder PATH) – trufflehog-holen.sh ausführen bzw. brew install trufflehog."
  gp_log "WARNUNG: trufflehog fehlt – SECRETS_SCAN_OHNE_TRUFFLEHOG=1, es prüft NUR gitleaks."
fi

# ---- Repo-Dateien (Config, .gitleaksignore) -------------------------------------------------------------------------
GITDIR=""
QUELLE=""
case "$MODUS" in
  baum) REPO_DATEIEN=$ZIEL ;;
  historie)
    if [ "$(git -C "$REPO_ARG" rev-parse --is-bare-repository 2>/dev/null || true)" = true ]; then
      GITDIR=$(git -C "$REPO_ARG" rev-parse --absolute-git-dir)
      QUELLE=$GITDIR
      REPO_DATEIEN="$W/repo" # Bare: Dateien aus HEAD
      git --git-dir="$GITDIR" show HEAD:.gitleaks.toml > "$W/repo/.gitleaks.toml" 2>/dev/null || rm -f "$W/repo/.gitleaks.toml"
      git --git-dir="$GITDIR" show HEAD:.gitleaksignore > "$W/repo/.gitleaksignore" 2>/dev/null || rm -f "$W/repo/.gitleaksignore"
    else
      QUELLE=$(git -C "$REPO_ARG" rev-parse --show-toplevel 2>/dev/null) || gp_die "Kein Git-Repo: $REPO_ARG"
      GITDIR=$(git -C "$QUELLE" rev-parse --absolute-git-dir)
      REPO_DATEIEN=$QUELLE
    fi ;;
  canary) REPO_DATEIEN=$(git rev-parse --show-toplevel 2>/dev/null || pwd) ;;
esac
if [ -n "${SECRETS_SCAN_CONFIG:-}" ]; then
  [ -f "$SECRETS_SCAN_CONFIG" ] || gp_die "SECRETS_SCAN_CONFIG zeigt auf keine Datei: $SECRETS_SCAN_CONFIG"
  CFG=$SECRETS_SCAN_CONFIG
elif [ -f "$REPO_DATEIEN/.gitleaks.toml" ]; then CFG="$REPO_DATEIEN/.gitleaks.toml"
elif [ -f "$HERE/gitleaks-basis.toml" ]; then CFG="$HERE/gitleaks-basis.toml"
else gp_die "Keine gitleaks-Config: weder .gitleaks.toml im Repo noch gitleaks-basis.toml neben secrets-scan.sh (gitleaks-config-sync.sh <repo>)."
fi
: > "$W/gitleaksignore"
if [ -n "${SECRETS_SCAN_IGNORE:-}" ]; then
  [ -f "$SECRETS_SCAN_IGNORE" ] || gp_die "SECRETS_SCAN_IGNORE zeigt auf keine Datei: $SECRETS_SCAN_IGNORE"
  cat "$SECRETS_SCAN_IGNORE" >> "$W/gitleaksignore"
  printf '\n' >> "$W/gitleaksignore"
fi
[ ! -f "$REPO_DATEIEN/.gitleaksignore" ] || cat "$REPO_DATEIEN/.gitleaksignore" >> "$W/gitleaksignore"

# ---- Läufe ----------------------------------------------------------------------------------------------------------
# gl_lauf Bericht Arbeitsordner Ignore-Datei gitleaks-Argumente… → Exit von gitleaks (0 sauber, 2 Fund)
gl_lauf() {
  local rep=$1 cwd=$2 ign=$3 rc=0
  shift 3
  ( cd "$cwd" && "$GL" "$@" --config "$CFG" --gitleaks-ignore-path "$ign" --ignore-gitleaks-allow --no-banner --redact \
      --exit-code 2 --log-level error --report-format json --report-path "$rep" ) >&2 < /dev/null || rc=$?
  case "$rc" in 0|2) return "$rc" ;; esac
  gp_die "gitleaks ließ sich nicht ausführen (Exit $rc, Config $CFG)."
}
# th_lauf Ausgabe Arbeitsordner trufflehog-Argumente… → JSON-Zeilen in Ausgabe
printf '%s\n' '(^|/)\.git/' > "$W/th-exclude"
th_lauf() {
  local out=$1 cwd=$2 rc=0
  shift 2
  ( cd "$cwd" && "$TH" "$@" --no-verification --no-update --json --no-ignore-tag --fail-on-scan-errors --no-color ) \
    > "$out" 2> "$W/th.err" < /dev/null || rc=$?
  if [ "$rc" -ne 0 ]; then
    tail -n 3 "$W/th.err" | cut -c1-300 | sed 's/^/  trufflehog: /' >&2
    gp_die "trufflehog ließ sich nicht ausführen (Exit $rc)."
  fi
}
zufall() { python3 -I -c 'import secrets,sys; print("".join(secrets.choice(sys.argv[1]) for _ in range(int(sys.argv[2]))))' "$1" "$2"; }

# ---- Canary ---------------------------------------------------------------------------------------------------------
canary() {
  local c="$W/canary" zeichen=ABCDEFGHIJKLMNOPQRSTUVWYZabcdefghijklmnopqrstuvwyz0123456789 ziffern
  mkdir "$c"
  ziffern="$(zufall 123456789 1)$(zufall 0123456789 8)"
  printf 'https://api.telegram.org/bot%s:%s/getMe\n' "$ziffern" "$(zufall "$zeichen" 34)" > "$c/telegram34.txt"
  printf 'https://api.telegram.org/bot%s:%s/getMe\n' "$ziffern" "$(zufall "$zeichen" 35)" > "$c/telegram35.txt"
  printf '%s%s %s %s%s\n' '-----' BEGIN RSA 'PRIVATE KEY' '-----' > "$c/schluessel.txt"
  printf 'db.password=%s\n' "$(zufall "$zeichen" 16)" > "$c/zugang.properties"
  printf 'token = "%s_%s"\n' ghp "$(zufall "$zeichen" 36)" > "$c/github.txt"
  gl_lauf "$W/canary-gl.json" "$c" "$W/leer.ignore" dir . || true
  [ -z "$TH" ] || th_lauf "$W/canary-th.jsonl" "$c" filesystem .
  local fehlt
  fehlt=$(python3 -I - "$W/canary-gl.json" "$W/canary-th.jsonl" "${TH:+ja}" <<'EOF'
import json, os, sys
gl = json.load(open(sys.argv[1])) if os.path.exists(sys.argv[1]) and os.path.getsize(sys.argv[1]) else []
gefunden = {os.path.basename(f.get("File", "")) for f in gl}
fehlt = ["gitleaks:" + n for n in ("telegram34", "telegram35", "schluessel", "zugang")
         if not any(g.startswith(n + ".") for g in gefunden)]
if sys.argv[3]:
    th = set()
    if os.path.exists(sys.argv[2]):
        for l in open(sys.argv[2]):
            try:
                th.add(os.path.basename(json.loads(l)["SourceMetadata"]["Data"]["Filesystem"]["file"]))
            except Exception:
                pass
    if "github.txt" not in th:
        fehlt.append("trufflehog:github")
print(" ".join(fehlt))
EOF
)
  rm -rf "$c"
  [ -z "$fehlt" ] || gp_die "Canary nicht erkannt – Regeln greifen nicht, Abbruch (fehlt: $fehlt; Config $CFG)."
  gp_log "Canary ok (Config $(basename "$CFG")${TH:+, gitleaks + trufflehog})."
}

# ---- Auswertung -----------------------------------------------------------------------------------------------------
# auswerten Art(baum|historie) gitleaks.json trufflehog.jsonl revs-Datei → Funde auf stderr, Exit 0/2
auswerten() {
  python3 -I - "$1" "$2" "$3" "$4" "$W/gitleaksignore" "${SECRETS_SCAN_FINGERPRINTS:-}" <<'EOF'
import json, os, sys
art, glp, thp, revp, ignp, fpp = sys.argv[1:7]
def norm(p):
    p = p or ""
    while p.startswith("./"):
        p = p[2:]
    return p
ign = {l.strip() for l in open(ignp, encoding="utf-8", errors="replace") if l.strip() and not l.lstrip().startswith("#")}
revs = {l.strip() for l in open(revp)} if revp and os.path.exists(revp) else None
funde = []  # (Werkzeug, Regel, Datei, Zeile, Commit, Fingerprint)
gl = json.load(open(glp)) if os.path.exists(glp) and os.path.getsize(glp) else []
for f in gl:
    c = f.get("Commit") or ""
    d = norm(f.get("File"))
    fp = f.get("Fingerprint") or ":".join(x for x in (c, d, f.get("RuleID", ""), str(f.get("StartLine", ""))) if x)
    funde.append(("gitleaks", f.get("RuleID", "?"), d, f.get("StartLine", "?"), c, fp))
if os.path.exists(thp):
    for l in open(thp, encoding="utf-8", errors="replace"):
        try:
            j = json.loads(l)
        except ValueError:
            continue
        if "DetectorName" not in j:
            continue
        data = (j.get("SourceMetadata") or {}).get("Data") or {}
        m = data.get("Git") or data.get("Filesystem") or {}
        c = m.get("commit") or ""
        if revs is not None and c and c not in revs:
            continue  # außerhalb des geprüften Bereichs
        d, z, regel = norm(m.get("file")), m.get("line", "?"), "trufflehog-" + str(j.get("DetectorName"))
        global_fp = f"{d}:{regel}:{z}"
        fp = f"{c}:{global_fp}" if c else global_fp
        if fp in ign or global_fp in ign:
            continue
        funde.append(("trufflehog", str(j.get("DetectorName")), d, z, c, fp))
gesehen, aus = set(), []
for f in funde:
    if f[5] not in gesehen:
        gesehen.add(f[5]); aus.append(f)
for w, r, d, z, c, _ in aus:
    print(f"  {w:<10} {r:<28} {d}:{z}" + (f"  (Commit {c[:12]})" if c else ""), file=sys.stderr)
if fpp and aus:
    with open(fpp, "a", encoding="utf-8") as o:
        for f in aus:
            o.write(f[5] + "\n")
if aus:
    n = {w: sum(1 for f in aus if f[0] == w) for w in ("gitleaks", "trufflehog")}
    print(f"{len(aus)} möglicher Fund/mögliche Funde (gitleaks {n['gitleaks']}, trufflehog {n['trufflehog']}) – Werte werden nie angezeigt.", file=sys.stderr)
    sys.exit(2)
EOF
}

# ---- Ablauf ---------------------------------------------------------------------------------------------------------
canary
case "$MODUS" in
  canary) exit 0 ;;
  baum)
    gp_log "Secret-Prüfung Baum: $ZIEL"
    gl_lauf "$W/gl.json" "$ZIEL" "$W/gitleaksignore" dir . || true
    [ -z "$TH" ] || th_lauf "$W/th.jsonl" "$ZIEL" filesystem . --exclude-paths "$W/th-exclude"
    rc=0; auswerten baum "$W/gl.json" "$W/th.jsonl" "" || rc=$?
    ;;
  historie)
    if [ "$BEREICH" = --all ]; then ARGS=(--all); else read -r -a ARGS <<< "$BEREICH"; fi
    git --git-dir="$GITDIR" rev-list "${ARGS[@]}" > "$W/revs" 2> "$W/revs.err" \
      || { sed 's/^/  git: /' "$W/revs.err" >&2; gp_die "Bereich '$BEREICH' ist ungültig."; }
    n=$(wc -l < "$W/revs" | tr -d ' ')
    if [ "$n" = 0 ]; then gp_log "Secret-Prüfung Historie: keine Commits im Bereich '$BEREICH' – nichts zu prüfen."; exit 0; fi
    gp_log "Secret-Prüfung Historie: $n Commit(s), Bereich '$BEREICH'."
    gl_lauf "$W/gl.json" "$W/leer" "$W/gitleaksignore" git --log-opts="$BEREICH" "$QUELLE" || true
    if [ -n "$TH" ]; then
      # Hilfsrepo: leeres Bare-Repo mit Zugriff auf die Objekte (alternates) und je einem Ref pro Spitze des Bereichs →
      # trufflehog prüft genau diese Spitzen samt Vorgängern; Funde außerhalb des Bereichs filtert die Auswertung weg.
      git init -q --bare "$W/hilf.git"
      git --git-dir="$GITDIR" rev-parse --path-format=absolute --git-path objects > "$W/hilf.git/objects/info/alternates"
      sh_datei=$(git --git-dir="$GITDIR" rev-parse --path-format=absolute --git-path shallow)
      [ ! -f "$sh_datei" ] || cp "$sh_datei" "$W/hilf.git/shallow"
      git --git-dir="$GITDIR" rev-list --parents "${ARGS[@]}" | python3 -I -c '
import sys
zeilen = [l.split() for l in sys.stdin if l.strip()]
eltern = {p for z in zeilen for p in z[1:]}
for i, c in enumerate(z[0] for z in zeilen if z[0] not in eltern):
    print(f"create refs/heads/scan-{i} {c}")' | git --git-dir="$W/hilf.git" update-ref --stdin
      th_lauf "$W/th.jsonl" "$W/leer" git "file://$W/hilf.git" --bare
    fi
    rc=0; auswerten historie "$W/gl.json" "$W/th.jsonl" "$W/revs" || rc=$?
    ;;
esac
case "$rc" in
  0) gp_log "Secret-Prüfung: keine Funde." ;;
  2) ;;
  *) gp_die "Auswertung fehlgeschlagen (Exit $rc)." ;;
esac
exit "$rc"
