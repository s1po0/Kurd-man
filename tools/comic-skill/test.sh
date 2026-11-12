#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
if [[ -n "${JAVA_HOME:-}" ]]; then
  JAVAC="$JAVA_HOME/bin/javac"
  JAVA="$JAVA_HOME/bin/java"
else
  JAVAC=javac
  JAVA=java
fi
"$JAVAC" --release 17 -encoding UTF-8 -d "$WORK" \
  "$ROOT/android/app/src/main/java/com/kurdman/app/ComicTranslationSkill.java" \
  "$ROOT/android/app/src/main/java/com/kurdman/app/ComicQualityChecks.java" \
  "$ROOT/tools/comic-skill/ComicQualityChecksTest.java" \
  "$ROOT/tools/comic-skill/ComicSystemPanelTest.java" \
  "$ROOT/tools/comic-skill/ComicTranslationSkillTest.java"
"$JAVA" -cp "$WORK" com.kurdman.app.ComicTranslationSkillTest \
  "$ROOT/android/app/src/main/assets/comic-lexicon-v1.tsv"

"$JAVA" -cp "$WORK" com.kurdman.app.ComicQualityChecksTest

"$JAVA" -cp "$WORK" com.kurdman.app.ComicSystemPanelTest \
  "$ROOT/android/app/src/main/assets/comic-lexicon-v1.tsv"
