/**
 * Copyright (C) 2015 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.kubernetes.examples;

import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.ws.WebSocket;
import com.squareup.okhttp.ws.WebSocketCall;
import com.squareup.okhttp.ws.WebSocketListener;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.ExecListener;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.internal.ExecConnectionManager;
import okio.Buffer;
import okio.BufferedSource;
import okio.Okio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ExecExample {

  private static final Logger logger = LoggerFactory.getLogger(FullExample.class);

  public static void main(String[] args) throws InterruptedException {
    try {
      Class.forName("org.eclipse.jetty.alpn.ALPN");
    } catch (ClassNotFoundException e) {
      throw new RuntimeException("ALPN not available... required for exec");
    }

    try (final KubernetesClient client = new DefaultKubernetesClient()) {
      OkHttpClient ok = client.adapt(OkHttpClient.class);

      new ExecConnectionManager(ok, );

      Thread stdoutThread = new Thread() {
        @Override
        public void run() {
          try {
            while (true) {
              executor.stdout.readAll(Okio.sink(System.out));
            }
          } catch (IOException e) {
            e.printStackTrace();
          }
        }
      };
      stdoutThread.setDaemon(true);
      stdoutThread.start();

      Thread stderrThread = new Thread() {
        @Override
        public void run() {
          try {
            while (true) {
              executor.stderr.readAll(Okio.sink(System.err));
            }
          } catch (IOException e) {
            e.printStackTrace();
          }
        }
      };
      stderrThread.setDaemon(true);
      stderrThread.start();

      new ExecConnectionManager();

      new CountDownLatch(1).await(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      e.printStackTrace();
      logger.error(e.getMessage(), e);

      Throwable[] suppressed = e.getSuppressed();
      if (suppressed != null) {
        for (Throwable t : suppressed) {
          logger.error(t.getMessage(), t);
        }
      }
    }
  }

  private static void log(String action, Object obj) {
    logger.info("{}: {}", action, obj);
  }

  private static void log(String action) {
    logger.info(action);
  }

}
