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

import java.util.ArrayList;
import java.util.List;

import org.onosproject.core.ApplicationId;
import org.onosproject.net.config.Config;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.JsonNode;

public class RouterConfig extends Config<ApplicationId> {
    public static final String QUAGGA = "quagga";
    public static final String QUAGGAMAC = "quagga-mac";
    public static final String VIRTUALIP = "virtual-ip";
    public static final String VIRTUALMAC = "virtual-mac";
    public static final String PEERS = "peers";

    public String quagga() {
        return get(QUAGGA, null);
    }

    public String quagga_mac() {
        return get(QUAGGAMAC, null);
    }

    public String virtual_ip() {
        return get(VIRTUALIP, null);
    }

    public String virtual_mac() {
        return get(VIRTUALMAC, null);
    }

    public List<String> peers() {
        List<String> list = new ArrayList<>();
        JsonNode jsonNode = object.path(PEERS);
        
        ArrayNode arrayNode = (ArrayNode) jsonNode;
        arrayNode.forEach(i -> list.add(i.toString()));
        return list;
    }
}