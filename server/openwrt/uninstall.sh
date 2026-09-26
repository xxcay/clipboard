#!/bin/sh
# Removes clipd from the router.
/etc/init.d/clipd stop 2>/dev/null
/etc/init.d/clipd disable 2>/dev/null
rm -f /usr/bin/clipd /etc/init.d/clipd /etc/config/clipd
rm -rf /tmp/clipd

ip="$(uci -q get network.lan.ipaddr || echo 192.168.8.1)"
ip="${ip%% *}"
ip="${ip%%/*}"
if uci -q get dhcp.@dnsmasq[0].address | grep -q "/clip.lan/$ip"; then
	uci del_list dhcp.@dnsmasq[0].address="/clip.lan/$ip"
	uci commit dhcp
	/etc/init.d/dnsmasq restart >/dev/null 2>&1
fi
sed -i '\#^/usr/bin/clipd$#d; \#^/etc/init.d/clipd$#d; \#^/etc/config/clipd$#d' /etc/sysupgrade.conf 2>/dev/null
echo "clipd удалён."
