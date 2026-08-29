-- PRICE_ABOVE notification type was removed; drop any orphaned rows so reads don't fail enum mapping.
DELETE FROM notification_rules WHERE type = 'PRICE_ABOVE';
