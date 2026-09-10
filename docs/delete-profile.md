# Delete your Jay profile and data

Jay is developed and its default service is operated by **RIS PENG**. You can request deletion through the app or by contacting **[ris@poppybit.com](mailto:ris@poppybit.com?subject=Jay%20profile%20deletion)**. You do not need to reinstall Jay to send a request.

## Delete through the app

Open **Settings → Jay social → Reset identity** and confirm. Keep the app connected to the server until the reset completes. This disables your current profile and starts a new one; it cannot be undone. Every device using the old exported profile loses access to it.

The server removes the old profile from its groups. If it is a group's only leader, the whole group is deleted even if other members remain, including its shared alarms, timers and stored sounds. Shared content is preserved only in groups with another leader. Stored files are removed through background cleanup; provider backups and older file versions follow the retention periods in the privacy policy.

## Request deletion without the app

Email **ris@poppybit.com** with the subject **Jay profile deletion**. Include the server you used and the profile name you remember. Explain if you no longer have access to the app or profile.

A profile name alone is not proof of ownership. We will arrange any additional verification needed before deleting a profile, so that someone else cannot delete your data by copying your name. Do not send your exported profile link in a public issue or post. You do not need to provide your Google password or payment-card details.

The request follows the same retirement and cleanup process as the in-app option after ownership has been verified. For a self-hosted server, contact that server's operator; RIS PENG cannot delete data on a server controlled by someone else.

## Delete data without deleting your profile

You can delete individual shared alarms and timers while keeping your Jay profile. Open the relevant group, select the alarm or timer, and use its delete action. You must have permission to edit that group's content. Synchronize to send the deletion to the server; it applies to the shared item for all group members. Do not use **Reset identity** if you want to keep your profile.

To request this without the app, email **[ris@poppybit.com](mailto:ris@poppybit.com?subject=Jay%20data%20deletion)** with the subject **Jay data deletion**. Identify your server, profile and the group items you want deleted, and explicitly say that you want to keep your profile. We verify ownership and group permissions before acting. A profile name alone is not proof of ownership; do not post exported profile links publicly. For another operator's server, contact that operator.

Your profile, memberships and other content remain. Deleted shared alarm and timer records become eligible for cleanup after 30 days. Unused shared sounds become eligible for cleanup after 24 hours; sounds still referenced by other shared items remain. Background cleanup, older audio versions, logs, backups and copies already downloaded by other members follow the [privacy policy's retention rules](privacy-policy.md#retention-and-deletion). This option does not erase all profile history or other members' data.

## What is removed and what may remain

Profile access and server push subscriptions are disabled immediately when retirement starts. Profile details and associated personal history are cleaned up after the 30-day synchronization retention period and completion of membership removal. A minimal retired-profile record remains to prevent the old profile from being recreated by old credentials or offline requests.

Shared content retained by continuing groups, other members' downloaded copies, operational logs and backups have separate retention periods. Resetting the profile does not delete your personal alarms or other local clock data. See the [privacy policy](privacy-policy.md#retention-and-deletion) for the full retention details, including database backups, audio storage and Firebase installation identifiers.
