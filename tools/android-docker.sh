#!/bin/sh
# Build the companion app (android/) in the Dockerfile.android container:
# JDK, Android SDK and Gradle never touch the host. Gradle's caches stay in
# the screenlet-android-gradle volume (or the folder SCREENLET_GRADLE_CACHE, an
# absolute path: CI caches it). SCREENLET_IMAGE_BUILT=1: the container image is
# there already (CI builds it with its layers cached). Arguments are Gradle tasks
# (default: assembleDebug); the APK lands in android/app/build/outputs/apk.
# For a signed release (assembleRelease), set SCREENLET_KEYSTORE (a path on
# the host), SCREENLET_KEYSTORE_PASSWORD and optionally SCREENLET_KEY_ALIAS.
set -e
cd "$(dirname "$0")/.."
[ -n "$SCREENLET_IMAGE_BUILT" ] || ${DOCKER:-docker} build -q -t screenlet-android - < Dockerfile.android > /dev/null
# Docker runs as root: the output is the user's again. Rootless podman maps
# root to the user already (and SELinux wants the folders relabelled: :Z).
z=""; chown="chown -R $(id -u):$(id -g) \$owned;"
if [ "${DOCKER:-docker}" = podman ]; then z=":Z"; chown=""; fi
gradle="${SCREENLET_GRADLE_CACHE:-screenlet-android-gradle}"
owned="/src"
[ -z "$SCREENLET_GRADLE_CACHE" ] || owned="$owned /gradle"  # a folder of the host's: the user's again
tasks="${*:-assembleDebug}"
signing=""
if [ -n "$SCREENLET_KEYSTORE" ]; then
    # The key file alone, read only: not the folder it is in.
    key="$(cd "$(dirname "$SCREENLET_KEYSTORE")" && pwd)/$(basename "$SCREENLET_KEYSTORE")"
    signing="-v $key:/keys/release.p12:ro -e SCREENLET_KEYSTORE=/keys/release.p12
             -e SCREENLET_KEYSTORE_PASSWORD -e SCREENLET_KEY_ALIAS"
fi
# shellcheck disable=SC2086 # $signing is a list of options
${DOCKER:-docker} run --rm -v "$PWD:/src$z" -v "$gradle:/gradle$z" -w /src -e owned="$owned" $signing screenlet-android \
    sh -c "gradle --no-daemon --console=plain $tasks; status=\$?; $chown exit \$status"
