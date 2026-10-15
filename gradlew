#!/bin/sh
DIRNAME=$(dirname "$0")
JAVA_EXE="${JAVA_HOME}/bin/java"
if [ -z "$JAVA_HOME" ]; then
    JAVA_EXE="java"
fi
exec "$JAVA_EXE" -classpath "$DIRNAME/gradle/wrapper/gradle-wrapper.jar" \
    org.gradle.wrapper.GradleWrapperMain "$@"
