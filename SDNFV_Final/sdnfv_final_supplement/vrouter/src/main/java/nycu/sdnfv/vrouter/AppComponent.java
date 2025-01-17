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
package nycu.sdnfv.vrouter;

import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.FilteredConnectPoint;
import org.onosproject.net.Host;
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
import org.onosproject.net.intent.MultiPointToSinglePointIntent;
import org.onosproject.net.intent.PointToPointIntent;
import org.onosproject.net.intf.Interface;
import org.onosproject.net.intf.InterfaceService;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.onosproject.routeservice.ResolvedRoute;
import org.onosproject.routeservice.RouteService;
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

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// import java.util.List;

import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.IpPrefix;
import org.onlab.packet.MacAddress;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.IpAddress;

/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true)
public class AppComponent {

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected InterfaceService interfaceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected NetworkConfigRegistry ncfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected RouteService routeService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected IntentService intentService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected HostService hostService;

    /** Some configurable property. */
    private ApplicationId appId;

    private VrouterProcessor processor = new VrouterProcessor();
    private final ConfigListener cfgListener = new ConfigListener();
    private final ConfigFactory<ApplicationId, RouterConfig> factory = new ConfigFactory<ApplicationId, RouterConfig>(
            APP_SUBJECT_FACTORY, RouterConfig.class, "router") {
        @Override
        public RouterConfig createConfig() {
            return new RouterConfig();
        }
    };

    String quagga;
    String quagga_mac;
    String virtual_ip;
    String virtual_mac;
    // "172.30.1.2"...
    List<String> peers;
    protected HashMap<String, ConnectPoint> localTable = new HashMap<String, ConnectPoint>();

    @Activate
    protected void activate() {
        appId = coreService.registerApplication("nycu.sdnfv.vrouter");
        ncfgService.addListener(cfgListener);
        ncfgService.registerConfigFactory(factory);
        packetService.addProcessor(processor, PacketProcessor.director(6));
        packetService.requestPackets(DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV4).build(),
                PacketPriority.REACTIVE, appId);
        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        ncfgService.removeListener(cfgListener);
        ncfgService.unregisterConfigFactory(factory);
        log.info("Stopped");
    }

    private class ConfigListener implements NetworkConfigListener {
        @Override
        public void event(NetworkConfigEvent event) {
            if ((event.type() == CONFIG_ADDED || event.type() == CONFIG_UPDATED)) {
                RouterConfig config = ncfgService.getConfig(appId, RouterConfig.class);
                if (config != null) {
                    quagga = config.quagga();
                    quagga_mac = config.quagga_mac();
                    virtual_ip = config.virtual_ip();
                    virtual_mac = config.virtual_mac();
                    peers = config.peers();
                    log.info("quagga = {}", quagga);
                    log.info("quagga_mac = {}", quagga_mac);
                    log.info("virtual_ip = {}", virtual_ip);
                    log.info("virtual_mac = {}", virtual_mac);
                    log.info("peers = {}", peers);
                    // for (Interface interface1 : interfaceService.getInterfaces()) {
                    // String ip =
                    // interface1.ipAddressesList().iterator().next().ipAddress().toString();
                    // log.info("interface ip :{}",ip);
                    // for (String i : peers) {
                    // Interface a =
                    // interfaceService.getMatchingInterface(IpAddress.valueOf(i.substring(1,
                    // i.length() - 1)));
                    // log.info("test interface = {}, size =
                    // {}",a.ipAddressesList().iterator().next().toString(),a.ipAddressesList().size());
                    // bgpIntent(i.substring(1, i.length() - 1), ip);
                    // // bgpIntent(i.substring(1, i.length() - 1), "172.30.2.1");
                    // // bgpIntent(i.substring(1, i.length() - 1), "172.30.3.1");
                    // }
                    // }

                    for (String i : peers) {
                        String outIp = i.substring(1, i.length() - 1);
                        Interface a = interfaceService.getMatchingInterface(IpAddress.valueOf(outIp));
                        String inIp = a.ipAddressesList().iterator().next().toString();
                        log.info("test interface = {}", inIp);
                        bgpIntent(outIp, inIp);
                        // bgpIntent(i.substring(1, i.length() - 1), "172.30.2.1");
                        // bgpIntent(i.substring(1, i.length() - 1), "172.30.3.1");
                    }

                    // String i = peers.get(0);
                    // bgpIntent(i.substring(1, i.length() - 1), "172.30.1.1");
                    // i = peers.get(1);
                    // bgpIntent(i.substring(1, i.length() - 1), "172.30.2.1");
                    // i = peers.get(2);
                    // bgpIntent(i.substring(1, i.length() - 1), "172.30.3.1");
                }
            }
        }

        void bgpIntent(String desIP, String quaggaIP) {
            String ipPre = desIP.concat("/24");

            // String quaggaPre = desIP.substring(0,desIP.length()-1).concat("1/24");
            // String quaggaPre = virtual_ip.concat("/24");
            String quaggaPre = quaggaIP;
            IpPrefix desPrefix = IpPrefix.valueOf(ipPre);
            IpPrefix quaggaPrefix = IpPrefix.valueOf(quaggaPre);

            ConnectPoint quaggaCP = ConnectPoint.deviceConnectPoint(quagga);
            Interface outIntf = interfaceService.getMatchingInterface(Ip4Address.valueOf(desIP));

            TrafficSelector outSelector = DefaultTrafficSelector.builder()
                    .matchEthType((short) 0x0800)
                    .matchIPDst(desPrefix)
                    .build();
            TrafficTreatment treatment = DefaultTrafficTreatment.builder().build();
            PointToPointIntent.Builder outIntent = PointToPointIntent.builder()
                    .appId(appId)
                    .filteredIngressPoint(new FilteredConnectPoint(quaggaCP))
                    .filteredEgressPoint(new FilteredConnectPoint(outIntf.connectPoint()))
                    .selector(outSelector)
                    .treatment(treatment)
                    .priority(30);
            log.info("add eBGP intent from {} to {}", quaggaPre, ipPre);
            intentService.submit(outIntent.build());

            TrafficSelector inSelector = DefaultTrafficSelector.builder()
                    .matchEthType((short) 0x0800)
                    .matchIPDst(quaggaPrefix)
                    .build();
            PointToPointIntent.Builder inIntent = PointToPointIntent.builder()
                    .appId(appId)
                    .filteredIngressPoint(new FilteredConnectPoint(outIntf.connectPoint()))
                    .filteredEgressPoint(new FilteredConnectPoint(quaggaCP))
                    .selector(inSelector)
                    .treatment(treatment)
                    .priority(30);
            log.info("add eBGP intent from {} to {}", ipPre, quaggaPre);
            intentService.submit(inIntent.build());
        }
    }

    private class VrouterProcessor implements PacketProcessor {
        public void process(PacketContext context) {
            if (context.isHandled()) {
                return;
            }

            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();

            ConnectPoint inCP = pkt.receivedFrom();
            String srcMac = MacAddress.valueOf(ethPkt.getSourceMACAddress()).toString();
            localTable.put(srcMac, inCP);

            if (ethPkt.getEtherType() == Ethernet.TYPE_ARP) {
                return;
            }
            IPv4 ipPkt = (IPv4) ethPkt.getPayload();
            IpAddress srcIP = IpAddress.valueOf(ipPkt.getSourceAddress());
            IpAddress desIP = IpAddress.valueOf(ipPkt.getDestinationAddress());

            // special event
            if (desIP.equals(IpAddress.valueOf("224.0.0.251"))) {
                log.info("get 224.0.0.251 return");
                return;
            }

            // ConnectPoint inCP = pkt.receivedFrom();
            ConnectPoint outCP = null;
            String desMAC = null;
            // String srcMac = MacAddress.valueOf(ethPkt.getSourceMACAddress()).toString();

            boolean in = isInDomain(desIP);
            boolean out = isInDomain(srcIP);
            // localTable.put(srcMac, inCP);

            // SDN to SDN (using bridge)
            if (in && out) {
                return;
            }
            log.info("Vrouter Packet srcIP = {} , desIP = {}", srcIP, desIP);
            if (!in && !out) {
                // External to External
                IpAddress nextIP = null;
                String strippre = desIP.toString().concat("/24");
                IpPrefix ipp = IpPrefix.valueOf(strippre);

                // IpPrefix ipp = desIP.toIpPrefix();
                log.info("string ip prefix = {}", ipp.toString());

                Collection<ResolvedRoute> colRoute = routeService.getAllResolvedRoutes(ipp);
                for (ResolvedRoute i : colRoute) {
                    nextIP = i.nextHop();
                }
                log.info("next ip = {}", nextIP.toString());
                Interface outIntf = interfaceService.getMatchingInterface(nextIP);
                if (outIntf != null) {
                    outCP = outIntf.connectPoint();
                }

                Set<Host> desHost = hostService.getHostsByIp(nextIP);

                for (Host i : desHost) {
                    desMAC = i.mac().toString();
                }
                Set<FilteredConnectPoint> iports = new HashSet<>();
                Set<Interface> allInterface = new HashSet<>();
                allInterface.addAll(interfaceService.getInterfaces());
                allInterface.remove(outIntf);
                iports.clear();
                for (Interface i : allInterface) {
                    iports.add(new FilteredConnectPoint(i.connectPoint()));
                }

                log.info("add externel to externl {} intent", ipp.toString());

                TrafficSelector outSelector = DefaultTrafficSelector.builder()
                        .matchEthType((short) 0x0800)
                        .matchIPDst(ipp)
                        .build();
                TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                        .setEthSrc(MacAddress.valueOf(quagga_mac))
                        .setEthDst(MacAddress.valueOf(desMAC))
                        .build();

                MultiPointToSinglePointIntent.Builder intent = MultiPointToSinglePointIntent.builder()
                        .appId(appId)
                        .filteredIngressPoints(iports)
                        .filteredEgressPoint(new FilteredConnectPoint(outCP))
                        .selector(outSelector)
                        .treatment(treatment)
                        .priority(30);
                intentService.submit(intent.build());
            } else if (in) {
                // to SDN
                Set<Host> hostSet = hostService.getHostsByIp(desIP);

                for (Host i : hostSet) {
                    desMAC = i.mac().toString();
                }

                outCP = localTable.get(desMAC);
                if (outCP == null) {
                    return;
                }

                // to SDN
                createIntent(inCP, outCP, desIP, virtual_mac, desMAC);
                // to Externel
                createIntent(outCP, inCP, srcIP, quagga_mac, srcMac);
            } else {
                // to Externel
                IpAddress nextIP = null;
                String strippre = desIP.toString().concat("/24");
                IpPrefix ipp = IpPrefix.valueOf(strippre);

                // IpPrefix ipp = desIP.toIpPrefix();
                log.info("string ip prefix = {}", ipp.toString());

                Collection<ResolvedRoute> colRoute = routeService.getAllResolvedRoutes(ipp);
                for (ResolvedRoute i : colRoute) {
                    nextIP = i.nextHop();
                }
                log.info("next ip = {}", nextIP.toString());
                Interface outIntf = interfaceService.getMatchingInterface(nextIP);
                outCP = outIntf.connectPoint();

                Set<Host> desHost = hostService.getHostsByIp(nextIP);

                for (Host i : desHost) {
                    desMAC = i.mac().toString();
                }
                // to Externel
                createIntent(inCP, outCP, desIP, quagga_mac, desMAC);
                // to SDN
                createIntent(outCP, inCP, srcIP, virtual_mac, srcMac);
            }
            context.block();
        }

        boolean isInDomain(IpAddress ip) {
            String strIP = ip.toString();
            String[] token = strIP.split("\\.");
            if (token[0].equals("192") &&
                    token[1].equals("168") &&
                    token[2].equals("50")) {
                return true;
            }
            return false;
        }

        void createIntent(ConnectPoint inCP, ConnectPoint outCP,
                IpAddress desIP, String srcMAC, String desMAC) {
            IpPrefix desPrefix = desIP.toIpPrefix();
            TrafficSelector outSelector = DefaultTrafficSelector.builder()
                    .matchEthType((short) 0x0800)
                    .matchIPDst(desPrefix)
                    .build();
            TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                    .setEthSrc(MacAddress.valueOf(srcMAC))
                    .setEthDst(MacAddress.valueOf(desMAC))
                    .build();
            PointToPointIntent.Builder outIntent = PointToPointIntent.builder()
                    .appId(appId)
                    .filteredIngressPoint(new FilteredConnectPoint(inCP))
                    .filteredEgressPoint(new FilteredConnectPoint(outCP))
                    .selector(outSelector)
                    .treatment(treatment)
                    .priority(30);
            log.info("add packet intent to {}", desIP.toString());
            intentService.submit(outIntent.build());
        }
    }
}
