-- Execute in SQL Editor as postgres after 202611100001_comment_notifications.sql; all fixtures are rolled back.
begin;
select set_config('fitlog_test.a',gen_random_uuid()::text,true);
select set_config('fitlog_test.b',gen_random_uuid()::text,true);
select set_config('fitlog_test.post',gen_random_uuid()::text,true);
select set_config('fitlog_test.own',gen_random_uuid()::text,true);
select set_config('fitlog_test.comment',gen_random_uuid()::text,true);
select set_config('fitlog_test.reply',gen_random_uuid()::text,true);
insert into auth.users(id,email,aud,role)
select current_setting('fitlog_test.'||n)::uuid,current_setting('fitlog_test.'||n)||'@example.invalid','authenticated','authenticated'
from unnest(array['a','b']) n;
set local role authenticated;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.a'),true);
select public.friend_action(current_setting('fitlog_test.b')::uuid,'request');
select public.social_feed('publish',current_setting('fitlog_test.post')::uuid,'post');
-- The author's own comment notifies nobody.
select public.social_feed('comment',current_setting('fitlog_test.post')::uuid,'own',p_comment_id=>current_setting('fitlog_test.own')::uuid);
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.b'),true);
select public.friend_action(current_setting('fitlog_test.a')::uuid,'accept');
select public.social_feed('comment',current_setting('fitlog_test.post')::uuid,'nice',p_comment_id=>current_setting('fitlog_test.comment')::uuid);
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.a'),true);
do $$ begin
 if public.social_activity('count')<>'1'::jsonb then raise exception 'TEST_FAILED: the post author was not told of the comment'; end if;
 if public.social_activity('inbox')->0->>'id'<>current_setting('fitlog_test.comment')
 or public.social_activity('inbox')->0->>'parent_id' is not null
 then raise exception 'TEST_FAILED: the inbox does not hold the top-level comment'; end if;
end $$;
-- The author answers: the commenter is told, the author is not told of their own reply.
select public.social_feed('comment',current_setting('fitlog_test.post')::uuid,'thanks',
 p_comment_id=>current_setting('fitlog_test.reply')::uuid,p_parent_id=>current_setting('fitlog_test.comment')::uuid);
do $$ begin
 if public.social_activity('count')<>'1'::jsonb then raise exception 'TEST_FAILED: the author was told of their own reply'; end if;
end $$;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.b'),true);
do $$ begin
 if public.social_activity('count')<>'1'::jsonb then raise exception 'TEST_FAILED: the commenter was not told of the reply'; end if;
end $$;
rollback;
select 'Comment notification assertions passed; fixtures rolled back' as result;
