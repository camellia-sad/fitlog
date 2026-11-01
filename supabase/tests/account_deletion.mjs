// Node 22+ --experimental-strip-types. No real users, credentials or cloud calls.
import assert from "node:assert/strict";
let handler;
const owner="00000000-0000-0000-0000-000000000001";
globalThis.Deno={env:{get:key=>({SUPABASE_URL:"https://example.invalid",SUPABASE_SERVICE_ROLE_KEY:"server-only-test-key"})[key]},serve:value=>{handler=value}};
await import("../functions/account-delete/index.ts");
let calls=[],assets=[],failStorage=false,valid=true,failAuthDelete=false,lostDelete=false;
globalThis.fetch=async(url,options)=>{
 calls.push({url,options});
 if(url.endsWith("/auth/v1/user"))return valid?Response.json({id:owner}):new Response("",{status:401});
 if(url.endsWith("account_delete_assets"))return Response.json(assets);
 if(url.includes("/storage/")&&failStorage)return new Response("",{status:500});
 if(url.includes("/auth/v1/admin/users/")&&failAuthDelete)return new Response("",{status:500});
 if(url.includes("/auth/v1/admin/users/")&&lostDelete) {if(options.method==="DELETE")throw new Error("response lost");return new Response("",{status:404});}
 return Response.json({});
};
const invoke=(body={confirmation:"DELETE",user_id:"another-user"})=>handler(new Request("https://example.invalid",{
 method:"POST",headers:{Authorization:"Bearer caller-token","Content-Type":"application/json"},body:JSON.stringify(body)}));
assert.equal((await invoke({})).status,400);assert.equal(calls.length,0);
valid=false;assert.equal((await invoke()).status,401);assert.equal(calls.length,1);
valid=true;calls=[];assets=[{bucket_id:"fitlog-media",name:owner+"/chat/a.jpg"},{bucket_id:"fitlog-backups",name:owner+"/backup.zip"}];
let result=await invoke();assert.equal(result.status,202);assert.equal((await result.json()).done,false);
assert(!calls.some(x=>x.url.includes("/auth/v1/admin")));
assert.equal(JSON.parse(calls.find(x=>x.url.endsWith("account_delete_begin")).options.body).p_owner,owner);
assert.equal(calls.filter(x=>x.options.method==="DELETE").length,2);
failStorage=true;calls=[];assert.equal((await invoke()).status,503);
assert(!calls.some(x=>x.url.includes("/auth/v1/admin")));failStorage=false;
assets=[{bucket_id:"fitlog-media",name:"another-user/secret.jpg"}];calls=[];
assert.equal((await invoke()).status,503);assert(!calls.some(x=>x.options.method==="DELETE"));
assets=[];calls=[];failAuthDelete=true;
assert.equal((await invoke()).status,503);assert(!calls.some(x=>x.url.endsWith("account_delete_finish")));
failAuthDelete=false;calls=[];result=await invoke();assert.equal(result.status,200);assert.equal((await result.json()).done,true);
const deletion=calls.find(x=>x.url.endsWith("/auth/v1/admin/users/"+owner));
assert.equal(deletion.options.method,"DELETE");assert.equal(JSON.parse(deletion.options.body).should_soft_delete,false);
assert(calls.some(x=>x.url.endsWith("account_delete_finish")));
lostDelete=true;calls=[];result=await invoke();assert.equal(result.status,200);assert.equal((await result.json()).done,true);
console.log("Account deletion authentication, batching, failure and hard-delete tests passed");
