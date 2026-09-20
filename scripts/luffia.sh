#!/bin/bash
BOT_DIR="/home/vitamin/luffia/JDA-Luffia"
SCREEN_NAME="luffia"
JAVA="java"
JAVA_OPTS="-Xms512M -Xmx1G -Dfile.encoding=UTF-8 --enable-native-access=ALL-UNNAMED"
MAIN_CLASS="kr.kro.backas.Main"

is_running() {
  ps -ef | grep -v "grep" | grep SCREEN | grep -q "$SCREEN_NAME"
}

case "$1" in
  start)
    if is_running; then
      echo "${SCREEN_NAME} is already running."
    else
      screen -dmS "$SCREEN_NAME" bash -c "cd '$BOT_DIR' && $JAVA $JAVA_OPTS -cp 'lib/*' $MAIN_CLASS"
    fi
    ;;
  stop)
    if is_running; then
      screen -S "$SCREEN_NAME" -X quit
      echo "${SCREEN_NAME} stopped."
    else
      echo "${SCREEN_NAME} is not running."
    fi
    ;;
  restart)
    "$0" stop
    sleep 2
    "$0" start
    ;;
  view)
    screen -x "$SCREEN_NAME"
    ;;
  sv)
    if is_running; then
      screen -x "$SCREEN_NAME"
    else
      screen -S "$SCREEN_NAME" bash -c "cd '$BOT_DIR' && $JAVA $JAVA_OPTS -cp 'lib/*' $MAIN_CLASS"
    fi
    ;;
  status)
    if is_running; then
      echo "${SCREEN_NAME} is running."
    else
      echo "${SCREEN_NAME} is not running."
    fi
    ;;
  *)
    echo "$0 (start|stop|restart|view|sv|status)"
    ;;
esac
