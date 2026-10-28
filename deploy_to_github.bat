@echo off
echo ========================================================
echo   Deploying WitAnime Pro v5 to GitHub Automatically...
echo ========================================================
git init
git checkout -B main
git add -A
git commit -m "Upgrade WitAnime Pro v5 for new witanime.site platform"
git remote remove origin 2>nul
git remote add origin https://github.com/ahmedessameldeen-code/witanime-cloudstream-repo.git
git push -f -u origin main
echo.
echo Done! Open https://github.com/ahmedessameldeen-code/witanime-cloudstream-repo/actions to watch the 1-minute build.
pause
