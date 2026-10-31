#!/usr/bin/env bash
# Publishes the guide in docs/wiki/ to the repository's GitHub wiki (github.com/DevEmperor/SayOVA/wiki),
# which the watch opens under Settings → Help & guide. The pages are written here, beside the code they
# describe, and the wiki is only their copy: edit docs/wiki/, never the wiki on GitHub.
#
# GitHub creates the wiki's own git repository only with its first page: once, open the repository's
# Wiki tab, choose "Create the first page" and save it as it is. This script then replaces it.
#
#   tools/publish-wiki.sh
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
WIKI=https://github.com/DevEmperor/SayOVA.wiki.git
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

git clone --quiet "$WIKI" "$WORK/wiki"
rsync -a --delete --exclude .git "$ROOT/docs/wiki/" "$WORK/wiki/"
cd "$WORK/wiki"
git add -A
if git diff --cached --quiet; then
  echo "The wiki is up to date."
  exit 0
fi
git commit --quiet -m "Guide from docs/wiki at $(git -C "$ROOT" rev-parse --short HEAD)"
git push --quiet
echo "Published: https://github.com/DevEmperor/SayOVA/wiki"
