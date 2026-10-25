# Koorix — ڕێنمایی خاوەن ئەپەکە (Admin)

**Koorix** وەشانی گشتی/بازرگانیی KurdManە بۆ خەڵک:

- بەبێ بەشی Telegram (تۆکن/بۆت) و بەبێ مۆدی Baidu/Unlimited-OCR.
- چوونەژوورەوەی بێ وشەی نهێنی: بە هەژماری Googleی ناو مۆبایلەکە
  (پێویستی بە هەنگاوەکانی بەشی ١ هەیە)، یان بە **کۆد/لینکی
  چوونەژوورەوە** کە بۆ Gmailی بەکارهێنەر دەچێت (ئێستا کار دەکات؛
  بۆ ئەوەی ژمارەی کۆدەکە لەناو ئیمەیڵەکەدا دەربکەوێت، یەکجار
  قالبی ئیمەیڵەکە لە داشبۆرد بگۆڕە — بەشی ٥).
- زمانەکانی ئەپەکە: کوردی سۆرانی، English و العربية
  (ڕێکخستنەکان ← 🌐 زمانی ئەپەکە).
- وەشانی KurdMan ئیتر لەسەر هەر گۆڕانکارییەک خۆکارانە بنیات
  نانرێت/نانێردرێت (بە خواستی خاوەنەکەی)؛ تەنیا Koorix دێت.
- بەکارهێنانی خۆڕایی: **٣ وەرگێڕان لە ڕۆژێکدا، تا ٦٠ لاپەڕە لە ڕۆژێکدا**.
- **Premium**: بێسنوور، بە **کۆدی چالاککردن** کە خۆت دەیانفرۆشیت
  (Telegram/WhatsApp/FastPay/ZainCash…) و بەکارهێنەر لەناو ئەپەکەدا دایدەنێت.
- هەژمار و سنوور و کۆدەکان لەسەر **Supabase**ن (پڕۆژەی
  `cbkddsztcivllpwzflkx`)؛ یاساکان لەسەر سێرڤەر جێبەجێ دەکرێن، بۆیە
  کەس ناتوانێت بە دەستکاریکردنی ئەپەکە خۆی بکاتە Premium.

وەشانی **KurdMan**ی خۆت وەک خۆی دەمێنێتەوە (بەبێ هەژمار و سنوور، بە
Telegram و Unlimited-OCRەوە). هەردووکیان لەسەر یەک مۆبایل پێکەوە
دادەمەزرێن و هەردووکیان بە هەمان واژۆ نوێ دەکرێنەوە.

---

## ١) چالاککردنی چوونەژوورەوە بە Google (یەکجار، ~١٠ خولەک)

چوونەژوورەوە بە ئیمەیڵ ئێستا خۆی کار دەکات. بۆ دوگمەی Google:

1. بچۆ بۆ [Google Cloud Console](https://console.cloud.google.com) و
   پڕۆژەیەک دروست بکە بە ناوی Koorix.
2. **APIs & Services ← OAuth consent screen**: جۆرەکە بکە External،
   ناوی ئەپەکە بنووسە Koorix، ئیمەیڵی پشتگیری دابنێ، پاشەکەوت بکە.
3. **APIs & Services ← Credentials ← Create Credentials ← OAuth client ID**:
   - یەکێک لە جۆری **Android**: Package name = `com.koorix.app`،
     و SHA-1ی واژۆی ئەپەکە دابنێ:
     `4F:65:A0:57:F8:8D:50:8C:69:35:63:9E:18:F2:75:DF:E8:18:59:39`
     (هەمان واژۆی هەمیشەیی بیلدەکانە؛ لە هەر بیلدێکی Koorix لە
     GitHub Actionsدا بە `APK signer SHA-1=...` دەردەکەوێت).
   - یەکێکی تر لە جۆری **Web application** (ئەمەیان بۆ Supabase و
     ناو ئەپەکە پێویستە)؛ Client ID و Client Secretەکەی هەڵبگرە.
4. لە [Supabase Dashboard](https://supabase.com/dashboard/project/cbkddsztcivllpwzflkx/auth/providers):
   **Authentication ← Providers ← Google** چالاک بکە و Client ID و
   Client Secretی **Web**ەکە دابنێ.
5. لە کۆدەکەدا:
   `android/app/src/koorix/res/values/strings.xml` →
   `default_web_client_id` بگۆڕە بۆ Client IDی Webەکە،
   پاشان وەشانێکی نوێ بڵاو بکەرەوە.

## ٢) بەرنامەی بچووکی بەڕێوەبەر (Admin) — ئاسانترین ڕێگە

فایلی **`koorix/admin.html`** بکەرەوە لە وێبگەڕی مۆبایل یان
کۆمپیوتەرەکەت (هیچ دامەزراندنێکی ناوێت):

1. کلیلی **service_role** لە Supabase وەرگرە:
   [Settings ← API](https://supabase.com/dashboard/project/cbkddsztcivllpwzflkx/settings/api)
   → کلیلی `service_role`. لە خانەی یەکەمی بەرنامەکەدا بینووسە و
   «پاشەکەوت و پەیوەستبوون» دابگرە (تەنیا لەسەر ئامێرەکەی خۆت
   دەپارێزرێت — هەرگیز ئەم فایل/کلیلە بە کەس مەدە).
2. کۆد دروست بکە بە ماوەکانی: **١ مانگ / ٣ مانگ / ٦ مانگ / ١ ساڵ**
   — ژمارە هەڵبژێرە و «دروستکردن» دابگرە؛ کۆدەکان دەتوانیت بە
   یەک کرتە کۆپی بکەیت.
3. هەمان بەرنامە: لیستی کۆدەکان (بەکارنەهاتوو/بەکارهاتوو + کێ و
   کەی)، لیستی بەکارهێنەران و ماوەی Premiumیان، دانی Premiumی
   دەستی بە ئیمەیڵێک، و ئاماری خێرا.

### ڕێگەی دووەم: SQL لە Supabase

لە Supabase ← **SQL Editor** ئەمە بنووسە (ژمارە/ماوە بگۆڕە):

```sql
WITH gen AS (
  SELECT 'KOORIX-' || upper(substr(md5(random()::text || clock_timestamp()::text || g::text), 1, 4))
         || '-' || upper(substr(md5(random()::text || g::text || clock_timestamp()::text), 1, 4)) AS code
  FROM generate_series(1, 20) AS g   -- ٢٠ کۆد
)
INSERT INTO public.koorix_codes (code, days, note)
SELECT code, 30, 'فرۆشتن' FROM gen   -- هەر کۆدێک ٣٠ ڕۆژ
RETURNING code, days;
```

هەر کۆدێک تەنها **یەکجار** لەلایەن یەک هەژمارەوە بەکار دێت و ماوەکە
زیاد دەکرێتە سەر Premiumی ئێستای (ئەگەر هەیبێت).

بینینی کۆدە بەکارهاتووەکان و بەکارهێنەران:

```sql
SELECT c.code, c.days, c.redeemed_at, p.email
FROM public.koorix_codes c
LEFT JOIN public.koorix_profiles p ON p.id = c.redeemed_by
ORDER BY c.created_at DESC;

SELECT email, premium_until, created_at
FROM public.koorix_profiles ORDER BY created_at DESC;

SELECT day, sum(jobs) AS jobs, sum(pages) AS pages
FROM public.koorix_usage GROUP BY day ORDER BY day DESC;
```

دانی Premiumی دەستی بە هەژمارێک (بۆ نموونە خۆت):

```sql
UPDATE public.koorix_profiles
SET premium_until = now() + interval '365 days'
WHERE email = 'example@gmail.com';
```

## ٣) نرخ و لینکی کڕین و سنوورەکان

- **دەقی نرخەکان** (لە شاشەی هەژماردا دەردەکەوێت):
  `android/app/src/koorix/res/values/strings.xml` → `koorix_premium_pitch`.
- **دوگمەی کڕین**: هەمان فایل → `koorix_buy_url` لینکی خۆتی تێ بکە
  (بۆ نموونە `https://t.me/yourname`). ئەگەر بەتاڵ بێت دوگمەکە دەشارێتەوە.
- **سنووری خۆڕایی**: لەسەر سێرڤەرە — لە فەنکشنەکانی
  `koorix_consume_job` / `koorix_my_status` ژمارەکانی `v_limit`
  (وەرگێڕان/ڕۆژ) و `v_page_limit` (لاپەڕە/ڕۆژ) بگۆڕە؛ و بۆ پیشاندانی
  دروست لەناو ئەپەکەدا هەمان ژمارە لە `android/app/build.gradle`دا
  لە بەشی koorix (`FREE_DAILY_JOBS` / `FREE_DAILY_PAGES`) نوێ بکەرەوە.

## ٤) تێبینییە گرنگەکان

- ئێستا پشتڕاستکردنەوەی ئیمەیڵ **کوژاوەتەوە** (خەڵک دەستبەجێ دەچنە
  ژوورەوە بەبێ چاوەڕوانی لینک). ئەگەر ویستت بیکوژێنیتەوە/بیهێڵیتە
  پشتڕاستکردنەوە: Supabase ← Authentication ← Sign In / Up ←
  "Confirm email".
- ئیمەیڵەکانی Supabaseی خۆڕایی سنووردارن؛ ئەگەر بەکارهێنەر زۆر بوون،
  SMTPی خۆت دابنێ لە Supabase ← Authentication ← Emails.
- ئەمە بیلدی **debug**ە بە واژۆی هەمیشەیی هەمان کلیل؛ بۆ بڵاوکردنەوە
  لە **Google Play** پێویستت بە هەژماری گەشەپێدەری Play Console و
  واژۆی release هەیە — پاش ئەوە دەتوانرێت subscriptionی ڕاستەقینەی
  Play Billingیش زیاد بکرێت لەجیاتی/لەتەنیشت کۆدەکان.
- ئەگەر بەکارهێنەر ئینتەرنێتی نەبێت: Premiumی ناسراو لە دوایین
  پشکنینەوە کار دەکات، و بەکارهێنەری خۆڕایی ژمێرەری ناوخۆیی ڕۆژانەی
  هەیە؛ کاتێک سێرڤەر وەڵام دەداتەوە، ژمارەی سێرڤەر بڕیاردەرە.
- ئەگەر بەکارهێنەر ئینتەرنێتی نەبێت: Premiumی ناسراو لە دوایین
  پشکنینەوە کار دەکات، و بەکارهێنەری خۆڕایی ژمێرەری ناوخۆیی ڕۆژانەی
  هەیە؛ کاتێک سێرڤەر وەڵام دەداتەوە، ژمارەی سێرڤەر بڕیاردەرە.

## ٥) بۆ ئەوەی ژمارەی کۆدەکە لەناو ئیمەیڵەکەدا دەربکەوێت (یەکجار، ٢ خولەک)

لە Supabase بچۆ بۆ **Authentication ← Emails ← Templates ← Magic Link**
و ناوەڕۆکەکە بەمە بگۆڕە، پاشان Save بکە:

```html
<div style="font-family:Arial,sans-serif;max-width:480px;margin:auto;text-align:center">
<h2 style="color:#6D28D9">Koorix</h2>
<p>Your sign-in code is:</p>
<p style="font-size:34px;font-weight:bold;letter-spacing:6px;color:#2E1065">{{ .Token }}</p>
<p>Enter this code in the Koorix app. It expires in 1 hour.</p>
<p>Or tap this link to open the app signed in:</p>
<p><a href="{{ .ConfirmationURL }}" style="display:inline-block;padding:12px 22px;background:#7C3AED;color:#fff;border-radius:10px;text-decoration:none">Open Koorix</a></p>
<p style="color:#888;font-size:12px">If you did not request this, ignore this email.</p>
</div>
```

هەروەها لە هەمان شوێندا **Subject** بکە بە:
`Your Koorix sign-in code: {{ .Token }}`

و لە **Authentication ← Sign In / Up**دا "Email OTP length" بکە بە **6**.

تا ئەمە دەکەیت، بەکارهێنەران هەر دەتوانن بە کرتەکردن لە لینکی
ناو ئیمەیڵەکە ڕاستەوخۆ بچنە ژوورەوە (ئەپەکە دەکرێتەوە و چووەتە
ژوورەوە) — کۆد نووسین دوای ئەم هەنگاوەش کار دەکات.

تێبینی: ئیمەیڵی خۆڕایی ناوخۆیی Supabase سنووری ناردنی کەمی هەیە
لە کاتژمێرێکدا. ئەگەر بەکارهێنەر زۆر بوون و کۆدەکان نەگەیشتن،
SMTPی دەرەکی دابنێ (بۆ نموونە Resend) لە
Authentication ← Emails ← SMTP Settings.
