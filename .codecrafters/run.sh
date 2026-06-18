#!/bin/sh
set -e

stty -icanon -echo min 1 time 0 2>/dev/null || true

MAIN_CLASS_PATH=$(find target/classes -name Main.class | head -n 1)

if [ -n "$MAIN_CLASS_PATH" ]; then
  MAIN_CLASS=$(printf "%s\n" "$MAIN_CLASS_PATH" | sed 's#^target/classes/##; s#/#.#g; s#\.class$##')
  java --enable-preview -cp target/classes "$MAIN_CLASS"
  status=$?
  stty sane 2>/dev/null || true
  exit $status
fi

stty sane 2>/dev/null || true
echo "Main.class not found"
exit 1
