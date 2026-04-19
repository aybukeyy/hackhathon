@echo off
chcp 65001 >nul
echo.
echo  ██╗██████╗ ██████╗ ██████╗ ████████╗
echo  ██║██╔══██╗██╔══██╗██╔══██╗╚══██╔══╝
echo  ██║██████╔╝██████╔╝██████╔╝   ██║
echo  ██║██╔══██╗██╔══██╗██╔══██╗   ██║
echo  ██║██████╔╝██████╔╝██████╔╝   ██║
echo  ╚═╝╚═════╝ ╚═════╝ ╚═════╝    ╚═╝
echo.
echo  İBB AI Chatbot - Başlatılıyor...
echo ==========================================

:: Docker çalışıyor mu?
docker info >nul 2>&1
if errorlevel 1 (
    echo.
    echo  [HATA] Docker Desktop çalışmıyor!
    echo  Lütfen Docker Desktop'ı başlatın ve tekrar deneyin.
    pause
    exit /b 1
)

echo.
echo  [1/3] Servisler derleniyor ve başlatılıyor...
echo  (İlk çalıştırmada modeller indirileceğinden 10-20 dakika sürebilir)
echo.

docker compose up --build -d

if errorlevel 1 (
    echo.
    echo  [HATA] Başlatma başarısız. Logları kontrol edin:
    echo  docker compose logs
    pause
    exit /b 1
)

echo.
echo  [2/3] Servisler hazır olana kadar bekleniyor...
echo.

:wait_loop
timeout /t 5 /nobreak >nul
docker compose ps --format "table {{.Name}}\t{{.Status}}" 2>nul | findstr "ibbbot-frontend" | findstr "Up" >nul
if errorlevel 1 (
    echo  Bekleniyor... (frontend henüz hazır degil)
    goto wait_loop
)

echo.
echo  [3/3] Tüm servisler hazır!
echo.
echo ==========================================
echo   Uygulama açık: http://localhost
echo ==========================================
echo.
echo  Servisleri durdurmak için: docker compose down
echo  Logları görmek için: docker compose logs -f
echo.

start http://localhost
pause
