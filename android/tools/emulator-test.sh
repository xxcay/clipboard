#!/bin/bash
# End-to-end check of the release APK on an Android emulator:
# runs clipd on the host (the emulator sees it as 10.0.2.2), sets the app up
# through a sharedclipboard://setup link, and walks the main screens,
# saving screenshots to ./screens. Fails on a crash or a missing connection.
set -u
APK=${1:-android/SharedClipboard.apk}
PKG=io.github.xxcay.clipboard
OUT=screens
mkdir -p $OUT
UI="python3 android/tools/ui.py"
H='Authorization: Bearer tok'
fail=0

shot() { sleep "${2:-2}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }
post() { curl -s -H "$H" -H "X-Device: $1" --data-binary "$2" http://127.0.0.1:8765/api/clip > /dev/null; }

(cd server && go build -o /tmp/clipd .)
CLIPD_TOKEN=tok /tmp/clipd -listen 0.0.0.0:8765 -data /tmp/clipd-data > /tmp/clipd.log 2>&1 &
sleep 2
post "%D0%9D%D0%BE%D1%83%D1%82%D0%B1%D1%83%D0%BA" "https://youtu.be/dQw4w9WgXcQ"
post "%D0%9D%D0%BE%D1%83%D1%82%D0%B1%D1%83%D0%BA" "Адрес доставки: ул. Ленина, 10, кв. 5. Домофон 25, код 1234"
python3 android/tools/make_png.py /tmp/IMG_2031.png
curl -s -H "$H" -H "X-Device: %D0%9F%D0%9A" -T /tmp/IMG_2031.png "http://127.0.0.1:8765/api/files?name=IMG_2031.png" > /dev/null
head -c 2500000 /dev/urandom > /tmp/deck.pptx
curl -s -H "$H" -H "X-Device: %D0%9F%D0%9A" -T /tmp/deck.pptx "http://127.0.0.1:8765/api/files?name=%D0%9F%D1%80%D0%B5%D0%B7%D0%B5%D0%BD%D1%82%D0%B0%D1%86%D0%B8%D1%8F.pptx" > /dev/null

adb install -r "$APK" || exit 1
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb logcat -c

adb shell am start -n $PKG/.MainActivity
shot 01-first-run 6

adb shell am start -a android.intent.action.VIEW \
  -d "'sharedclipboard://setup?server=http%3A%2F%2F10.0.2.2%3A8765&token=tok&name=%D0%A2%D0%B5%D0%BB%D0%B5%D1%84%D0%BE%D0%BD'"
shot 02-setup-link 3
$UI tap-scroll "Проверить связь" && shot 03-check 3
$UI tap-scroll "Сохранить"
shot 04-main 6

echo "--- hub after setup"
health=$(curl -s http://127.0.0.1:8765/healthz); echo "$health"
echo "$health" | grep -q 'Телефон' || { echo "PHONE NOT CONNECTED"; fail=1; }

post "%D0%9F%D0%9A" "Новая заметка с компьютера"
shot 05-new-item 3

$UI long "Новая заметка" && shot 06-sheet 2
adb shell input keyevent KEYCODE_BACK
sleep 1

# Deleted on the PC -> gone on the phone (app open).
del_id() { curl -s -H "$H" http://127.0.0.1:8765/api/items | python3 -c "import json,sys; print(next(i['id'] for i in json.load(sys.stdin) if '$1' in i.get('text','') or '$1' in (i.get('file') or {}).get('name','')))"; }
curl -s -X DELETE -H "$H" "http://127.0.0.1:8765/api/items/$(del_id 'Новая заметка')"
sleep 2
if python3 android/tools/ui.py has "Новая заметка" >/dev/null; then echo "DELETE NOT SYNCED (open app)"; fail=1; else echo "delete sync (open app): OK"; fi

# Deleted while the app is in the background -> gone when it comes back.
adb shell input keyevent KEYCODE_HOME
sleep 1
curl -s -X DELETE -H "$H" "http://127.0.0.1:8765/api/items/$(del_id 'Адрес доставки')"
sleep 1
adb shell am start -n $PKG/.MainActivity
sleep 3
if python3 android/tools/ui.py has "Адрес доставки" >/dev/null; then echo "DELETE NOT SYNCED (background)"; fail=1; else echo "delete sync (background): OK"; fi
shot 06b-after-delete 1

# Swipe a card right to delete it (hold the finger to see the trash can).
pos=$(python3 android/tools/ui.py where "youtu.be") && read X Y <<< "$pos"
if [ -n "${X:-}" ]; then
  adb shell input motionevent DOWN $X $Y
  for dx in 60 140 220; do adb shell input motionevent MOVE $((X + dx)) $Y; done
  shot 06c-swipe-half 0.3
  for dx in 330 450 560; do adb shell input motionevent MOVE $((X + dx)) $Y; done
  shot 06d-swipe-armed 0.4
  adb shell input motionevent UP $((X + 560)) $Y
  sleep 3
  shot 06e-swiped 0
  curl -s -H "$H" http://127.0.0.1:8765/api/items | grep -q 'dQw4w9WgXcQ' \
    && { echo "SWIPE DELETE FAILED"; fail=1; } || echo "swipe delete: OK"
else
  echo "SWIPE: item not found"; fail=1
fi

$UI tap "Файлы" && shot 07-files 3
$UI tap "Все"

$UI tap "Настройки" && shot 08-settings 2
adb shell input swipe 540 1700 540 500 300
shot 09-settings-bottom 2
adb shell input keyevent KEYCODE_BACK

# Background: the service should post a notification for a new item.
adb shell input keyevent KEYCODE_HOME
sleep 2
post "%D0%9F%D0%9A" "https://github.com/xxcay/clipboard"
sleep 4
adb shell cmd statusbar expand-notifications
shot 10-notification 2
adb shell cmd statusbar collapse

# Share text from another app.
adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "'Текст из меню Поделиться'" -n $PKG/.ShareActivity
shot 11-share 1
sleep 4
curl -s -H "$H" http://127.0.0.1:8765/api/items | grep -q 'Текст из меню Поделиться' \
  && echo "share: OK" || { echo "SHARE FAILED"; fail=1; }

echo "--- clipd log"; cat /tmp/clipd.log
echo "--- crashes"
# The crash buffer holds only real crashes (other logs mention AndroidRuntime too).
adb logcat -d -b crash | grep -A 30 "$PKG" | tee $OUT/crash.txt
if [ -s $OUT/crash.txt ]; then echo "APP CRASHED"; fail=1; else rm -f $OUT/crash.txt; fi
adb logcat -d | grep -iE "$PKG|clipboard" | tail -60 > $OUT/logcat.txt
exit $fail
