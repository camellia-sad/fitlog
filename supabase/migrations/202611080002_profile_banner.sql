-- The profile card's backdrop: null for the time-of-day sky, one of four pixel scenes, or an image the owner uploaded.
-- Friends can read the image like the avatar. A replaced avatar or banner image is queued for the chat-maintenance job,
-- which deletes it only once nothing references it any more. Run after 202611080001_post_retention.sql.
begin;

alter table public.social_profiles add column if not exists banner text;
alter table public.social_profiles drop constraint if exists social_banner_value;
alter table public.social_profiles add constraint social_banner_value
 check(banner is null or banner in('gym','track','hills','sea') or banner like id::text||'/%');
grant update(banner) on public.social_profiles to authenticated;

create or replace function fitlog_private.media_visible(path text,viewer uuid) returns boolean
language sql stable security definer set search_path='' as $$
 select viewer is not null and (
 path like viewer::text||'/%'
 or exists(select 1 from public.social_profiles p where (p.avatar_path=path or p.banner=path) and fitlog_private.are_friends(viewer,p.id))
 or exists(select 1 from public.social_posts p where path=any(p.media_paths) and fitlog_private.post_visible(p,viewer))
 or exists(select 1 from public.social_messages m where not m.withdrawn and fitlog_private.message_visible(m,viewer)
    and (m.media_path=path or exists(select 1 from jsonb_array_elements(m.forwarded) e where e->>'media_path'=path)))
 );
$$;

drop policy if exists fitlog_media_delete on storage.objects;
create policy fitlog_media_delete on storage.objects for delete to authenticated
 using(bucket_id='fitlog-media' and (storage.foldername(name))[1]=auth.uid()::text
 and not exists(select 1 from public.social_profiles where avatar_path=name or banner=name));

create or replace function fitlog_private.queue_replaced_profile_media() returns trigger
language plpgsql security definer set search_path='' as $$
begin
 insert into public.chat_media_gc(path)
 select x from unnest(array[
  case when old.avatar_path is distinct from new.avatar_path then old.avatar_path end,
  case when old.banner is distinct from new.banner and old.banner like '%/%' then old.banner end]) x
 where x is not null
 on conflict do nothing;
 return new;
end $$;
revoke all on function fitlog_private.queue_replaced_profile_media() from public,anon,authenticated;
drop trigger if exists social_profiles_media_replaced on public.social_profiles;
create trigger social_profiles_media_replaced after update of avatar_path,banner on public.social_profiles
 for each row execute function fitlog_private.queue_replaced_profile_media();

-- Same body as 202611080001, with banner images kept like avatars.
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
   not exists(select 1 from public.social_profiles p where p.avatar_path=g.path or p.banner=g.path)
   and not exists(select 1 from public.social_posts p where not p.deleted and g.path=any(p.media_paths))
   and not exists(select 1 from public.social_messages m where m.media_path=g.path or
     exists(select 1 from jsonb_array_elements(m.forwarded) e where e->>'media_path'=g.path))
  order by g.queued_at limit 100
 ) q),'[]');
end $$;
revoke all on function public.chat_relay_cleanup() from public,anon,authenticated;
grant execute on function public.chat_relay_cleanup() to service_role;

commit;
