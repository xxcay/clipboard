#!/bin/sh
# Installs (or updates) clipd on OpenWrt / GL.iNet firmware.
# Usage, on the router, from the unpacked folder:  sh install.sh
set -e
cd "$(dirname "$0")"

if [ ! -f clipd ]; then
	echo "Ошибка: рядом со скриптом нет файла clipd" >&2
	exit 1
fi

echo "==> Копирую файлы"
[ -x /etc/init.d/clipd ] && /etc/init.d/clipd stop 2>/dev/null || true
cp clipd /usr/bin/clipd
chmod 755 /usr/bin/clipd
cp clipd.init /etc/init.d/clipd
chmod 755 /etc/init.d/clipd
# Keep the existing settings (and token) on update.
[ -f /etc/config/clipd ] || cp clipd.config /etc/config/clipd

if ! /usr/bin/clipd -version >/dev/null 2>&1; then
	echo "Ошибка: clipd не запускается на этом роутере (другая архитектура?)" >&2
	uname -m >&2
	exit 1
fi

token="$(uci -q get clipd.main.token || true)"
if [ -z "$token" ]; then
	echo "==> Генерирую токен"
	token="$(tr -dc 'A-Za-z0-9' </dev/urandom | head -c 24)"
	uci set clipd.main.token="$token"
	uci commit clipd
fi

echo "==> Имя clip.lan в локальной сети"
ip="$(uci -q get network.lan.ipaddr || echo 192.168.8.1)"
ip="${ip%% *}"
ip="${ip%%/*}"
if ! uci -q get dhcp.@dnsmasq[0].address | grep -q "/clip.lan/"; then
	uci add_list dhcp.@dnsmasq[0].address="/clip.lan/$ip"
	uci commit dhcp
	/etc/init.d/dnsmasq restart >/dev/null 2>&1 || true
fi

# Keep clipd across firmware upgrades ("Keep settings").
for f in /usr/bin/clipd /etc/init.d/clipd /etc/config/clipd; do
	grep -qxF "$f" /etc/sysupgrade.conf 2>/dev/null || echo "$f" >>/etc/sysupgrade.conf
done

echo "==> Запускаю службу"
/etc/init.d/clipd enable
/etc/init.d/clipd start

listen="$(uci -q get clipd.main.listen || echo ':8765')"
port="${listen##*:}"
ok=""
for i in 1 2 3 4 5; do
	sleep 1
	if wget -qO- "http://127.0.0.1:$port/healthz" 2>/dev/null | grep -q '"ok":true'; then
		ok=1
		break
	fi
done

echo
if [ -n "$ok" ]; then
	echo "Готово! clipd $(/usr/bin/clipd -version) работает."
else
	echo "Служба не ответила. Посмотрите журнал: logread -e clipd"
fi
echo
echo "  Адрес:  http://clip.lan:$port   (или http://$ip:$port)"
echo "  Токен:  $token"
echo
echo "  Быстрая ссылка для браузера (токен подставится сам):"
echo "  http://$ip:$port/#token=$token"
