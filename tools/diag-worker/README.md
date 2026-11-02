# 诊断上传服务

App「关于 → 导出诊断」把脱敏后的诊断文本（gzip）上传到这里，得到 8 位诊断码；开发者凭管理员令牌按码取回。

- `POST /upload`：公开，上限 2 MB，每个 IP 每 10 分钟最多 3 次（Durable Object 计数），返回 `{"code":"XXXXXXXX"}`
- `GET /d/<码>`：需要 `Authorization: Bearer <令牌>`，返回 gzip
- 数据存在 Workers KV，保留 14 天后自动清除

App 的上传地址不写在代码里，而是读 `endpoint.txt`（`main` 分支上的原始文件）。换域名只需改这个文件和 `wrangler.toml`，不用发版。

## 部署

需要 Cloudflare 账号，域名已托管在 Cloudflare，免费额度足够。

```powershell
cd tools/diag-worker
npm i -g wrangler
wrangler login
wrangler kv namespace create DIAG    # 把输出的 id 填进 wrangler.toml
wrangler deploy
wrangler secret put ADMIN_TOKEN      # 输入一串足够长的随机字符，自己保管
```

## 取回

```powershell
curl.exe -H "Authorization: Bearer <令牌>" https://diag.packeting.bid/d/<诊断码> -o diag.txt.gz
```

令牌只放在 Worker 的 secret 里，不要写进 App 或仓库。
