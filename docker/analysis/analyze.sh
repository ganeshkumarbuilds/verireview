#!/bin/sh
# /opt/analyze.sh /src /out — deterministic analyzers over an untrusted snapshot.
# Exit 0 unless the harness itself breaks (tool violations never fail the run;
# each tool records its status in tool-status.properties for the backend).
set -u
SRC="$1"
OUT="$2"
STATUS="$OUT/tool-status.properties"
mkdir -p "$OUT"
cp /opt/versions.properties "$OUT/versions.properties"
: > "$STATUS"

record() {
  printf '%s=%s\n' "$1" "$2" >> "$STATUS"
}

if find "$SRC" -name '*.java' -print -quit 2>/dev/null | grep -q .; then
  if java -jar /opt/analysis/checkstyle-all.jar -c /sun_checks.xml \
      -f xml -o "$OUT/checkstyle.xml" "$SRC" 2>"$OUT/checkstyle.stderr"; then
    record checkstyle RAN
  else
    # Exit 2+ with a report still means findings; only a missing report is a failure.
    if [ -f "$OUT/checkstyle.xml" ]; then record checkstyle RAN; else record checkstyle FAILED; fi
  fi
else
  record checkstyle SKIPPED_NO_JAVA
fi

if find "$SRC" -name '*.java' -print -quit 2>/dev/null | grep -q .; then
  if /opt/analysis/pmd/bin/pmd check -d "$SRC" \
      -R category/java/bestpractices.xml,category/java/codestyle.xml,category/java/design.xml,category/java/errorprone.xml,category/java/multithreading.xml,category/java/performance.xml,category/java/security.xml \
      --no-fail-on-violation -f xml -r "$OUT/pmd.xml" 2>"$OUT/pmd.stderr"; then
    record pmd RAN
  else
    if [ -f "$OUT/pmd.xml" ]; then record pmd RAN; else record pmd FAILED; fi
  fi
else
  record pmd SKIPPED_NO_JAVA
fi

if [ -f "$SRC/pom.xml" ]; then
  if (cd "$SRC" && mvn -o -B -q compile >"$OUT/spotbugs-build.log" 2>&1); then
    CLASSES=$(find "$SRC" -type d -path '*/target/classes' 2>/dev/null | tr '\n' ' ')
    if [ -n "$CLASSES" ]; then
      # shellcheck disable=SC2086
      if /opt/analysis/spotbugs-*/bin/spotbugs -textui -effort:default \
          -xml:withMessages -output "$OUT/spotbugs.xml" $CLASSES \
          >"$OUT/spotbugs.stdout" 2>"$OUT/spotbugs.stderr"; then
        record spotbugs RAN
      else
        if [ -f "$OUT/spotbugs.xml" ]; then record spotbugs RAN; else record spotbugs FAILED; fi
      fi
    else
      record spotbugs SKIPPED_NO_CLASSES
    fi
  else
    record spotbugs SKIPPED_COMPILE_FAILED
  fi
else
  record spotbugs SKIPPED_NO_BUILD_FILE
fi

exit 0
