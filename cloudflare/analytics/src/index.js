const headers = { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' };
const reply = (body, status = 200, extra = {}) => new Response(body, { status, headers: { ...headers, ...extra } });
const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

async function authorized(request, secret) {
  if (!secret) return false;
  const auth = request.headers.get('Authorization') || '';
  let provided;
  try { provided = atob(auth.startsWith('Basic ') ? auth.slice(6) : ''); } catch { return false; }
  const hash = async text => new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text)));
  return crypto.subtle.timingSafeEqual(await hash(provided), await hash(`admin:${secret}`));
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
    const limiter = url.pathname === '/' ? env.ADMIN_LIMIT : env.REPORT_LIMIT;
    if (!(await limiter.limit({ key: ip })).success) return reply('Too many requests', 429);
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
      if (!await authorized(request, env.ADMIN_PASSWORD)) return reply('Authentication required', 401, { 'WWW-Authenticate': 'Basic realm="Analytics", charset="UTF-8"' });
      const summary = await env.DB.prepare(`SELECT count(*) AS total, sum(last_seen >= datetime('now','-7 days')) AS active7, sum(last_seen >= datetime('now','-30 days')) AS active30 FROM installations WHERE device_type='phone'`).first();
      const models = await env.DB.prepare(`SELECT manufacturer, model, count(*) AS total, sum(last_seen >= datetime('now','-30 days')) AS active30 FROM installations WHERE device_type='phone' GROUP BY manufacturer, model ORDER BY total DESC, manufacturer, model LIMIT 500`).all();
      const types = await env.DB.prepare(`SELECT device_type, count(*) AS total FROM installations GROUP BY device_type ORDER BY total DESC`).all();
      const versions = await env.DB.prepare(`SELECT app_version, count(*) AS total FROM installations GROUP BY app_version ORDER BY total DESC LIMIT 100`).all();
      const rows = models.results.map(r => `<tr><td>${escape(r.manufacturer)}</td><td>${escape(r.model)}</td><td>${r.total}</td><td>${r.active30}</td></tr>`).join('');
      const listing = rs => rs.map(r => `<li>${escape(r.device_type ?? r.app_version)}：${r.total}</li>`).join('');
      return reply(`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>安装统计</title><style>body{font:16px system-ui;max-width:960px;margin:32px auto;padding:0 16px;color:#202431}table{border-collapse:collapse;width:100%}td,th{text-align:left;border-bottom:1px solid #ddd;padding:10px}section{margin:28px 0}.numbers{display:flex;gap:24px;flex-wrap:wrap}</style><h1>安装统计</h1><p>仅统计成功上报的安装实例；重装或清除数据可能重复计数。</p><section class="numbers"><div>手机累计：<strong>${summary.total}</strong></div><div>近 7 天活跃：<strong>${summary.active7 || 0}</strong></div><div>近 30 天活跃：<strong>${summary.active30 || 0}</strong></div></section><h2>手机型号</h2><table><thead><tr><th>品牌</th><th>型号</th><th>累计</th><th>近 30 天活跃</th></tr></thead><tbody>${rows}</tbody></table><h2>设备类型</h2><ul>${listing(types.results)}</ul><h2>App 版本</h2><ul>${listing(versions.results)}</ul></html>`, 200, { 'Content-Type': 'text/html; charset=utf-8', 'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'", 'X-Frame-Options': 'DENY' });
    }
    return reply('Not found', 404);
  }
};
