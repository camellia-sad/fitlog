-- A comment on one's own post now lands in the same inbox as a reply to one's comment, so it shows in the feed's new
-- replies bar and as a notification. Replies still go to the comment being answered. Never notifies oneself.
-- Run after 202611090001_post_workout.sql; safe to run again.
begin;

create or replace function fitlog_private.notify_comment_reply() returns trigger
language plpgsql security definer set search_path='' as $$
declare recipient_id uuid;
begin
 if new.parent_id is not null then
   select coalesce(new.reply_to_owner_id,c.owner_id) into recipient_id from public.social_comments c where c.id=new.parent_id;
 else
   select p.owner_id into recipient_id from public.social_posts p where p.id=new.post_id;
 end if;
 if recipient_id is not null and recipient_id<>new.owner_id then
   insert into public.social_reply_notifications(id,recipient) values(new.id,recipient_id) on conflict do nothing;
 end if;
 return new;
end $$;
revoke all on function fitlog_private.notify_comment_reply() from public,anon,authenticated;

commit;
