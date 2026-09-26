# Advanced Data Protection and Photos PCS

## Findings from source inspection

The app uses rclone v1.75.1's Apple Account client rather than implementing SRP itself. Its session authenticates to Apple, supports trusted-device 2FA and trusted-phone SMS, requests Apple's PCS authorization using the `photos` app name when the service requires it, and retains the resulting service cookies in the session. The Photos adapter then establishes the Photos CloudKit web service using the Photos service identity. Relevant code paths are in [`session.go`](https://github.com/rclone/rclone/blob/v1.75.1/backend/iclouddrive/api/session.go), [`photos.go`](https://github.com/rclone/rclone/blob/v1.75.1/backend/iclouddrive/api/photos.go), and [`client.go`](https://github.com/rclone/rclone/blob/v1.75.1/backend/iclouddrive/api/client.go).

This is a Photos-specific PCS request path, not an inference from iCloud Drive support. rclone's documentation describes ADP for both its Drive and Photos services and says Apple may require trusted-device approval and web access to iCloud data. The app preserves that approval flow and never turns off account protection. The native bridge calls rclone's Photos service with a Photos PCS identity and preserves the session for later use.

## What the app can and cannot infer

Apple's response includes a `PcsRequired` flag for the Photos web service. It is a service authorization signal, **not a definitive account-setting query proving that ADP is enabled**. The app models this as `PhotosPCSAuthorization`, not as a claimed ADP detection result. A false flag is not proof that the account has ADP off; a true flag is not the only possible reason authorization may be needed.

PCS cookies and session state are sensitive, service-scoped authorization material. The bridge requests the Photos service identity. It does not request a user's recovery key, bypass trusted-device approval, or attempt to decrypt data outside Apple's authorized service responses. The account's Photos library is still fetched from Apple's private CloudKit-backed service.

Source inspection establishes that rclone has an explicit Photos PCS path. It does not establish that every account, every ADP configuration, every asset representation, or every current Apple server deployment works. No real Apple account was available for this build, so end-to-end ADP Photos access remains unverified. The adapter surfaces authorization errors without recommending that the user weaken account security.

## Web access

Apple can require **Access iCloud Data on the Web** to be enabled and a trusted-device prompt approved before the service issues authorization cookies. The UI explains trusted-device approval. It does not ask the user to disable ADP, enter a recovery key, or grant web access preemptively. If Apple rejects the service request, the app reports that authorization could not be completed and offers a retry.

## Upload boundary

The current upload protocol is a separate, undocumented `uploadimagews` path observed in icloudgo. It does not establish a verified Photos PCS upload flow. Therefore the native bridge refuses to upload when the Photos service reports PCS is required. Browsing and original download use rclone's Photos PCS path; uploads in that security mode are not claimed to work.

## Release validation

Before calling ADP support production-ready, test a standard account and an ADP-enabled account with a disposable library. Verify a trusted-device approval, Photos authorization, initial library access, thumbnails, original download, session restore after process death, and session expiry. Record Apple OS and server behavior, without logging cookies, passwords, phone numbers, photo URLs, or asset metadata.
