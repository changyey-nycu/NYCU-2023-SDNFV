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
package nctu.winlab.unicastdhcp;

import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.FilteredConnectPoint;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.host.HostService;
import org.onosproject.net.intent.IntentService;
import org.onosproject.net.intent.PointToPointIntent;
import org.onosproject.net.packet.InboundPacket;
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

import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_ADDED;
import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_UPDATED;
import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;

import org.onlab.packet.DHCP;
import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.MacAddress;
import org.onlab.packet.TpPort;
import org.onlab.packet.UDP;

/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true)
public class AppComponent {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private ApplicationId appId;
    private ConnectPoint dhcpServer;

    private final NameConfigListener cfgListener = new NameConfigListener();
    private final ConfigFactory<ApplicationId, ServerLocationConfig> factory
     = new ConfigFactory<ApplicationId, ServerLocationConfig>(
            APP_SUBJECT_FACTORY, ServerLocationConfig.class, "UnicastDhcpConfig") {
        @Override
        public ServerLocationConfig createConfig() {
            return new ServerLocationConfig();
        }
    };

    private UnicastDhcpProcessor processor = new UnicastDhcpProcessor();

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected NetworkConfigRegistry ncfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected IntentService intentService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    HostService hostService;

    @Activate
    protected void activate() {
        appId = coreService.registerApplication("nctu.winlab.unicastdhcp");
        ncfgService.addListener(cfgListener);
        ncfgService.registerConfigFactory(factory);
        packetService.addProcessor(processor, PacketProcessor.director(3));
        packetService.requestPackets(
                DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4)
                        .matchIPProtocol(IPv4.PROTOCOL_UDP).build(),
                PacketPriority.REACTIVE, appId);
    }

    @Deactivate
    protected void deactivate() {
        ncfgService.removeListener(cfgListener);
        ncfgService.unregisterConfigFactory(factory);
        packetService.removeProcessor(processor);
        processor = null;
    }

    private class NameConfigListener implements NetworkConfigListener {
        @Override
        public void event(NetworkConfigEvent event) {
            if ((event.type() == CONFIG_ADDED || event.type() == CONFIG_UPDATED)
                    && event.configClass().equals(ServerLocationConfig.class)) {
                ServerLocationConfig config = ncfgService.getConfig(appId, ServerLocationConfig.class);
                if (config != null) {
                    String str = config.serverlocation();
                    String[] tokens = str.split("/");
                    String did = tokens[0];
                    String port = tokens[1];
                    log.info("DHCP server is connected to `{}`, port `{}`", did, port);
                    dhcpServer = ConnectPoint.deviceConnectPoint(str);
                }
            }
        }
    }

    private class UnicastDhcpProcessor implements PacketProcessor {
        @Override
        public void process(PacketContext context) {
            if (context.isHandled()) {
                return;
            }
            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();
            if (ethPkt == null || ethPkt.getEtherType() != Ethernet.TYPE_IPV4) {
                return;
            }
            IPv4 ipPkt = (IPv4) ethPkt.getPayload();
            if (ipPkt.getProtocol() != IPv4.PROTOCOL_UDP) {
                return;
            }
            UDP udpPkt = (UDP) ipPkt.getPayload();
            if ((udpPkt.getSourcePort() != UDP.DHCP_CLIENT_PORT
                    && udpPkt.getDestinationPort() != UDP.DHCP_SERVER_PORT)
                    && (udpPkt.getSourcePort() != UDP.DHCP_SERVER_PORT
                            && udpPkt.getDestinationPort() != UDP.DHCP_CLIENT_PORT)) {
                return;
            }

            MacAddress src = ethPkt.getSourceMAC();
            ConnectPoint cp = pkt.receivedFrom();
            DHCP dhcpPkt = (DHCP) udpPkt.getPayload();
            if (dhcpPkt.getPacketType() == DHCP.MsgType.DHCPDISCOVER
             || dhcpPkt.getPacketType() == DHCP.MsgType.DHCPREQUEST
             || dhcpPkt.getPacketType() == DHCP.MsgType.DHCPRELEASE) {
                createToServerIntent(cp, src);
                createToClientIntent(cp, src);
            }
        }

        void createToServerIntent(ConnectPoint cp, MacAddress clientMac) {
            TrafficSelector selector = DefaultTrafficSelector.builder()
                    .matchEthType((short) 0x0800)
                    .matchIPProtocol((byte) 17)
                    .matchUdpDst(TpPort.tpPort(67))
                    .matchUdpSrc(TpPort.tpPort(68))
                    .matchEthSrc(clientMac)
                    .build();
            TrafficTreatment treatment = DefaultTrafficTreatment.builder().build();
            PointToPointIntent.Builder intent = PointToPointIntent.builder()
                    .appId(appId)
                    .filteredIngressPoint(new FilteredConnectPoint(cp))
                    .filteredEgressPoint(new FilteredConnectPoint(dhcpServer))
                    .selector(selector)
                    .treatment(treatment)
                    .priority(30);
            log.info("Intent `{}`, port `{}` => `{}`, port `{}` is submitted.",
                    cp.deviceId(), cp.port(), dhcpServer.deviceId(), dhcpServer.port());
            intentService.submit(intent.build());
        }

        void createToClientIntent(ConnectPoint cp, MacAddress clientMac) {
            TrafficSelector selector = DefaultTrafficSelector.builder()
                    .matchEthType((short) 0x0800)
                    .matchIPProtocol((byte) 17)
                    .matchUdpDst(TpPort.tpPort(68))
                    .matchUdpSrc(TpPort.tpPort(67))
                    .matchEthDst(clientMac)
                    .build();
            TrafficTreatment treatment = DefaultTrafficTreatment.builder().build();
            PointToPointIntent.Builder intent = PointToPointIntent.builder()
                    .appId(appId)
                    .filteredIngressPoint(new FilteredConnectPoint(dhcpServer))
                    .filteredEgressPoint(new FilteredConnectPoint(cp))
                    .selector(selector)
                    .treatment(treatment)
                    .priority(30);
            log.info("Intent `{}`, port `{}` => `{}`, port `{}` is submitted.",
                    dhcpServer.deviceId(), dhcpServer.port(), cp.deviceId(), cp.port());
            intentService.submit(intent.build());
        }
    }

}
