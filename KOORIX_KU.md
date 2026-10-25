# Koorix — ڕێنمایی خاوەن ئەپەکە (Admin)

**Koorix** وەشانی گشتی/بازرگانیی KurdManە بۆ خەڵک:

- بەبێ بەشی Telegram (تۆکن/بۆت) و بەبێ مۆدی Baidu/Unlimited-OCR.
- چوونەژوورەوە بە ئیمەیڵ (Gmail) + وشەی نهێنی، و دوگمەی Google (پێویستی
  بە هەنگاوەکانی خوارەوە هەیە بۆ چالاککردن).
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
     و SHA-1ی واژۆی ئەپەکە دابنێ (لە دوایین بیلدی Koorix لە
     GitHub Actionsدا لە annotationەکاندا بە
     `APK signer SHA-1=...` دەردەکەوێت).
   - یەکێکی تر لە جۆری **Web application** (ئەمەیان بۆ Supabase و
     ناو ئەپەکە پێویستە)؛ Client ID و Client Secretەکەی هەڵبگرە.
4. لە [Supabase Dashboard](https://supabase.com/dashboard/project/cbkddsztcivllpwzflkx/auth/providers):
   **Authentication ← Providers ← Google** چالاک بکە و Client ID و
   Client Secretی **Web**ەکە دابنێ.
5. لە کۆدەکەدا:
   `android/app/src/koorix/res/values/strings.xml` →
   `default_web_client_id` بگۆڕە بۆ Client IDی Webەکە،
   پاشان وەشانێکی نوێ بڵاو بکەرەوە.

## ٢) دروستکردنی کۆدی چالاککردنی Premium بۆ فرۆشتن

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
