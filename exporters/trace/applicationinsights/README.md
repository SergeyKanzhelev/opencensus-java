# OpenCensus Microsoft Azure Application Insights Trace Exporter
[![Build Status][travis-image]][travis-url]
[![Windows Build Status][appveyor-image]][appveyor-url]
[![Maven Central][maven-image]][maven-url]

The *OpenCensus Application Insights Exporter* is a trace exporter that exports
data to Microsoft Azure Application Insights. [Microsoft Azure Application Insights](https://docs.microsoft.com/en-us/azure/application-insights/app-insights-overview)
is an extensible Application Performance Management (APM) service for web developers on multiple platforms. 

## Quickstart

### Prerequisites

Application Insights is one of the many services hosted within Microsoft Azure, and telemetry is sent there for analysis and presentation. 
So before you do anything else, you'll need a subscription to [Microsoft Azure](https://azure.com/). 
It's free to sign up, and if you choose the basic pricing plan of Application Insights, there's no charge until your application has grown to have substantial usage.
If your organization already has a subscription, they could add your Microsoft account to it.

To use this exporter, you must have an application that you'd like to trace. The app can be on Microsoft Azure, on-premise, or another cloud platform.
You would also need to create an Application Insights resource and copy its [instrumentation key](https://docs.microsoft.com/en-us/azure/application-insights/app-insights-create-new-resource).

### Hello Application Insights

#### Add the dependencies to your project

For Maven add to your `pom.xml`:
```xml
<dependencies>
  <dependency>
    <groupId>io.opencensus</groupId>
    <artifactId>opencensus-api</artifactId>
    <version>0.13.0</version>
  </dependency>
  <dependency>
    <groupId>io.opencensus</groupId>
    <artifactId>opencensus-exporter-trace-applicationinsights</artifactId>
    <version>0.13.0</version>
  </dependency>
  <dependency>
    <groupId>io.opencensus</groupId>
    <artifactId>opencensus-impl</artifactId>
    <version>0.13.0</version>
    <scope>runtime</scope>
  </dependency>
  <dependency>
    <groupId>com.microsoft.azure</groupId>
    <artifactId>applicationinsights-core</artifactId>
    <version>[2.0,)</version>
  </dependency>
</dependencies>
```

For Gradle add to your dependencies:
```groovy
compile 'io.opencensus:opencensus-api:0.12.2'
compile 'io.opencensus:opencensus-exporter-trace-applicationinsights:0.13.0'
compile group: 'com.microsoft.azure', name: 'applicationinsights-core', version: '2.+'
runtime 'io.opencensus:opencensus-impl:0.12.2'
```

#### Register the exporter

```java
public class MyMainClass {
  public static void main(String[] args) throws Exception {
    TelemetryConfiguration.getActive().setInstrumentationKey("**your instrumentation key**");
    ApplicationInsightsTraceExporter.createAndRegister("my-service");
    // ...
  }
}
```

#### Java Versions

Java 6 or above is required for using this exporter.

[travis-image]: https://travis-ci.org/census-instrumentation/opencensus-java.svg?branch=master
[travis-url]: https://travis-ci.org/census-instrumentation/opencensus-java
[appveyor-image]: https://ci.appveyor.com/api/projects/status/hxthmpkxar4jq4be/branch/master?svg=true
[appveyor-url]: https://ci.appveyor.com/project/opencensusjavateam/opencensus-java/branch/master
[maven-image]: https://maven-badges.herokuapp.com/maven-central/io.opencensus/opencensus-exporter-trace-applicationinsights/badge.svg
[maven-url]: https://maven-badges.herokuapp.com/maven-central/io.opencensus/opencensus-exporter-trace-applicationinsights
