all:
	docker compose up -d
	ovs-vsctl add-br ovsbr0
	ovs-docker add-port ovsbr0 eth1 R1 --ipaddress=172.20.0.2/16
	ovs-docker add-port ovsbr0 eth2 R2 --ipaddress=172.20.0.3/16
	ovs-docker add-port ovsbr0 eth3 R3 --ipaddress=172.20.0.4/16
	ovs-vsctl set bridge ovsbr0 protocols=OpenFlow13
	ovs-vsctl set-controller ovsbr0 tcp:127.0.0.1:6633

clean:
	ovs-docker del-port ovsbr0 eth1 R1
	ovs-docker del-port ovsbr0 eth2 R2
	ovs-docker del-port ovsbr0 eth3 R3
	ovs-vsctl del-controller ovsbr0
	ovs-vsctl del-br ovsbr0
	docker compose down