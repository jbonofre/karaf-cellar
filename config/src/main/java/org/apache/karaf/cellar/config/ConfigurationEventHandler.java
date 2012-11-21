/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.karaf.cellar.config;

import org.apache.karaf.cellar.core.Configurations;
import org.apache.karaf.cellar.core.Group;
import org.apache.karaf.cellar.core.control.BasicSwitch;
import org.apache.karaf.cellar.core.control.Switch;
import org.apache.karaf.cellar.core.control.SwitchStatus;
import org.apache.karaf.cellar.core.event.EventHandler;
import org.apache.karaf.cellar.core.event.EventType;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Dictionary;
import java.util.Map;
import java.util.Properties;

/**
 * Configuration event handler.
 */
public class ConfigurationEventHandler extends ConfigurationSupport implements EventHandler<RemoteConfigurationEvent> {

    private static final transient Logger LOGGER = LoggerFactory.getLogger(ConfigurationEventHandler.class);

    public static final String SWITCH_ID = "org.apache.karaf.cellar.configuration.handler";

    private final Switch eventSwitch = new BasicSwitch(SWITCH_ID);

    public void handle(RemoteConfigurationEvent event) {

        // check if the handler is ON
        if (this.getSwitch().getStatus().equals(SwitchStatus.OFF)) {
            LOGGER.warn("CELLAR CONFIG: {} switch is OFF, cluster event not handled", SWITCH_ID);
            return;
        }

        // check if the group is local
        if (!groupManager.isLocalGroup(event.getSourceGroup().getName())) {
            LOGGER.debug("CELLAR CONFIG: node is not part of the event cluster group");
            return;
        }

        Group group = event.getSourceGroup();
        String groupName = group.getName();

        Map<String, Properties> distributedConfigurations = clusterManager.getMap(Constants.CONFIGURATION_MAP + Configurations.SEPARATOR + groupName);

        String pid = event.getId();

        if (isAllowed(event.getSourceGroup(), Constants.CATEGORY, pid, EventType.INBOUND)) {

            Properties distributedDictionary = distributedConfigurations.get(pid);
            Configuration conf;
            try {
                conf = configurationAdmin.getConfiguration(pid, null);
                if (event.getType() == ConfigurationEvent.CM_DELETED) {
                    if (conf.getProperties() != null) {
                        // delete the properties
                        conf.delete();
                        deleteStorage(pid);
                    }
                } else {
                    if (distributedDictionary != null) {
                        Dictionary localDictionary = conf.getProperties();
                        if (localDictionary == null)
                            localDictionary = new Properties();
                        localDictionary = filter(localDictionary);
                        if (!equals(distributedDictionary, localDictionary)) {
                            conf.update(distributedDictionary);
                            persistConfiguration(configurationAdmin, pid, distributedDictionary);
                        }
                    }
                }
            } catch (IOException ex) {
                LOGGER.error("CELLAR CONFIG: failed to read remote distributed map", ex);
            }
        } else LOGGER.warn("CELLAR CONFIG: configuration with pid {} is marked as BLOCKED INBOUND", pid);
    }

    /**
     * Initialization Method.
     */
    public void init() {

    }

    /**
     * Destruction Method.
     */
    public void destroy() {

    }

    public Switch getSwitch() {
        // load the switch status from the config
        try {
            Configuration configuration = configurationAdmin.getConfiguration(Configurations.NODE);
            if (configuration != null) {
                Boolean status = new Boolean((String) configuration.getProperties().get(Configurations.HANDLER + "." + this.getClass().getName()));
                if (status) {
                    eventSwitch.turnOn();
                } else {
                    eventSwitch.turnOff();
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return eventSwitch;
    }

    public Class<RemoteConfigurationEvent> getType() {
        return RemoteConfigurationEvent.class;
    }

}
