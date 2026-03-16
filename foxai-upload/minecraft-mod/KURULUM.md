# 🦊 FoxAI Modu — Kurulum Rehberi

## Hızlı Özet
Bu mod Minecraft Java Edition 1.20.1 + Forge 47.3.0 için yapılmıştır.
PojavLauncher dahil tüm Java Edition platformlarında çalışır.

---

## JAR Dosyasını Nasıl Alırsın?

### 📱 Mobil İçin Kolay Yol — GitHub Actions

1. Bu `minecraft-mod` klasörünü GitHub'a yükle (ücretsiz hesap yeterli)
2. GitHub otomatik olarak JAR'ı derler (~5-10 dakika)
3. Şuradan indir: `Actions` sekmesi → En son workflow → `FoxAI-Mod-JAR` artifact

**Adım adım:**
```
1. github.com → Yeni repo oluştur (örn: foxai-mod)
2. Bu minecraft-mod klasörünün içindekilerini o repoya yükle
3. Actions sekmesine git — build otomatik başlar
4. Build bitince: Actions → En son run → Artifacts → FoxAI-Mod-JAR → İndir
```

### 💻 PC'de Derleme (Java 17 gerekli)
```bash
cd minecraft-mod
./gradlew build
# JAR: build/libs/aiplayermod-1.0.0.jar
```

---

## PojavLauncher'a Kurulum

1. İndirdiğin `aiplayermod-1.0.0.jar` dosyasını bul
2. Minecraft klasörüne git: `/sdcard/games/PojavLauncher/.minecraft/mods/`
3. JAR dosyasını o klasöre kopyala
4. Forge 1.20.1 ile başlat

---

## API Sunucusu Bağlantısı

`config/aiplayermod.properties` dosyasını düzenle:
```properties
apiUrl=https://SENIN-REPLIT-ADRESIN.replit.app/api/mod/chat
triggerPrefix=!ai
language=tr
enabled=true
```

> Replit adresi: Projenin sağ üstündeki "Published" linkini kopyala

---

## Kullanım

### Komut Modu (`!ai` ile)
```
!ai ev yap
!ai elmas ara
!ai beni takip et
!ai kılıç craft et
!ai güvenli in       <- fall damage yok! su kovası/merdiven kullanır
!ai zombileri temizle
!ai çiftlik kur
```

### Sohbet Modu (`fox` veya `foxai` ile — prefix gerekmez!)
```
fox nasılsın?
foxai Create modu nasıl çalışır?
fox bu bölgede ne bulabilirim?
hey fox elmas nerede çıkar?
```

### Acil Durdur
```
dur
stop
```

---

## FoxAI'nin Yaptıkları

### 🏠 İnşaat
- Ev, kulübe, kale inşa eder
- Malzeme toplar, craft eder, otomatik uygular

### ⚔️ Savaş
- Düşmanları tespit eder ve saldırır
- Kalkan kullanır, geri çekilir

### 🪜 Fall Damage Koruması
- Yüksekten inerken su kovası veya merdiven kullanır
- Her ikisi yoksa seni uyarır ve yavaşça iner (shift bas)

### 🌾 Çiftçilik
- Ekim yapar, sulama kontrolü, hasat

### 🎒 Envanter
- Craft eder, eritir, düzenler
- En iyi silah/zırhı otomatik kuşanır

### 🗺️ Mod Farkındalığı
- Yüklü modları tanır (Create, Botania, Tinkers, vb.)
- O modlara özel ipuçları ve yardım verir

### 💬 Pasif İzleme
- Canın azaldığında uyarır
- Aç kaldığında hatırlatır
- Gece olunca meşale hatırlatır
- Arada kendiliğinden yorum yapar (3 dakikada bir)

---

## Kişilik Özellikleri

- İstanbul gençlerinin diliyle konuşur ("ya", "kanka", "bro", "aga")
- Minecraft jargonu kullanır
- Espri yapar ama abartmaz
- Hata yapınca özür diler
- 6 farklı duygu modu: mutlu, heyecanlı, endişeli, yorgun, odaklı, coşkulu

---

## Teknik Bilgi
- Forge 1.20.1-47.3.0
- GPT-5.2 API (Replit AI üzerinden)
- Maksimum 20 aksiyon/komut
- Sohbet hafızası ve bağlam desteği
