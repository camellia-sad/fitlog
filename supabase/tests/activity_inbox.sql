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

-- A receives B's reply; B receives A's reply-to-reply. Never notify oneself.
do $$ begin
 if public.social_activity('count')<>'1'::jsonb then raise exception 'TEST_FAILED: recipient count'; end if;
 if public.social_activity('context',current_setting('fitlog_test.reply')::uuid)->'root'->>'id'<>current_setting('fitlog_test.root')
 then raise exception 'TEST_FAILED: context root'; end if;
 if public.social_activity('inbox')->0->>'post_id'<>current_setting('fitlog_test.post')
 or not (public.social_activity('inbox')->0 ? 'avatar_path')
 or public.social_activity('feed')->0->>'fitlog_id' is null
 then raise exception 'TEST_FAILED: enriched reply/post identity'; end if;
end $$;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.b'),true);
select public.social_activity('read',current_setting('fitlog_test.reply')::uuid);
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.a'),true);
do $$ begin
 if public.social_activity('count')<>'1'::jsonb then raise exception 'TEST_FAILED: other recipient marked read'; end if;
end $$;
select public.social_activity('read',current_setting('fitlog_test.reply')::uuid);
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.b'),true);
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.a'),true);
do $$ begin
 if public.social_activity('count')<>'0'::jsonb then raise exception 'TEST_FAILED: read not durable'; end if;
end $$;
-- Replies outside the first page still have a direct context; no full-history client scan.
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.b'),true);
do $$ begin
 for i in 1..55 loop
 perform public.social_feed('comment',current_setting('fitlog_test.post')::uuid,'older page reply',
 p_comment_id=>gen_random_uuid(),p_parent_id=>current_setting('fitlog_test.root')::uuid);
 end loop;
end $$;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.a'),true);
do $$ declare page jsonb:=public.social_activity('inbox'); older jsonb; begin
 if jsonb_array_length(page)<>20 or public.social_activity('count')<>'55'::jsonb then raise exception 'TEST_FAILED: inbox page/count'; end if;
 older:=public.social_activity('inbox',p_before=>(page->19->>'created_at')::timestamptz,p_before_id=>(page->19->>'id')::uuid);
 if jsonb_array_length(older)<>20 or exists(select 1 from jsonb_array_elements(page) a join jsonb_array_elements(older) b on a->>'id'=b->>'id')
 then raise exception 'TEST_FAILED: inbox cursor'; end if;
 perform public.social_activity('read_all');
 if public.social_activity('count')<>'0'::jsonb then raise exception 'TEST_FAILED: read all'; end if;
end $$;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.b'),true);
do $$ begin
 if public.social_activity('count')<>'1'::jsonb then raise exception 'TEST_FAILED: read all changed another recipient'; end if;
end $$;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.a'),true);
-- Blocking removes access, including direct reply context.
select public.friend_action(current_setting('fitlog_test.b')::uuid,'block');
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.b'),true);
do $$ begin
 if public.social_activity('count')<>'0'::jsonb then raise exception 'TEST_FAILED: blocked inbox'; end if;
 begin
 perform public.social_activity('context',current_setting('fitlog_test.nested')::uuid);
 raise exception 'TEST_FAILED: blocked context accessible';
 exception when others then if sqlerrm like 'TEST_FAILED%' then raise; end if; end;
end $$;
rollback;
select 'Activity inbox assertions passed; fixtures rolled back' as result;
