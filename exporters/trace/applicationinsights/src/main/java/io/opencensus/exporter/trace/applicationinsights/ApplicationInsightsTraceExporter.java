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

import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.microsoft.applicationinsights.TelemetryClient;
import io.opencensus.trace.Tracing;
import io.opencensus.trace.export.SpanExporter;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * An OpenCensus span exporter which exports data to Microsoft Azure Application Insights.
 *
 * <p>Example of usage:
 *
 * <pre>{@code
 * public static void main(String[] args) {
 *   ApplicationInsightsTraceExporter.createAndRegister("my-service");
 *   ... // Do work.
 * }
 * }</pre>
 *
 * @since 0.13
 */
public final class ApplicationInsightsTraceExporter {

  private static final String REGISTER_NAME = ApplicationInsightsTraceExporter.class.getName();

  // TODO: correct version and shorter string
  private static final String SDK_VERSION = "opencensus-exporter-trace-applicationinsights-0.0.1";

  private static final Object monitor = new Object();

  @GuardedBy("monitor")
  @Nullable
  private static SpanExporter.Handler handler = null;

  private ApplicationInsightsTraceExporter() {}

  /**
   * Creates and registers the Application Insights Trace exporter to the OpenCensus library. Only
   * one Application Insights exporter can be registered at any point.
   *
   * @param serviceName Name of the service
   * @throws IllegalStateException if a Application Insights exporter is already registered.
   * @since 0.13
   */
  public static void createAndRegister(String serviceName) {
    synchronized (monitor) {
      TelemetryClient telemetryClient = new TelemetryClient();
      telemetryClient.getContext().getDevice().setRoleName(serviceName);
      telemetryClient.getContext().getInternal().setSdkVersion(SDK_VERSION);

      checkState(handler == null, "Application Insights exporter is already registered.");
      SpanExporter.Handler newHandler = new ApplicationInsightsExporterHandler(telemetryClient);
      handler = newHandler;
      register(Tracing.getExportComponent().getSpanExporter(), newHandler);
    }
  }

  /**
   * Registers the {@code ApplicationInsightsTraceExporter}.
   *
   * @param spanExporter the instance of the {@code SpanExporter} where this service is registered.
   * @since 0.13
   */
  @VisibleForTesting
  static void register(SpanExporter spanExporter, SpanExporter.Handler handler) {
    spanExporter.registerHandler(REGISTER_NAME, handler);
  }

  /**
   * Unregisters the pplication Insights Trace exporter from the OpenCensus library.
   *
   * @throws IllegalStateException if a pplication Insights exporter is not registered.
   * @since 0.13
   */
  public static void unregister() {
    synchronized (monitor) {
      checkState(handler != null, "Application Insights exporter is not registered.");
      unregister(Tracing.getExportComponent().getSpanExporter());
      handler = null;
    }
  }

  /**
   * Unregisters the {@code ApplicationInsightsTraceExporter}.
   *
   * @param spanExporter the instance of the {@code SpanExporter} from where this service is
   *     unregistered.
   * @since 0.13
   */
  @VisibleForTesting
  static void unregister(SpanExporter spanExporter) {
    spanExporter.unregisterHandler(REGISTER_NAME);
  }
}
