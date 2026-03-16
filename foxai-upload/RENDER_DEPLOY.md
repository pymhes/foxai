# FoxAI Backend — Render.com Deploy Rehberi

## Değişiklikler (Replit → Render)
- ✅ OpenAI API → **Groq API** (ücretsiz, hızlı)
- ✅ Model: `gpt-5.2` → **`llama-3.3-70b-versatile`**
- ✅ PORT: zorunlu hata kaldırıldı (Render kendisi set eder)

---

## Adım 1: GitHub'a Yükle

1. github.com → "New repository" → isim: `foxai-backend`
2. **node_modules, .local, .gradle klasörlerini YÜKLEME** (büyük, gereksiz)
3. Yüklemeniz gereken klasörler:
   - `artifacts/`
   - `lib/`
   - `minecraft-mod/src/` (Java kaynak kodları)
   - `package.json`
   - `pnpm-lock.yaml`
   - `pnpm-workspace.yaml`
   - `tsconfig.base.json`
   - `tsconfig.json`

---

## Adım 2: Render PostgreSQL Oluştur

1. render.com → **New → PostgreSQL**
2. İsim: `foxai-db`
3. Plan: **Free**
4. Oluştur → **Internal Database URL**'yi kopyala (sonra lazım)

---

## Adım 3: Render Web Service Oluştur

1. render.com → **New → Web Service**
2. GitHub repo: `foxai-backend` seç
3. Ayarlar:

| Alan | Değer |
|------|-------|
| **Root Directory** | `artifacts/api-server` |
| **Environment** | Node |
| **Build Command** | `cd ../.. && npm install -g pnpm && pnpm install && pnpm -r --if-present run build` |
| **Start Command** | `node dist/index.cjs` |
| **Plan** | Free |

---

## Adım 4: Environment Variables

Render → Web Service → **Environment** sekmesi:

| Key | Value |
|-----|-------|
| `GROQ_API_KEY` | Groq'tan aldığın key (console.groq.com) |
| `DATABASE_URL` | Render PostgreSQL'den kopyaladığın Internal URL |
| `PORT` | `3000` |

---

## Adım 5: İlk Deploy Sonrası DB Migration

Render → Web Service → **Shell** sekmesi:

```bash
cd ../../lib/db && npx drizzle-kit push
```

Bu komut tabloları otomatik oluşturur (mod_config, mod_logs vs.)

---

## Adım 6: Minecraft Mod Config Güncelle

Deploy tamamlandıktan sonra Render'ın verdiği URL'yi kopyala
(örn: `https://foxai-backend.onrender.com`)

`config/aiplayermod.properties` dosyasını güncelle:
```properties
apiUrl=https://foxai-backend.onrender.com/api/mod/chat
```

---

## Adım 7: Uptime Robot (Uyumayı Engelle)

Render free plan 15 dk sonra uyuyor. Bunu engelle:

1. uptimerobot.com → ücretsiz kayıt
2. **New Monitor** → HTTP(s)
3. URL: `https://foxai-backend.onrender.com/api/health`
4. Interval: **5 minutes**
5. Kaydet

---

## Groq API Key Alma

1. console.groq.com → Google ile giriş
2. **API Keys** → **Create API Key**
3. İsim ver → Key'i kopyala
4. Render'daki `GROQ_API_KEY` environment variable'a yapıştır

**Ücretsiz limitler:**
- llama-3.3-70b-versatile: günlük 1000 istek, dakikada 30 istek
- Minecraft modu için fazlasıyla yeterli!
