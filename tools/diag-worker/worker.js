// 诊断上传服务（Cloudflare Worker + KV）。
// POST /upload  公开，收 gzip，返回 {"code":"XXXXXXXX"}
// GET  /d/<码>  需要 Authorization: Bearer <ADMIN_TOKEN>，返回 gzip 原文
// 数据保留 14 天，由 KV 的 expirationTtl 自动清除。
// 限流：每个 IP 一个 Durable Object，10 分钟内最多 3 次上传（强一致，不会被多节点绕过）。

const MAX_BYTES = 2 * 1024 * 1024;
const TTL = 14 * 24 * 3600;
const LIMIT = 3;
const WINDOW_MS = 10 * 60 * 1000;
// 去掉容易看错的 0 O 1 I L
const ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

function newCode() {
  const b = crypto.getRandomValues(new Uint8Array(8));
  let s = "";
  for (const x of b) s += ALPHABET[x % ALPHABET.length];
  return s;
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function safeEqual(a, b) {
  const x = new TextEncoder().encode(a);
  const y = new TextEncoder().encode(b);
  if (x.length !== y.length) return false;
  let d = 0;
  for (let i = 0; i < x.length; i++) d |= x[i] ^ y[i];
  return d === 0;
}

export class Limiter {
  constructor(ctx) {
    this.ctx = ctx;
  }

  async fetch() {
    const now = Date.now();
    const hits = ((await this.ctx.storage.get("hits")) || []).filter(
      (t) => now - t < WINDOW_MS,
    );
    const ok = hits.length < LIMIT;
    if (ok) hits.push(now);
    await this.ctx.storage.put("hits", hits);
    await this.ctx.storage.setAlarm(now + WINDOW_MS);
    return json({ ok });
  }

  async alarm() {
    await this.ctx.storage.deleteAll();
  }
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);

    if (req.method === "POST" && url.pathname === "/upload") {
      const ip = req.headers.get("CF-Connecting-IP") || "unknown";
      const r = await env.LIMITER.get(env.LIMITER.idFromName(ip)).fetch("https://limiter/");
      if (!(await r.json()).ok) return json({ error: "rate limited" }, 429);
      const len = Number(req.headers.get("Content-Length") || 0);
      if (!len || len > MAX_BYTES) return json({ error: "size" }, 413);
      const body = await req.arrayBuffer();
      if (body.byteLength > MAX_BYTES) return json({ error: "size" }, 413);
      const m = new Uint8Array(body, 0, 2);
      if (m[0] !== 0x1f || m[1] !== 0x8b) return json({ error: "format" }, 400);
      const code = newCode();
      await env.DIAG.put(code, body, { expirationTtl: TTL });
      return json({ code });
    }

    const m = url.pathname.match(/^\/d\/([A-Z0-9]{8})$/);
    if (req.method === "GET" && m) {
      const auth = req.headers.get("Authorization") || "";
      if (!env.ADMIN_TOKEN || !safeEqual(auth, "Bearer " + env.ADMIN_TOKEN)) {
        return json({ error: "unauthorized" }, 401);
      }
      const obj = await env.DIAG.get(m[1], "arrayBuffer");
      if (!obj) return json({ error: "not found" }, 404);
      return new Response(obj, {
        headers: {
          "Content-Type": "application/gzip",
          "Content-Disposition": `attachment; filename="diag-${m[1]}.txt.gz"`,
        },
      });
    }

    return json({ error: "not found" }, 404);
  },
};
