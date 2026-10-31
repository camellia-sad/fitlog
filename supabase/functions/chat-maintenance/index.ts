// Deploy as chat-maintenance. Keep CHAT_MAINTENANCE_SECRET in Edge Function secrets and Vault.
Deno.serve(async (request: Request) => {
  const secret = Deno.env.get("CHAT_MAINTENANCE_SECRET");
  if (request.method !== "POST" || !secret || request.headers.get("x-fitlog-maintenance") !== secret) {
    return new Response("Unauthorized", { status: 401 });
  }
  const url = Deno.env.get("SUPABASE_URL")!;
  const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
  const headers = { apikey: key, Authorization: "Bearer " + key, "Content-Type": "application/json" };
  async function call(path: string, method: string, body: unknown) {
    const response = await fetch(url + path, { method, headers, body: JSON.stringify(body) });
    if (!response.ok) throw new Error("Maintenance request failed: " + response.status);
    return response;
  }
  try {
    let removed = 0;
    // Bounded batches; an interrupted run retries the remaining queue.
    for (let batch = 0; batch < 10; batch++) {
      const paths: string[] = await (await call("/rest/v1/rpc/chat_relay_cleanup", "POST", {})).json();
      if (!paths.length) break;
      await call("/storage/v1/object/fitlog-media", "DELETE", { prefixes: paths });
      await call("/rest/v1/rpc/chat_relay_gc_done", "POST", { p_paths: paths });
      removed += paths.length;
      if (paths.length < 100) break;
    }
    return Response.json({ removed });
  } catch {
    return new Response("Maintenance failed; next run will retry", { status: 500 });
  }
});
