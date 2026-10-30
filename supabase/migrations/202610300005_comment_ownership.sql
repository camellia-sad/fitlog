create or replace function public.social_feed(
 p_action text,p_id uuid default null,p_body text default '',p_media text[] default '{}',
 p_peer uuid default null,p_before timestamptz default null,p_before_id uuid default null,
 p_comment_id uuid default null
) returns jsonb language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid(); post public.social_posts; result jsonb;
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
   delete from public.social_comments where id=p_comment_id and post_id=p_id and owner_id=me;
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
