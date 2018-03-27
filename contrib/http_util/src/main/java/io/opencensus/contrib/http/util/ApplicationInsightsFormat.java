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

package io.opencensus.contrib.http.util;

import static com.google.common.base.Preconditions.checkNotNull;

import io.grpc.Context;
import io.opencensus.trace.SpanContext;
import io.opencensus.trace.SpanId;
import io.opencensus.trace.TraceId;
import io.opencensus.trace.TraceOptions;
import io.opencensus.trace.propagation.SpanContextParseException;
import io.opencensus.trace.propagation.TextFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class ApplicationInsightsFormat extends TextFormat {
  public static final Context.Key<Map<String, String>> MS_CONTEXT_KEY =
      Context.key("ms-context-key");
  static final String REQUEST_ID_HEADER_NAME = "Request-Id";
  static final String REQUEST_CONTEXT_HEADER_NAME = "Request-Context";
  static final Random random = new Random();

  @Override
  public List<String> fields() {
    return null;
  }

  @Override
  public <C> void inject(SpanContext spanContext, C carrier, Setter<C> setter) {
    checkNotNull(spanContext, "spanContext");
    checkNotNull(setter, "setter");
    checkNotNull(carrier, "carrier");

    Map<String, String> msContext = MS_CONTEXT_KEY.get();
    String parentId = null;
    String rootId = null;
    if (msContext == null) {
      setter.put(
          carrier,
          REQUEST_ID_HEADER_NAME,
          String.format(
              "|%s.%s.",
              spanContext.getTraceId().toLowerBase16(), spanContext.getSpanId().toLowerBase16()));
    } else {
      parentId = msContext.get("id");
      rootId = msContext.get("root");
      String newId = String.format("|%s.%s.", rootId, spanContext.getSpanId().toLowerBase16());
      spanContext.getState().put("ms-request-parent-id", parentId);
      spanContext.getState().put("ms-request-root-id", rootId);
      setter.put(carrier, REQUEST_ID_HEADER_NAME, newId);
    }

    setter.put(
        carrier, REQUEST_CONTEXT_HEADER_NAME, "appId=cid-v1:2852cbe1-8fa3-496b-8fee-7ad61212e65b");
  }

  @Override
  public <C> SpanContext extract(C carrier, Getter<C> getter) throws SpanContextParseException {
    checkNotNull(carrier, "carrier");
    checkNotNull(getter, "getter");

    SpanContext spanContext =
        SpanContext.create(
            TraceId.generateRandomId(random),
            SpanId.generateRandomId(random),
            TraceOptions.DEFAULT);

    String requestContext = getter.get(carrier, REQUEST_CONTEXT_HEADER_NAME);
    if (requestContext != null) {
      int appIdStart = requestContext.indexOf("appId=");
      if (appIdStart >= 0) {
        int appIdEnd = requestContext.indexOf(',', appIdStart);
        if (appIdEnd < 0) {
          appIdEnd = requestContext.length();
        }
        spanContext.getState().put("ms-appId", requestContext.substring(appIdStart + 6, appIdEnd));
      }
    }

    String requestId = getter.get(carrier, REQUEST_ID_HEADER_NAME);
    if (requestId != null) {
      String rootId = spanContext.getTraceId().toLowerBase16();
      if (requestId.startsWith("|")) {
        int dotIndex = requestId.indexOf('.');
        if (dotIndex < 0) {
          dotIndex = requestId.length();
        }

        rootId = requestId.substring(1, dotIndex);
      }

      spanContext.getState().put("ms-request-root-id", rootId);
      spanContext.getState().put("ms-request-parent-id", requestId);

      HashMap<String, String> msContext = new HashMap<String, String>();

      msContext.put(
          "id", String.format("|%s.%s.", rootId, spanContext.getSpanId().toLowerBase16()));
      msContext.put("root", rootId);
      Context.current().withValue(MS_CONTEXT_KEY, msContext).attach();
    }

    return spanContext;
  }
}
