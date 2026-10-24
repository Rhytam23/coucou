# Android branches: how versions are kept

Read this file before you delete, rename, merge away or rewrite any branch or tag
of the Android port. Read the `android` branch first, then this document.

## The rule

- `android` is the working branch. New Android work lands here.
- `main` is not touched by Android version bookkeeping.
- Every Android release is saved forever in its own branch:
  `android-0.1`, `android-0.2`, `android-0.3`, `android-0.4`, and so on
  (patch lines such as `android-0.1.1` or `android-0.3.0` count as part of their line).
- The tags `android-v0.1.0`, `android-v0.2.0` and `android-v0.3.0` are released
  versions. Never move, update or delete them.
- Never update a released version branch. A fix goes to `android`, then to the
  next version branch.

## When the line changes: `android-0-end`

When we move from one line to another, we create `android-0-end`. It holds
everything built from Android 0.1 up to that point, in one branch.

## Deleting

No version branch or tag is deleted until Louis and Rhytam both agree it can go.
An agent never decides this alone. If asked to clean up branches, read this file,
list what would go, and ask.

## Keeping the code small

Every time you make an update or a change, delete the code that is no longer
used or not needed: dead functions, unused resources, unused strings,
commented-out blocks, orphaned files. Do it in the same change.
