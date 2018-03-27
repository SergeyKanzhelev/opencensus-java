/*
 * Copyright 2018, OpenCensus Authors
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

package io.opencensus.exporter.trace.applicationinsights;

import com.microsoft.applicationinsights.TelemetryClient;
import com.microsoft.applicationinsights.extensibility.context.OperationContext;
import com.microsoft.applicationinsights.telemetry.Duration;
import com.microsoft.applicationinsights.telemetry.RemoteDependencyTelemetry;
import com.microsoft.applicationinsights.telemetry.RequestTelemetry;
import com.microsoft.applicationinsights.telemetry.Telemetry;
import com.microsoft.applicationinsights.telemetry.TraceTelemetry;
import io.opencensus.common.Function;
import io.opencensus.common.Functions;
import io.opencensus.common.Timestamp;
import io.opencensus.trace.Annotation;
import io.opencensus.trace.AttributeValue;
import io.opencensus.trace.Link;
import io.opencensus.trace.MessageEvent;
import io.opencensus.trace.export.SpanData;
import io.opencensus.trace.export.SpanExporter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.Date;
import java.util.Map;

final class ApplicationInsightsExporterHandler extends SpanExporter.Handler {

  private static final String LINK_PROPERTY_NAME = "link";
  private static final String LINK_SPAN_ID_PROPERTY_NAME = "spanId";
  private static final String LINK_TRACE_ID_PROPERTY_NAME = "traceId";
  private static final String LINK_TYPE_PROPERTY_NAME = "type";

  private static final Function<Object, String> RETURN_STRING =
      new Function<Object, String>() {
        @Override
        public String apply(Object input) {
          return input.toString();
        }
      };

  private static final Function<Object, Long> RETURN_LONG =
      new Function<Object, Long>() {
        @Override
        public Long apply(Object input) {
          return (Long) input;
        }
      };

  private final TelemetryClient telemetryClient;

  public ApplicationInsightsExporterHandler(TelemetryClient telemetryClient) {
    this.telemetryClient = telemetryClient;
  }

  private static String attributeValueToString(AttributeValue attributeValue) {
    return attributeValue.match(
        RETURN_STRING, RETURN_STRING, RETURN_STRING, Functions.<String>returnNull());
  }

  private static Long attributeValueToLong(AttributeValue attributeValue) {
    return attributeValue.match(
        Functions.returnConstant(-1L),
        Functions.returnConstant(-1L),
        RETURN_LONG,
        Functions.returnConstant(-1L));
  }

  @Override
  public void export(Collection<SpanData> spanDataList) {
    for (SpanData span : spanDataList) {
      try {
        exportSpan(span);
      } catch (Exception ex) {
        ex.printStackTrace();
      }
    }
  }

  private void exportSpan(SpanData span) {
    String operationId = null;
    String parentId = null;
    if (Boolean.TRUE.equals(span.getHasRemoteParent())) {
      RequestTelemetry request = trackRequestFromSpan(span);
      operationId = request.getContext().getOperation().getId();
      parentId = request.getId();
    } else if (span.getName().startsWith("Sent.")) {
      // widespread hack (see zipkin and instana exporters)
      RemoteDependencyTelemetry dependency = trackDependencyFromSpan(span);
      if (dependency != null) {
        operationId = dependency.getContext().getOperation().getId();
        parentId = dependency.getId();
      }
    } else {
      RequestTelemetry request = trackRequestFromSpan(span);
      operationId = request.getContext().getOperation().getId();
      parentId = request.getId();
    }

    for (SpanData.TimedEvent<Annotation> annotation : span.getAnnotations().getEvents()) {
      trackTraceFromAnnotation(annotation, operationId, parentId);
    }

    for (SpanData.TimedEvent<MessageEvent> messageEvent : span.getMessageEvents().getEvents()) {
      trackTraceFromMessageEvent(messageEvent, operationId, parentId);
    }
  }

  private RequestTelemetry trackRequestFromSpan(SpanData span) {
    RequestTelemetry request = new RequestTelemetry();
    setOperationContext(span, request, span.getParentSpanState());

    request.setTimestamp(getDate(span.getStartTimestamp()));
    request.setDuration(getDuration(span.getStartTimestamp(), span.getEndTimestamp()));

    request.setSuccess(span.getStatus().isOk());
    setRequestSource(span.getContext().getState(), request);

    String host = null;
    String method = null;
    String path = null;
    String route = null;
    String scheme = null;
    int port = -1;
    boolean isResultSet = false;

    for (Map.Entry<String, AttributeValue> entry :
        span.getAttributes().getAttributeMap().entrySet()) {
      switch (entry.getKey()) {
        case "http.status_code":
          request.setResponseCode(attributeValueToString(entry.getValue()));
          isResultSet = true;
          break;
        case "http.user_agent":
          request.getContext().getUser().setUserAgent(attributeValueToString(entry.getValue()));
          break;
        case "http.route":
          route = attributeValueToString(entry.getValue());
          break;
        case "http.path":
          path = attributeValueToString(entry.getValue());
          break;
        case "http.method":
          method = attributeValueToString(entry.getValue());
          break;
        case "http.host":
          host = attributeValueToString(entry.getValue());
          break;
        case "http.port":
          port = attributeValueToLong(entry.getValue()).intValue();
          break;
        case "http.scheme":
          scheme = attributeValueToString(entry.getValue());
          break;
        default:
          if (!request.getProperties().containsKey(entry.getKey())) {
            request.getProperties().put(entry.getKey(), attributeValueToString(entry.getValue()));
          }
      }
    }

    if (host != null) {
      request.setUrl(getUrl(host, port, path, scheme));
      request.setName(String.format("%s %s", method, route != null ? route : path));
    } else { // perhaps not http
      request.setName(span.getName());
    }

    if (!isResultSet) {
      request.setResponseCode(span.getStatus().getDescription());
    }

    setLinks(span.getLinks(), request.getProperties());

    telemetryClient.trackRequest(request);
    return request;
  }

  private URL getUrl(String host, int port, String path, String scheme) {
    try {
      if (scheme == null) {
        scheme = port == 80 ? "http" : "https";
      }
      if (port < 0 || port == 80 || port == 443) {
        return new URL(String.format("%s://%s%s", scheme, host, path));
      }

      return new URL(String.format("%s://%s:%d%s", scheme, host, port, path));
    } catch (MalformedURLException e) {
      return null;
    }
  }

  private boolean isApplicationInsightsUrl(String host) {
    return host.startsWith("dc.services.visualstudio.com")
        || host.startsWith("rt.services.visualstudio.com");
  }

  private RemoteDependencyTelemetry trackDependencyFromSpan(SpanData span) {
    String host = null;
    if (span.getAttributes().getAttributeMap().containsKey("http.host")) {
      host = attributeValueToString(span.getAttributes().getAttributeMap().get("http.host"));
      if (isApplicationInsightsUrl(host)) {
        return null;
      }
    }

    RemoteDependencyTelemetry dependency = new RemoteDependencyTelemetry();
    setOperationContext(span, dependency, span.getContext().getState());

    dependency.setTimestamp(getDate(span.getStartTimestamp()));
    dependency.setDuration(getDuration(span.getStartTimestamp(), span.getEndTimestamp()));

    dependency.setSuccess(span.getStatus().isOk());
    dependency.setResultCode(span.getStatus().getDescription());

    String method = null;
    String path = null;
    int port = -1;

    boolean isHttp = false;
    boolean isResultSet = false;
    String scheme = null;
    for (Map.Entry<String, AttributeValue> entry :
        span.getAttributes().getAttributeMap().entrySet()) {
      switch (entry.getKey()) {
        case "http.status_code":
          dependency.setResultCode(attributeValueToString(entry.getValue()));
          isHttp = true;
          isResultSet = true;
          break;
        case "http.path":
          path = attributeValueToString(entry.getValue());
          isHttp = true;
          break;
        case "http.method":
          method = attributeValueToString(entry.getValue());
          isHttp = true;
          break;
        case "http.host":
          break;
        case "http.port":
          port = attributeValueToLong(entry.getValue()).intValue();
          break;
        case "http.scheme":
          scheme = attributeValueToString(entry.getValue());
          break;
        default:
          if (!dependency.getProperties().containsKey(entry.getKey())) {
            dependency
                .getProperties()
                .put(entry.getKey(), attributeValueToString(entry.getValue()));
          }
      }
    }

    String target = host;
    if (span.getContext().getState().containsKey("ms-appId")) {
      target += " | " + span.getContext().getState().get("ms-appId");
      dependency.setType("Http (tracked component)");
    } else if (isHttp) {
      dependency.setType("HTTP");
    }

    dependency.setTarget(target);

    if (!isResultSet) {
      dependency.setResultCode(span.getStatus().getDescription());
    }

    if (host != null) {
      dependency.setCommandName(getUrl(host, port, path, scheme).toString());
    }

    if (method != null && path != null) {
      dependency.setName(String.format("%s %s", method, path));
    } else {
      dependency.setName(span.getName());
    }

    setLinks(span.getLinks(), dependency.getProperties());

    telemetryClient.trackDependency(dependency);

    return dependency;
  }

  private void trackTraceFromAnnotation(
      SpanData.TimedEvent<Annotation> annotationEvent, String operationId, String parentId) {
    Annotation annotation = annotationEvent.getEvent();
    TraceTelemetry trace = new TraceTelemetry();
    setParentOperationContext(operationId, parentId, trace.getContext().getOperation());
    trace.setMessage(annotation.getDescription());
    trace.setTimestamp(getDate(annotationEvent.getTimestamp()));
    setAttributes(annotation.getAttributes(), trace.getProperties());

    telemetryClient.trackTrace(trace);
  }

  private void trackTraceFromMessageEvent(
      SpanData.TimedEvent<MessageEvent> messageEvent, String operationId, String parentId) {
    MessageEvent event = messageEvent.getEvent();
    TraceTelemetry trace = new TraceTelemetry();
    setParentOperationContext(operationId, parentId, trace.getContext().getOperation());
    trace.setMessage(
        String.format(
            "MessageEvent. messageId: '%d',"
                + " type: '%s',"
                + " compressed message size: '%d',"
                + " uncompressed message size: '%d'",
            event.getMessageId(),
            event.getType().name(),
            event.getCompressedMessageSize(),
            event.getUncompressedMessageSize()));
    trace.setTimestamp(getDate(messageEvent.getTimestamp()));

    telemetryClient.trackTrace(trace);
  }

  private void setLinks(SpanData.Links spanLinks, Map<String, String> telemetryProperties) {
    // for now, we just put links to telemetry properties
    // link0_spanId = ...
    // link0_traceId = ...
    // link0_type = child | parent | other
    // link0_<attributeKey> = <attributeValue>
    // this is not convenient for querying data
    // We'll consider adding Links to operation telemetry schema
    Link[] links = spanLinks.getLinks().toArray(new Link[0]);
    for (int i = 0; i < links.length; i++) {
      String prefix = String.format("%s%d_", LINK_PROPERTY_NAME, i);
      telemetryProperties.put(
          prefix + LINK_SPAN_ID_PROPERTY_NAME, links[i].getSpanId().toLowerBase16());
      telemetryProperties.put(
          prefix + LINK_TRACE_ID_PROPERTY_NAME, links[i].getTraceId().toLowerBase16());
      telemetryProperties.put(prefix + LINK_TYPE_PROPERTY_NAME, links[i].getType().name());
      for (Map.Entry<String, AttributeValue> entry : links[i].getAttributes().entrySet()) {
        if (!telemetryProperties.containsKey(entry.getKey())) {
          telemetryProperties.put(
              prefix + entry.getKey(), attributeValueToString(entry.getValue()));
        }
      }
    }
  }

  private void setOperationContext(SpanData span, Telemetry telemetry, Map<String, String> state) {
    OperationContext context = telemetry.getContext().getOperation();
    String root = null;
    String parent = null;
    String newId = null;
    if (state != null && state.containsKey("ms-request-root-id")) {
      parent = state.get("ms-request-id");
      root = state.get("ms-request-root-id");
    } else {
      parent = span.getParentSpanId().toLowerBase16();
      root = span.getContext().getTraceId().toLowerBase16();
    }
    newId = String.format("|%s.%s.", root, span.getContext().getSpanId().toLowerBase16());

    context.setId(root);
    context.setParentId(parent);

    if (telemetry instanceof RemoteDependencyTelemetry) {
      RemoteDependencyTelemetry dependency = (RemoteDependencyTelemetry) telemetry;
      dependency.setId(newId);
    }

    if (telemetry instanceof RequestTelemetry) {
      RequestTelemetry request = (RequestTelemetry) telemetry;
      request.setId(newId);
    }
  }

  private void setRequestSource(Map<String, String> state, RequestTelemetry telemetry) {
    if (state.containsKey("ms-appId")) {
      String appId = state.get("ms-appId");
      telemetry.setSource(appId);
    }
  }

  private void setParentOperationContext(
      String operationId, String parentId, OperationContext context) {
    context.setId(operationId);
    context.setParentId(parentId);
  }

  private void setAttributes(
      Map<String, AttributeValue> attributes, Map<String, String> telemetryProperties) {
    for (Map.Entry<String, AttributeValue> entry : attributes.entrySet()) {
      if (!telemetryProperties.containsKey(entry.getKey())) {
        telemetryProperties.put(entry.getKey(), attributeValueToString(entry.getValue()));
      }
    }
  }

  private Date getDate(Timestamp timestamp) {
    return new Date(timestamp.getSeconds() * 1000 + timestamp.getNanos() / 1000000);
  }

  private Duration getDuration(Timestamp start, Timestamp stop) {
    io.opencensus.common.Duration ocDuration = stop.subtractTimestamp(start);
    return new Duration(ocDuration.getSeconds() * 1000 + ocDuration.getNanos() / 1000000);
  }
}
