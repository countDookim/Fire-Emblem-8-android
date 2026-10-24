#!/usr/bin/env bash
# Session bootstrap for scheduled/interactive cloud sessions. NOT used by CI (CI has its own setup).
#
#   Minimal:    JDK check, kotlinc, ROM-repo assets (ROM, headless mGBA harness, reference data),
#               and a Gradle warm-up for the pure-JVM modules (no Android SDK, ever).
#   Idempotent: every step is skipped when its output already exists and verifies.
#   Fast:       target < 3 min on a fresh session; logs its own duration.
#
# Env:
#   FE8_ASSETS   where assets go (default: <repo>/.assets, gitignored)
#   FE8_TOOLS    where kotlinc goes (default: ~/.local/fe8-tools)
#   ROM_REPO     default countDookim/Fire-Emblem-8-rom
#   SKIP_GRADLE  set to 1 to skip the Gradle warm-up
set -euo pipefail
START=$(date +%s)
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FE8_ASSETS="${FE8_ASSETS:-$ROOT/.assets}"
FE8_TOOLS="${FE8_TOOLS:-$HOME/.local/fe8-tools}"
ROM_REPO="${ROM_REPO:-countDookim/Fire-Emblem-8-rom}"
KOTLIN_VERSION="$(grep -E '^kotlin *=' "$ROOT/gradle/libs.versions.toml" | sed -E 's/.*"(.*)".*/\1/')"
log() { printf '[setup %3ss] %s\n' "$(( $(date +%s) - START ))" "$*"; }

# 1. JDK (17+). Sessions ship with one; we never install a JDK here.
if ! command -v java >/dev/null; then echo "ERROR: no JDK on PATH" >&2; exit 1; fi
JV=$(java -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/{print $2}')
[ "${JV%%.*}" -ge 17 ] || { echo "ERROR: JDK 17+ required, found $JV" >&2; exit 1; }
log "JDK $JV ok"

# 2. kotlinc (for quick scripts / one-off compiles; Gradle brings its own compiler).
KC="$FE8_TOOLS/kotlinc-$KOTLIN_VERSION"
if [ ! -x "$KC/bin/kotlinc" ]; then
  log "installing kotlinc $KOTLIN_VERSION"
  mkdir -p "$FE8_TOOLS"
  curl -fsSL -o "$FE8_TOOLS/kc.zip" "https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN_VERSION/kotlin-compiler-$KOTLIN_VERSION.zip"
  rm -rf "$KC" "$FE8_TOOLS/kotlinc" && (cd "$FE8_TOOLS" && unzip -q kc.zip && mv kotlinc "$KC" && rm kc.zip)
fi
mkdir -p "$HOME/.local/bin" && ln -sfn "$KC/bin/kotlinc" "$HOME/.local/bin/kotlinc"
log "kotlinc $KOTLIN_VERSION ok ($KC/bin/kotlinc)"

# 3. ROM-repo assets: latest release, falling back to a shallow git clone of the ROM repo.
verify_assets() { [ -f "$FE8_ASSETS/MANIFEST.sha1" ] && (cd "$FE8_ASSETS" && sha1sum -c --quiet MANIFEST.sha1) >/dev/null 2>&1; }
if verify_assets && [ -d "$FE8_ASSETS/reference/out" ]; then
  log "assets ok ($FE8_ASSETS)"
else
  mkdir -p "$FE8_ASSETS"
  if gh release download -R "$ROM_REPO" -D "$FE8_ASSETS" --clobber \
       -p baserom.gba -p mgba-harness -p reference.tar.xz -p MANIFEST.sha1 2>/dev/null; then
    log "downloaded latest release of $ROM_REPO"
  else
    log "release download failed; shallow-cloning $ROM_REPO"
    TMP="$(mktemp -d)"
    git clone -q --depth 1 "https://github.com/$ROM_REPO" "$TMP/rom"
    cp "$TMP/rom/rom/baserom.gba" "$TMP/rom/bin/mgba-harness" "$TMP/rom/dist/reference.tar.xz" "$TMP/rom/dist/MANIFEST.sha1" "$FE8_ASSETS/"
    rm -rf "$TMP"
  fi
  verify_assets || { echo "ERROR: asset checksum mismatch in $FE8_ASSETS" >&2; exit 1; }
  chmod +x "$FE8_ASSETS/mgba-harness"
  rm -rf "$FE8_ASSETS/reference" && mkdir -p "$FE8_ASSETS/reference" && tar -C "$FE8_ASSETS/reference" -xJf "$FE8_ASSETS/reference.tar.xz"
  log "assets ready ($FE8_ASSETS)"
fi

# 4. Local retrying Maven mirror (Maven Central answers spurious 404/429 through the session proxy).
MIRROR_PORT="${FE8_MIRROR_PORT:-8765}"
export FE8_MAVEN_MIRROR="http://127.0.0.1:$MIRROR_PORT"
if ! curl -fs -o /dev/null "$FE8_MAVEN_MIRROR/health" 2>/dev/null; then
  nohup python3 "$ROOT/tools/maven_mirror.py" --port "$MIRROR_PORT" --cache "$HOME/.cache/fe8-maven" >/dev/null 2>&1 &
  for _ in $(seq 1 20); do curl -fs -o /dev/null "$FE8_MAVEN_MIRROR/health" && break; sleep 0.5; done
fi
log "maven mirror on $FE8_MAVEN_MIRROR"

# 5. Gradle warm-up: downloads the wrapper distribution + JVM deps and compiles the pure-JVM modules.
#    The :app module is excluded automatically when no Android SDK is present (see settings.gradle.kts).
if [ "${SKIP_GRADLE:-0}" != 1 ]; then
  ok=0
  for attempt in 1 2 3; do  # the mirror converges: each attempt caches what succeeded
    if (cd "$ROOT" && ./gradlew --quiet jvmClasses >/dev/null 2>"$ROOT/.gradle-setup.log"); then ok=1; break; fi
    log "gradle warm-up attempt $attempt failed; retrying (see .gradle-setup.log)"
    (cd "$ROOT" && ./gradlew --quiet --refresh-dependencies help >/dev/null 2>&1) || true
  done
  [ "$ok" = 1 ] || { echo "ERROR: gradle warm-up failed 3 times (.gradle-setup.log)" >&2; exit 1; }
  log "gradle warm (JVM modules compiled)"
fi

cat > "$ROOT/.assets.env" <<EOF
export FE8_ASSETS="$FE8_ASSETS"
export FE8_MAVEN_MIRROR="$FE8_MAVEN_MIRROR"
export PATH="\$HOME/.local/bin:\$PATH"
EOF
log "done in $(( $(date +%s) - START ))s  (source .assets.env to export FE8_ASSETS)"
