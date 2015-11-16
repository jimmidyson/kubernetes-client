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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.ws.WebSocket;
import com.squareup.okhttp.ws.WebSocketCall;
import com.squareup.okhttp.ws.WebSocketListener;
import io.fabric8.kubernetes.api.model.Status;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class ExecExample {

  private static final Logger logger = LoggerFactory.getLogger(FullExample.class);

  public static void main(String[] args) throws InterruptedException {
    try (final KubernetesClient client = new DefaultKubernetesClient()) {
      OkHttpClient ok = client.adapt(OkHttpClient.class);
      ok.setReadTimeout(0, TimeUnit.MILLISECONDS);

      String url = "https://localhost:8443/api/v1/namespaces/default/pods/nginx-1-yvcux/exec?command=cat&command=%2Fetc%2Fhosts&container=nginx&container=nginx&stderr=false&stdout=true&stdin=false&tty=false";
      Request.Builder r = new Request.Builder().url(url).get();
      WebSocketCall webSocketCall = WebSocketCall.create(ok, r.build());

      final CountDownLatch latch = new CountDownLatch(1);
      try (final CommandExecutor executor = new CommandExecutor(new ExecHandler() {
        @Override
        public void onStdout(byte[] output) {
          System.out.println(new String(output));
        }

        @Override
        public void onStderr(byte[] output) {
          System.err.println(new String(output));
        }

        @Override
        public void onError(byte[] output) {
          System.err.println(new String(output));
        }

        @Override
        public void onClose(Exception e) {
          if (e != null) {
            e.printStackTrace();
          }
          latch.countDown();
        }
      })) {

        webSocketCall.enqueue(executor);

        latch.await(10, TimeUnit.SECONDS);
      }

      url = "https://localhost:8443/api/v1/namespaces/default/pods/nginx-1-yvcux/exec?command=bash&container=nginx&container=nginx&stderr=true&stdout=true&stdin=true&tty=true";
      r = new Request.Builder().url(url).get();
      webSocketCall = WebSocketCall.create(ok, r.build());

      final CountDownLatch latch2 = new CountDownLatch(1);
      try (final CommandExecutor executor = new CommandExecutor(new ExecHandler() {
        @Override
        public void onStdout(byte[] output) {
          System.out.print(new String(output));
        }

        @Override
        public void onStderr(byte[] output) {
          System.err.print(new String(output));
        }

        @Override
        public void onError(byte[] output) {
          System.err.print(new String(output));
        }

        @Override
        public void onClose(Exception e) {
          if (e != null) {
            e.printStackTrace();
          }
          latch2.countDown();
        }
      })) {

        webSocketCall.enqueue(executor);

        new Thread(new Runnable() {
          @Override
          public void run() {
            Scanner scanner = new Scanner(System.in);

            try {
              while(true) {
                String nextCmd = scanner.nextLine();
                executor.send(nextCmd + "\n");
                if (nextCmd.equals("exit")) {
                  break;
                }
              }
            } catch (IOException e) {
              e.printStackTrace();
            } finally {
              try {
                executor.close();
              } catch (IOException e) {
                e.printStackTrace();
              }
            }
          }
        }).start();

        latch2.await(10, TimeUnit.MINUTES);
      }

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

  private interface ExecHandler {
    void onStdout(byte[] output);
    void onStderr(byte[] output);
    void onError(byte[] output);
    void onClose(Exception e);
  }

  private static class CommandExecutor implements WebSocketListener, AutoCloseable {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final AtomicReference<WebSocket> webSocketRef = new AtomicReference<>();
    private final Buffer presendSink = new Buffer();

    private final ExecHandler handler;

    private CommandExecutor(ExecHandler handler) {
      this.handler = handler;
    }

    public void send(byte[] bytes) throws IOException {
      if (bytes.length > 0) {
        WebSocket ws = webSocketRef.get();
        if (ws != null) {
          try (BufferedSink sink = ws.newMessageSink(WebSocket.PayloadType.BINARY)) {
            sink.write(new byte[]{0});
            sink.write(bytes);
          }
        } else {
          presendSink.write(bytes);
        }
      }
    }

    public void send(String msg) throws IOException {
      send(msg.getBytes());
    }

    @Override
    public void close() throws IOException {
      WebSocket ws = webSocketRef.get();
      if (ws != null) {
        ws.close(1000, "Closing...");
      }
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
      webSocketRef.set(webSocket);
      try {
        send(presendSink.readByteArray());
      } catch (IOException e) {
        throw KubernetesClientException.launderThrowable(e);
      }
    }

    @Override
    public void onFailure(IOException ioe, Response response) {
      try {
        Status responseStatus = mapper.readValue(response.body().byteStream(), Status.class);
        handler.onClose(new KubernetesClientException("Exec failed", response.code(), responseStatus));
      } catch (Exception e) {
        handler.onClose(KubernetesClientException.launderThrowable(ioe));
      }
    }

    @Override
    public void onMessage(BufferedSource payload, WebSocket.PayloadType type) throws IOException {
      try {
        byte streamID = payload.readByte();
        switch (streamID) {
          case 1:
            ByteString stdout = payload.readByteString();
            if (stdout.size() > 0) {
              handler.onStdout(stdout.toByteArray());
            }
            break;
          case 2:
            ByteString stderr = payload.readByteString();
            if (stderr.size() > 0) {
              handler.onStderr(stderr.toByteArray());
            }
            break;
          case 3:
            ByteString error = payload.readByteString();
            if (error.size() > 0) {
              handler.onError(error.toByteArray());
            }
            break;
          default:
            throw new IOException("Unknown stream ID " + streamID);
        }
      } finally {
        payload.close();
      }
    }

    @Override
    public void onPong(Buffer payload) {

    }

    @Override
    public void onClose(int code, String reason) {
      try {
        Exception closeExc = null;
        if (code != 1000) {
          closeExc = new KubernetesClientException(reason, code, null);
        }
        webSocketRef.set(null);
        handler.onClose(closeExc);
      } catch (Exception e) {
        handler.onClose(KubernetesClientException.launderThrowable(e));
      }
    }
  }

}
