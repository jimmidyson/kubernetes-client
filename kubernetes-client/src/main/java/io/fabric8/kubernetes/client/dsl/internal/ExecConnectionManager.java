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
package io.fabric8.kubernetes.client.dsl.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.ws.WebSocket;
import com.squareup.okhttp.ws.WebSocketCall;
import com.squareup.okhttp.ws.WebSocketListener;
import io.fabric8.kubernetes.api.model.Status;
import io.fabric8.kubernetes.client.Exec;
import io.fabric8.kubernetes.client.ExecListener;
import io.fabric8.kubernetes.client.KubernetesClientException;
import okio.Buffer;
import okio.BufferedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class ExecConnectionManager implements Exec {

  private static final ObjectMapper mapper = new ObjectMapper();
  private final ExecListener execListener;
  private WebSocketCall webSocketCall;
  private OkHttpClient clonedClient;

  private final Buffer stdout = new Buffer();
  private final Buffer stderr = new Buffer();
  private final Buffer stdin = new Buffer();

  public ExecConnectionManager(final OkHttpClient client, final ExecListener execListener) throws InterruptedException, ExecutionException, MalformedURLException {
    this.clonedClient = client.clone();
    this.execListener = execListener;
    runExec();
  }

  private final void runExec() throws MalformedURLException, ExecutionException, InterruptedException {
    Request request = new Request.Builder()
      .get()
      .url("https://localhost:8443/api/v1/namespaces/default/pods/nginx-1-yvcux/exec?command=cat&command=%2Fetc%2Fhosts&container=nginx&container=nginx&stderr=true&stdout=true&stdin=true&tty=false")
      .addHeader("Origin", "https://localhost:8443")
      .build();
    clonedClient.setReadTimeout(0, TimeUnit.MILLISECONDS);

    webSocketCall = WebSocketCall.create(clonedClient, request);
    webSocketCall.enqueue(new WebSocketListener() {
      private final Logger logger = LoggerFactory.getLogger(this.getClass());

      @Override
      public void onOpen(WebSocket webSocket, Response response) {
      }

      @Override
      public void onFailure(IOException e, Response response) {
        try {
          Status responseStatus = mapper.readValue(response.body().byteStream(), Status.class);
          execListener.onClose(new KubernetesClientException("Connection unexpectedly closed", response.code(), responseStatus));
        } catch (IOException ioe) {
          execListener.onClose(new KubernetesClientException("Connection unexpectedly closed", e));
        }
      }

      @Override
      public void onMessage(BufferedSource payload, WebSocket.PayloadType payloadType) throws IOException {
        try {
          byte streamID = payload.readByte();
          switch (streamID) {
            case 1:
              payload.readAll(stdout);
              break;
            case 2:
              payload.readAll(stderr);
              break;
            default:
              throw new IOException("Unknown stream ID " + streamID);
          }
        } finally {
          payload.close();
        }
      }

      @Override
      public void onPong(Buffer buffer) {

      }

      @Override
      public void onClose(int code, String reason) {
        try {
          close();
          execListener.onClose(null);
        } catch (Exception e) {
          execListener.onClose(new KubernetesClientException(e.getMessage(), e));
        }
      }
    });
  }

  @Override
  public void close() {
    stdout.close();
    stderr.close();
    stdin.close();
  }

}
