#!/usr/bin/env bash
# Reliable local/VPS lifecycle wrapper for the swing alert monitor.
# Resolves the repository directory itself, so it cannot accidentally
# launch a Clojure REPL because the :swing-alerts alias was not found.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PID_FILE="$REPO_DIR/.richbot-swing-alerts.pid"
LOG_FILE="$REPO_DIR/swing-alerts.log"

read_pid() {
  local pid
  if [[ -f "$PID_FILE" ]]; then
    IFS= read -r pid < "$PID_FILE" || true
    if [[ "$pid" =~ ^[0-9]+$ ]]; then
      printf '%s' "$pid"
    fi
  fi
}

is_running() {
  local command pid
  pid="$(read_pid)"
  [[ -n "$pid" ]] || return 1
  kill -0 "$pid" 2>/dev/null || return 1
  command="$(ps -p "$pid" -o args= 2>/dev/null || true)"
  [[ "$command" == *"richbot.core swing-alerts"* ]]
}

start() {
  local pid
  if is_running; then
    echo "Swing alerts already running (PID $(read_pid))."
    return 0
  fi

  cd "$REPO_DIR"
  nohup clojure -M:swing-alerts </dev/null >>"$LOG_FILE" 2>&1 &
  pid=$!
  printf '%s\n' "$pid" > "$PID_FILE"
  sleep 2

  if kill -0 "$pid" 2>/dev/null; then
    echo "Swing alerts started (PID $pid). Log: $LOG_FILE"
  else
    echo "Swing alerts failed to start. Last log lines:" >&2
    tail -40 "$LOG_FILE" >&2 || true
    rm -f "$PID_FILE"
    return 1
  fi
}

stop() {
  local pid
  pid="$(read_pid)"
  if ! is_running; then
    rm -f "$PID_FILE"
    echo "Swing alerts is not running."
    return 0
  fi

  kill "$pid"
  for _ in {1..20}; do
    if ! kill -0 "$pid" 2>/dev/null; then
      rm -f "$PID_FILE"
      echo "Swing alerts stopped."
      return 0
    fi
    sleep 0.25
  done

  echo "Swing alerts did not stop cleanly (PID $pid)." >&2
  return 1
}

status() {
  if is_running; then
    echo "Swing alerts running (PID $(read_pid))."
  else
    rm -f "$PID_FILE"
    echo "Swing alerts stopped."
  fi
  cd "$REPO_DIR"
  clojure -M:swing-alerts-status
}

foreground() {
  cd "$REPO_DIR"
  exec clojure -M:swing-alerts
}

once() {
  cd "$REPO_DIR"
  exec clojure -M:swing-alerts-once
}

case "${1:-status}" in
  start) start ;;
  stop) stop ;;
  restart)
    stop
    start
    ;;
  status) status ;;
  foreground) foreground ;;
  once) once ;;
  *)
    echo "Usage: $0 {start|stop|restart|status|foreground|once}" >&2
    exit 2
    ;;
esac
