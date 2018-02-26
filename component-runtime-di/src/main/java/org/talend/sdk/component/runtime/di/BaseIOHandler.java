/**
 * Copyright (C) 2006-2018 Talend Inc. - www.talend.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.talend.sdk.component.runtime.di;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import javax.json.bind.Jsonb;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public abstract class BaseIOHandler {

    protected final Jsonb jsonb;

    protected final Map<String, IO> connections = new TreeMap<>();

    public void init(final Collection<String> branchesOrder) {
        if (branchesOrder == null) {
            return;
        }

        final Map<String, String> mapping = new HashMap<>(); // temp structure to avoid concurrent modification
        final Iterator<String> branches = branchesOrder.iterator();
        for (final String rowStruct : connections.keySet()) {
            if (!branches.hasNext()) {
                break;
            }
            mapping.put(rowStruct, branches.next());
        }
        if (!mapping.isEmpty()) {
            mapping.forEach((row, branch) -> connections.putIfAbsent(branch, connections.get(row)));
        }
    }

    public void addConnection(final String connectorName, final Class<?> type) {
        connections.put(connectorName, new IO<>(new AtomicReference<>(), type, false));
    }

    public void reset() {
        connections.values().forEach(IO::reset);
    }

    public <T> T getValue(final String connectorName, final Class<T> type) {
        return type.cast(connections.get(connectorName).value.get());
    }

    protected String getActualName(final String name) {
        return "__default__".equals(name) ? "FLOW" : name;
    }

    @AllArgsConstructor
    @Data
    static class IO<T> {

        private final AtomicReference<T> value;

        private final Class<T> type;

        private boolean mutated;

        private void reset() {
            try {
                value.set(type.getConstructor().newInstance());
            } catch (Exception e) {
                throw new IllegalStateException("Can't create an instance of " + type, e);
            }
        }
    }

}