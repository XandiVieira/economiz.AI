-- We store an opaque storage-key (not the bytes) on the User row. The
-- backend resolves the key to bytes via a ProfilePictureStorage interface.
-- Local-disk impl in dev (key = filename under /tmp/economizai/profile-pics);
-- swap for S3/Cloudinary/etc in prod by changing the impl + env var. See
-- DEV_NOTES.md for the prod-storage migration plan.

ALTER TABLE users ADD COLUMN profile_picture_key VARCHAR(255);
ALTER TABLE users ADD COLUMN profile_picture_content_type VARCHAR(50);
ALTER TABLE users ADD COLUMN profile_picture_uploaded_at TIMESTAMP;
