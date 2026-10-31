begin;
-- Existing rows are retained until both participants save a local copy.
alter table public.social_profiles add column chat_local boolean not null default false;
alter table public.social_messages add column delivery_expires_at timestamptz;
create table public.chat_local_ack(
 owner_id uuid not null references public.social_profiles(id) on delete cascade,
 message_id uuid not null references public.social_messages(id) on delete cascade,
 primary key(owner_id,message_id));
create table public.chat_delivery_receipts(
 id uuid primary key, sender uuid not null references public.social_profiles(id) on delete cascade,
 recipient uuid not null references public.social_profiles(id) on delete cascade,
 created_at timestamptz not null, expires_at timestamptz not null);
create table public.chat_media_gc(path text primary key, queued_at timestamptz not null default now());
alter table public.chat_local_ack enable row level security;
alter table public.chat_delivery_receipts enable row level security;
alter table public.chat_media_gc enable row level security;
revoke all on public.chat_local_ack,public.chat_delivery_receipts,public.chat_media_gc from public,anon,authenticated;
-- Enabling local delivery is only available through authenticated RPC, not profile PATCH.
revoke update on public.social_profiles from authenticated;
grant update(nickname,bio,avatar_path) on public.social_profiles to authenticated;

create function fitlog_private.enqueue_chat_media() returns trigger
language plpgsql security definer set search_path='' as $$
begin
 insert into public.chat_media_gc(path)
 select distinct x from (
  select old.media_path x union all select e->>'media_path' from jsonb_array_elements(old.forwarded) e
 ) q where x is not null on conflict do nothing;
 return old;
end $$;
create trigger chat_deleted_media after delete on public.social_messages
for each row execute function fitlog_private.enqueue_chat_media();
revoke all on function fitlog_private.enqueue_chat_media() from public,anon,authenticated;

-- Freeze legacy sends after either endpoint upgrades; avoid silent delivery to an incompatible client.
alter function public.social_chat(text,uuid,uuid,text,text,uuid,uuid[],timestamptz,uuid) rename to social_chat_legacy;
revoke all on function public.social_chat_legacy(text,uuid,uuid,text,text,uuid,uuid[],timestamptz,uuid) from public,anon,authenticated;
create function public.social_chat(
 p_action text,p_peer uuid,p_id uuid default null,p_body text default '',p_media text default null,
 p_reply uuid default null,p_ids uuid[] default '{}',p_before timestamptz default null,p_before_id uuid default null
) returns jsonb language plpgsql security definer set search_path='' as $$
begin
 if auth.uid() is null then raise exception '请先登录'; end if;
 if p_action in('send','forward') and exists(select 1 from public.social_profiles
 where id in(auth.uid(),p_peer) and chat_local) then raise exception '聊天已改为本地保存，请双方更新 FitLog 后重试'; end if;
 return public.social_chat_legacy(p_action,p_peer,p_id,p_body,p_media,p_reply,p_ids,p_before,p_before_id);
end $$;
revoke all on function public.social_chat(text,uuid,uuid,text,text,uuid,uuid[],timestamptz,uuid) from public,anon;
grant execute on function public.social_chat(text,uuid,uuid,text,text,uuid,uuid[],timestamptz,uuid) to authenticated;

create function public.social_chat_relay(p_action text,p_peer uuid default null,p_id uuid default null,
 p_payload jsonb default '{}') returns jsonb language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid(); m public.social_messages; receipt public.chat_delivery_receipts; result jsonb; item jsonb;
begin
 if me is null then raise exception '请先登录'; end if;
 if p_action='enable' then
  update public.social_profiles set chat_local=true where id=me;
  return '{}';
 elsif p_action='pull' then
  select coalesce(jsonb_agg(fitlog_private.chat_json(x,me) order by x.created_at,x.id),'[]') into result from (
   select msg.* from public.social_messages msg where me in(msg.sender,msg.recipient)
   and (msg.delivery_expires_at is null or msg.delivery_expires_at>now())
   and fitlog_private.message_visible(msg,me)
   and not exists(select 1 from public.chat_local_ack a where a.owner_id=me and a.message_id=msg.id)
   order by msg.created_at,msg.id limit 50
  ) x;
  return result;
 elsif p_action='ack' then
  select * into m from public.social_messages s where s.id=p_id and me in(s.sender,s.recipient);
  if m.id is null then return '{}'; end if;
  insert into public.chat_local_ack values(me,p_id) on conflict do nothing;
  if (select count(*) from public.chat_local_ack a where a.message_id=p_id and a.owner_id in(m.sender,m.recipient))=2 then
   delete from public.social_messages where id=p_id;
  end if;
  return '{}';
 elsif p_action='send' then
  if p_peer is null or not fitlog_private.are_friends(me,p_peer) then raise exception '请先成为好友'; end if;
  if not exists(select 1 from public.social_profiles where id=p_peer and chat_local) then
   raise exception '好友需要更新 FitLog 并打开好友页，才能接收本地聊天'; end if;
  if p_id is null then raise exception '消息标识缺失'; end if;
  select * into receipt from public.chat_delivery_receipts where id=p_id;
  if receipt.id is not null then
   if receipt.sender<>me or receipt.recipient<>p_peer then raise exception '消息标识冲突'; end if;
   return jsonb_build_object('created_at',receipt.created_at);
  end if;
  if octet_length(p_payload::text)>250000 or char_length(coalesce(p_payload->>'body',''))>8000
   or jsonb_typeof(coalesce(p_payload->'forwarded','[]'))<>'array'
   or jsonb_array_length(coalesce(p_payload->'forwarded','[]'))>20
   or (trim(coalesce(p_payload->>'body',''))='' and p_payload->>'media_path' is null)
   or char_length(coalesce(p_payload->>'reply_preview',''))>200 then raise exception '消息内容无效'; end if;
  if p_payload->>'media_path' is not null and
   (p_payload->>'media_path' not like me::text||'/chat/%' or char_length(p_payload->>'media_path')>200)
   then raise exception '聊天图片归属无效'; end if;
  for item in select * from jsonb_array_elements(coalesce(p_payload->'forwarded','[]')) loop
   if char_length(coalesce(item->>'body',''))>8000 or char_length(coalesce(item->>'sender',''))>100
    or (item->>'media_path' is not null and (item->>'media_path' not like me::text||'/chat/%' or char_length(item->>'media_path')>200))
    then raise exception '转发内容无效'; end if;
  end loop;
  insert into public.chat_delivery_receipts values(p_id,me,p_peer,clock_timestamp(),now()+interval '7 days')
  returning * into receipt;
  insert into public.social_messages(id,sender,recipient,body,media_path,reply_id,reply_preview,forwarded,created_at,delivery_expires_at)
   values(p_id,me,p_peer,coalesce(p_payload->>'body',''),p_payload->>'media_path',
    (p_payload->>'reply_id')::uuid,p_payload->>'reply_preview',coalesce(p_payload->'forwarded','[]'),receipt.created_at,receipt.expires_at);
  return jsonb_build_object('created_at',receipt.created_at);
 elsif p_action='withdraw' then
  select * into receipt from public.chat_delivery_receipts where id=p_id and sender=me and expires_at>now();
  if receipt.id is null then raise exception '仅支持撤回七天内的新消息'; end if;
  delete from public.social_messages where id=p_id;
  insert into public.social_messages(id,sender,recipient,body,withdrawn,created_at,delivery_expires_at)
   values(p_id,me,receipt.recipient,'',true,receipt.created_at,now()+interval '7 days');
  insert into public.chat_local_ack values(me,p_id);
  return '{}';
 else raise exception '未知的本地聊天操作';
 end if;
end $$;
revoke all on function public.social_chat_relay(text,uuid,uuid,jsonb) from public,anon;
grant execute on function public.social_chat_relay(text,uuid,uuid,jsonb) to authenticated;

-- Called by a service-role maintenance job; no message body or file content in receipts.
create function public.chat_relay_cleanup() returns jsonb
language plpgsql security definer set search_path='' as $$
begin
 delete from public.social_messages where delivery_expires_at<now();
 delete from public.chat_delivery_receipts where expires_at<now();
 insert into public.chat_media_gc(path) select name from storage.objects
 where bucket_id='fitlog-media' and name like '%/chat/%' and created_at<now()-interval '7 days'
 on conflict do nothing;
 return coalesce((select jsonb_agg(path) from (
  select g.path from public.chat_media_gc g where
   not exists(select 1 from public.social_profiles p where p.avatar_path=g.path)
   and not exists(select 1 from public.social_posts p where not p.deleted and g.path=any(p.media_paths))
   and not exists(select 1 from public.social_messages m where m.media_path=g.path or
     exists(select 1 from jsonb_array_elements(m.forwarded) e where e->>'media_path'=g.path))
  order by g.queued_at limit 100
 ) q),'[]');
end $$;
create function public.chat_relay_gc_done(p_paths text[]) returns void
language sql security definer set search_path='' as $$
 delete from public.chat_media_gc where path=any(p_paths);
$$;
revoke all on function public.chat_relay_cleanup(),public.chat_relay_gc_done(text[]) from public,anon,authenticated;
grant execute on function public.chat_relay_cleanup(),public.chat_relay_gc_done(text[]) to service_role;

insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types) values
 ('fitlog-backups','fitlog-backups',false,52428800,array['application/zip']),
 ('fitlog-updates','fitlog-updates',true,52428800,array['application/json','application/vnd.android.package-archive','application/octet-stream'])
 on conflict(id) do nothing;
create policy fitlog_backup_owner on storage.objects for all to authenticated
 using(bucket_id='fitlog-backups' and (storage.foldername(name))[1]=auth.uid()::text)
 with check(bucket_id='fitlog-backups' and (storage.foldername(name))[1]=auth.uid()::text);
-- No client write policies for the public update bucket: only the project owner publishes releases.
commit;
