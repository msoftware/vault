/*
 * Copyright (C) 2015 Contentful GmbH
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

package com.contentful.vaultintegration;

import com.contentful.java.cda.CDAClient;
import com.contentful.vault.SyncCallback;
import com.contentful.vault.SyncConfig;
import com.contentful.vault.Vault;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.MockWebServer;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = "src/main/AndroidManifest.xml")
public abstract class BaseTest {
  MockWebServer server;
  CDAClient client;
  Vault vault;

  @Before public void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    setupClient();
    setupVault();
  }

  @After public void tearDown() throws Exception {
    server.shutdown();
    vault.releaseAll();
  }

  protected abstract void setupVault();

  protected void setupClient() {
    client = CDAClient.builder()
        .setSpaceKey("space")
        .setAccessToken("token")
        .setEndpoint(getServerUrl())
        .noSSL()
        .build();
  }

  protected String getServerUrl() {
    URL url = server.getUrl("/");
    return url.getHost() + ":" + url.getPort();
  }

  protected void enqueue(String fileName) throws IOException {
    URL resource = getClass().getClassLoader().getResource(fileName);
    if (resource == null) {
      throw new IllegalArgumentException("File not found");
    }
    server.enqueue(new MockResponse().setResponseCode(200)
        .setBody(FileUtils.readFileToString(new File(resource.getFile()))));
  }

  protected void sync() throws InterruptedException {
    sync(null);
  }

  protected void sync(SyncConfig config) throws InterruptedException {
    if (config == null) {
      config = SyncConfig.builder().setClient(client).build();
    }

    final CountDownLatch latch = new CountDownLatch(1);

    Executor executor = new Executor() {
      @Override public void execute(Runnable command) {
        command.run();
      }
    };

    SyncCallback callback = new SyncCallback() {
      @Override public void onComplete(boolean success) {
        latch.countDown();
      }
    };

    vault.requestSync(config, callback, executor);
    latch.await();
  }
}
