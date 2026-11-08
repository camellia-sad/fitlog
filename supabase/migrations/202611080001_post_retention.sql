-- Posts are kept in the cloud, and visible, for 30 days. Older posts are removed with their comments, likes and
-- reply notices (all cascade), and their images are queued for the existing chat-maintenance job to delete.
-- Each user's own posts stay on their device; training history sharing is unchanged.
begin;

create or replace function fitlog_private.post_visible(p public.social_posts,viewer uuid) returns boolean
language sql stable security definer set search_path='' as $$
 select viewer is not null and not p.deleted and p.created_at>now()-interval '30 days' and
 (p.owner_id=viewer or fitlog_private.are_friends(viewer,p.owner_id));
$$;

create or replace function fitlog_private.purge_expired_posts() returns integer
language plpgsql security definer set search_path='' as $$
declare removed integer;
begin
 insert into public.chat_media_gc(path)
 select distinct m from public.social_posts p,unnest(p.media_paths) m
 where p.created_at<=now()-interval '30 days'
 on conflict do nothing;
 delete from public.social_posts where created_at<=now()-interval '30 days';
 get diagnostics removed=row_count;
 return removed;
end $$;
revoke all on function fitlog_private.purge_expired_posts() from public,anon,authenticated;

-- Same body as 202611010001, with expired posts purged first so their images join this run's queue.
create or replace function public.chat_relay_cleanup() returns jsonb
language plpgsql security definer set search_path='' as $$
begin
 perform fitlog_private.purge_expired_posts();
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
revoke all on function public.chat_relay_cleanup() from public,anon,authenticated;
grant execute on function public.chat_relay_cleanup() to service_role;

commit;
