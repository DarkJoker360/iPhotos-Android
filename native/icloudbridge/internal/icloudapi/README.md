# Local rclone Photos API extension

This package contains the production Go sources from rclone `v1.75.1`'s
`backend/iclouddrive/api` package, copied and adapted under its MIT license.
iPhotos adds `Album.GetPhotosPage` so the Android bridge can use the existing
CloudKit `startRank` index a page at a time instead of materializing an entire
album before rendering the first page. Keep protocol changes small and preserve
upstream attribution when updating these files.

Upstream: <https://github.com/rclone/rclone/tree/v1.75.1/backend/iclouddrive/api>
License: [`../../../../licenses/rclone-COPYING`](../../../../licenses/rclone-COPYING)
