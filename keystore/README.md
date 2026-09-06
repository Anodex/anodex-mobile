# Signing key

Anodex Mobile is signed with a key that is **not in this repository** and must
never be. CI decodes it from the `ANODEX_KEYSTORE_BASE64` secret into
`keystore/anodex-release.jks` at build time, and `.gitignore` keeps it out.

## Why it cannot live here

Android identifies an app by its signing key. It refuses to install a build signed
by a different one — which is the only thing standing between a user and a hostile
APK claiming to be an update. That protection is worth exactly as much as the
key's secrecy. In a public repository, a committed key is a key anyone can sign
with.

## The key that used to be here

A preview key *was* committed for a while, on purpose. Without a fixed key, Gradle
generates one per machine, and a CI runner is a fresh machine every run — so every
build produced an APK Android refused to install over the last one. The only way
to update was to uninstall, and uninstalling wipes the pairing with the desktop.
Every update silently cost a re-pair.

That was the right trade while the repository was private and builds were handed
over one at a time. Publishing ended it. The key was rotated and removed from
history before the repository was made public; it signs nothing now.

## If the key is ever lost

Every existing install has to be uninstalled and replaced — an app signed by a new
key cannot update one signed by the old. Keep a backup somewhere that is not this
repository.
