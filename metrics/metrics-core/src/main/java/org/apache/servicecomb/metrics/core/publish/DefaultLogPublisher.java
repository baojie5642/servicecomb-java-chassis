/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.servicecomb.metrics.core.publish;

import static org.apache.servicecomb.foundation.common.utils.StringBuilderUtils.appendLine;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.servicecomb.foundation.common.net.NetUtils;
import org.apache.servicecomb.foundation.metrics.MetricsBootstrapConfig;
import org.apache.servicecomb.foundation.metrics.MetricsInitializer;
import org.apache.servicecomb.foundation.metrics.PolledEvent;
import org.apache.servicecomb.foundation.metrics.publish.spectator.MeasurementNode;
import org.apache.servicecomb.foundation.metrics.publish.spectator.MeasurementTree;
import org.apache.servicecomb.foundation.metrics.registry.GlobalRegistry;
import org.apache.servicecomb.foundation.vertx.VertxUtils;
import org.apache.servicecomb.metrics.core.VertxMetersInitializer;
import org.apache.servicecomb.metrics.core.meter.invocation.MeterInvocationConst;
import org.apache.servicecomb.metrics.core.meter.os.CpuMeter;
import org.apache.servicecomb.metrics.core.meter.os.NetMeter;
import org.apache.servicecomb.metrics.core.meter.os.OsMeter;
import org.apache.servicecomb.metrics.core.publish.model.DefaultPublishModel;
import org.apache.servicecomb.metrics.core.publish.model.ThreadPoolPublishModel;
import org.apache.servicecomb.metrics.core.publish.model.invocation.OperationPerf;
import org.apache.servicecomb.metrics.core.publish.model.invocation.OperationPerfGroup;
import org.apache.servicecomb.metrics.core.publish.model.invocation.OperationPerfGroups;
import org.apache.servicecomb.metrics.core.publish.model.invocation.PerfInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.netflix.config.DynamicPropertyFactory;
import com.netflix.spectator.api.Meter;

import io.vertx.core.impl.VertxImplEx;

public class DefaultLogPublisher implements MetricsInitializer {
  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultLogPublisher.class);

  public static final String ENABLED = "servicecomb.metrics.publisher.defaultLog.enabled";

  // for a client, maybe will connect to too many endpoints, so default not print detail, just print summary
  public static final String ENDPOINTS_CLIENT_DETAIL_ENABLED = "servicecomb.metrics.publisher.defaultLog.endpoints.client.detail.enabled";

  //sample
  private static final String SIMPLE_HEADER = "%s:\n  simple:\n"
      + "    status          tps      latency             operation\n";

  private static final String FIRST_LINE_SIMPLE_FORMAT = "    %-15s %-8s %-19s %s\n";

  private static final String SIMPLE_FORMAT = "                    %-8s %-19s %s\n";

  //details
  private static final String PRODUCER_DETAILS_FORMAT = ""
      + "        prepare: %-19s queue       : %-19s filtersReq : %-19s handlersReq: %s\n"
      + "        execute: %-19s handlersResp: %-19s filtersResp: %-19s sendResp   : %s\n";

  private static final String CONSUMER_DETAILS_FORMAT = ""
      + "        prepare     : %-19s handlersReq : %-19s cFiltersReq: %-19s sendReq     : %s\n"
      + "        getConnect  : %-19s writeBuf    : %-19s waitResp   : %-19s wakeConsumer: %s\n"
      + "        cFiltersResp: %-19s handlersResp: %s\n";

  private static final String EDGE_DETAILS_FORMAT = ""
      + "        prepare     : %-19s queue       : %-19s sFiltersReq : %-19s handlersReq : %s\n"
      + "        cFiltersReq : %-19s sendReq     : %-19s getConnect  : %-19s writeBuf    : %s\n"
      + "        waitResp    : %-19s wakeConsumer: %-19s cFiltersResp: %-19s handlersResp: %s\n"
      + "        sFiltersResp: %-19s sendResp    : %s\n";

  @Override
  public void init(GlobalRegistry globalRegistry, EventBus eventBus, MetricsBootstrapConfig config) {
    if (!DynamicPropertyFactory.getInstance()
        .getBooleanProperty(ENABLED, false)
        .get()) {
      return;
    }

    eventBus.register(this);
  }

  @Subscribe
  public void onPolledEvent(PolledEvent event) {
    try {
      printLog(event.getMeters());
    } catch (Throwable e) {
      // make development easier
      LOGGER.error("Failed to print perf log.", e);
    }
  }

  protected void printLog(List<Meter> meters) {
    StringBuilder sb = new StringBuilder();
    sb.append("\n");

    PublishModelFactory factory = new PublishModelFactory(meters);
    DefaultPublishModel model = factory.createDefaultPublishModel();

    printOsLog(factory.getTree(), sb);
    printVertxMetrics(factory.getTree(), sb);
    printThreadPoolMetrics(model, sb);

    printConsumerLog(model, sb);
    printProducerLog(model, sb);
    printEdgeLog(model, sb);

    LOGGER.info(sb.toString());
  }

  protected void printOsLog(MeasurementTree tree, StringBuilder sb) {
    MeasurementNode osNode = tree.findChild(OsMeter.OS_NAME);
    if (osNode == null || osNode.getMeasurements().isEmpty()) {
      return;
    }

    appendLine(sb, "os:");
    printCpuLog(sb, osNode);
    printNetLog(sb, osNode);
  }

  private void printNetLog(StringBuilder sb, MeasurementNode osNode) {
    MeasurementNode netNode = osNode.findChild(OsMeter.OS_TYPE_NET);
    if (netNode == null || netNode.getMeasurements().isEmpty()) {
      return;
    }

    appendLine(sb, "  net:");
    appendLine(sb, "    send(Bps)    recv(Bps)    send(pps)    recv(pps)    interface");

    StringBuilder tmpSb = new StringBuilder();
    for (MeasurementNode interfaceNode : netNode.getChildren().values()) {
      double sendRate = interfaceNode.findChild(NetMeter.TAG_SEND.value()).summary();
      double sendPacketsRate = interfaceNode.findChild(NetMeter.TAG_PACKETS_SEND.value()).summary();
      double receiveRate = interfaceNode.findChild(NetMeter.TAG_RECEIVE.value()).summary();
      double receivePacketsRate = interfaceNode.findChild(NetMeter.TAG_PACKETS_RECEIVE.value()).summary();
      if (sendRate == 0 && receiveRate == 0 && receivePacketsRate == 0 && sendPacketsRate == 0) {
        continue;
      }

      appendLine(tmpSb, "    %-12s %-12s %-12s %-12s %s",
          NetUtils.humanReadableBytes((long) sendRate),
          NetUtils.humanReadableBytes((long) receiveRate),
          NetUtils.humanReadableBytes((long) sendPacketsRate),
          NetUtils.humanReadableBytes((long) receivePacketsRate),
          interfaceNode.getName());
    }
    if (tmpSb.length() != 0) {
      sb.append(tmpSb.toString());
    }
  }

  private void printCpuLog(StringBuilder sb, MeasurementNode osNode) {
    MeasurementNode cpuNode = osNode.findChild(OsMeter.OS_TYPE_ALL_CPU);
    MeasurementNode processNode = osNode.findChild(OsMeter.OS_TYPE_PROCESS_CPU);
    if (cpuNode == null || cpuNode.getMeasurements().isEmpty() ||
        processNode == null || processNode.getMeasurements().isEmpty()) {
      return;
    }

    double allRate = cpuNode.summary();
    double processRate = processNode.summary();

    appendLine(sb, "  cpu:");
    appendLine(sb, "    all: %.2f%%    process: %.2f%%", allRate * 100, processRate * 100);
  }


  protected void printThreadPoolMetrics(DefaultPublishModel model, StringBuilder sb) {
    if (model.getThreadPools().isEmpty()) {
      return;
    }

    sb.append("threadPool:\n");
    sb.append("  corePoolSize maxThreads poolSize currentThreadsBusy queueSize taskCount completedTaskCount name\n");
    for (Entry<String, ThreadPoolPublishModel> entry : model.getThreadPools().entrySet()) {
      ThreadPoolPublishModel threadPoolPublishModel = entry.getValue();
      sb.append(String.format("  %-12d %-10d %-8d %-18d %-9d %-9.1f %-18.1f %s\n",
          threadPoolPublishModel.getCorePoolSize(),
          threadPoolPublishModel.getMaxThreads(),
          threadPoolPublishModel.getPoolSize(),
          threadPoolPublishModel.getCurrentThreadsBusy(),
          threadPoolPublishModel.getQueueSize(),
          threadPoolPublishModel.getAvgTaskCount(),
          threadPoolPublishModel.getAvgCompletedTaskCount(),
          entry.getKey()));
    }
  }

  protected void printEdgeLog(DefaultPublishModel model, StringBuilder sb) {
    OperationPerfGroups edgePerf = model.getEdge().getOperationPerfGroups();
    if (edgePerf == null) {
      return;
    }
    sb.append(String.format(SIMPLE_HEADER, "edge"));

    StringBuilder detailsBuilder = new StringBuilder();
    //print sample
    for (Map<String, OperationPerfGroup> statusMap : edgePerf.getGroups().values()) {
      for (OperationPerfGroup perfGroup : statusMap.values()) {
        //append sample
        sb.append(printSamplePerf(perfGroup));
        //append details
        detailsBuilder.append(printEdgeDetailsPerf(perfGroup));
      }
    }

    sb.append("  details:\n")
        .append(detailsBuilder);
  }


  protected void printConsumerLog(DefaultPublishModel model, StringBuilder sb) {
    OperationPerfGroups consumerPerf = model.getConsumer().getOperationPerfGroups();
    if (consumerPerf == null) {
      return;
    }

    sb.append(String.format(SIMPLE_HEADER, "consumer"));

    StringBuilder detailsBuilder = new StringBuilder();
    //print sample
    for (Map<String, OperationPerfGroup> statusMap : consumerPerf.getGroups().values()) {
      for (OperationPerfGroup perfGroup : statusMap.values()) {
        //append sample
        sb.append(printSamplePerf(perfGroup));
        //append details
        detailsBuilder.append(printConsumerDetailsPerf(perfGroup));
      }
    }
    sb.append("  details:\n")
        .append(detailsBuilder);
  }


  protected void printProducerLog(DefaultPublishModel model, StringBuilder sb) {
    OperationPerfGroups producerPerf = model.getProducer().getOperationPerfGroups();

    if (producerPerf == null) {
      return;
    }
    sb.append(String.format(SIMPLE_HEADER, "producer"));
    // use detailsBuilder, we can traverse the map only once
    StringBuilder detailsBuilder = new StringBuilder();
    //print sample
    for (Map<String, OperationPerfGroup> statusMap : producerPerf.getGroups().values()) {
      for (OperationPerfGroup perfGroup : statusMap.values()) {
        //append sample
        sb.append(printSamplePerf(perfGroup));
        //append details
        detailsBuilder.append(printProducerDetailsPerf(perfGroup));
      }
    }
    //print details
    sb.append("  details:\n")
        .append(detailsBuilder);
  }


  private StringBuilder printSamplePerf(OperationPerfGroup perfGroup) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < perfGroup.getOperationPerfs().size(); i++) {
      OperationPerf operationPerf = perfGroup.getOperationPerfs().get(i);
      PerfInfo stageTotal = operationPerf.findStage(MeterInvocationConst.STAGE_TOTAL);
      if (i == 0) {
        // first line
        String status = perfGroup.getTransport() + "." + perfGroup.getStatus();
        sb.append(String.format(FIRST_LINE_SIMPLE_FORMAT, status, stageTotal.getTps(),
            getDetailsFromPerf(stageTotal), operationPerf.getOperation()));
      } else {
        sb.append(String
            .format(SIMPLE_FORMAT, stageTotal.getTps(), getDetailsFromPerf(stageTotal), operationPerf.getOperation()));
      }
    }
    //print summary
    OperationPerf summaryOperation = perfGroup.getSummary();
    PerfInfo stageSummaryTotal = summaryOperation.findStage(MeterInvocationConst.STAGE_TOTAL);
    sb.append(
        String.format(SIMPLE_FORMAT, stageSummaryTotal.getTps(), getDetailsFromPerf(stageSummaryTotal), "(summary)"));
    return sb;
  }

  private StringBuilder printProducerDetailsPerf(OperationPerfGroup perfGroup) {
    StringBuilder sb = new StringBuilder();
    //append rest.200:
    sb.append("    ")
        .append(perfGroup.getTransport())
        .append(".")
        .append(perfGroup.getStatus())
        .append(":\n");
    PerfInfo prepare, queue, filtersReq, handlersReq, execute, handlersResp, filtersResp, sendResp;
    for (OperationPerf operationPerf : perfGroup.getOperationPerfs()) {

      prepare = operationPerf.findStage(MeterInvocationConst.STAGE_PREPARE);
      queue = operationPerf.findStage(MeterInvocationConst.STAGE_EXECUTOR_QUEUE);
      filtersReq = operationPerf.findStage(MeterInvocationConst.STAGE_SERVER_FILTERS_REQUEST);
      handlersReq = operationPerf.findStage(MeterInvocationConst.STAGE_HANDLERS_REQUEST);
      execute = operationPerf.findStage(MeterInvocationConst.STAGE_EXECUTION);
      handlersResp = operationPerf.findStage(MeterInvocationConst.STAGE_HANDLERS_RESPONSE);
      filtersResp = operationPerf.findStage(MeterInvocationConst.STAGE_SERVER_FILTERS_RESPONSE);
      sendResp = operationPerf.findStage(MeterInvocationConst.STAGE_PRODUCER_SEND_RESPONSE);

      sb.append("      ")
          .append(operationPerf.getOperation())
          .append(":\n")
          .append(String.format(PRODUCER_DETAILS_FORMAT,
              getDetailsFromPerf(prepare),
              getDetailsFromPerf(queue),
              getDetailsFromPerf(filtersReq),
              getDetailsFromPerf(handlersReq),
              getDetailsFromPerf(execute),
              getDetailsFromPerf(handlersResp),
              getDetailsFromPerf(filtersResp),
              getDetailsFromPerf(sendResp)
          ));
    }

    return sb;
  }

  private StringBuilder printConsumerDetailsPerf(OperationPerfGroup perfGroup) {
    StringBuilder sb = new StringBuilder();
    //append rest.200:
    sb.append("    ")
        .append(perfGroup.getTransport())
        .append(".")
        .append(perfGroup.getStatus())
        .append(":\n");

    PerfInfo prepare, handlersReq, clientFiltersReq, sendReq, getConnect, writeBuf,
        waitResp, wakeConsumer, clientFiltersResp, handlersResp;
    for (OperationPerf operationPerf : perfGroup.getOperationPerfs()) {

      prepare = operationPerf.findStage(MeterInvocationConst.STAGE_PREPARE);
      handlersReq = operationPerf.findStage(MeterInvocationConst.STAGE_HANDLERS_REQUEST);
      clientFiltersReq = operationPerf.findStage(MeterInvocationConst.STAGE_CLIENT_FILTERS_REQUEST);
      sendReq = operationPerf.findStage(MeterInvocationConst.STAGE_CONSUMER_SEND_REQUEST);
      getConnect = operationPerf.findStage(MeterInvocationConst.STAGE_CONSUMER_GET_CONNECTION);
      writeBuf = operationPerf.findStage(MeterInvocationConst.STAGE_CONSUMER_WRITE_TO_BUF);
      waitResp = operationPerf.findStage(MeterInvocationConst.STAGE_CONSUMER_WAIT_RESPONSE);
      wakeConsumer = operationPerf.findStage(MeterInvocationConst.STAGE_CONSUMER_WAKE_CONSUMER);
      clientFiltersResp = operationPerf.findStage(MeterInvocationConst.STAGE_CLIENT_FILTERS_RESPONSE);
      handlersResp = operationPerf.findStage(MeterInvocationConst.STAGE_HANDLERS_RESPONSE);

      sb.append("      ")
          .append(operationPerf.getOperation())
          .append(":\n")
          .append(String.format(CONSUMER_DETAILS_FORMAT,
              getDetailsFromPerf(prepare),
              getDetailsFromPerf(handlersReq),
              getDetailsFromPerf(clientFiltersReq),
              getDetailsFromPerf(sendReq),
              getDetailsFromPerf(getConnect),
              getDetailsFromPerf(writeBuf),
              getDetailsFromPerf(waitResp),
              getDetailsFromPerf(wakeConsumer),
              getDetailsFromPerf(clientFiltersResp),
              getDetailsFromPerf(handlersResp)
          ));
    }

    return sb;
  }

  private StringBuilder printEdgeDetailsPerf(OperationPerfGroup perfGroup) {
    StringBuilder sb = new StringBuilder();
    //append rest.200:
    sb.append("    ")
        .append(perfGroup.getTransport())
        .append(".")
        .append(perfGroup.getStatus())
        .append(":\n");

    PerfInfo prepare, queue, serverFiltersReq, handlersReq, clientFiltersReq, sendReq, getConnect, writeBuf,
        waitResp, wakeConsumer, clientFiltersResp, handlersResp, serverFiltersResp, sendResp;
    for (OperationPerf operationPerf : perfGroup.getOperationPerfs()) {
      prepare = operationPerf.findStage(MeterInvocationConst.STAGE_PREPARE);
      queue = operationPerf.findStage(MeterInvocationConst.STAGE_EXECUTOR_QUEUE);
      serverFiltersReq = operationPerf.findStage(MeterInvocationConst.STAGE_SERVER_FILTERS_REQUEST);
      handlersReq = operationPerf.findStage(MeterInvocationConst.STAGE_HANDLERS_REQUEST);
      clientFiltersReq = operationPerf.findStage(MeterInvocationConst.STAGE_CLIENT_FILTERS_REQUEST);
      sendReq = operationPerf.findStage(MeterInvocationConst.STAGE_CONSUMER_SEND_REQUEST);
      getConnect = operationPerf.findStage(MeterInvocationConst.STAGE_CONSUMER_GET_CONNECTION);
      writeBuf = operationPerf.findStage(MeterInvocationConst.STAGE_CONSUMER_WRITE_TO_BUF);
      waitResp = operationPerf.findStage(MeterInvocationConst.STAGE_CONSUMER_WAIT_RESPONSE);
      wakeConsumer = operationPerf.findStage(MeterInvocationConst.STAGE_CONSUMER_WAKE_CONSUMER);
      clientFiltersResp = operationPerf.findStage(MeterInvocationConst.STAGE_CLIENT_FILTERS_RESPONSE);
      handlersResp = operationPerf.findStage(MeterInvocationConst.STAGE_HANDLERS_RESPONSE);
      serverFiltersResp = operationPerf.findStage(MeterInvocationConst.STAGE_SERVER_FILTERS_RESPONSE);
      sendResp = operationPerf.findStage(MeterInvocationConst.STAGE_PRODUCER_SEND_RESPONSE);

      sb.append("      ")
          .append(operationPerf.getOperation())
          .append(":\n")
          .append(String.format(EDGE_DETAILS_FORMAT,
              getDetailsFromPerf(prepare),
              getDetailsFromPerf(queue),
              getDetailsFromPerf(serverFiltersReq),
              getDetailsFromPerf(handlersReq),
              getDetailsFromPerf(clientFiltersReq),
              getDetailsFromPerf(sendReq),
              getDetailsFromPerf(getConnect),
              getDetailsFromPerf(writeBuf),
              getDetailsFromPerf(waitResp),
              getDetailsFromPerf(wakeConsumer),
              getDetailsFromPerf(clientFiltersResp),
              getDetailsFromPerf(handlersResp),
              getDetailsFromPerf(serverFiltersResp),
              getDetailsFromPerf(sendResp)
          ));
    }

    return sb;
  }

  protected void printVertxMetrics(MeasurementTree tree, StringBuilder sb) {
    appendLine(sb, "vertx:");

    appendLine(sb, "  instances:");
    appendLine(sb, "    name       eventLoopContext-created");
    for (Entry<String, VertxImplEx> entry : VertxUtils.getVertxMap().entrySet()) {
      appendLine(sb, "    %-10s %d",
          entry.getKey(),
          entry.getValue().getEventLoopContextCreatedCount());
    }

    ClientEndpointsLogPublisher client = new ClientEndpointsLogPublisher(tree, sb,
        VertxMetersInitializer.ENDPOINTS_CLINET);
    ServerEndpointsLogPublisher server = new ServerEndpointsLogPublisher(tree, sb,
        VertxMetersInitializer.ENDPOINTS_SERVER);
    if (client.isExists() || server.isExists()) {
      appendLine(sb, "  transport:");
      if (client.isExists()) {
        client.print(DynamicPropertyFactory
            .getInstance()
            .getBooleanProperty(ENDPOINTS_CLIENT_DETAIL_ENABLED, false)
            .get());
      }

      if (server.isExists()) {
        server.print(true);
      }
    }
  }

  private static String getDetailsFromPerf(PerfInfo perfInfo) {
    String result = "";
    if (perfInfo != null) {
      result = String.format("%.3f/%.3f", perfInfo.calcMsLatency(), perfInfo.getMsMaxLatency());
    }
    return result;
  }
}
