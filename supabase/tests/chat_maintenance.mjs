// Run with Node 22+ --experimental-strip-types; no credentials or live services.
import assert from "node:assert/strict";
let handler;
const env = { CHAT_MAINTENANCE_SECRET: "test-only-maintenance", SUPABASE_URL: "https://example.invalid", SUPABASE_SERVICE_ROLE_KEY: "test-only-role" };
globalThis.Deno = { env: { get: key => env[key] }, serve: value => { handler = value; } };
await import("../functions/chat-maintenance/index.ts");
let calls=[];
globalThis.fetch=async (url,options)=>{
 calls.push({url,options});
 if(url.endsWith("chat_relay_cleanup")) return Response.json(["owner/chat/image.jpg"]);
 return Response.json({});
};
const invoke=(key)=>handler(new Request("https://example.invalid",{method:"POST",headers:{"x-fitlog-maintenance":key}}));
assert.equal((await invoke("wrong")).status,401);
assert.equal(calls.length,0);
const valid=await invoke(env.CHAT_MAINTENANCE_SECRET);
assert.equal(valid.status,200);assert.equal((await valid.json()).removed,1);
assert.equal(calls.length,3);
assert(calls[1].url.endsWith("/storage/v1/object/fitlog-media"));
assert.equal(calls[1].options.method,"DELETE");
assert.deepEqual(JSON.parse(calls[1].options.body).prefixes,["owner/chat/image.jpg"]);
assert(calls[2].url.endsWith("chat_relay_gc_done"));
calls=[];
globalThis.fetch=async (url,options)=>{
 calls.push({url,options});
 if(url.endsWith("chat_relay_cleanup")) return Response.json(["owner/chat/image.jpg"]);
 return new Response("failure",{status:500});
};
assert.equal((await invoke(env.CHAT_MAINTENANCE_SECRET)).status,500);
assert.equal(calls.length,2); // Never mark a failed file removal as complete.
console.log("Chat maintenance authorization and retry tests passed");
