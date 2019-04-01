package com.netifi.proteus.httpgateway.endpoint;

import com.google.protobuf.Descriptors;
import com.google.protobuf.Empty;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import com.netifi.proteus.httpgateway.rsocket.RSocketSupplier;
import com.netifi.proteus.httpgateway.util.ProtoUtil;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.rsocket.Payload;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerResponse;
import reactor.netty.http.websocket.WebsocketInbound;
import reactor.netty.http.websocket.WebsocketOutbound;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static com.netifi.proteus.httpgateway.util.ProtoUtil.*;

public class RequestStreamEndpoint implements Endpoint {
  private final Descriptors.Descriptor request;
  private final Descriptors.Descriptor response;
  private final String defaultGroup;
  private final RSocketSupplier rSocketSupplier;
  private final boolean hasTimeout;
  private final Duration timeout;
  private final int maxConcurrency;
  private final AtomicInteger outstandingRequests;
  private final String service;
  private final String method;
  private final JsonFormat.TypeRegistry typeRegistry;
  private final boolean requestEmpty;

  public RequestStreamEndpoint(
      String service,
      String method,
      Descriptors.Descriptor request,
      Descriptors.Descriptor response,
      String defaultGroup,
      RSocketSupplier rSocketSupplier,
      boolean hasTimeout,
      Duration timeout,
      int maxConcurrency,
      JsonFormat.TypeRegistry typeRegistry) {
    this.request = request;
    this.response = response;
    this.defaultGroup = defaultGroup;
    this.rSocketSupplier = rSocketSupplier;
    this.hasTimeout = hasTimeout;
    this.timeout = timeout;
    this.maxConcurrency = maxConcurrency;
    this.outstandingRequests = new AtomicInteger();
    this.service = service;
    this.method = method;
    this.typeRegistry = typeRegistry;
    this.requestEmpty = EMPTY_MESSAGE.equals(request.getFullName());
  }

  private ByteBuf payloadToString(Payload payload) {
    return new TextWebSocketFrame(
            ProtoUtil.parseResponseToJson(payload.sliceData(), response, typeRegistry))
        .content();
  }

  @Override
  public Publisher<Void> apply(
      HttpHeaders headers, String _j, HttpServerResponse httpServerResponse) {
    return httpServerResponse.sendWebsocket(
        (WebsocketInbound inbound, WebsocketOutbound outbound) -> {
          if (isRequestEmpty()) {
            Message message = Empty.getDefaultInstance();
            Payload p = messageToPayload(message, service, method);
            return Flux.merge(inbound.receiveFrames().then(), requestStream(headers, p, outbound));
          } else {
            return inbound
                .receiveFrames()
                .take(1)
                .concatMap(
                    frame -> {
                      String s = frame.content().toString(StandardCharsets.UTF_8);
                      Message m = jsonToMessage(s, request);
                      Payload p = messageToPayload(m, service, method);

                      return requestStream(headers, p, outbound);
                    },
                    256);
          }
        });
  }

  Mono<Void> requestStream(HttpHeaders headers, Payload p, WebsocketOutbound outbound) {
    return rSocketSupplier
        .apply(defaultGroup, headers)
        .requestStream(p)
        .flatMap(
            r -> {
              ByteBuf byteBuf = payloadToString(r);
              TextWebSocketFrame f = new TextWebSocketFrame(byteBuf);
              return outbound.sendObject(f);
            })
        .then();
  }

  @Override
  public boolean isRequestEmpty() {
    return requestEmpty;
  }

  @Override
  public boolean isResponseStreaming() {
    return true;
  }
}
