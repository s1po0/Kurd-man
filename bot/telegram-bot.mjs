#!/usr/bin/env node
/**
 * Kurd-man / Iron & Crown — Telegram Bot
 * ---------------------------------------
 * No dependencies. Node 18+ (uses global fetch).
 *
 * What to give the agent / put in .env:
 *   TELEGRAM_BOT_TOKEN=123456789:AAE...   <- from @BotFather  (/newbot)
 *   TELEGRAM_ADMIN_ID=123456789           <- your numeric ID  (@userinfobot)
 *   APP_URL=https://your-game-url         <- optional, opens game inside Telegram
 *
 * Run:    npm run bot
 * Check:  npm run bot:check     (validates .env format, no network)
 * Test:   node bot/telegram-bot.mjs --selftest
 *
 * SECURITY: token = bot password. Keep it ONLY in .env (git-ignored)
 * or in your hosting provider's Environment Variables. Never commit it.
 * Leaked? -> @BotFather -> /revoke
 */
import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

// ---------- tiny .env loader (no dependency) ----------
const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
function loadEnv() {
  const file = path.join(root, '.env');
  if (!existsSync(file)) return;
  for (const line of readFileSync(file, 'utf8').split('\n')) {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*"?([^"\n]*)"?\s*$/i);
    if (m && !(m[1] in process.env)) process.env[m[1]] = m[2].trim();
  }
}
loadEnv();

const TOKEN = (process.env.TELEGRAM_BOT_TOKEN || '').trim();
const ADMIN_ID = (process.env.TELEGRAM_ADMIN_ID || '').trim();
const APP_URL = (process.env.TELEGRAM_WEBAPP_URL || process.env.APP_URL || '')
  .trim()
  .replace(/^MY_APP_URL$/, '');
const ADMIN_ONLY = /^(1|true|yes)$/i.test(process.env.TELEGRAM_ADMIN_ONLY || '');
const API = `https://api.telegram.org/bot${TOKEN}`;
const startedAt = Date.now();
let botUsername = '';

// ---------- command router (pure — testable without network) ----------
const isAdmin = (userId) => ADMIN_ID !== '' && String(userId) === ADMIN_ID;

function gameKeyboard() {
  if (!/^https:\/\//i.test(APP_URL)) return undefined;
  return {
    inline_keyboard: [[{ text: '🎮 یاری بکە / Play Game', web_app: { url: APP_URL } }]],
  };
}

export function buildReply(msg) {
  const chatId = msg?.chat?.id;
  const userId = msg?.from?.id;
  const text = (msg?.text || '').trim();
  if (!chatId || !text.startsWith('/')) {
    return chatId
      ? { chat_id: chatId, text: '👋 /start بنووسە بۆ دەستپێکردن — Type /help for commands.' }
      : null;
  }
  if (ADMIN_ONLY && !isAdmin(userId)) {
    return { chat_id: chatId, text: '⛔ ئەم بۆتە تایبەتە. / This bot is private.' };
  }
  const cmd = text.split(/\s+/)[0].split('@')[0].toLowerCase();

  switch (cmd) {
    case '/start':
      return {
        chat_id: chatId,
        text:
          '⚔️ بەخێربێیت بۆ Iron & Crown!\n' +
          'یارییەکی ستراتیژی-ڕۆڵگێڕانی سەدەکانی ناوەڕاستە.\n\n' +
          'Welcome to Iron & Crown — medieval sandbox strategy-RPG.\n' +
          (APP_URL ? '👇 دوگمەکە دابگرە بۆ یاریکردن' : '🎮 /game بۆ زانیاری زیاتر'),
        reply_markup: gameKeyboard(),
      };
    case '/help':
      return {
        chat_id: chatId,
        text:
          '📜 فەرمانەکان / Commands:\n' +
          '/start — دەستپێبکە\n/game — دەربارەی یارییەکە\n/ping — تاقیکردنەوەی پەیوەندی\n' +
          '/id — IDـی خۆت بزانە (بۆ ڕێکخستنی Admin)\n/status — دۆخی بۆت (تەنها Admin)',
      };
    case '/ping':
      return { chat_id: chatId, text: `🏓 pong — ${new Date().toISOString()}` };
    case '/id':
      return {
        chat_id: chatId,
        text: `🆔 Your User ID: ${userId}\n💬 Chat ID: ${chatId}\n\nئەم ژمارەیە بکە بە TELEGRAM_ADMIN_ID لە .env`,
      };
    case '/game':
      return {
        chat_id: chatId,
        text:
          '⚔️ Iron & Crown: Medieval Sandbox RPG\n' +
          'بسووڕێوە لە شانشینێکدا، سەرباز کۆبکەرەوە، بازرگانی بکە و چارەنووسی خۆت دروست بکە.\n' +
          (APP_URL ? `🎮 ${APP_URL}` : '🎮 لینکی یاری بەمزووانە...'),
        reply_markup: gameKeyboard(),
      };
    case '/status':
      if (ADMIN_ID && !isAdmin(userId))
        return { chat_id: chatId, text: '⛔ تەنها Admin دەتوانێت ئەمە بەکاربهێنێت.' };
      return {
        chat_id: chatId,
        text:
          `✅ Bot: @${botUsername || '...'}\n⏱ Uptime: ${Math.round((Date.now() - startedAt) / 1000)}s\n` +
          `🖥 Node: ${process.version}\n👑 Admin locked: ${ADMIN_ID ? 'yes' : 'NO — set TELEGRAM_ADMIN_ID!'}`,
      };
    default:
      return { chat_id: chatId, text: '❓ فەرمانەکە ناناسم. /help بنووسە.' };
  }
}

// ---------- Telegram API ----------
async function api(method, body) {
  const res = await fetch(`${API}/${method}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body ?? {}),
  });
  const data = await res.json().catch(() => ({}));
  if (!data.ok) throw new Error(`${method}: ${data.description || res.status}`);
  return data.result;
}

async function poll() {
  const me = await api('getMe');
  botUsername = me.username;
  console.log(`✅ Connected as @${me.username} — waiting for messages... (Ctrl+C to stop)`);
  let offset = 0;
  for (;;) {
    try {
      const updates = await api('getUpdates', { offset, timeout: 30, allowed_updates: ['message'] });
      for (const u of updates) {
        offset = u.update_id + 1;
        const reply = buildReply(u.message);
        if (reply) await api('sendMessage', reply).catch((e) => console.error('send:', e.message));
      }
    } catch (e) {
      if (/409/.test(e.message))
        console.error('⚠️ 409: another copy of this bot is already running. Stop it first.');
      else console.error('poll:', e.message);
      await new Promise((r) => setTimeout(r, 3000));
    }
  }
}

// ---------- modes ----------
const tokenLooksValid = /^\d{8,12}:[A-Za-z0-9_-]{30,}$/.test(TOKEN);
const mode = process.argv[2];

if (mode === '--selftest') {
  const fake = (text, id = 111) => ({ chat: { id: 999 }, from: { id }, text });
  const cases = [
    ['/start', 'بەخێربێیت'], ['/help', '/status'], ['/ping', 'pong'],
    ['/id', '111'], ['/game', 'Iron & Crown'], ['/unknown', '/help'],
  ];
  let fail = 0;
  for (const [cmd, expect] of cases) {
    const ok = buildReply(fake(cmd))?.text.includes(expect);
    console.log(`${ok ? 'PASS' : 'FAIL'} ${cmd}`); if (!ok) fail++;
  }
  console.log(fail ? `\n❌ ${fail} failed` : '\n✅ All selftests passed');
  process.exit(fail ? 1 : 0);
}

if (mode === '--check') {
  console.log(`TELEGRAM_BOT_TOKEN : ${TOKEN ? (tokenLooksValid ? '✅ set, format OK' : '⚠️ set but format looks wrong') : '❌ MISSING — get it from @BotFather'}`);
  console.log(`TELEGRAM_ADMIN_ID  : ${ADMIN_ID ? '✅ ' + ADMIN_ID : '⚠️ not set — get it from @userinfobot, then /status is open to anyone'}`);
  console.log(`APP_URL (WebApp)   : ${APP_URL || '— not set (optional)'}`);
  process.exit(TOKEN && tokenLooksValid ? 0 : 1);
}

if (mode === '--send-test') {
  if (!TOKEN || !tokenLooksValid) { console.error('❌ Token missing/invalid'); process.exit(1); }
  if (!ADMIN_ID) { console.error('❌ TELEGRAM_ADMIN_ID missing'); process.exit(1); }
  try {
    const me = await api('getMe');
    console.log(`✅ getMe OK: @${me.username} (id ${me.id})`);
    const sent = await api('sendMessage', {
      chat_id: ADMIN_ID,
      text:
        '✅ سڵاو! ئەمە نامەی تاقیکردنەوەیە لە Arena Agentـەوە.\n' +
        'من ئێستا کۆنترۆڵی ئەم بۆتەم هەیە 🤖\n\n' +
        'Hello! This is a test message from your Arena agent — I can control this bot.\n' +
        `⏰ ${new Date().toISOString()}`,
      reply_markup: gameKeyboard(),
    });
    console.log(`✅ sendMessage OK: message_id=${sent.message_id} chat=${sent.chat.id}`);
  } catch (e) {
    console.error('❌', e.message);
    if (/chat not found|bot can.t initiate/i.test(e.message))
      console.error('👉 سەرەتا لە تلگرام بۆتەکە بکەرەوە و /start دابگرە، پاشان دووبارە هەوڵ بدە.');
    process.exit(2);
  }
  process.exit(0);
}

if (!TOKEN || !tokenLooksValid) {
  console.error(
    '\n❌ TELEGRAM_BOT_TOKEN نییە یان هەڵەیە.\n' +
    '1) Telegram → @BotFather → /newbot → Token کۆپی بکە\n' +
    '2) لە فایلی .env بنووسە:  TELEGRAM_BOT_TOKEN=123456789:AAE...\n' +
    '3) دووبارە: npm run bot\n\nSee TELEGRAM_KU.md\n',
  );
  process.exit(1);
}

poll().catch((e) => { console.error('Fatal:', e.message); process.exit(1); });
