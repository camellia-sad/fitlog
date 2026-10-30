begin;
alter table public.social_profiles add constraint social_avatar_owner
 check(avatar_path is null or avatar_path like id::text||'/%');
create table public.social_messages (
 id uuid primary key,
 sender uuid not null references public.social_profiles(id) on delete cascade,
 recipient uuid not null references public.social_profiles(id) on delete cascade,
 body text not null default '' check(char_length(body)<=8000),
 media_path text,
 reply_id uuid,
 reply_preview text,
 forwarded jsonb not null default '[]' check(jsonb_typeof(forwarded)='array' and jsonb_array_length(forwarded)<=20),
 withdrawn boolean not null default false,
 created_at timestamptz not null default clock_timestamp(),
 check(sender<>recipient)
);
create index social_messages_thread on public.social_messages
 (least(sender,recipient),greatest(sender,recipient),created_at desc,id desc);
create index social_messages_inbox on public.social_messages(recipient,sender,created_at desc);
create table public.social_message_hides (
 owner_id uuid not null references public.social_profiles(id) on delete cascade,
 message_id uuid not null references public.social_messages(id) on delete cascade,
 primary key(owner_id,message_id)
);
create table public.social_read_marks (
 owner_id uuid not null references public.social_profiles(id) on delete cascade,
 peer_id uuid not null references public.social_profiles(id) on delete cascade,
 read_through timestamptz not null default 'epoch',
 cleared_through timestamptz not null default 'epoch',
 primary key(owner_id,peer_id)
);
create table public.social_posts (
 id uuid primary key,
 owner_id uuid not null references public.social_profiles(id) on delete cascade,
 body text not null default '' check(char_length(body)<=4000),
 media_paths text[] not null default '{}' check(cardinality(media_paths)<=9),
 deleted boolean not null default false,
 created_at timestamptz not null default clock_timestamp()
);
create index social_posts_timeline on public.social_posts(created_at desc,id desc) where not deleted;
create index social_posts_owner on public.social_posts(owner_id,created_at desc,id desc) where not deleted;
create table public.social_comments (
 id uuid primary key,
 post_id uuid not null references public.social_posts(id) on delete cascade,
 owner_id uuid not null references public.social_profiles(id) on delete cascade,
 body text not null check(char_length(body) between 1 and 1000),
 created_at timestamptz not null default clock_timestamp()
);
create index social_comments_post on public.social_comments(post_id,created_at desc,id desc);
create table public.social_likes (
 post_id uuid not null references public.social_posts(id) on delete cascade,
 owner_id uuid not null references public.social_profiles(id) on delete cascade,
 primary key(post_id,owner_id)
);
create table public.social_live (
 owner_id uuid not null references public.social_profiles(id) on delete cascade,
 device_id uuid not null,
 status text not null check(status in('online','training','rest','paused','offline')),
 snapshot jsonb not null default '{}',
 expires_at timestamptz not null,
 updated_at timestamptz not null default clock_timestamp(),
 primary key(owner_id,device_id)
);
create table public.social_training_history (
 id uuid primary key default gen_random_uuid(),
 owner_id uuid not null references public.social_profiles(id) on delete cascade,
 local_id text not null check(char_length(local_id)<=100),
 snapshot jsonb not null check(jsonb_typeof(snapshot)='object' and octet_length(snapshot::text)<=100000),
 started_at timestamptz not null,
 unique(owner_id,local_id)
);
create index social_training_owner on public.social_training_history(owner_id,started_at desc,id desc);

alter table public.social_messages enable row level security;
alter table public.social_message_hides enable row level security;
alter table public.social_read_marks enable row level security;
alter table public.social_posts enable row level security;
alter table public.social_comments enable row level security;
alter table public.social_likes enable row level security;
alter table public.social_live enable row level security;
alter table public.social_training_history enable row level security;
-- These tables are accessible only through the bounded, authenticated RPCs below.
revoke all on public.social_messages,public.social_message_hides,public.social_read_marks,
 public.social_posts,public.social_comments,public.social_likes,public.social_live,
 public.social_training_history from public,anon,authenticated;

create function fitlog_private.message_visible(m public.social_messages, viewer uuid) returns boolean
language sql stable security definer set search_path='' as $$
 select viewer is not null and viewer in(m.sender,m.recipient)
 and not exists(select 1 from public.social_message_hides where owner_id=viewer and message_id=m.id)
 and m.created_at>coalesce((select cleared_through from public.social_read_marks
 where owner_id=viewer and peer_id=case when m.sender=viewer then m.recipient else m.sender end),'epoch');
$$;
create function fitlog_private.post_visible(p public.social_posts,viewer uuid) returns boolean
language sql stable security definer set search_path='' as $$
 select viewer is not null and not p.deleted and
 (p.owner_id=viewer or fitlog_private.are_friends(viewer,p.owner_id));
$$;

create function public.social_chat(
 p_action text,p_peer uuid,p_id uuid default null,p_body text default '',
 p_media text default null,p_reply uuid default null,p_ids uuid[] default '{}',
 p_before timestamptz default null,p_before_id uuid default null
) returns jsonb language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid(); source public.social_messages; result jsonb;
 preview text; forward_data jsonb:='[]'; row_id uuid;
begin
 if me is null or p_peer is null or me=p_peer then raise exception '请先登录并选择好友'; end if;
 if p_action='list' then
   select coalesce(jsonb_agg(to_jsonb(x) order by x.created_at desc,x.id desc),'[]') into result from (
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

create function public.social_feed(
 p_action text,p_id uuid default null,p_body text default '',p_media text[] default '{}',
 p_peer uuid default null,p_before timestamptz default null,p_before_id uuid default null,
 p_comment_id uuid default null
) returns jsonb language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid(); post public.social_posts; result jsonb; c public.social_comments;
begin
 if me is null then raise exception '请先登录'; end if;
 if p_action='list' then
   select coalesce(jsonb_agg(to_jsonb(x) order by x.created_at desc,x.id desc),'[]') into result from(
    select p.*,u.nickname,u.avatar_path,
      (select count(*) from public.social_likes where post_id=p.id) like_count,
      exists(select 1 from public.social_likes where post_id=p.id and owner_id=me) liked,
      (select count(*) from public.social_comments where post_id=p.id) comment_count
    from public.social_posts p join public.social_profiles u on u.id=p.owner_id
    where fitlog_private.post_visible(p,me) and (p_peer is null or p.owner_id=p_peer)
      and (p_before is null or (p.created_at,p.id)<(p_before,coalesce(p_before_id,'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid)))
    order by p.created_at desc,p.id desc limit 20
   ) x; return result;
 elsif p_action='publish' then
   if p_id is null or char_length(p_body)>4000 or cardinality(p_media)>9 or (trim(p_body)='' and cardinality(p_media)=0)
      or exists(select 1 from unnest(p_media) path where path not like me::text||'/%' or char_length(path)>200)
      then raise exception '动态须有文字或图片，最多 4000 字、9 张图'; end if;
   insert into public.social_posts(id,owner_id,body,media_paths) values(p_id,me,p_body,p_media)
     on conflict(id) do nothing;
   if not exists(select 1 from public.social_posts where id=p_id and owner_id=me) then raise exception '动态标识已存在'; end if;
   return '{}';
 end if;
 select * into post from public.social_posts p where id=p_id and fitlog_private.post_visible(p,me);
 if post.id is null then raise exception '动态不可访问或已删除'; end if;
 case p_action
 when 'delete' then
   if post.owner_id<>me then raise exception '只能删除自己的动态'; end if;
   update public.social_posts set deleted=true where id=p_id;
 when 'like' then insert into public.social_likes values(p_id,me) on conflict do nothing;
 when 'unlike' then delete from public.social_likes where post_id=p_id and owner_id=me;
 when 'comment' then
   if char_length(trim(p_body)) not between 1 and 1000 or p_comment_id is null then raise exception '评论为 1～1000 字'; end if;
   insert into public.social_comments(id,post_id,owner_id,body) values(p_comment_id,p_id,me,trim(p_body)) on conflict(id) do nothing;
   if not exists(select 1 from public.social_comments where id=p_comment_id and owner_id=me and post_id=p_id)
     then raise exception '评论标识已存在'; end if;
 when 'delete_comment' then
   delete from public.social_comments where id=p_comment_id and post_id=p_id and (owner_id=me or post.owner_id=me);
 when 'comments' then
   select coalesce(jsonb_agg(to_jsonb(x) order by x.created_at desc,x.id desc),'[]') into result from(
    select c.*,u.nickname,u.avatar_path from public.social_comments c join public.social_profiles u on u.id=c.owner_id
    where c.post_id=p_id and (p_before is null or (c.created_at,c.id)<(p_before,coalesce(p_before_id,'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid)))
    order by c.created_at desc,c.id desc limit 50
   ) x; return result;
 else raise exception '未知的动态操作';
 end case;
 return '{}';
end;
$$;

create function public.social_training(
 p_action text,p_peer uuid default null,p_local_id text default null,p_snapshot jsonb default '{}',
 p_device uuid default null,p_status text default 'online',p_before timestamptz default null,p_before_id uuid default null
) returns jsonb language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid(); result jsonb; enabled boolean;
begin
 if me is null then raise exception '请先登录'; end if;
 if octet_length(p_snapshot::text)>100000 or jsonb_typeof(p_snapshot)<>'object' then raise exception '训练摘要过大'; end if;
 if p_action='heartbeat' then
   if p_device is null or p_status not in('online','training','rest','paused','offline') then raise exception '无效的在线状态'; end if;
   select share_live into enabled from public.social_privacy where owner_id=me;
   insert into public.social_live(owner_id,device_id,status,snapshot,expires_at)
    values(me,p_device,case when enabled then p_status when p_status='offline' then 'offline' else 'online' end,
      case when enabled then p_snapshot else '{}' end,
      clock_timestamp()+case when p_status='offline' then interval '0 seconds' else interval '90 seconds' end)
    on conflict(owner_id,device_id) do update set status=excluded.status,snapshot=excluded.snapshot,
      expires_at=excluded.expires_at,updated_at=clock_timestamp();
   return '{}';
 elsif p_action='upload' then
   select share_history into enabled from public.social_privacy where owner_id=me;
   if not coalesce(enabled,false) then raise exception '请先开启训练历史共享'; end if;
   if p_local_id is null or char_length(p_local_id)>100 or p_snapshot->>'started_at' is null then raise exception '无效的训练记录'; end if;
   insert into public.social_training_history(owner_id,local_id,snapshot,started_at)
    values(me,p_local_id,p_snapshot,(p_snapshot->>'started_at')::timestamptz)
    on conflict(owner_id,local_id) do update set snapshot=excluded.snapshot,started_at=excluded.started_at;
   return '{}';
 elsif p_action='history' then
   if p_peer is null or (p_peer<>me and not fitlog_private.are_friends(me,p_peer)) then raise exception '训练历史不可访问'; end if;
   select share_history into enabled from public.social_privacy where owner_id=p_peer;
   if p_peer<>me and not coalesce(enabled,false) then return '[]'; end if;
   select coalesce(jsonb_agg(to_jsonb(x) order by x.started_at desc,x.id desc),'[]') into result from(
     select * from public.social_training_history where owner_id=p_peer
     and(p_before is null or(started_at,id)<(p_before,coalesce(p_before_id,'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid)))
     order by started_at desc,id desc limit 20
   ) x; return result;
 else raise exception '未知的训练共享操作';
 end if;
end;
$$;

create function public.social_person(p_peer uuid) returns jsonb
language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid(); result jsonb; live public.social_live;
begin
 if me is null or (me<>p_peer and not fitlog_private.are_friends(me,p_peer)) then raise exception '主页不可访问'; end if;
 select to_jsonb(p)||jsonb_build_object('share_history',s.share_history,'share_live',s.share_live)
 into result from public.social_profiles p join public.social_privacy s on s.owner_id=p.id where p.id=p_peer;
 select * into live from public.social_live where owner_id=p_peer and expires_at>clock_timestamp() and status<>'offline'
 order by case when status in('training','rest','paused') then 0 else 1 end,updated_at desc limit 1;
 return result||jsonb_build_object('status',case when live.status is null then 'offline'
 when (result->>'share_live')::boolean then live.status else 'online' end,
 'live',case when (result->>'share_live')::boolean then coalesce(live.snapshot,'{}') else '{}' end,
 'updated_at',live.updated_at);
end;
$$;

create or replace function public.list_fitlog_friends() returns jsonb
language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid();
begin
 if me is null then raise exception '请先登录'; end if;
 return coalesce((select jsonb_agg(jsonb_build_object(
  'id',p.id,'fitlog_id',p.fitlog_id,'nickname',p.nickname,'avatar_path',p.avatar_path,'bio',p.bio,
  'note',coalesce(n.note,''),'status',f.status,'incoming',f.recipient=me,
  'presence',case when l.status is null then 'offline' when s.share_live then l.status else 'online' end,'presence_updated_at',l.updated_at,
  'last_message',last.body,'last_message_at',last.created_at,
  'unread',(select count(*) from public.social_messages m where m.sender=p.id and m.recipient=me
    and not withdrawn and fitlog_private.message_visible(m,me)
    and m.created_at>coalesce(r.read_through,'epoch'))
 ) order by coalesce(last.created_at,f.created_at) desc) from public.friendships f
 join public.social_profiles p on p.id=case when f.requester=me then f.recipient else f.requester end
 left join public.social_privacy s on s.owner_id=p.id
 left join public.friend_notes n on n.owner_id=me and n.peer_id=p.id
 left join public.social_read_marks r on r.owner_id=me and r.peer_id=p.id
 left join lateral (select status,updated_at from public.social_live where owner_id=p.id
    and expires_at>clock_timestamp() and status<>'offline'
    order by case when status in('training','rest','paused') then 0 else 1 end,updated_at desc limit 1) l on true
 left join lateral (select case when withdrawn then '[已撤回]' when body='' then '[图片]' else left(body,100) end body,created_at
    from public.social_messages m where ((sender=me and recipient=p.id) or(sender=p.id and recipient=me))
    and fitlog_private.message_visible(m,me) order by created_at desc,id desc limit 1) last on true
 where (f.requester=me or f.recipient=me) and not fitlog_private.blocked(me,p.id)), '[]');
end;
$$;

create function public.social_blocks() returns jsonb language plpgsql security definer set search_path='' as $$
begin
 if auth.uid() is null then raise exception '请先登录'; end if;
 return coalesce((select jsonb_agg(to_jsonb(x)) from(
  select p.id,p.fitlog_id,p.nickname,p.bio,p.avatar_path from public.friend_blocks b
  join public.social_profiles p on p.id=b.peer_id where b.owner_id=auth.uid()
 ) x),'[]');
end;
$$;

create function fitlog_private.media_visible(path text,viewer uuid) returns boolean
language sql stable security definer set search_path='' as $$
 select viewer is not null and (
 path like viewer::text||'/%'
 or exists(select 1 from public.social_profiles p where p.avatar_path=path and fitlog_private.are_friends(viewer,p.id))
 or exists(select 1 from public.social_posts p where path=any(p.media_paths) and fitlog_private.post_visible(p,viewer))
 or exists(select 1 from public.social_messages m where not m.withdrawn and fitlog_private.message_visible(m,viewer)
    and (m.media_path=path or exists(select 1 from jsonb_array_elements(m.forwarded) e where e->>'media_path'=path)))
 );
$$;
revoke all on function fitlog_private.message_visible(public.social_messages,uuid),
 fitlog_private.post_visible(public.social_posts,uuid),fitlog_private.media_visible(text,uuid) from public;
grant execute on function fitlog_private.media_visible(text,uuid) to authenticated;

insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
 values('fitlog-media','fitlog-media',false,2097152,array['image/jpeg','image/png','image/webp'])
 on conflict(id) do nothing;
create policy fitlog_media_insert on storage.objects for insert to authenticated
 with check(bucket_id='fitlog-media' and (storage.foldername(name))[1]=auth.uid()::text);
create policy fitlog_media_read on storage.objects for select to authenticated
 using(bucket_id='fitlog-media' and fitlog_private.media_visible(name,auth.uid()));
create policy fitlog_media_delete on storage.objects for delete to authenticated
 using(bucket_id='fitlog-media' and (storage.foldername(name))[1]=auth.uid()::text
 and not exists(select 1 from public.social_profiles where avatar_path=name));

revoke all on function public.social_chat(text,uuid,uuid,text,text,uuid,uuid[],timestamptz,uuid),
 public.social_feed(text,uuid,text,text[],uuid,timestamptz,uuid,uuid),
 public.social_training(text,uuid,text,jsonb,uuid,text,timestamptz,uuid),
 public.social_person(uuid),public.social_blocks() from public,anon;
grant execute on function public.social_chat(text,uuid,uuid,text,text,uuid,uuid[],timestamptz,uuid),
 public.social_feed(text,uuid,text,text[],uuid,timestamptz,uuid,uuid),
 public.social_training(text,uuid,text,jsonb,uuid,text,timestamptz,uuid),
 public.social_person(uuid),public.social_blocks() to authenticated;
commit;
