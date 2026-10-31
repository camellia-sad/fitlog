-- Run once after deploying chat-maintenance.
-- In Vault, create fitlog_project_url = your Project URL, and
-- fitlog_chat_maintenance = the same random value as Edge secret CHAT_MAINTENANCE_SECRET.
-- Never use the publishable key as the maintenance secret.
create extension if not exists pg_cron with schema pg_catalog;
create extension if not exists pg_net with schema extensions;
do $$ begin
 if not exists(select 1 from vault.decrypted_secrets where name='fitlog_project_url')
 or not exists(select 1 from vault.decrypted_secrets where name='fitlog_chat_maintenance')
 then raise exception 'Create fitlog_project_url and fitlog_chat_maintenance in Vault first'; end if;
end $$;
select cron.schedule('fitlog-chat-maintenance','*/15 * * * *',$job$
 select net.http_post(
  url:=(select decrypted_secret from vault.decrypted_secrets where name='fitlog_project_url' limit 1)||'/functions/v1/chat-maintenance',
  headers:=jsonb_build_object('Content-Type','application/json','x-fitlog-maintenance',
   (select decrypted_secret from vault.decrypted_secrets where name='fitlog_chat_maintenance' limit 1)),
  body:='{}'::jsonb,timeout_milliseconds:=60000
 );
$job$);
