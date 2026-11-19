#!/usr/bin/env bash
# Starts the fake model (fake-model.mjs) on 127.0.0.1:FAKE_MODEL_PORT, where seed-ai.sh points
# iTerm2's manual model, and waits until it answers /health. It logs to fake-model.log in the
# diagnostics directory and writes the captured request shape and tool schema there too.
#
# Needs node (20+).

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_tools node curl

fake_model_up() {
    curl --silent --fail --max-time 2 "http://127.0.0.1:${FAKE_MODEL_PORT}/health" >/dev/null
}
fake_model_up && die "something already answers on 127.0.0.1:${FAKE_MODEL_PORT}"

FAKE_MODEL_PORT="$FAKE_MODEL_PORT" \
    FAKE_MODEL_DIAGNOSTICS_DIR="$DIAGNOSTICS_DIR" \
    FAKE_MODEL_TOOL="$FAKE_MODEL_TOOL" \
    FAKE_MODEL_TOOL_SCHEMA_SHA256="$FAKE_MODEL_TOOL_SCHEMA_SHA256" \
    nohup node "$REAL_ITERM2_SCRIPTS/fake-model.mjs" >> "$FAKE_MODEL_LOG" 2>&1 &
echo $! > "$WORK_DIR/fake-model.pid"
wait_for 30 fake_model_up || { indent < "$FAKE_MODEL_LOG"; die "the fake model did not start"; }
log "fake model up (pid $(cat "$WORK_DIR/fake-model.pid")): $FAKE_MODEL_URL"
indent < "$FAKE_MODEL_LOG"
