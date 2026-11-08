-- Execute in SQL Editor as postgres after 202611080001_post_retention.sql; all fixtures are rolled back.
begin;
select set_config('fitlog_test.a',gen_random_uuid()::text,true);
select set_config('fitlog_test.b',gen_random_uuid()::text,true);
select set_config('fitlog_test.fresh',gen_random_uuid()::text,true);
select set_config('fitlog_test.old',gen_random_uuid()::text,true);
insert into auth.users(id,email,aud,role)
select current_setting('fitlog_test.'||n)::uuid,current_setting('fitlog_test.'||n)||'@example.invalid','authenticated','authenticated'
from unnest(array['a','b']) n;
set local role authenticated;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.a'),true);
select public.friend_action(current_setting('fitlog_test.b')::uuid,'request');
select public.social_feed('publish',current_setting('fitlog_test.fresh')::uuid,'fresh');
select public.social_feed('publish',current_setting('fitlog_test.old')::uuid,'old',
 p_media=>array[current_setting('fitlog_test.a')||'/'||current_setting('fitlog_test.old')||'.jpg']);
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.b'),true);
select public.friend_action(current_setting('fitlog_test.a')::uuid,'accept');
reset role;
update public.social_posts set created_at=now()-interval '31 days' where id=current_setting('fitlog_test.old')::uuid;
set local role authenticated;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.b'),true);
do $$ begin
 if jsonb_array_length(public.social_feed('list',p_peer=>current_setting('fitlog_test.a')::uuid))<>1
 then raise exception 'TEST_FAILED: a friend still sees a post older than 30 days';end if;
end $$;
reset role;
select fitlog_private.purge_expired_posts();
do $$ begin
 if exists(select 1 from public.social_posts where id=current_setting('fitlog_test.old')::uuid)
 then raise exception 'TEST_FAILED: expired post was not removed';end if;
 if not exists(select 1 from public.social_posts where id=current_setting('fitlog_test.fresh')::uuid)
 then raise exception 'TEST_FAILED: a recent post was removed';end if;
 if not exists(select 1 from public.chat_media_gc where path=current_setting('fitlog_test.a')||'/'||current_setting('fitlog_test.old')||'.jpg')
 then raise exception 'TEST_FAILED: expired post image was not queued for deletion';end if;
end $$;
rollback;
select 'Post retention assertions passed; fixtures rolled back' as result;
