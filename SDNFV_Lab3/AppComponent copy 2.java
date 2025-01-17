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
package nctu.winlab.bridge;

import org.onlab.packet.Ethernet;
import org.onlab.packet.MacAddress;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
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

import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.ForwardingObjective;

import com.google.common.collect.Maps;
import java.util.Map;

@Component(immediate = true, service = { SomeInterface.class })
public class AppComponent implements SomeInterface {

    private final Logger log = LoggerFactory.getLogger(getClass());

    /** Some configurable property. */
    private ApplicationId appId;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowObjectiveService flowObjectiveService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    private LearningBridgeProcessor processor = new LearningBridgeProcessor();

    protected Map<DeviceId, Map<MacAddress, PortNumber>> macTables = Maps.newConcurrentMap();

    @Activate
    protected void activate() {
        appId = coreService.registerApplication("nctu.winlab.bridge");
        packetService.addProcessor(processor, PacketProcessor.director(3));
        packetService.requestPackets(
                DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4).build(),
                PacketPriority.REACTIVE, appId);
        packetService.requestPackets(
                DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_ARP).build(),
                PacketPriority.REACTIVE, appId);

        log.info("Started", appId.id());
    }

    @Deactivate
    protected void deactivate() {
        packetService.removeProcessor(processor);
        processor = null;
        log.info("Stopped", appId.id());
    }

    private class LearningBridgeProcessor implements PacketProcessor {
        @Override
        public void process(PacketContext context) {
            if (context.isHandled()) {
                return;
            }

            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();
            if (ethPkt == null) {
                return;
            }

            if (ethPkt.getEtherType() != Ethernet.TYPE_IPV4 && ethPkt.getEtherType() != Ethernet.TYPE_ARP) {
                return;
            }

            MacAddress src = ethPkt.getSourceMAC();
            MacAddress dst = ethPkt.getDestinationMAC();
            ConnectPoint cp = pkt.receivedFrom();
            DeviceId devId = cp.deviceId();

            macTables.putIfAbsent(devId, Maps.newConcurrentMap());
            log.info("Add an entry to the port table of `{}`. MAC address: `{}`=> Port: `{}`.",
                    devId.toString(), src.toString(), cp.port());

            // find the port in the table
            Map<MacAddress, PortNumber> currentMacTable = macTables.get(cp.deviceId());
            PortNumber outputPort = currentMacTable.get(dst);
            currentMacTable.put(src, cp.port());
            // port not found and flood the package
            if (outputPort == null) {
                log.info("MAC address `{}` is missed on `{}`. Flood the packet.", dst.toString(), devId.toString());
                flood(context);
            } else {
                log.info("MAC address `{}` is matched on `{}`. Install a flow rule.", dst.toString(), devId.toString());
                installRule(context, outputPort);
                packetOut(context, outputPort);
            }
        }
    }

    // Floods the specified packet if permissible.
    private void flood(PacketContext context) {
        packetOut(context, PortNumber.FLOOD);
    }

    // Sends a packet out the specified port.
    private void packetOut(PacketContext context, PortNumber portNumber) {
        context.treatmentBuilder().setOutput(portNumber);
        context.send();
    }

    // Install a rule forwarding the packet to the specified port.
    private void installRule(PacketContext context, PortNumber portNumber) {
        InboundPacket pkt = context.inPacket();
        Ethernet ethPkt = pkt.parsed();
        MacAddress src = ethPkt.getSourceMAC();
        MacAddress dst = ethPkt.getDestinationMAC();
        DeviceId devId = context.inPacket().receivedFrom().deviceId();

        TrafficSelector selector = DefaultTrafficSelector.builder().matchEthSrc(src).matchEthDst(dst).build();
        TrafficTreatment treatment = DefaultTrafficTreatment.builder().setOutput(portNumber).build();
        ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
                .withSelector(selector)
                .withTreatment(treatment)
                .withPriority(30)
                .withFlag(ForwardingObjective.Flag.VERSATILE)
                .fromApp(appId)
                .makeTemporary(30)
                .add();

        flowObjectiveService.forward(devId, forwardingObjective);
    }
}
