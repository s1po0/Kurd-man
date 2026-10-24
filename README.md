# 📚 Kurd-man — وەرگێڕانی مانهوا بۆ کوردی سۆرانی + بۆتی تلگرام

ئەم ڕیپۆیە تەنها دوو شت لەخۆدەگرێت:

1. **وەرگێڕی مانهوا/مانگا بۆ کوردی سۆرانی (`ckb`)** — بەخۆڕایی و بێ هیچ API Keyـی پارەدار.
2. **بۆتی تلگرام** — وێنەی مانهوا درێژەکە بۆ بۆتەکە بنێرە، وەرگێڕدراوەکەت بۆ دەگەڕێتەوە.

## چۆن کاردەکات؟

```
وێنەی درێژی مانهوا لە تلگرام (وەک File/Document)
   → GitHub Actions هەر ~١٠ خولەک جارێک بە خۆکار دەیپشکنێت
   → وێنەکە بە پارچە (tiles) و بەپێی هەڵۆکەکان OCR دەکرێت
   → دەقەکان وەردەگیرێن بۆ سۆرانی و لەناو هەمان هەڵۆکەکاندا دادەنرێن
   → ئەنجامەکە وەک Document + دەقەکان دەگەڕێتەوە بۆت
```

پێویست ناکات بێیتە ئەم چاتە و بڵێیت «ناردم» — وێنەکە بنێرە و چاوەڕوان بکە.
بۆ باشترین ئەنجام ڕەسەنەکە وەک **File/Document** بنێرە، نەک وەک Photoی پەستێنراو.

## پێکهاتە

| بەش | چییە |
|---|---|
| [`manhwa/`](manhwa/) | بزوێنەری وەرگێڕان: OCR بەش-بەش، دۆزینەوەی هەڵۆک، وەرگێڕانی بێ Key + Cache، ڕێندەری سۆرانی |
| [`bot/`](bot/) | `bot_inbox.py` (پرۆسەکردنی خۆکار لە GitHub Actions) و `telegram-bot.mjs` (بۆتی فەرمانەکان بۆ هۆستی هەمیشەیی) |
| [`.github/workflows/`](.github/workflows/) | پشکنینی خۆکاری بۆت، وەرگێڕانی دەقی نموونە، نامەی تاقیکردنەوە |
| [`MANHWA_KU.md`](MANHWA_KU.md) | ڕێبەری تەواوی وەرگێڕی مانهوا |
| [`TELEGRAM_KU.md`](TELEGRAM_KU.md) | ڕێبەری دروستکردن و بەڕێوەبردنی بۆتی تلگرام |

## بەکارهێنان لەسەر کۆمپیوتەرەکەی خۆت

```bash
# Ubuntu/Debian:
sudo apt install tesseract-ocr tesseract-ocr-kor fonts-noto-core libraqm0
pip install -r manhwa/requirements.txt

python manhwa/translate_manhwa.py --text "I will protect you!"
python manhwa/translate_manhwa.py --image long.jpg --render
```

بۆتی فەرمانەکان (Node 18+، بێ پێداویستی):

```bash
cp .env.example .env     # Token و IDـەکەت تێدا بنووسە — هەرگیز commit مەکە
npm run bot:check
npm run bot
```

## تاقیکردنەوەکان

```bash
cd manhwa && python -m unittest -v    # 20/20
cd bot && python -m unittest -v       # 8/8
node bot/telegram-bot.mjs --selftest
```

## پاراستنی نهێنی

Bot Token وەکو پاسۆردە: تەنها لە `.env`ـی خۆت یان لە GitHub Secrets دایبنێ،
هەرگیز لە کۆد/چاتدا مەینووسە. ئەگەر دزە بکات: `@BotFather` → `/revoke`.

تەنها ئەو بەرھەمانە وەرگێڕە کە مافت هەیە / بۆ بەکارهێنانی کەسی خۆتە.
