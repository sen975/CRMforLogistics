UPDATE email_submissions AS submission
SET owner_user_id = account.owner_user_id
FROM channel_accounts AS account
WHERE submission.owner_user_id IS NULL
  AND submission.channel_account_id = account.id
  AND account.owner_user_id IS NOT NULL;
