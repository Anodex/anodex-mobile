# Preview signing key

`anodex-preview.jks` signs every preview build of Anodex Mobile, on every machine
and every CI runner. Its password is `android`, the Android debug convention, and
it is committed on purpose.

## Why it is not a secret

Android refuses to install an APK over one signed by a different key. Gradle
generates a debug keystore per machine when none exists — so a fresh CI runner
generated a fresh key on **every run**, and installing a new build meant
uninstalling the old one first. Uninstalling wipes the app's storage, and the
pairing with the desktop lives there.

So for twenty-one releases, every update silently cost a re-pair, and no update
check could ever have worked. This key exists to fix that. It establishes that
two builds came from the same place, not that the place is trustworthy.

## What this key is not

It is **not** a release key. If Anodex Mobile is ever published anywhere, the
signing key for that must be generated fresh, kept out of the repository, and
stored in CI secrets. Publishing an app whose signing key is in a readable
repository means anyone with the repository can ship an update to it.

Changing the key later forces every existing install to be uninstalled once more,
which is the reason to get it right before there are installs worth keeping.
