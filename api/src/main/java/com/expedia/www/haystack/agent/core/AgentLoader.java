/*
 *  Copyright 2017 Expedia, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */
package com.expedia.www.haystack.agent.core;

import com.expedia.www.haystack.agent.core.config.AgentConfig;
import com.expedia.www.haystack.agent.core.config.Config;
import com.expedia.www.haystack.agent.core.config.ConfigReader;
import com.expedia.www.haystack.agent.core.metrics.SharedMetricRegistry;
import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class AgentLoader {

    private static Logger LOGGER = LoggerFactory.getLogger(AgentLoader.class);
    private final static String CONFIG_PROVIDER_ARG_NAME = "--config-provider";

    AgentLoader() { }

    void run(String configProviderName, final Map<String, String> configProviderArgs) throws Exception {
        SharedMetricRegistry.startJmxMetricReporter();

        final ClassLoader cl = Thread.currentThread().getContextClassLoader();
        final Config config = loadConfig(configProviderName, configProviderArgs, cl);
        final List<Agent> runningAgents = loadAgents(config, cl);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (final Agent agent : runningAgents) {
                try {
                    agent.close();
                } catch(Exception ignored) { }
            }
            SharedMetricRegistry.closeJmxMetricReporter();
        }));
}

    @VisibleForTesting
    List<Agent> loadAgents(final Config config, ClassLoader cl) throws Exception {
        final ServiceLoader<Agent> agentLoader = ServiceLoader.load(Agent.class, cl);
        final List<Agent> loadedAgents = new ArrayList<>();
        for (final Agent agent : agentLoader) {
            loadedAgents.add(agent);
        }

        final List<Agent> runningAgents = new ArrayList<>();
        final List<String> missingAgents = new ArrayList<>();

        for(final AgentConfig cfg : config.getAgentConfigs()) {
            final Optional<Agent> maybeAgent = loadedAgents
                    .stream()
                    .filter((l) -> l.getName().equalsIgnoreCase(cfg.getName()))
                    .findFirst();

            if (maybeAgent.isPresent()) {
                final Agent agent = maybeAgent.get();
                LOGGER.info("Initializing agent with name={} and config={}", cfg.getName(), cfg);
                agent.initialize(cfg);
                runningAgents.add(agent);
            } else {
                missingAgents.add(cfg.getName());
            }
        }

        if(!missingAgents.isEmpty()) {
            throw new ServiceConfigurationError("Fail to load the agents with names="
                    + StringUtils.join(missingAgents, ","));
        }

        return runningAgents;
    }

    @VisibleForTesting
    Config loadConfig(final String configProviderName,
                      final Map<String, String> configProviderArgs,
                      final ClassLoader cl) throws Exception {
        final ServiceLoader<ConfigReader> configLoader = ServiceLoader.load(ConfigReader.class, cl);
        for (final ConfigReader reader : configLoader) {
            if (reader.getName().equalsIgnoreCase(configProviderName)) {
                return reader.read(configProviderArgs);
            }
        }
        throw new ServiceConfigurationError("Fail to load the config provider for type = " + configProviderName);
    }

    @VisibleForTesting
    static ImmutablePair<String, Map<String, String>> parseArgs(String[] args) {
        final Map<String, String> configProviderArgs = new HashMap<>();
        String configProviderName = "file";

        if(args != null) {
            for(int idx = 0; idx < args.length; idx = idx + 2) {
                if(Objects.equals(args[idx], CONFIG_PROVIDER_ARG_NAME)) {
                    configProviderName = args[idx + 1];
                } else {
                    configProviderArgs.put(args[idx], args[idx + 1]);
                }
            }
        }

        return ImmutablePair.of(configProviderName, configProviderArgs);
    }

    public static void main(String[] args) throws Exception {
        final ImmutablePair<String, Map<String, String>> configReader = parseArgs(args);
        new AgentLoader().run(configReader.getKey(), configReader.getValue());
    }
}