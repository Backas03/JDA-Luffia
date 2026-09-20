#!/bin/bash
BASE_DIR="/home/vitamin/kimnore"
BOT_JAR="$BASE_DIR/JDA-Luffia-1.0.0-SNAPSHOT-all.jar"
BOT_SCREEN="kimnore"
TRANSLATOR_DIR="$BASE_DIR/translator"
TRANSLATOR_SCREEN="translator"
JAVA="java"
JAVA_OPTS="-Xms512M -Xmx2G -Dfile.encoding=UTF-8"

is_running() {
  screen -ls | grep -qE "\.$1[[:space:]]"
}

start_translator() {
  if is_running "$TRANSLATOR_SCREEN"; then
    echo "${TRANSLATOR_SCREEN} is already running."
  else
    screen -dmS "$TRANSLATOR_SCREEN" bash -c "cd '$TRANSLATOR_DIR' && ./run.sh"
    echo "${TRANSLATOR_SCREEN} started."
  fi
}

stop_translator() {
  if is_running "$TRANSLATOR_SCREEN"; then
    screen -S "$TRANSLATOR_SCREEN" -X quit
    echo "${TRANSLATOR_SCREEN} stopped."
  else
    echo "${TRANSLATOR_SCREEN} is not running."
  fi
}

start_bot() {
  if is_running "$BOT_SCREEN"; then
    echo "${BOT_SCREEN} is already running."
  else
    screen -dmS "$BOT_SCREEN" bash -c "cd '$BASE_DIR' && $JAVA $JAVA_OPTS -jar '$BOT_JAR'"
    echo "${BOT_SCREEN} started."
  fi
}

stop_bot() {
  if is_running "$BOT_SCREEN"; then
    screen -S "$BOT_SCREEN" -X quit
    echo "${BOT_SCREEN} stopped."
  else
    echo "${BOT_SCREEN} is not running."
  fi
}

TARGET="${2:-all}"

case "$1" in
  start)
    [ "$TARGET" = "all" ] || [ "$TARGET" = "translator" ] && start_translator
    [ "$TARGET" = "all" ] || [ "$TARGET" = "bot" ] && start_bot
    ;;
  stop)
    [ "$TARGET" = "all" ] || [ "$TARGET" = "bot" ] && stop_bot
    [ "$TARGET" = "all" ] || [ "$TARGET" = "translator" ] && stop_translator
    ;;
  restart)
    "$0" stop "$TARGET"
    sleep 2
    "$0" start "$TARGET"
    ;;
  view)
    if [ "$TARGET" = "translator" ]; then
      screen -x "$TRANSLATOR_SCREEN"
    else
      screen -x "$BOT_SCREEN"
    fi
    ;;
  sv)
    start_translator
    if is_running "$BOT_SCREEN"; then
      screen -x "$BOT_SCREEN"
    else
      screen -S "$BOT_SCREEN" bash -c "cd '$BASE_DIR' && $JAVA $JAVA_OPTS -jar '$BOT_JAR'"
    fi
    ;;
  status)
    screen -ls
    ;;
  *)
    echo "$0 (start|stop|restart|view|sv|status) [all|bot|translator]"
    ;;
esac
