begin;
-- Preserve every remaining message, including old expired envelopes not yet cleaned.
-- Already deleted messages cannot be reconstructed by this migration.
update public.social_messages set delivery_expires_at=null where delivery_expires_at is not null;

-- Keep the existing authenticated wrapper, ACK recovery and seven-day withdrawal window.
-- New messages and withdrawal notices no longer expire before delivery.
create or replace function public.social_chat_relay_v1(p_action text,p_peer uuid default null,p_id uuid default null,
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
    (p_payload->>'reply_id')::uuid,p_payload->>'reply_preview',coalesce(p_payload->'forwarded','[]'),receipt.created_at,null);
  return jsonb_build_object('created_at',receipt.created_at);
 elsif p_action='withdraw' then
  select * into receipt from public.chat_delivery_receipts where id=p_id and sender=me and expires_at>now();
  if receipt.id is null then raise exception '仅支持撤回七天内的新消息'; end if;
  delete from public.social_messages where id=p_id;
  insert into public.social_messages(id,sender,recipient,body,withdrawn,created_at,delivery_expires_at)
   values(p_id,me,receipt.recipient,'',true,receipt.created_at,null);
  insert into public.chat_local_ack values(me,p_id);
  return '{}';
 else raise exception '未知的本地聊天操作';
 end if;
end $$;

create or replace function public.chat_relay_cleanup() returns jsonb
language plpgsql security definer set search_path='' as $$
begin
 -- Keep retry receipts while an undelivered message or withdrawal still exists.
 delete from public.chat_delivery_receipts r where r.expires_at<now()
 and not exists(select 1 from public.social_messages m where m.id=r.id);
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
-- Replacing functions retains their ACLs; explicitly keep maintenance service-only.
revoke all on function public.social_chat_relay_v1(text,uuid,uuid,jsonb) from public,anon,authenticated;
revoke all on function public.chat_relay_cleanup() from public,anon,authenticated;
grant execute on function public.chat_relay_cleanup() to service_role;
commit;
