-- Execute in SQL Editor as postgres; all fixtures are rolled back.
begin;
select set_config('fitlog_test.a',gen_random_uuid()::text,true);
select set_config('fitlog_test.b',gen_random_uuid()::text,true);
select set_config('fitlog_test.post',gen_random_uuid()::text,true);
select set_config('fitlog_test.comment',gen_random_uuid()::text,true);
insert into auth.users(id,email,aud,role)
select current_setting('fitlog_test.'||n)::uuid,current_setting('fitlog_test.'||n)||'@example.invalid','authenticated','authenticated'
from unnest(array['a','b']) n;
set local role authenticated;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.a'),true);
select public.friend_action(current_setting('fitlog_test.b')::uuid,'request');
select public.social_feed('publish',current_setting('fitlog_test.post')::uuid,'post');
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.b'),true);
select public.friend_action(current_setting('fitlog_test.a')::uuid,'accept');
select public.social_feed('comment',current_setting('fitlog_test.post')::uuid,'comment',p_comment_id=>current_setting('fitlog_test.comment')::uuid);
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.a'),true);
select public.social_feed('delete_comment',current_setting('fitlog_test.post')::uuid,p_comment_id=>current_setting('fitlog_test.comment')::uuid);
do $$ begin
 if jsonb_array_length(public.social_feed('comments',current_setting('fitlog_test.post')::uuid))<>1
 then raise exception 'TEST_FAILED: post owner deleted another user comment';end if;
end $$;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.b'),true);
select public.social_feed('delete_comment',current_setting('fitlog_test.post')::uuid,p_comment_id=>current_setting('fitlog_test.comment')::uuid);
do $$ begin
 if jsonb_array_length(public.social_feed('comments',current_setting('fitlog_test.post')::uuid))<>0
 then raise exception 'TEST_FAILED: comment author could not delete own comment';end if;
end $$;
rollback;
select 'Comment ownership assertions passed; fixtures rolled back' as result;
