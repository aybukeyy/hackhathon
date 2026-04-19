#!/bin/bash
set -e

echo ""
echo " İBBBot - AI Chatbot Başlatılıyor..."
echo "=========================================="

# Docker çalışıyor mu?
if ! docker info > /dev/null 2>&1; then
    echo ""
    echo " [HATA] Docker çalışmıyor!"
    echo " Lütfen Docker'ı başlatın ve tekrar deneyin."
    exit 1
fi

echo ""
echo " [1/3] Servisler derleniyor ve başlatılıyor..."
echo " (İlk çalıştırmada modeller indirileceğinden 10-20 dakika sürebilir)"
echo ""

docker compose up --build -d

echo ""
echo " [2/3] Servisler hazır olana kadar bekleniyor..."

until docker compose ps | grep "ibbbot-frontend" | grep -q "Up"; do
    echo " Bekleniyor..."
    sleep 5
done

echo ""
echo " [3/3] Tüm servisler hazır!"
echo ""
echo "=========================================="
echo "  Uygulama açık: http://localhost"
echo "=========================================="
echo ""
echo " Servisleri durdurmak için: docker compose down"
echo " Logları görmek için: docker compose logs -f"
echo ""

# Mac'te otomatik aç
if [[ "$OSTYPE" == "darwin"* ]]; then
    open http://localhost
fi
