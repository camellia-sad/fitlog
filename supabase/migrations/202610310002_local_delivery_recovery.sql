begin;
-- Treat legacy user-deleted history as already discarded by that participant.
alter function public.social_chat_relay(text,uuid,uuid,jsonb) rename to social_chat_relay_v1;
revoke all on function public.social_chat_relay_v1(text,uuid,uuid,jsonb) from public,anon,authenticated;
create function public.social_chat_relay(p_action text,p_peer uuid default null,p_id uuid default null,
 p_payload jsonb default '{}') returns jsonb language plpgsql security definer set search_path='' as $$
declare result jsonb;
begin
 if auth.uid() is null then raise exception '请先登录'; end if;
 if p_action='pull' then
  select coalesce(jsonb_agg(fitlog_private.chat_json(x,auth.uid()) order by x.created_at,x.id),'[]') into result from (
   select msg.* from public.social_messages msg where auth.uid() in(msg.sender,msg.recipient)
   and (msg.delivery_expires_at is null or msg.delivery_expires_at>now())
   and fitlog_private.message_visible(msg,auth.uid())
   and not exists(select 1 from public.chat_local_ack a where a.owner_id=auth.uid() and a.message_id=msg.id)
   and (p_payload->>'after_time' is null or (msg.created_at,msg.id)>
     ((p_payload->>'after_time')::timestamptz,(p_payload->>'after_id')::uuid))
   order by msg.created_at,msg.id limit 50
  ) x;
  return result;
 end if;
 result:=public.social_chat_relay_v1(p_action,p_peer,p_id,p_payload);
 if p_action='ack' then
  delete from public.social_messages msg where msg.id=p_id and auth.uid() in(msg.sender,msg.recipient)
   and not exists(select 1 from unnest(array[msg.sender,msg.recipient]) participant where
    fitlog_private.message_visible(msg,participant) and
    not exists(select 1 from public.chat_local_ack a where a.message_id=msg.id and a.owner_id=participant));
 end if;
 return result;
end $$;
revoke all on function public.social_chat_relay(text,uuid,uuid,jsonb) from public,anon;
grant execute on function public.social_chat_relay(text,uuid,uuid,jsonb) to authenticated;
commit;
