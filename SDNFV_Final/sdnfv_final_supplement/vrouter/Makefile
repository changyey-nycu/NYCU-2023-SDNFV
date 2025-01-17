all:
	sudo ./build_topo.sh
	sudo ./dhcp_start.sh
app:
	mvn clean install -DskipTests
	onos-app localhost install! TA/unicastdhcp-1.0-SNAPSHOT.oar
	onos-app localhost install! target/vrouter-1.0-SNAPSHOT.oar
	onos-app localhost install! TA/proxyarp-1.0-SNAPSHOT.oar
	onos-app localhost install! TA/bridge-1.0-SNAPSHOT.oar
	onos-netcfg localhost config.json
config:
	onos-netcfg localhost config.json
build:
	mvn clean install -DskipTests
test:
	mvn clean install -DskipTests
	onos-app localhost install! target/vrouter-1.0-SNAPSHOT.oar
	onos-netcfg localhost config.json
clean:
	sudo killall dhcpd
	sudo ./clean_topo.sh
uninstall:
	onos-app localhost deactivate nycu.sdnfv.vrouter
	onos-app localhost uninstall nycu.sdnfv.vrouter
