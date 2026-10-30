#!/usr/bin/env bash
# CI only: fetch ROM-repo assets (ROM, mgba-harness, reference data; optionally extracted/ + decomp symbols) into $FE8_ASSETS.
# The ROM repo is private and GITHUB_TOKEN cannot read other repos, so this needs ONE of these
# repository secrets (set by the owner in Settings -> Secrets and variables -> Actions):
#   ROM_REPO_TOKEN       fine-grained PAT, repository access = Fire-Emblem-8-rom only, Contents: Read
#   ROM_REPO_DEPLOY_KEY  private half of a read-only deploy key added to Fire-Emblem-8-rom
# Without either, differential tests are SKIPPED (they never count as passed) and CI says so loudly.
set -euo pipefail
ROM_REPO="${ROM_REPO:-countDookim/Fire-Emblem-8-rom}"
mkdir -p "$FE8_ASSETS"
out() { echo "available=$1" >> "${GITHUB_OUTPUT:-/dev/null}"; }
if [ -n "${ROM_REPO_TOKEN:-}" ]; then
  GH_TOKEN="$ROM_REPO_TOKEN" gh release download -R "$ROM_REPO" -D "$FE8_ASSETS" --clobber \
    -p baserom.gba -p mgba-harness -p reference.tar.xz -p MANIFEST.sha1
  # Optional: committed extraction + decomp symbols, for tools/ci_check_extraction.sh.
  GH_TOKEN="$ROM_REPO_TOKEN" gh release download -R "$ROM_REPO" -D "$FE8_ASSETS" --clobber \
    -p extracted.tar.xz -p fireemblem8.map -p fireemblem8.nm.txt || echo "::warning::release lacks extracted.tar.xz / decomp symbols"
  if [ -f "$FE8_ASSETS/extracted.tar.xz" ]; then
    mkdir -p "$FE8_ASSETS/extracted" && tar -C "$FE8_ASSETS/extracted" -xJf "$FE8_ASSETS/extracted.tar.xz"
  fi
elif [ -n "${ROM_REPO_DEPLOY_KEY:-}" ]; then
  eval "$(ssh-agent -s)" >/dev/null
  ssh-add - <<< "$ROM_REPO_DEPLOY_KEY" 2>/dev/null
  mkdir -p ~/.ssh && ssh-keyscan -t ed25519 github.com >> ~/.ssh/known_hosts 2>/dev/null
  git clone -q --depth 1 "git@github.com:$ROM_REPO.git" /tmp/romrepo
  cp /tmp/romrepo/rom/baserom.gba /tmp/romrepo/bin/mgba-harness /tmp/romrepo/dist/reference.tar.xz /tmp/romrepo/dist/MANIFEST.sha1 "$FE8_ASSETS/"
  cp /tmp/romrepo/decomp/fireemblem8.map /tmp/romrepo/decomp/fireemblem8.nm.txt "$FE8_ASSETS/" 2>/dev/null || true
  if [ -d /tmp/romrepo/extracted ]; then cp -r /tmp/romrepo/extracted "$FE8_ASSETS/extracted"; fi
else
  echo "::warning title=No ROM assets::Neither ROM_REPO_TOKEN nor ROM_REPO_DEPLOY_KEY is set; differential tests will be SKIPPED and progress cannot advance. See HANDOFF.md (NEEDS USER)."
  out false
  exit 0
fi
(cd "$FE8_ASSETS" && sha1sum -c --quiet MANIFEST.sha1)
mkdir -p "$FE8_ASSETS/reference" && tar -C "$FE8_ASSETS/reference" -xJf "$FE8_ASSETS/reference.tar.xz"
chmod +x "$FE8_ASSETS/mgba-harness"
out true
echo "ROM assets ready in $FE8_ASSETS"
