#!/bin/sh
set -e

stty -icanon -echo min 1 time 0 2>/dev/null || true

if [ -d /app/target/classes ]; then
  CP=/app/target/classes
else
  CP=target/classes
fi

java --enable-preview -cp "$CP" Main
status=$?

stty sane 2>/dev/null || true

exit $status
