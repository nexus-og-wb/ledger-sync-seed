#!/usr/bin/env bash
# Compiles and runs the pipeline against fixtures/corpus-a.jsonl.
# Needs a JDK 21 and nothing else - no network, no database, no Gradle.
set -euo pipefail
cd "$(dirname "$0")"

SEP=":"
case "$(uname -s 2>/dev/null || echo Windows)" in
    *CYGWIN*|*MINGW*|*MSYS*|*NT*) SEP=";" ;;
esac

CP="build/selfcheck"
if [ -d "lib" ]; then
    for jar in lib/*.jar; do
        if [ -f "$jar" ]; then
            CP="$CP$SEP$jar"
        fi
    done
fi

echo "==> compiling"
rm -rf build/selfcheck && mkdir -p build/selfcheck
javac -cp "$CP" -d build/selfcheck $(find src/main/java -name '*.java')

echo
echo "==> running"
java -cp "$CP" in.simplifymoney.ledgersync.SelfCheck "$@"
