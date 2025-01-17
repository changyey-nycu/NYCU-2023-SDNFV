/*
 * Copyright 2023-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nctu.winlab.ProxyArp;

import java.nio.ByteBuffer;
import java.util.HashMap;

import org.onlab.packet.ARP;
import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.MacAddress;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.edge.EdgePortService;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.packet.DefaultOutboundPacket;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.OutboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true)
public class AppComponent implements SomeInterface {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private ApplicationId appId;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected EdgePortService edgePortService;

    private ProxyArpProcessor processor = new ProxyArpProcessor();

    protected HashMap<String, MacAddress> arpTable = new HashMap<String, MacAddress>();
    protected HashMap<MacAddress, ConnectPoint> localTable = new HashMap<MacAddress, ConnectPoint>();

    @Activate
    protected void activate() {
        appId = coreService.registerApplication("nctu.winlab.ProxyArp");
        packetService.addProcessor(processor, PacketProcessor.director(3));
        packetService.requestPackets(
                DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_ARP).build(),
                PacketPriority.REACTIVE, appId);
    }

    @Deactivate
    protected void deactivate() {
        packetService.removeProcessor(processor);
        processor = null;
    }

    private class ProxyArpProcessor implements PacketProcessor {
        public void process(PacketContext context) {
            if (context.isHandled()) {
                return;
            }
            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();
            if (ethPkt == null) {
                return;
            }

            if (ethPkt.getEtherType() != Ethernet.TYPE_ARP) {
                return;
            }
            ARP arpPkt = (ARP) ethPkt.getPayload();

            MacAddress srcMac = MacAddress.valueOf(arpPkt.getSenderHardwareAddress());
            MacAddress desMac = MacAddress.valueOf(arpPkt.getTargetHardwareAddress());

            String srcIpAddress = IPv4.fromIPv4Address(IPv4.toIPv4Address(arpPkt.getSenderProtocolAddress()));

            arpTable.put(srcIpAddress, srcMac);
            localTable.put(srcMac, pkt.receivedFrom());

            if (arpPkt.getOpCode() == ARP.OP_REQUEST) // request
            {
                String desIpAddress = IPv4.fromIPv4Address(IPv4.toIPv4Address(arpPkt.getTargetProtocolAddress()));
                desMac = arpTable.get(desIpAddress);
                if (desMac == null) {
                    log.info("TABLE MISS. Send request to edge ports");
                    Iterable<ConnectPoint> edgePoints = edgePortService.getEdgePoints();
                    for (ConnectPoint edgePoint : edgePoints) {
                        if (edgePoint == pkt.receivedFrom())
                            continue;
                        ByteBuffer bytePkt = ByteBuffer.wrap(ethPkt.serialize());
                        TrafficTreatment treatment = DefaultTrafficTreatment.builder().setOutput(edgePoint.port())
                                .build();
                        OutboundPacket oPacket = new DefaultOutboundPacket(edgePoint.deviceId(), treatment, bytePkt);
                        packetService.emit(oPacket);
                    }

                } else {
                    log.info("TABLE HIT. Requested MAC = {}", desMac);
                    ConnectPoint cp = localTable.get(srcMac);

                    Ethernet eth = buildPacket(ARP.OP_REPLY, srcIpAddress, desIpAddress, srcMac, desMac);

                    ByteBuffer bytePkt = ByteBuffer.wrap(eth.serialize());
                    TrafficTreatment treatment = DefaultTrafficTreatment.builder().setOutput(cp.port()).build();
                    OutboundPacket oPacket = new DefaultOutboundPacket(cp.deviceId(), treatment, bytePkt);
                    packetService.emit(oPacket);
                }
            } else if (arpPkt.getOpCode() == ARP.OP_REPLY)// reply
            {
                log.info("RECV REPLY. Requested MAC = {}", srcMac);

                ConnectPoint cp = localTable.get(desMac);
                String desIpAddress = IPv4.fromIPv4Address(IPv4.toIPv4Address(arpPkt.getSenderProtocolAddress()));
                Ethernet eth = buildPacket(ARP.OP_REPLY, desIpAddress, srcIpAddress,
                        desMac, srcMac);
                ByteBuffer bytePkt = ByteBuffer.wrap(eth.serialize());
                TrafficTreatment treatment = DefaultTrafficTreatment.builder().setOutput(cp.port()).build();
                OutboundPacket oPacket = new DefaultOutboundPacket(cp.deviceId(), treatment, bytePkt);
                packetService.emit(oPacket);
            }
        }
    }

    private Ethernet buildPacket(short op, String desIp, String srcIp, MacAddress desMac, MacAddress srcMac) {
        ARP arp = new ARP();
        arp.setOpCode(ARP.OP_REPLY);
        arp.setProtocolType(ARP.PROTO_TYPE_IP);
        arp.setHardwareType(ARP.HW_TYPE_ETHERNET);
        arp.setProtocolAddressLength((byte) Ip4Address.BYTE_LENGTH);
        arp.setHardwareAddressLength((byte) Ethernet.DATALAYER_ADDRESS_LENGTH);
        arp.setTargetHardwareAddress(desMac.toBytes());
        arp.setSenderHardwareAddress(srcMac.toBytes());
        arp.setTargetProtocolAddress(IPv4.toIPv4Address(desIp));
        arp.setSenderProtocolAddress(IPv4.toIPv4Address(srcIp));

        Ethernet eth = new Ethernet();
        eth.setDestinationMACAddress(desMac);
        eth.setSourceMACAddress(srcMac);
        eth.setEtherType(Ethernet.TYPE_ARP);
        eth.setPayload(arp);
        return eth;
    }
}
