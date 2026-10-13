#!/usr/bin/env bash
# Shared mandatory result validation, used by the actual emulator gate and its regression test.
security_results_valid() {
  grep -q '^INSTRUMENTATION_RESULT: failures=0$' "$1" &&
  grep -q '^INSTRUMENTATION_RESULT: tests=7$' "$1" &&
  grep -q '^INSTRUMENTATION_CODE: -1$' "$1"
}
