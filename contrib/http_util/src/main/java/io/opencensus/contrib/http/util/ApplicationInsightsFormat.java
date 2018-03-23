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
import java.util.List;
import java.util.Random;

public class ApplicationInsightsFormat extends TextFormat {

  static final String REQUEST_ID_HEADER_NAME = "Request-Id";
  static final String REQUEST_CONTEXT_HEADER_NAME = "Request-Context";
  static final Random random = new Random(1234);

  @Override
  public List<String> fields() {
    return null;
  }

  @Override
  public <C> void inject(SpanContext spanContext, C carrier, Setter<C> setter) {
    checkNotNull(spanContext, "spanContext");
    checkNotNull(setter, "setter");
    checkNotNull(carrier, "carrier");

    String parentId = Context.key("ms-request-id").get().toString();
    String Id = parentId + "." + getRandomHexString(4) + ".";

    Context.current().withValue(Context.key("ms-request-parent-id"), parentId);
    Context.current().withValue(Context.key("ms-request-id"), Id);

    spanContext.getState().put("ms-request-parent-id", parentId);
    spanContext.getState().put("ms-request-id", Id);

    setter.put(carrier, REQUEST_ID_HEADER_NAME, Id);
    setter.put(
        carrier, REQUEST_CONTEXT_HEADER_NAME, "appId=cid-v1:2852cbe1-8fa3-496b-8fee-7ad61212e65b");
  }

  @Override
  public <C> SpanContext extract(C carrier, Getter<C> getter) throws SpanContextParseException {
    checkNotNull(carrier, "carrier");
    checkNotNull(getter, "getter");
    String requestId = getter.get(carrier, REQUEST_ID_HEADER_NAME);
    String requestContext = getter.get(carrier, REQUEST_CONTEXT_HEADER_NAME);

    SpanContext ctx =
        SpanContext.create(
            TraceId.generateRandomId(random),
            SpanId.generateRandomId(random),
            TraceOptions.DEFAULT);
    String newId = requestId + "." + getRandomHexString(4) + ".";
    ctx.getState().put("ms-request-parent-id", requestId);
    ctx.getState().put("ms-request-id", newId);
    ctx.getState().put("ms-request-context", requestContext);

    Context.current().withValue(Context.key("ms-request-parent-id"), requestId);
    Context.current().withValue(Context.key("ms-request-id"), newId);

    return ctx;
  }

  private String getRandomHexString(int numchars) {
    Random r = new Random();
    StringBuilder sb = new StringBuilder();
    while (sb.length() < numchars) {
      sb.append(Integer.toHexString(r.nextInt()));
    }

    return sb.toString().substring(0, numchars);
  }
}
