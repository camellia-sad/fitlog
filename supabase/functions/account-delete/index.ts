// Deploy as account-delete. The caller is verified by Auth; never trust a body-supplied user ID.
Deno.serve(async (request: Request) => {
  const reply = (status: number, value: unknown) => Response.json(value, { status });
  if (request.method !== "POST") return reply(405, { message: "仅支持 POST" });
  const authorization = request.headers.get("Authorization") ?? "";
  if (!authorization.startsWith("Bearer ")) return reply(401, { message: "请先登录" });
  const url = Deno.env.get("SUPABASE_URL");
  const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !key) return reply(503, { message: "账号注销服务尚未配置" });
  try {
    const body = await request.json();
    if (body?.confirmation !== "DELETE") return reply(400, { message: "请确认注销账号" });
  } catch {
    return reply(400, { message: "请求无效" });
  }
  try {
    const identity = await fetch(url + "/auth/v1/user", { headers: { apikey: key, Authorization: authorization } });
    if (!identity.ok) return reply(401, { message: "登录已失效，请重新登录后注销" });
    const user = await identity.json();
    const owner = user.id;
    if (typeof owner !== "string" || !/^[0-9a-f-]{36}$/i.test(owner)) throw new Error("Invalid identity");
    const headers = { apikey: key, Authorization: "Bearer " + key, "Content-Type": "application/json" };
    async function call(path: string, method: string, payload: unknown) {
      const response = await fetch(url + path, { method, headers, body: JSON.stringify(payload) });
      if (!response.ok) throw new Error("Deletion step failed");
      return response;
    }
    await call("/rest/v1/rpc/account_delete_begin", "POST", { p_owner: owner });
    const assets: { bucket_id: string; name: string }[] =
      await (await call("/rest/v1/rpc/account_delete_assets", "POST", { p_owner: owner })).json();
    if (!Array.isArray(assets) || assets.length > 100) throw new Error("Invalid batch");
    for (const asset of assets) {
      if (!["fitlog-media", "fitlog-backups"].includes(asset.bucket_id) ||
          !asset.name.startsWith(owner + "/") || asset.name.includes("..")) throw new Error("Unsafe asset");
    }
    for (const bucket of ["fitlog-media", "fitlog-backups"]) {
      const paths = assets.filter(asset => asset.bucket_id === bucket).map(asset => asset.name);
      if (paths.length) await call("/storage/v1/object/" + bucket, "DELETE", { prefixes: paths });
    }
    // Another invocation continues the bounded batch, also after a network interruption.
    if (assets.length) return reply(202, { done: false });
    try {
      await call("/auth/v1/admin/users/" + owner, "DELETE", { should_soft_delete: false });
    } catch (error) {
      // If the delete succeeded but its response was lost, verify before reporting failure.
      const check = await fetch(url + "/auth/v1/admin/users/" + owner, { headers });
      if (check.status !== 404) throw error;
    }
    // Identity deletion is complete; a leftover GC path contains no message/file content.
    // The regular maintenance job can remove that bookkeeping if this final step fails.
    try { await call("/rest/v1/rpc/account_delete_finish", "POST", { p_owner: owner }); } catch { /* retry by maintenance */ }
    return reply(200, { done: true });
  } catch {
    return reply(503, { message: "注销尚未完成，请保持联网后重试；已清理的云端内容无法恢复。" });
  }
});
