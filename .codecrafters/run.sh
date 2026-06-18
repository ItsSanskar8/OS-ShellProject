#!/bin/sh
set -e

stty -icanon -echo min 1 time 0 2>/dev/null || true

java --enable-preview -cp target/classes Main
status=$?

stty sane 2>/dev/null || true

exit $status
