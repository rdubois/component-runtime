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
package org.talend.sdk.component.proxy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import javax.inject.Inject;

import org.junit.jupiter.api.Test;
import org.talend.sdk.component.proxy.service.client.ConfigurationClient;
import org.talend.sdk.component.proxy.test.CdiInject;
import org.talend.sdk.component.proxy.test.WithServer;

@CdiInject
@WithServer
class ConfigurationClientTest {

    @Inject
    private ConfigurationClient configurationClient;

    @Test
    void migrate() throws ExecutionException, InterruptedException {
        final Map<String, String> configuration = new HashMap<>();
        configuration.put("__version", "0"); // connection version is 1 so we put an earlier version
        configuration.put("url", "http://previous"); // connection version is 1 so we put an earlier version
        final Map<String, String> migrated = configurationClient
                .migrate("dGVzdC1jb21wb25lbnQjVGhlVGVzdEZhbWlseSNkYXRhc3RvcmUjQ29ubmVjdGlvbi0y", configuration,
                        i -> null)
                .toCompletableFuture()
                .get();
        assertEquals("http://migrated", migrated.get("url"));
    }
}
