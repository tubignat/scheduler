#!/usr/bin/env sh

# Gradle Wrapper script (portable, self-bootstrapping)
# This script downloads the Gradle distribution specified in
#   gradle/wrapper/gradle-wrapper.properties (distributionUrl)
# and executes it. If a working wrapper JAR is available, it will
# try to use it first; otherwise it falls back to downloading and
# running the distribution directly.

set -eu

APP_BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROPS_FILE="$APP_BASE_DIR/gradle/wrapper/gradle-wrapper.properties"
WRAPPER_DIR="$APP_BASE_DIR/gradle/wrapper"
WRAPPER_JAR="$WRAPPER_DIR/gradle-wrapper.jar"
WRAPPER_SHARED_JAR="$WRAPPER_DIR/gradle-wrapper-shared.jar"

# Resolve Java executable
if [ "${JAVA_HOME-}" != "" ] ; then
  JAVA_EXE="$JAVA_HOME/bin/java"
  if [ ! -x "$JAVA_EXE" ] ; then
    echo "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME" >&2
    echo "Please set the JAVA_HOME variable in your environment to match the location of your Java installation." >&2
    exit 1
  fi
else
  JAVA_EXE="java"
fi

# Attempt to use wrapper JARs if they are good
if [ -f "$WRAPPER_JAR" ]; then
  CLASSPATH="$WRAPPER_JAR"
  if [ -f "$WRAPPER_SHARED_JAR" ]; then
    CLASSPATH="$CLASSPATH:$WRAPPER_SHARED_JAR"
  fi
  if "$JAVA_EXE" -Dorg.gradle.appname=gradlew -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain --version >/dev/null 2>&1; then
    exec "$JAVA_EXE" -Dorg.gradle.appname=gradlew -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
  fi
fi

# Fallback: download and run distribution directly
if [ ! -f "$PROPS_FILE" ]; then
  echo "ERROR: $PROPS_FILE not found; cannot determine distributionUrl" >&2
  exit 1
fi

# Extract and normalize distribution URL from properties (remove backslashes)
DIST_URL=$(sed -n 's/^distributionUrl=//p' "$PROPS_FILE" | sed 's/\\//g')
if [ -z "$DIST_URL" ]; then
  echo "ERROR: distributionUrl not set in $PROPS_FILE" >&2
  exit 1
fi

# Choose Gradle user home
GRADLE_USER_HOME_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}"
DISTS_DIR="$GRADLE_USER_HOME_DIR/wrapper/dists"
mkdir -p "$DISTS_DIR"

# Derive a directory name based on the zip filename
ZIP_NAME=$(basename "$DIST_URL")
BASENAME=${ZIP_NAME%.zip}
INSTALL_DIR="$DISTS_DIR/$BASENAME"

# Download if not present
if [ ! -d "$INSTALL_DIR" ]; then
  TMP_ZIP="$DISTS_DIR/$ZIP_NAME"
  echo "Downloading Gradle distribution: $DIST_URL" >&2
  if command -v curl >/dev/null 2>&1; then
    curl -fL "$DIST_URL" -o "$TMP_ZIP"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$TMP_ZIP" "$DIST_URL"
  else
    echo "ERROR: Neither curl nor wget is available to download $DIST_URL" >&2
    exit 1
  fi
  mkdir -p "$INSTALL_DIR"
  unzip -q "$TMP_ZIP" -d "$INSTALL_DIR"
  rm -f "$TMP_ZIP"
fi

# Find the gradle launcher script inside the extracted distribution
GRADLE_BIN=$(find "$INSTALL_DIR" -type f -path '*/bin/gradle' | head -n 1)
if [ ! -x "$GRADLE_BIN" ]; then
  echo "ERROR: Could not locate gradle launcher under $INSTALL_DIR" >&2
  exit 1
fi

# Propagate JAVA_HOME if set
if [ "${JAVA_HOME-}" != "" ]; then
  export JAVA_HOME
fi

exec "$GRADLE_BIN" "$@"
