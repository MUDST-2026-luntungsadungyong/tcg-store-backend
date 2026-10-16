-- Flyway V8: backfill pending seller application notifications.
INSERT INTO notifications (
    recipient_id,
    seller_profile_id,
    notification_type,
    title,
    message,
    created_at
)
SELECT
    admin_user.id,
    seller_profile.id,
    'SELLER_APPLICATION_SUBMITTED',
    'New seller application',
    seller_profile.shop_name || ' submitted a seller application for review.',
    CURRENT_TIMESTAMP
FROM seller_profiles seller_profile
JOIN users applicant
    ON applicant.id = seller_profile.user_id
CROSS JOIN users admin_user
WHERE applicant.seller_status = 'PENDING'
  AND admin_user.role = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1
      FROM notifications existing_notification
      WHERE existing_notification.recipient_id = admin_user.id
        AND existing_notification.seller_profile_id = seller_profile.id
        AND existing_notification.notification_type = 'SELLER_APPLICATION_SUBMITTED'
  );
