@echo off
chcp 65001 >nul
title Day app-dich-truyen (Android) len GitHub
cd /d "%~dp0"

set REPO_NAME=app-dich-truyen

if not exist ".git" (
    git init
    git branch -M main
)

git add .
git commit -m "Initial release: App Dich Truyen Android (real-time comic screen translator)"

where gh >nul 2>nul
if %errorlevel%==0 (
    echo Phat hien GitHub CLI (gh). Dang tao repo "%REPO_NAME%" va push len GitHub...
    gh repo create "%REPO_NAME%" --public --source=. --remote=origin --push
    if %errorlevel%==0 (
        echo.
        echo XONG! Vao tab Actions tren GitHub cua repo "%REPO_NAME%" de tai file APK tu dong build!
        pause
        exit /b 0
    )
)

echo.
echo Nhap link repo GitHub cua bro (vi du: https://github.com/TEN_CUA_BRO/app-dich-truyen.git):
set /p REMOTE_URL=Link GitHub: 
git remote remove origin 2>nul
git remote add origin "%REMOTE_URL%"
git push -u origin main
echo.
echo XONG! Vao tab Actions tren GitHub de tai file AppDichTruyen-v1.0.0.apk nhe!
pause
