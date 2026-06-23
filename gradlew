#!/bin/sh
APP_HOME="$(cd "$(dirname "$0")" && pwd)"
GRADLE_HOME="${HOME}/.gradle/wrapper/dists/gradle-9.1.0-bin/9agqghryom9wkf8r80qlhnts3/gradle-9.1.0"
cd "${APP_HOME}"
exec "${GRADLE_HOME}/bin/gradle" "$@"
