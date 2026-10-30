-- Quote previews are resolved for the viewer; deleted/withdrawn sources no longer expose their text.
create function fitlog_private.chat_json(m public.social_messages,viewer uuid) returns jsonb
language sql stable security definer set search_path='' as $$
 select to_jsonb(m)||jsonb_build_object('reply_preview',
   case when m.reply_id is null then null
   when exists(select 1 from public.social_messages s where s.id=m.reply_id and not s.withdrawn
     and fitlog_private.message_visible(s,viewer)) then m.reply_preview
   else '[原消息不可用]' end);
$$;
revoke all on function fitlog_private.chat_json(public.social_messages,uuid) from public,anon,authenticated;

create function public.social_chat_context(p_peer uuid,p_id uuid) returns jsonb
language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid(); source public.social_messages; result jsonb;
begin
 if me is null or p_peer is null then raise exception '请先登录'; end if;
 select * into source from public.social_messages m where m.id=p_id
 and ((m.sender=me and m.recipient=p_peer) or(m.sender=p_peer and m.recipient=me))
 and fitlog_private.message_visible(m,me);
 if source.id is null then raise exception '原消息已删除或不可访问'; end if;
 select coalesce(jsonb_agg(fitlog_private.chat_json(x,me) order by x.created_at desc,x.id desc),'[]') into result from(
   (select m.* from public.social_messages m
    where ((m.sender=me and m.recipient=p_peer) or(m.sender=p_peer and m.recipient=me))
    and fitlog_private.message_visible(m,me) and (m.created_at,m.id)<=(source.created_at,source.id)
    order by m.created_at desc,m.id desc limit 26)
   union all
   (select m.* from public.social_messages m
    where ((m.sender=me and m.recipient=p_peer) or(m.sender=p_peer and m.recipient=me))
    and fitlog_private.message_visible(m,me) and (m.created_at,m.id)>(source.created_at,source.id)
    order by m.created_at,m.id limit 24)
 ) x;
 return result;
end;
$$;
revoke all on function public.social_chat_context(uuid,uuid) from public,anon;
grant execute on function public.social_chat_context(uuid,uuid) to authenticated;
create or replace function public.social_chat(
 p_action text,p_peer uuid,p_id uuid default null,p_body text default '',
 p_media text default null,p_reply uuid default null,p_ids uuid[] default '{}',
 p_before timestamptz default null,p_before_id uuid default null
) returns jsonb language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid(); source public.social_messages; result jsonb;
 preview text; forward_data jsonb:='[]'; row_id uuid;
begin
 if me is null or p_peer is null or me=p_peer then raise exception '请先登录并选择好友'; end if;
 if p_action='list' then
   select coalesce(jsonb_agg(fitlog_private.chat_json(x,me) order by x.created_at desc,x.id desc),'[]') into result from (
    select m.* from public.social_messages m
    where ((sender=me and recipient=p_peer) or(sender=p_peer and recipient=me))
    and fitlog_private.message_visible(m,me)
    and (p_before is null or (m.created_at,m.id)<(p_before,coalesce(p_before_id,'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid)))
    order by m.created_at desc,m.id desc limit 50
   ) x;
   return result;
 elsif p_action='mark_read' then
   insert into public.social_read_marks(owner_id,peer_id,read_through)
   select me,p_peer,coalesce(max(created_at),'epoch') from public.social_messages m
   where sender=p_peer and recipient=me and fitlog_private.message_visible(m,me)
   on conflict(owner_id,peer_id) do update set read_through=greatest(public.social_read_marks.read_through,excluded.read_through);
   return '{}';
 elsif p_action='clear' then
   insert into public.social_read_marks(owner_id,peer_id,cleared_through) values(me,p_peer,clock_timestamp())
   on conflict(owner_id,peer_id) do update set cleared_through=excluded.cleared_through;
   return '{}';
 elsif p_action in('delete','withdraw') then
   select * into source from public.social_messages m where id=p_id
   and ((sender=me and recipient=p_peer) or(sender=p_peer and recipient=me))
   and fitlog_private.message_visible(m,me);
   if source.id is null then raise exception '消息不存在或已删除'; end if;
   if p_action='delete' then
     insert into public.social_message_hides values(me,p_id) on conflict do nothing;
   else
     if source.sender<>me then raise exception '只能撤回自己发送的消息'; end if;
     update public.social_messages set body='',media_path=null,forwarded='[]',reply_preview=null,withdrawn=true where id=p_id;
   end if;
   return '{}';
 elsif p_action in('send','forward') then
   if not fitlog_private.are_friends(me,p_peer) then raise exception '需要先成为好友才能发送消息'; end if;
   if p_id is null then raise exception '缺少消息标识'; end if;
   select * into source from public.social_messages where id=p_id;
   if source.id is not null then
     if source.sender=me and source.recipient=p_peer then return to_jsonb(source); end if;
     raise exception '消息标识已存在';
   end if;
   if p_action='forward' then
     if cardinality(p_ids) not between 1 and 20 then raise exception '一次转发 1～20 条消息'; end if;
     foreach row_id in array p_ids loop
       select * into source from public.social_messages m where id=row_id
         and not withdrawn and fitlog_private.message_visible(m,me);
       if source.id is null then raise exception '转发的消息不可访问'; end if;
       forward_data:=forward_data||jsonb_build_array(jsonb_build_object(
         'sender',coalesce((select nickname from public.social_profiles where id=source.sender),'用户'),
         'body',source.body,'media_path',source.media_path,'created_at',source.created_at,'forwarded',source.forwarded));
     end loop;
     if cardinality(p_ids)=1 then
       insert into public.social_messages(id,sender,recipient,body,media_path,forwarded)
       values(p_id,me,p_peer,source.body,source.media_path,source.forwarded) returning * into source;
     else
       -- Nested forwarded bundles are flattened to text at one level to bound payload growth.
       forward_data:=(select jsonb_agg(e-'forwarded') from jsonb_array_elements(forward_data) e);
       insert into public.social_messages(id,sender,recipient,body,forwarded)
       values(p_id,me,p_peer,'聊天记录',forward_data) returning * into source;
     end if;
   else
     if char_length(p_body)>8000 or (trim(p_body)='' and p_media is null) then raise exception '消息不能为空，最多 8000 字'; end if;
     if p_media is not null and (p_media not like me::text||'/%' or char_length(p_media)>200) then raise exception '无效的图片'; end if;
     if p_reply is not null then
       select * into source from public.social_messages m where id=p_reply
         and ((sender=me and recipient=p_peer) or(sender=p_peer and recipient=me))
         and not withdrawn and fitlog_private.message_visible(m,me);
       if source.id is null then raise exception '引用的消息不可访问'; end if;
       preview:=left(coalesce(nullif(source.body,''),'[图片]'),200);
     end if;
     insert into public.social_messages(id,sender,recipient,body,media_path,reply_id,reply_preview)
       values(p_id,me,p_peer,p_body,p_media,p_reply,preview) returning * into source;
   end if;
   return to_jsonb(source);
 else raise exception '未知的聊天操作';
 end if;
end;
$$;
