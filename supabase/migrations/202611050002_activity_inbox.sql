-- Minimal durable reply inbox; all reads re-check the current post permissions.
begin;
create table public.social_reply_notifications(
 id uuid primary key references public.social_comments(id) on delete cascade,
 recipient uuid not null references public.social_profiles(id) on delete cascade,
 read_at timestamptz
);
alter table public.social_reply_notifications enable row level security;
create index social_reply_inbox on public.social_reply_notifications(recipient,id) where read_at is null;
create function fitlog_private.notify_comment_reply() returns trigger
language plpgsql security definer set search_path='' as $$
declare recipient_id uuid;
begin
 if new.parent_id is not null then
   select coalesce(new.reply_to_owner_id,c.owner_id) into recipient_id from public.social_comments c where c.id=new.parent_id;
   if recipient_id<>new.owner_id then
     insert into public.social_reply_notifications(id,recipient) values(new.id,recipient_id) on conflict do nothing;
   end if;
 end if;
 return new;
end $$;
create trigger social_comment_notification after insert on public.social_comments
for each row execute function fitlog_private.notify_comment_reply();

create function fitlog_private.activity_comment(c public.social_comments) returns jsonb
language sql stable security definer set search_path='' as $$
 select to_jsonb(c)||jsonb_build_object('nickname',u.nickname,'fitlog_id',u.fitlog_id,'avatar_path',u.avatar_path,
 'reply_to_nickname',t.nickname,'reply_count',(select count(*) from public.social_comments r where r.parent_id=c.id))
 from public.social_profiles u left join public.social_profiles t on t.id=c.reply_to_owner_id where u.id=c.owner_id
$$;
create function public.social_activity(p_action text,p_id uuid default null,p_before timestamptz default null,
 p_before_id uuid default null,p_peer uuid default null,p_root uuid default null) returns jsonb
language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid(); result jsonb; source public.social_posts; target public.social_comments; root_comment public.social_comments;
begin
 if me is null then raise exception '请先登录'; end if;
 if p_action in ('inbox','count','read_all') then
   if p_action='count' then
     select to_jsonb(count(*)) into result from public.social_reply_notifications n
      join public.social_comments c on c.id=n.id join public.social_posts p on p.id=c.post_id
      where n.recipient=me and n.read_at is null and not c.deleted and fitlog_private.post_visible(p,me);
   elsif p_action='read_all' then
     update public.social_reply_notifications set read_at=now() where recipient=me and read_at is null;
     result:='{}';
   else
     select coalesce(jsonb_agg(x.j order by x.at desc,x.id desc),'[]') into result from(
      select c.created_at at,c.id,fitlog_private.activity_comment(c)||jsonb_build_object('post_id',p.id,'post_preview',left(p.body,100)) j
      from public.social_reply_notifications n join public.social_comments c on c.id=n.id join public.social_posts p on p.id=c.post_id
      where n.recipient=me and n.read_at is null and not c.deleted and fitlog_private.post_visible(p,me)
      and (p_before is null or (c.created_at,c.id)<(p_before,coalesce(p_before_id,'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid)))
      order by c.created_at desc,c.id desc limit 20
     ) x;
   end if;
   return result;
 elsif p_action='read' then
   update public.social_reply_notifications set read_at=coalesce(read_at,now()) where id=p_id and recipient=me;
   return '{}';
 elsif p_action='feed' then
   select coalesce(jsonb_agg(v.j||jsonb_build_object('fitlog_id',u.fitlog_id) order by v.ord),'[]') into result
   from jsonb_array_elements(public.social_feed('list',p_peer=>p_peer,p_before=>p_before,p_before_id=>p_before_id)) with ordinality v(j,ord)
   join public.social_profiles u on u.id=(v.j->>'owner_id')::uuid;
   return result;
 elsif p_action='context' then
   select * into target from public.social_comments where id=p_id and not deleted;
   select * into source from public.social_posts p where p.id=target.post_id and fitlog_private.post_visible(p,me);
   if source.id is null then raise exception '原动态或回复已删除或不可访问'; end if;
   select * into root_comment from public.social_comments where id=coalesce(target.parent_id,target.id);
   select to_jsonb(p)||jsonb_build_object('nickname',u.nickname,'avatar_path',u.avatar_path,'fitlog_id',u.fitlog_id,
     'liked',exists(select 1 from public.social_likes where post_id=p.id and owner_id=me),
     'like_count',(select count(*) from public.social_likes where post_id=p.id),
     'comment_count',(select count(*) from public.social_comments where post_id=p.id and not deleted))
   into result from public.social_posts p join public.social_profiles u on u.id=p.owner_id where p.id=source.id;
   -- Direct target lookup does not scan the feed or every older comment page.
   return jsonb_build_object('post',result,'root',fitlog_private.activity_comment(root_comment),
     'target',fitlog_private.activity_comment(target));
 elsif p_action in ('comments','replies') then
   select * into source from public.social_posts p where p.id=p_id and fitlog_private.post_visible(p,me);
   if source.id is null then raise exception '动态不可访问或已删除'; end if;
   if p_action='comments' then
     select coalesce(jsonb_agg(x.j order by x.at desc,x.id desc),'[]') into result from(
      select c.created_at at,c.id,fitlog_private.activity_comment(c) j from public.social_comments c
      where c.post_id=p_id and c.parent_id is null
      and (p_before is null or (c.created_at,c.id)<(p_before,coalesce(p_before_id,'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid)))
      order by c.created_at desc,c.id desc limit 50) x;
   else
     select coalesce(jsonb_agg(x.j order by x.at,x.id),'[]') into result from(
      select c.created_at at,c.id,fitlog_private.activity_comment(c) j from public.social_comments c
      where c.post_id=p_id and c.parent_id=p_root
      and (p_before is null or (c.created_at,c.id)>(p_before,coalesce(p_before_id,'00000000-0000-0000-0000-000000000000'::uuid)))
      order by c.created_at,c.id limit 50) x;
   end if;
   return result;
 end if;
 raise exception '未知动态操作';
end $$;
revoke all on function fitlog_private.activity_comment(public.social_comments) from public,anon,authenticated;
revoke all on function fitlog_private.notify_comment_reply() from public,anon,authenticated;
revoke all on function public.social_activity(text,uuid,timestamptz,uuid,uuid,uuid) from public,anon;
grant execute on function public.social_activity(text,uuid,timestamptz,uuid,uuid,uuid) to authenticated;
commit;
