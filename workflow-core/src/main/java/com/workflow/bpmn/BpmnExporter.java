package com.workflow.bpmn;

import com.alibaba.fastjson2.JSON;
import com.workflow.definition.Candidate;
import com.workflow.definition.NodeDefinition;
import com.workflow.definition.ProcessDefinition;
import com.workflow.definition.Transition;
import com.workflow.definition.VariableDefinition;
import com.workflow.enums.CandidateStrategy;
import com.workflow.enums.NodeType;
import com.workflow.enums.TimeoutPolicy;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 流程定义 → BPMN 2.0 XML。
 *
 * <p>用 JDK 自带 DOM 生成而非拼字符串：转义与缩进交给解析器，
 * 手写拼接迟早会在条件表达式里出现 {@code amount < 1000} 这种带尖括号的取值时炸开。
 *
 * <p><b>命名空间约定</b>：BPMN 标准里没有"候选人策略""超时策略"这些概念，
 * 强行塞进标准属性会产出看起来合法、实则别的引擎读不懂的文件。
 * 因此这类扩展统一挂在自定义命名空间 {@code wf:} 下，一眼可辨是本项目私有扩展。
 *
 * <p><b>不承诺与 Flowable/Camunda 互通</b>：目标是给我们自己的模型一个
 * 标准化的交换格式（可版本化、可被外部工具检视），不是做一个 BPMN 兼容引擎。
 */
public final class BpmnExporter {

    /** BPMN 2.0 官方命名空间。 */
    public static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    /** 本项目私有扩展命名空间。 */
    public static final String WF_NS = "https://workflow.engine/bpmn";
    /** Flowable 兼容命名空间（用于动态 assignee 等 Flowable 标准属性）。 */
    public static final String FLOWABLE_NS = "http://flowable.org/bpmn";

    private BpmnExporter() { }

    public static String export(ProcessDefinition def) {
        return export(List.of(def));
    }

    /** 一次导出多个流程定义（BPMN 的 definitions 根本就允许多个 process）。 */
    public static String export(Collection<ProcessDefinition> definitions) {
        try {
            Document doc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().newDocument();
            doc.setXmlStandalone(true);

            var roots = doc.createElementNS(BPMN_NS, "definitions");
            roots.setAttribute("xmlns", BPMN_NS);
            roots.setAttribute("xmlns:wf", WF_NS);
            roots.setAttribute("xmlns:flowable", FLOWABLE_NS);
            roots.setAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance");
            doc.appendChild(roots);

            for (ProcessDefinition def : definitions) {
                roots.appendChild(exportProcess(doc, def));
            }
            return write(doc);
        } catch (Exception ex) {
            throw new BpmnException("导出 BPMN 失败: " + ex.getMessage(), ex);
        }
    }

    private static Element exportProcess(Document doc, ProcessDefinition def) {
        var process = doc.createElementNS(BPMN_NS, "process");
        process.setAttribute("id", def.getKey());
        process.setAttribute("name", def.getName());
        process.setAttribute("isExecutable", "true");
        process.setAttributeNS(WF_NS, "wf:version", String.valueOf(def.getVersion()));

        if (def.hasVariableDefinitions()) {
            var ext = doc.createElementNS(BPMN_NS, "extensionElements");
            var vars = doc.createElementNS(WF_NS, "wf:variableDefinitions");
            // 复用与 wf_process_def.variable_definitions_json 完全一致的序列化格式，
            // 不再另造一套变量描述 —— 两处格式迟早分叉
            vars.setTextContent(JSON.toJSONString(def.getVariableDefinitions()));
            ext.appendChild(vars);
            process.appendChild(ext);
        }

        for (NodeDefinition node : def.getNodes().values()) {
            process.appendChild(exportNode(doc, def, node));
        }
        int flowIndex = 0;
        for (NodeDefinition node : def.getNodes().values()) {
            for (Transition t : def.getOutgoing(node.getId())) {
                process.appendChild(exportFlow(doc, t, "flow_" + def.getKey() + "_" + (flowIndex++)));
            }
        }
        return process;
    }

    private static Element exportNode(Document doc, ProcessDefinition def,
                                                  NodeDefinition node) {
        String tag = switch (node.getType()) {
            case START -> "startEvent";
            case END -> "endEvent";
            case USER_TASK -> "userTask";
            case EXCLUSIVE_GATEWAY -> "exclusiveGateway";
            case PARALLEL_GATEWAY -> "parallelGateway";
            case SUB_PROCESS -> "callActivity";
            case DYNAMIC_PARALLEL -> throw new UnsupportedOperationException(
                    "DYNAMIC_PARALLEL 节点暂无 BPMN 标准对应物，不支持导出: node=" + node.getId()
                            + "。需要交换格式请先改造为 USER_TASK + 会签，或自行写扩展。");
            case MESSAGE_EVENT -> "intermediateCatchEvent";
            case SIGNAL_EVENT -> "intermediateCatchEvent";
            case TIMER_BOUNDARY -> "boundaryEvent";
            case DECISION -> "businessRuleTask";
            case MULTI_INSTANCE -> "userTask";
            case SERVICE_TASK -> "serviceTask";
        };

        var el = doc.createElementNS(BPMN_NS, tag);
        el.setAttribute("id", node.getId());
        if (node.getName() != null) {
            el.setAttribute("name", node.getName());
        }
        if (node.getType() == NodeType.SUB_PROCESS) {
            el.setAttribute("calledElement", node.getSubProcessKey());
        }

        appendExtensions(doc, el, node);
        appendOutgoing(doc, el, def, node);
        appendMultiInstanceLoop(doc, el, node);
        return el;
    }

    /** 候选人策略与超时挂 wf: 扩展；会签同时补上 BPMN 标准的多实例标记。 */
    private static void appendExtensions(Document doc, Element el, NodeDefinition node) {
        boolean hasStd = node.getCandidate() != null;
        boolean hasAssigneeVar = node.hasAssigneeVariable();
        boolean hasDelegate = node.isServiceTask();
        boolean hasWf = hasStd || hasAssigneeVar || hasDelegate || node.hasTimeout()
                || node.getTimeoutPolicy() != TimeoutPolicy.NONE
                || node.isMessageEvent() || node.isSignalEvent() || node.isTimerBoundary()
                || node.isDecision();
        if (!hasStd && !hasWf) {
            return;
        }

        var ext = doc.createElementNS(BPMN_NS, "extensionElements");
        if (node.getCandidate() != null) {
            Candidate c = node.getCandidate();
            var cand = doc.createElementNS(WF_NS, "wf:candidate");
            cand.setAttribute("strategy", c.getStrategy().name());
            cand.setTextContent(String.join(",", c.getUserIds()));
            ext.appendChild(cand);
        }
        // 动态 assignee 导出为 flowable:assignee="${varName}" 兼容格式
        if (hasAssigneeVar) {
            el.setAttributeNS(FLOWABLE_NS, "flowable:assignee", "${" + node.getAssigneeVariable() + "}");
        }
        // serviceTask delegate 导出为 wf:delegate
        if (hasDelegate) {
            var delegate = doc.createElementNS(WF_NS, "wf:delegate");
            delegate.setAttribute("key", node.getDelegateKey());
            ext.appendChild(delegate);
        }
        if (node.hasTimeout() || node.getTimeoutPolicy() != TimeoutPolicy.NONE) {
            var to = doc.createElementNS(WF_NS, "wf:timeout");
            to.setAttribute("millis", String.valueOf(node.getTimeoutMillis()));
            to.setAttribute("policy", node.getTimeoutPolicy().name());
            if (node.getTimeoutTargetUserId() != null) {
                to.setAttribute("targetUser", node.getTimeoutTargetUserId());
            }
            ext.appendChild(to);
        }
        // 事件属性导出
        if (node.isMessageEvent()) {
            var msg = doc.createElementNS(WF_NS, "wf:message");
            msg.setAttribute("messageName", node.getMessageEvent().messageName());
            msg.setAttribute("correlationKey", node.getMessageEvent().correlationKeyExpression());
            ext.appendChild(msg);
        }
        if (node.isSignalEvent()) {
            var sig = doc.createElementNS(WF_NS, "wf:signal");
            sig.setAttribute("signalName", node.getSignalEvent().signalName());
            ext.appendChild(sig);
        }
        if (node.isTimerBoundary()) {
            var tmr = doc.createElementNS(WF_NS, "wf:timer");
            tmr.setAttribute("attachedTo", node.getTimerBoundaryEvent().attachedToNodeId());
            tmr.setAttribute("duration", String.valueOf(node.getTimerBoundaryEvent().durationMillis()));
            tmr.setAttribute("interrupting", String.valueOf(node.getTimerBoundaryEvent().interrupting()));
            ext.appendChild(tmr);
        }
        // 决策节点属性导出
        if (node.isDecision()) {
            var dec = doc.createElementNS(WF_NS, "wf:decision");
            dec.setAttribute("tableId", node.getDecisionTableId());
            ext.appendChild(dec);
        }
        el.appendChild(ext);
    }

    /**
     * BPMN 标准多实例标记 —— 让外部 BPMN 工具（含设计器）能识别会签/或签。
     *
     * <p>两种来源写法有意不同，导入端正是靠这个区分（见 BpmnImporter 类 javadoc）：
     * <ul>
     *   <li>USER_TASK + candidate → 只写 {@code wf:cardinality}，本项目自有标记</li>
     *   <li>MULTI_INSTANCE → 写 {@code flowable:collection="${var}"}，Flowable 兼容格式</li>
     * </ul>
     */
    private static void appendMultiInstanceLoop(Document doc, Element el, NodeDefinition node) {
        if (node.getType() == NodeType.MULTI_INSTANCE) {
            var mi = doc.createElementNS(BPMN_NS, "multiInstanceLoopCharacteristics");
            mi.setAttribute("isSequential", "false");
            mi.setAttributeNS(FLOWABLE_NS, "flowable:collection",
                    "${" + node.getMultiInstanceCollection() + "}");
            appendCompletionCondition(doc, mi, node.getMultiInstanceStrategy());
            el.appendChild(mi);
            return;
        }
        if (node.getCandidate() != null) {
            Candidate c = node.getCandidate();
            var mi = doc.createElementNS(BPMN_NS, "multiInstanceLoopCharacteristics");
            mi.setAttribute("isSequential", "false");
            mi.setAttributeNS(WF_NS, "wf:cardinality", String.valueOf(c.getUserIds().size()));
            appendCompletionCondition(doc, mi, c.getStrategy());
            el.appendChild(mi);
        }
    }

    /** 或签 = 任一完成即结束，用 BPMN 标准完成条件表达；会签不写条件（默认全部完成）。 */
    private static void appendCompletionCondition(Document doc, Element mi, CandidateStrategy strategy) {
        if (strategy == CandidateStrategy.ANY) {
            var cc = doc.createElementNS(BPMN_NS, "completionCondition");
            cc.setTextContent("${nrOfCompletedInstances >= 1}");
            mi.appendChild(cc);
        }
    }

    private static void appendOutgoing(Document doc, Element el,
                                       ProcessDefinition def, NodeDefinition node) {
        int i = 0;
        for (Transition t : def.getOutgoing(node.getId())) {
            var out = doc.createElementNS(BPMN_NS, "outgoing");
            out.setTextContent(flowIdFor(def, node, i++, def.getOutgoing(node.getId())));
            el.appendChild(out);
        }
    }

    private static Element exportFlow(Document doc, Transition t, String id) {
        var flow = doc.createElementNS(BPMN_NS, "sequenceFlow");
        flow.setAttribute("id", id);
        flow.setAttribute("sourceRef", t.getFrom());
        flow.setAttribute("targetRef", t.getTo());
        if (t.getCondition() != null && !t.getCondition().isBlank()) {
            var cond = doc.createElementNS(BPMN_NS, "conditionExpression");
            cond.setAttribute("xsi:type", "bpmn:tFormalExpression");
            cond.setTextContent(t.getCondition());
            flow.appendChild(cond);
        }
        return flow;
    }

    /** 与 exportProcess 里 flow id 的生成规则保持一致。 */
    private static String flowIdFor(ProcessDefinition def, NodeDefinition node,
                                    int indexInNode, List<Transition> all) {
        return "flow_" + def.getKey() + "_" + globalFlowIndex(def, node.getId(), indexInNode);
    }

    private static int globalFlowIndex(ProcessDefinition def, String nodeId, int indexInNode) {
        int idx = 0;
        for (NodeDefinition n : def.getNodes().values()) {
            if (n.getId().equals(nodeId)) {
                return idx + indexInNode;
            }
            idx += def.getOutgoing(n.getId()).size();
        }
        return idx + indexInNode;
    }

    private static String write(Document doc) throws Exception {
        Transformer tf = TransformerFactory.newInstance().newTransformer();
        tf.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        tf.setOutputProperty(OutputKeys.INDENT, "yes");
        tf.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        StringWriter sw = new StringWriter();
        tf.transform(new DOMSource(doc), new StreamResult(sw));
        return sw.toString();
    }
}
