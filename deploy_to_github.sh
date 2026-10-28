#!/usr/bin/env bash
set -e
echo "Deploying WitAnime Pro v4 to GitHub..."
git init
git checkout -B main
git add -A
git commit -m "Fix status=1 (Active) and compile WitAnime Pro v4" || true
git remote remove origin 2>/dev/null || true
git remote add origin https://github.com/ahmedessameldeen-code/witanime-cloudstream-repo.git
git push -f -u origin main
echo "Done! Check https://github.com/ahmedessameldeen-code/witanime-cloudstream-repo/actions"
