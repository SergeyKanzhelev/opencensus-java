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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.spy;

import com.microsoft.applicationinsights.TelemetryClient;
import com.microsoft.applicationinsights.TelemetryConfiguration;
import com.microsoft.applicationinsights.telemetry.RequestTelemetry;
import com.microsoft.applicationinsights.telemetry.Telemetry;
import com.microsoft.applicationinsights.telemetry.TraceTelemetry;
import io.opencensus.common.Duration;
import io.opencensus.common.Timestamp;
import io.opencensus.trace.Annotation;
import io.opencensus.trace.AttributeValue;
import io.opencensus.trace.Link;
import io.opencensus.trace.MessageEvent;
import io.opencensus.trace.MessageEvent.Type;
import io.opencensus.trace.SpanContext;
import io.opencensus.trace.SpanId;
import io.opencensus.trace.Status;
import io.opencensus.trace.TraceId;
import io.opencensus.trace.TraceOptions;
import io.opencensus.trace.export.SpanData;
import io.opencensus.trace.export.SpanData.Attributes;
import io.opencensus.trace.export.SpanData.Links;
import io.opencensus.trace.export.SpanData.TimedEvent;
import io.opencensus.trace.export.SpanData.TimedEvents;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * Unit tests for {@link
 * io.opencensus.exporter.trace.applicationinsights.ApplicationInsightsExporterHandler}.
 */
@RunWith(JUnit4.class)
public class ApplicationInsightsExporterHandlerTest {

  private static final int DEFAULT_STATUS_CODE = 200;
  private static final String DEFAULT_HOST = "example.com";
  private static final int DEFAULT_PORT = 443;
  private static final String DEFAULT_PATH = "/http/path";
  private static final String DEFAULT_ROUTE = "/http/route";
  private static final String DEFAULT_USER_AGENT = "UserAgent";
  private static final String DEFAULT_METHOD = "GET";
  private static final String DEFAULT_ANNOTATION_DESCRIPTION = "test message";

  private ApplicationInsightsExporterHandler aiExporter;

  private final Random random = new Random(1234);
  private List<Telemetry> sentItems;

  private TelemetryClient realTelemetryClient;
  private TelemetryClient telemetryClient;

  @Before
  public void setUp() {
    // MockitoAnnotations.initMocks(this);
    sentItems = new ArrayList<Telemetry>();
    realTelemetryClient = new TelemetryClient(TelemetryConfiguration.createDefault());
    telemetryClient = spy(realTelemetryClient);

    Answer<Void> track =
        new Answer<Void>() {
          @Override
          public Void answer(InvocationOnMock invocation) throws Throwable {
            sentItems.add((Telemetry) invocation.getArguments()[0]);
            return null;
          }
        };

    Mockito.doAnswer(track).when(telemetryClient).trackRequest(any(RequestTelemetry.class));
    Mockito.doAnswer(track).when(telemetryClient).trackTrace(any(TraceTelemetry.class));

    aiExporter = new ApplicationInsightsExporterHandler(telemetryClient);
  }

  @Test
  public void exportBasicHttpRequestSpanWithAnnotationsAndEvents() throws MalformedURLException {
    SpanContext spanContext =
        SpanContext.create(
            TraceId.generateRandomId(random),
            SpanId.generateRandomId(random),
            TraceOptions.DEFAULT);
    SpanId parentId = SpanId.generateRandomId(random);

    Timestamp startTimestamp = Timestamp.fromMillis(System.currentTimeMillis() - 1000);
    Timestamp endTimestamp = Timestamp.fromMillis(System.currentTimeMillis());

    Attributes attributes =
        createHttpIncomingRequestAttributes(
            DEFAULT_HOST,
            DEFAULT_PORT,
            DEFAULT_PATH,
            DEFAULT_STATUS_CODE,
            DEFAULT_METHOD,
            DEFAULT_ROUTE,
            DEFAULT_USER_AGENT);

    TimedEvents<Annotation> annotations =
        createAnnotation(
            startTimestamp.addDuration(Duration.fromMillis(100)),
            DEFAULT_ANNOTATION_DESCRIPTION,
            creatAttributes("annotation"));

    TimedEvents<MessageEvent> messages =
        createMessageEvent(
            startTimestamp.addDuration(Duration.fromMillis(10)), 15243, 54321, 12345);

    SpanContext childContext =
        SpanContext.create(
            TraceId.generateRandomId(random),
            SpanId.generateRandomId(random),
            TraceOptions.DEFAULT);
    SpanContext parentContext =
        SpanContext.create(TraceId.generateRandomId(random), parentId, TraceOptions.DEFAULT);
    Links links =
        createLinks(
            childContext,
            creatAttributes("childLink"),
            parentContext,
            creatAttributes("prentLink"));
    Status status = Status.OK;

    SpanData spanData =
        SpanData.create(
            spanContext,
            parentId,
            true,
            "Recv.url",
            startTimestamp,
            attributes,
            annotations,
            messages,
            links,
            0,
            status,
            endTimestamp);

    List<SpanData> spans = new ArrayList<>();
    spans.add(spanData);
    aiExporter.export(spans);

    assertThat(sentItems).hasSize(3);

    List<RequestTelemetry> requests = getTelemetryOfType(sentItems, RequestTelemetry.class);
    List<TraceTelemetry> traces = getTelemetryOfType(sentItems, TraceTelemetry.class);

    assertThat(requests).hasSize(1);

    assertRequestTelemetryFromSpan(requests.get(0), spanData);

    assertThat(traces).hasSize(2);

    assertTraceTelemetryFromAnnotation(traces.get(0), annotations.getEvents().get(0), spanData);
    assertTraceTelemetryFromMessageEvent(traces.get(1), messages.getEvents().get(0), spanData);
  }

  private Attributes createHttpIncomingRequestAttributes(
      String host,
      int port,
      String path,
      int status,
      String method,
      String route,
      String userAgent) {
    HashMap<String, AttributeValue> attributes = new HashMap<>();
    attributes.put("http.host", AttributeValue.stringAttributeValue(host));
    attributes.put("http.port", AttributeValue.longAttributeValue(port));
    attributes.put("http.path", AttributeValue.stringAttributeValue(path));
    attributes.put("http.status_code", AttributeValue.longAttributeValue(status));
    attributes.put("http.method", AttributeValue.stringAttributeValue(method));
    attributes.put("http.route", AttributeValue.stringAttributeValue(route));
    attributes.put("http.user_agent", AttributeValue.stringAttributeValue(userAgent));
    attributes.putAll(creatAttributes("span"));
    return Attributes.create(attributes, 0);
  }

  private TimedEvents<Annotation> createAnnotation(
      Timestamp timestamp, String description, Map<String, AttributeValue> attributes) {
    List<TimedEvent<Annotation>> annotations = new ArrayList<>();

    annotations.add(
        TimedEvent.create(
            timestamp, Annotation.fromDescriptionAndAttributes(description, attributes)));
    return TimedEvents.create(annotations, 0);
  }

  private TimedEvents<MessageEvent> createMessageEvent(
      Timestamp timestamp, long messageId, long uncompressedSize, long compressedSize) {
    List<TimedEvent<MessageEvent>> messageEvents = new ArrayList<>();

    MessageEvent message =
        MessageEvent.builder(Type.RECEIVED, messageId)
            .setUncompressedMessageSize(uncompressedSize)
            .setCompressedMessageSize(compressedSize)
            .build();

    messageEvents.add(TimedEvent.create(timestamp, message));
    return TimedEvents.create(messageEvents, 0);
  }

  private Links createLinks(
      SpanContext childSpanContext,
      Map<String, AttributeValue> childAttributes,
      SpanContext parentSpanContext,
      Map<String, AttributeValue> parentAttributes) {
    List<Link> links = new ArrayList<>();

    links.add(Link.fromSpanContext(childSpanContext, Link.Type.CHILD_LINKED_SPAN, childAttributes));
    links.add(
        Link.fromSpanContext(parentSpanContext, Link.Type.PARENT_LINKED_SPAN, parentAttributes));
    return Links.create(links, 0);
  }

  private Map<String, AttributeValue> creatAttributes(String namePrefix) {
    Map<String, AttributeValue> attributes = new HashMap<>();
    attributes.put(namePrefix + "-stringAttribute", AttributeValue.stringAttributeValue("value"));
    attributes.put(namePrefix + "-booleanAttribute", AttributeValue.booleanAttributeValue(true));
    attributes.put(namePrefix + "-longAttribute", AttributeValue.longAttributeValue(123));

    return attributes;
  }

  private void assertRequestTelemetryFromSpan(final RequestTelemetry request, SpanData span)
      throws MalformedURLException {
    assertThat(request.getTimestamp().getTime())
        .isEqualTo(getEpochMillis(span.getStartTimestamp()));
    assertThat(request.getDuration().getTotalMilliseconds())
        .isEqualTo(
            getEpochMillis(span.getEndTimestamp().subtractTimestamp(span.getStartTimestamp())));
    assertThat(request.isSuccess()).isEqualTo(span.getStatus().isOk());
    assertThat(request.getId()).isEqualTo(span.getContext().getSpanId().toLowerBase16());
    assertThat(request.getResponseCode()).isEqualTo(Integer.toString(DEFAULT_STATUS_CODE));

    URL url = request.getUrl();

    assertThat(url.toString()).isEqualTo(String.format("https://%s%s", DEFAULT_HOST, DEFAULT_PATH));
    assertThat(request.getName()).isEqualTo(String.format("%s %s", DEFAULT_METHOD, DEFAULT_ROUTE));
    assertThat(request.getResponseCode()).isEqualTo(Integer.toString(DEFAULT_STATUS_CODE));

    assertThat(request.getContext().getOperation().getId())
        .isEqualTo(span.getContext().getTraceId().toLowerBase16());
    assertThat(request.getContext().getOperation().getParentId())
        .isEqualTo(span.getParentSpanId().toLowerBase16());

    assertThat(request.getContext().getUser().getUserAgent()).isEqualTo(DEFAULT_USER_AGENT);
    assertThat(request.getProperties()).containsEntry("span-stringAttribute", "value");
    assertThat(request.getProperties()).containsEntry("span-booleanAttribute", "true");
    assertThat(request.getProperties()).containsEntry("span-longAttribute", "123");
  }

  private void assertTraceTelemetryFromAnnotation(
      TraceTelemetry trace, TimedEvent<Annotation> annotation, SpanData span) {
    assertThat(trace.getTimestamp().getTime()).isEqualTo(getEpochMillis(annotation.getTimestamp()));
    assertThat(trace.getMessage()).isEqualTo(annotation.getEvent().getDescription());

    assertThat(trace.getProperties()).containsEntry("annotation-stringAttribute", "value");
    assertThat(trace.getProperties()).containsEntry("annotation-booleanAttribute", "true");
    assertThat(trace.getProperties()).containsEntry("annotation-longAttribute", "123");

    assertThat(trace.getContext().getOperation().getId())
        .isEqualTo(span.getContext().getTraceId().toLowerBase16());
    assertThat(trace.getContext().getOperation().getParentId())
        .isEqualTo(span.getContext().getSpanId().toLowerBase16());
  }

  private void assertTraceTelemetryFromMessageEvent(
      TraceTelemetry trace, TimedEvent<MessageEvent> messageEvent, SpanData span) {
    assertThat(trace.getTimestamp().getTime())
        .isEqualTo(getEpochMillis(messageEvent.getTimestamp()));
    assertThat(trace.getMessage())
        .contains(String.format("'%d'", messageEvent.getEvent().getMessageId()));
    assertThat(trace.getMessage())
        .contains(String.format("'%d'", messageEvent.getEvent().getUncompressedMessageSize()));
    assertThat(trace.getMessage())
        .contains(String.format("'%d'", messageEvent.getEvent().getCompressedMessageSize()));

    assertThat(trace.getContext().getOperation().getId())
        .isEqualTo(span.getContext().getTraceId().toLowerBase16());
    assertThat(trace.getContext().getOperation().getParentId())
        .isEqualTo(span.getContext().getSpanId().toLowerBase16());
  }

  private <T> List<T> getTelemetryOfType(List<Telemetry> items, Class<T> clazz) {
    List<T> result = new ArrayList<>();
    for (Telemetry i : items) {
      if (i.getClass().isAssignableFrom(clazz)) {
        result.add(clazz.cast(i));
      }
    }

    return result;
  }

  private long getEpochMillis(Timestamp timestamp) {
    return timestamp.getSeconds() * 1000 + timestamp.getNanos() / 1000 / 1000;
  }

  private long getEpochMillis(Duration duration) {
    return duration.getSeconds() * 1000 + duration.getNanos() / 1000 / 1000;
  }
}
