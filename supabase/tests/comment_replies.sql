-- Execute in SQL Editor as postgres; all fixtures are rolled back.
begin;
select set_config('fitlog_test.a',gen_random_uuid()::text,true);
select set_config('fitlog_test.b',gen_random_uuid()::text,true);
select set_config('fitlog_test.post',gen_random_uuid()::text,true);
select set_config('fitlog_test.root',gen_random_uuid()::text,true);
select set_config('fitlog_test.reply',gen_random_uuid()::text,true);
select set_config('fitlog_test.nested',gen_random_uuid()::text,true);
insert into auth.users(id,email,aud,role)
select current_setting('fitlog_test.'||n)::uuid,current_setting('fitlog_test.'||n)||'@example.invalid','authenticated','authenticated'
from unnest(array['a','b']) n;
set local role authenticated;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.a'),true);
select public.friend_action(current_setting('fitlog_test.b')::uuid,'request');
select public.social_feed('publish',current_setting('fitlog_test.post')::uuid,'post');
select public.social_feed('comment',current_setting('fitlog_test.post')::uuid,'root',p_comment_id=>current_setting('fitlog_test.root')::uuid);
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.b'),true);
select public.friend_action(current_setting('fitlog_test.a')::uuid,'accept');
select public.social_feed('comment',current_setting('fitlog_test.post')::uuid,'reply',
 p_comment_id=>current_setting('fitlog_test.reply')::uuid,p_parent_id=>current_setting('fitlog_test.root')::uuid);
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.a'),true);
-- Replying to a reply stays under the same root and names the replied user.
select public.social_feed('comment',current_setting('fitlog_test.post')::uuid,'nested',
 p_comment_id=>current_setting('fitlog_test.nested')::uuid,p_parent_id=>current_setting('fitlog_test.reply')::uuid);
do $$ declare post uuid:=current_setting('fitlog_test.post')::uuid; root uuid:=current_setting('fitlog_test.root')::uuid; top jsonb; replies jsonb;
begin
 top:=public.social_feed('comments',post);
 if jsonb_array_length(top)<>1 or (top->0->>'reply_count')::int<>2 then raise exception 'TEST_FAILED: replies listed as top-level'; end if;
 if (public.social_feed('list')->0->>'comment_count')::int<>3 then raise exception 'TEST_FAILED: comment count'; end if;
 replies:=public.social_feed('replies',post,p_parent_id=>root);
 if jsonb_array_length(replies)<>2 or exists(select 1 from jsonb_array_elements(replies) r where r->>'parent_id'<>root::text)
   or not exists(select 1 from jsonb_array_elements(replies) r where r->>'body'='nested' and r->>'reply_to_nickname' is not null)
   or not exists(select 1 from jsonb_array_elements(replies) r where r->>'body'='reply' and r->>'reply_to_owner_id' is null)
 then raise exception 'TEST_FAILED: reply thread'; end if;
 if jsonb_array_length(public.social_feed('replies',post,p_parent_id=>root,
   p_before=>(replies->0->>'created_at')::timestamptz,p_before_id=>(replies->0->>'id')::uuid))<>1
 then raise exception 'TEST_FAILED: reply cursor'; end if;
end $$;
-- Root with replies becomes a placeholder; its replies stay readable.
select public.social_feed('delete_comment',current_setting('fitlog_test.post')::uuid,p_comment_id=>current_setting('fitlog_test.root')::uuid);
do $$ declare top jsonb:=public.social_feed('comments',current_setting('fitlog_test.post')::uuid);
begin
 if jsonb_array_length(top)<>1 or not (top->0->>'deleted')::boolean or top->0->>'body'<>'' then raise exception 'TEST_FAILED: root placeholder'; end if;
 if (public.social_feed('list')->0->>'comment_count')::int<>2 then raise exception 'TEST_FAILED: count after placeholder'; end if;
end $$;
do $$ begin
 perform public.social_feed('comment',current_setting('fitlog_test.post')::uuid,'late',
  p_comment_id=>gen_random_uuid(),p_parent_id=>current_setting('fitlog_test.root')::uuid);
 raise exception 'TEST_FAILED: replied to deleted comment';
exception when others then if sqlerrm like 'TEST_FAILED%' then raise; end if;
end $$;
-- Removing the last reply also removes the placeholder.
select public.social_feed('delete_comment',current_setting('fitlog_test.post')::uuid,p_comment_id=>current_setting('fitlog_test.nested')::uuid);
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.b'),true);
select public.social_feed('delete_comment',current_setting('fitlog_test.post')::uuid,p_comment_id=>current_setting('fitlog_test.reply')::uuid);
do $$ begin
 if jsonb_array_length(public.social_feed('comments',current_setting('fitlog_test.post')::uuid))<>0
 then raise exception 'TEST_FAILED: empty placeholder kept'; end if;
end $$;
rollback;
select 'Comment reply assertions passed; fixtures rolled back' as result;
