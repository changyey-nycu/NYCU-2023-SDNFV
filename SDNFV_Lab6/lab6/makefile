all:
	sudo docker compose up -d
	sudo ovs-vsctl add-br ovsbr0
	sudo ovs-docker add-port ovsbr0 eth1 R1 --ipaddress=172.20.0.2/16
	sudo ovs-docker add-port ovsbr0 eth2 R2 --ipaddress=172.20.0.3/16
	sudo ovs-docker add-port ovsbr0 eth3 R3 --ipaddress=172.20.0.4/16
	sudo ovs-vsctl set bridge ovsbr0 protocols=OpenFlow14
	sudo ovs-vsctl set-controller ovsbr0 tcp:127.0.0.1:6633

clean:
	sudo ovs-docker del-port ovsbr0 eth1 R1
	sudo ovs-docker del-port ovsbr0 eth2 R2
	sudo ovs-docker del-port ovsbr0 eth3 R3
	sudo ovs-vsctl del-controller ovsbr0
	sudo ovs-vsctl del-br ovsbr0
	sudo docker compose down