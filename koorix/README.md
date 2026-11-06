# Koorix 1.6.1 — یەک هەڵبژێرەر / One chooser / منتقٍ واحد

[**⬇ Download the regular Android APK**](https://github.com/s1po0/Kurd-man/releases/download/koorix-v1.6.1/Koorix-v1.6.1.apk)

- **یەک دوگمەی هەڵبژاردن:** ئەپە گونجاوە دامەزراوەکان وەک Samsung Gallery، MT Manager، ZArchiver و My Files لە یەک هەڵبژێرەری Androidدا؛ نەک دوو بەشی Gallery و Files.
- وێنە یان CBZ/ZIP هەڵبژێرە، پاشان **Translate** بکە. فلتەری MIME یان پەیوەستکردن بە فۆڵدەرێکی تایبەت نییە.
- بۆ ئێستا بە تەواوی خۆڕاییە؛ Premium، سنووری ڕۆژانە، Login و تۆمارکردنی ئامێر نییە.
- هەمان واژۆی v2؛ لەسەر ئەپی پێشوو دایبمەزرێنە و مەیسڕەوە.

Corrects the split picker in 1.6. One input button launches a system
**ACTION_GET_CONTENT** (`*/*`) chooser. Compatible installed gallery, archive and
file-manager apps are eligible, not just document-provider roots or a photo-only
picker. Exact app lists depend on the installed apps, their declared intent
support and Android/OEM behaviour. No hardcoded package, MIME whitelist or
initial storage root. Multi-select and selected-URI read access remain.

زر واحد لتطبيقات المصدر المتوافقة المثبتة مثل Samsung Gallery وMT Manager
وZArchiver وMy Files؛ ليس قسمين للمعرض والملفات. اختر صورًا أو CBZ/ZIP ثم
اضغط ترجمة. يبقى التطبيق مجانيًا بلا تسجيل دخول أو حد يومي. ثبّت التحديث فوق
التطبيق الحالي دون حذفه.

**Preserved:** saved editable projects, backups/restores, STOP, recovery, Reports,
studio, optional PDF, automatic Skill, all 74 target choices and the
Kurdish/English/Arabic interface. Historical private records are untouched. No
new model, paid API or NLLB.

**Technical limits:** Android 8+ / ARM64. Existing 300-page/300-MB import and
1-GB cache safeguards remain. Source apps may impose their own selection caps.
Android-protected folders cannot be bypassed. Showing all MIME types does not
add support for arbitrary unsupported documents. Online providers retain their
own rate limits. Synthetic/JVM tests do not certify every Samsung/provider app,
physical device, translation meaning or rendered page.

[Release](https://github.com/s1po0/Kurd-man/releases/tag/koorix-v1.6.1) ·
[Technical notes](../docs/KOORIX_1_6_1.md) · [Privacy source](site/privacy.html)

The standalone static site source reflects 1.6.1; no successful production
website deployment is claimed. The previous Vercel/Pages integration block is
unchanged. The public GitHub release supplies the permanent APK download.
