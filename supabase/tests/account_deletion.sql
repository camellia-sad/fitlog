-- Temporary fixtures only. Never DELETE storage metadata; roll back its fixtures to a savepoint.
begin;
select set_config('request.jwt.claim.sub','',true);
select set_config('fitlog_test.a',gen_random_uuid()::text,true);
select set_config('fitlog_test.b',gen_random_uuid()::text,true);
select set_config('fitlog_test.apost',gen_random_uuid()::text,true);
select set_config('fitlog_test.bpost',gen_random_uuid()::text,true);
insert into auth.users(id,email,aud,role)
select current_setting('fitlog_test.'||n)::uuid,current_setting('fitlog_test.'||n)||'@example.invalid','authenticated','authenticated'
from unnest(array['a','b']) n;
insert into public.friendships(requester,recipient,status) values
(current_setting('fitlog_test.a')::uuid,current_setting('fitlog_test.b')::uuid,'accepted');
insert into public.social_posts(id,owner_id,body) values
(current_setting('fitlog_test.apost')::uuid,current_setting('fitlog_test.a')::uuid,'delete account post'),
(current_setting('fitlog_test.bpost')::uuid,current_setting('fitlog_test.b')::uuid,'keep peer post');
insert into public.social_comments(id,post_id,owner_id,body) values
(gen_random_uuid(),current_setting('fitlog_test.bpost')::uuid,current_setting('fitlog_test.a')::uuid,'delete account comment');
insert into public.social_likes(post_id,owner_id) values
(current_setting('fitlog_test.bpost')::uuid,current_setting('fitlog_test.a')::uuid);
savepoint account_storage;
insert into storage.objects(bucket_id,name) values
('fitlog-media',current_setting('fitlog_test.a')||'/avatar.jpg'),
('fitlog-media',current_setting('fitlog_test.a')||'/chat/image.jpg'),
('fitlog-backups',current_setting('fitlog_test.a')||'/backup.zip'),
('fitlog-media',current_setting('fitlog_test.b')||'/keep.jpg'),
('fitlog-updates',current_setting('fitlog_test.a')||'/test-latest.json');
set local role authenticated;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.b'),true);
do $$ begin
 begin
  perform public.account_delete_begin(current_setting('fitlog_test.a')::uuid);
  raise exception 'TEST_FAILED: user can delete another account';
 exception when insufficient_privilege then null;end;
 begin
  perform public.account_delete_assets(current_setting('fitlog_test.a')::uuid);
  raise exception 'TEST_FAILED: user can enumerate deletion assets';
 exception when insufficient_privilege then null;end;
end $$;
reset role;
select set_config('request.jwt.claim.sub','',true);
select public.account_delete_begin(current_setting('fitlog_test.a')::uuid);
select public.account_delete_begin(current_setting('fitlog_test.a')::uuid);
do $$ declare assets jsonb;
begin
 assets:=public.account_delete_assets(current_setting('fitlog_test.a')::uuid);
 if jsonb_array_length(assets)<>3 or exists(select 1 from jsonb_array_elements(assets) x
 where x->>'name' not like current_setting('fitlog_test.a')||'/%')
 then raise exception 'TEST_FAILED: deletion asset isolation';end if;
 if fitlog_private.are_friends(current_setting('fitlog_test.a')::uuid,current_setting('fitlog_test.b')::uuid)
 then raise exception 'TEST_FAILED: deleting account remains writable as friend';end if;
end $$;
set local role authenticated;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.a'),true);
do $$ begin
 begin
  update public.social_profiles set nickname='still writing' where id=auth.uid();
  raise exception 'TEST_FAILED: profile write during deletion';
 exception when others then if sqlerrm<>'账号正在注销或已注销' then raise;end if;end;
 begin
  insert into storage.objects(bucket_id,name) values('fitlog-media',auth.uid()::text||'/new.jpg');
  raise exception 'TEST_FAILED: upload during deletion';
 exception when others then if sqlerrm<>'账号正在注销或已注销' then raise;end if;end;
end $$;
reset role;
select set_config('request.jwt.claim.sub','',true);
-- Storage protects direct SQL deletion. Discard only these temporary storage fixtures,
-- then independently verify identity/profile cascades. Storage API ordering has a function test.
rollback to savepoint account_storage;
select set_config('request.jwt.claim.sub','',true);
select public.account_delete_begin(current_setting('fitlog_test.a')::uuid);
delete from auth.users where id=current_setting('fitlog_test.a')::uuid;
select public.account_delete_finish(current_setting('fitlog_test.a')::uuid);
do $$ begin
 if exists(select 1 from public.social_profiles where id=current_setting('fitlog_test.a')::uuid)
 or exists(select 1 from public.friendships where requester=current_setting('fitlog_test.a')::uuid or recipient=current_setting('fitlog_test.a')::uuid)
 or exists(select 1 from public.social_posts where owner_id=current_setting('fitlog_test.a')::uuid)
 or exists(select 1 from public.social_comments where owner_id=current_setting('fitlog_test.a')::uuid)
 or exists(select 1 from public.social_likes where owner_id=current_setting('fitlog_test.a')::uuid)
 then raise exception 'TEST_FAILED: account data remains';end if;
 if not exists(select 1 from public.social_posts where id=current_setting('fitlog_test.bpost')::uuid)
 or not exists(select 1 from public.social_profiles where id=current_setting('fitlog_test.b')::uuid)
 then raise exception 'TEST_FAILED: peer data or release removed';end if;
end $$;
-- A stale access token cannot create files after identity removal.
set local role authenticated;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.a'),true);
do $$ begin
 begin
  insert into storage.objects(bucket_id,name) values('fitlog-media',auth.uid()::text||'/after.jpg');
  raise exception 'TEST_FAILED: stale token upload after deletion';
 exception when others then if sqlerrm<>'账号正在注销或已注销' then raise;end if;end;
end $$;
reset role;
select set_config('request.jwt.claim.sub','',true);
-- Reusing the email creates a fresh identity, never restores the deleted profile.
insert into auth.users(id,email,aud,role) values
(gen_random_uuid(),current_setting('fitlog_test.a')||'@example.invalid','authenticated','authenticated');
rollback;
select 'Account deletion permission assertions passed; fixtures rolled back' as result;
