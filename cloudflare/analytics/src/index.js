import { dashboard, loginPage as renderLoginPage } from './dashboard.js';
const headers = { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' };
const reply = (body, status = 200, extra = {}) => new Response(body, { status, headers: { ...headers, ...extra } });
const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const encoder = new TextEncoder();
const sessionSeconds = 30 * 24 * 60 * 60;
const cookieName = '__Host-analytics_session';
const htmlHeaders = { 'Content-Type': 'text/html; charset=utf-8', 'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'", 'X-Frame-Options': 'DENY' };

async function equal(a, b) {
  const hash = async text => new Uint8Array(await crypto.subtle.digest('SHA-256', encoder.encode(text)));
  return crypto.subtle.timingSafeEqual(await hash(a), await hash(b));
}

async function signature(expires, env) {
  const key = await crypto.subtle.importKey('raw', encoder.encode(env.SESSION_SECRET), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const signed = await crypto.subtle.sign('HMAC', key, encoder.encode(`analytics-session:v1:${expires}:${env.ADMIN_PASSWORD}`));
  return Array.from(new Uint8Array(signed), byte => byte.toString(16).padStart(2, '0')).join('');
}

async function authorized(request, env) {
  if (!env.ADMIN_PASSWORD || !env.SESSION_SECRET) return false;
  const cookie = (request.headers.get('Cookie') || '').split(';').map(c => c.trim()).find(c => c.startsWith(`${cookieName}=`));
  const match = cookie?.slice(cookieName.length + 1).match(/^(\d{10})\.([0-9a-f]{64})$/);
  if (!match || Number(match[1]) <= Math.floor(Date.now() / 1000)) return false;
  return equal(match[2], await signature(match[1], env));
}

function loginPage(error = false) {
  return reply(renderLoginPage(error), error ? 401 : 200, htmlHeaders);
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
    const limiter = ['/v1/installations', '/v1/website-visits'].includes(url.pathname) ? env.REPORT_LIMIT : env.ADMIN_LIMIT;
    if (!(await limiter.limit({ key: ip })).success) return reply('Too many requests', 429);
    if (url.pathname === '/login' && request.method === 'POST') {
      if (request.headers.get('Origin') !== url.origin) return reply('Forbidden', 403);
      if (!env.ADMIN_PASSWORD || !env.SESSION_SECRET) return reply('Login is not configured', 503);
      if (!request.headers.get('content-type')?.toLowerCase().startsWith('application/x-www-form-urlencoded')) return reply('Unsupported content type', 415);
      if (!request.body) return reply('Bad request', 400);
      const reader = request.body.getReader();
      let size = 0, chunks = [];
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        size += value.length;
        if (size > 2048) { await reader.cancel(); return reply('Too large', 413); }
        chunks.push(value);
      }
      const bytes = new Uint8Array(size); let offset = 0;
      for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
      const form = new URLSearchParams(new TextDecoder().decode(bytes));
      if (!await equal(`${form.get('username') || ''}:${form.get('password') || ''}`, `admin:${env.ADMIN_PASSWORD}`)) return loginPage(true);
      const expires = String(Math.floor(Date.now() / 1000) + sessionSeconds);
      const cookie = `${cookieName}=${expires}.${await signature(expires, env)}; Path=/; Secure; HttpOnly; SameSite=Lax${form.get('remember') === '1' ? `; Max-Age=${sessionSeconds}` : ''}`;
      return reply(null, 303, { 'Location': '/', 'Set-Cookie': cookie });
    }
    if (url.pathname === '/logout' && request.method === 'POST') {
      if (request.headers.get('Origin') !== url.origin) return reply('Forbidden', 403);
      return reply(null, 303, { 'Location': '/', 'Set-Cookie': `${cookieName}=; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=0` });
    }
    if (url.pathname === '/v1/website-visits' && request.method === 'POST') {
      const origin = request.headers.get('Origin');
      if (origin !== 'https://feige-yingshicang-app.mistybookmark3.chatgpt.site') return reply('Forbidden', 403);
      if (!request.body) return reply('Bad request', 400);
      const reader = request.body.getReader();
      let size = 0, chunks = [];
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        size += value.length;
        if (size > 1024) { await reader.cancel(); return reply('Too large', 413); }
        chunks.push(value);
      }
      let data;
      try { const bytes = new Uint8Array(size); let offset = 0; for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; } data = JSON.parse(new TextDecoder().decode(bytes)); } catch { return reply('Bad request', 400); }
      if (!data || typeof data.referrer !== 'string' || data.referrer.length > 255) return reply('Bad request', 400);
      let referrer = '';
      if (data.referrer) {
        try { const parsed = new URL(data.referrer); if (!['http:', 'https:'].includes(parsed.protocol)) return reply('Bad request', 400); referrer = parsed.hostname.slice(0, 253); } catch { return reply('Bad request', 400); }
      }
      const ua = request.headers.get('User-Agent') || '';
      const device = /bot|spider|crawler|slurp|bingpreview/i.test(ua) ? '爬虫' : /ipad|tablet|android(?!.*mobile)/i.test(ua) ? '平板' : /mobile|iphone|ipod/i.test(ua) ? '手机' : '电脑';
      const browser = /Edg\//i.test(ua) ? 'Edge' : /OPR\//i.test(ua) ? 'Opera' : /Firefox\//i.test(ua) ? 'Firefox' : /(?:Chrome|CriOS)\//i.test(ua) ? 'Chrome' : /Safari\//i.test(ua) ? 'Safari' : '其他';
      const os = /Android/i.test(ua) ? 'Android' : /iPhone|iPad|iPod/i.test(ua) ? 'iOS' : /Windows/i.test(ua) ? 'Windows' : /Mac OS/i.test(ua) ? 'macOS' : /Linux/i.test(ua) ? 'Linux' : '其他';
      const clean = value => typeof value === 'string' ? value.replace(/[\u0000-\u001f\u007f]/g, '').slice(0, 100) : '';
      await env.DB.prepare('INSERT INTO website_visits (country, region, city, device, browser, os, language, referrer) VALUES (?, ?, ?, ?, ?, ?, ?, ?)')
        .bind(clean(request.cf?.country), clean(request.cf?.region), clean(request.cf?.city), device, browser, os, clean((request.headers.get('Accept-Language') || '').split(',')[0]), referrer).run();
      return reply(null, 204);
    }
    if (url.pathname === '/v1/installations' && request.method === 'POST') {
      if (!request.headers.get('content-type')?.toLowerCase().startsWith('application/json')) return reply('Unsupported content type', 415);
      // Bound the body even when Content-Length is missing or false.
      if (!request.body) return reply('Bad request', 400);
      const reader = request.body.getReader();
      let size = 0, chunks = [];
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        size += value.length;
        if (size > 2048) { await reader.cancel(); return reply('Too large', 413); }
        chunks.push(value);
      }
      let data;
      try { const bytes = new Uint8Array(size); let offset = 0; for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; } data = JSON.parse(new TextDecoder().decode(bytes)); } catch { return reply('Bad request', 400); }
      const validText = value => typeof value === 'string' && value.trim().length > 0 && value.length <= 100 && !/[\u0000-\u001f\u007f]/.test(value);
      if (!data || !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(data.id) || !validText(data.manufacturer) || !validText(data.model) || !validText(data.appVersion) || !['phone', 'tablet', 'television'].includes(data.deviceType)) return reply('Bad request', 400);
      await env.DB.prepare(`INSERT INTO installations (id, manufacturer, model, device_type, app_version) VALUES (?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET manufacturer=excluded.manufacturer, model=excluded.model, device_type=excluded.device_type, app_version=excluded.app_version, last_seen=datetime('now')`)
        .bind(data.id, data.manufacturer.trim(), data.model.trim(), data.deviceType, data.appVersion.trim()).run();
      return reply(null, 204);
    }
    if (url.pathname === '/' && request.method === 'GET') {
      if (!await authorized(request, env)) return loginPage();
      return reply(await dashboard(env.DB), 200, htmlHeaders);
    }
    return reply('Not found', 404);
  }
};
