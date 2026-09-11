package com.workflow.bpmn;

import com.alibaba.fastjson2.JSON;
import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.definition.VariableDefinition;
import com.workflow.enums.CandidateStrategy;
import com.workflow.enums.TimeoutPolicy;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * BPMN 2.0 XML → 流程定义。
 *
 * <p><b>必须走 {@link ProcessBuilder#build()} 的校验</b>，不能直接构造 ProcessDefinition ——
 * 否则就是我们在 REST 层刚拒绝过的"第二条无校验入口"。
 *
 * <p>支持的 BPMN 元素：
 * <ul>
 *   <li>startEvent / endEvent → START / END</li>
 *   <li>userTask → USER_TASK（从 extensionElements 读 wf:candidate）</li>
 *   <li>exclusiveGateway → EXCLUSIVE_GATEWAY</li>
 *   <li>parallelGateway → PARALLEL_GATEWAY</li>
 *   <li>callActivity → SUB_PROCESS（读 calledElement 属性）</li>
 *   <li>intermediateCatchEvent → MESSAGE_EVENT / SIGNAL_EVENT（从 extensionElements 读 wf:message / wf:signal）</li>
 *   <li>boundaryEvent → TIMER_BOUNDARY（从 extensionElements 读 wf:timer）</li>
 *   <li>sequenceFlow → Transition（读 conditionExpression）</li>
 * </ul>
 *
 * <p>不支持的：
 * <ul>
 *   <li>DYNAMIC_PARALLEL —— BPMN 标准里没有对应物，导出时已拒绝，导入时也不支持</li>
 *   <li>serviceTask / sendTask / receiveTask —— 引擎没有这些节点类型</li>
 * </ul>
 */
public final class BpmnImporter {

    private BpmnImporter() { }

    public static ProcessDefinition importFrom(String xml) {
        try {
            Document doc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            doc.getDocumentElement().normalize();

            Element process = findProcess(doc);
            if (process == null) {
                throw new BpmnException("BPMN 文件中未找到 <process> 元素");
            }
            return parseProcess(process);
        } catch (BpmnException | IllegalStateException ex) {
            // BpmnException 和 ProcessBuilder.build() 的校验错误直接透传
            throw ex;
        } catch (Exception ex) {
            throw new BpmnException("解析 BPMN 失败: " + ex.getMessage(), ex);
        }
    }

    private static Element findProcess(Document doc) {
        // 先尝试标准 BPMN 命名空间
        NodeList list = doc.getElementsByTagNameNS(BpmnExporter.BPMN_NS, "process");
        if (list.getLength() == 0) {
            // 再尝试无前缀
            list = doc.getElementsByTagName("process");
        }
        if (list.getLength() == 0) {
            // 最后尝试带 bpmn: 前缀的标签名
            list = doc.getElementsByTagName("bpmn:process");
        }
        if (list.getLength() > 0) {
            return (Element) list.item(0);
        }
        // 兜底：遍历所有元素找 process
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element el = (Element) all.item(i);
            String tag = el.getLocalName() != null ? el.getLocalName() : el.getTagName();
            if (tag.contains(":")) {
                tag = tag.substring(tag.indexOf(':') + 1);
            }
            if ("process".equals(tag)) {
                return el;
            }
        }
        return null;
    }

    private static ProcessDefinition parseProcess(Element process) {
        String key = process.getAttribute("id");
        String name = process.getAttribute("name");
        // 版本号：先尝试命名空间属性，再尝试带前缀的属性名
        String versionStr = process.getAttributeNS(BpmnExporter.WF_NS, "version");
        if (versionStr.isBlank()) {
            versionStr = process.getAttribute("wf:version");
        }
        int version = parseInt(versionStr, 1);

        ProcessBuilder builder = ProcessBuilder.create(key, name).version(version);

        // 先读变量定义（挂在 process 的 extensionElements 下）
        parseVariableDefinitions(process, builder);

        // 遍历节点
        NodeList children = process.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element el)) {
                continue;
            }
            // 处理带前缀的标签名（如 bpmn:startEvent → startEvent）
            String tag = el.getLocalName() != null ? el.getLocalName() : el.getTagName();
            if (tag.contains(":")) {
                tag = tag.substring(tag.indexOf(':') + 1);
            }
            switch (tag) {
                case "startEvent" -> builder.start(el.getAttribute("id"));
                case "endEvent" -> builder.end(el.getAttribute("id"));
                case "userTask" -> parseUserTask(el, builder);
                case "serviceTask" -> parseServiceTask(el, builder);
                case "exclusiveGateway" -> builder.exclusiveGateway(el.getAttribute("id"));
                case "parallelGateway" -> builder.parallelGateway(el.getAttribute("id"));
                case "callActivity" -> parseCallActivity(el, builder);
                case "intermediateCatchEvent" -> parseIntermediateCatchEvent(el, builder);
                case "boundaryEvent" -> parseBoundaryEvent(el, builder);
                case "businessRuleTask" -> parseBusinessRuleTask(el, builder);
                case "sequenceFlow" -> parseSequenceFlow(el, builder);
                // multiInstanceLoopCharacteristics 在 userTask 内部处理，这里忽略
            }
        }

        return builder.build();
    }

    private static void parseVariableDefinitions(Element process, ProcessBuilder builder) {
        NodeList extList = process.getElementsByTagNameNS(BpmnExporter.BPMN_NS, "extensionElements");
        if (extList.getLength() == 0) {
            extList = process.getElementsByTagName("extensionElements");
        }
        for (int i = 0; i < extList.getLength(); i++) {
            Element ext = (Element) extList.item(i);
            NodeList varDefs = ext.getElementsByTagNameNS(BpmnExporter.WF_NS, "variableDefinitions");
            if (varDefs.getLength() == 0) {
                varDefs = ext.getElementsByTagName("wf:variableDefinitions");
            }
            if (varDefs.getLength() > 0) {
                String json = varDefs.item(0).getTextContent();
                List<VariableDefinition> defs = JSON.parseArray(json, VariableDefinition.class);
                for (VariableDefinition d : defs) {
                    builder.variable(d);
                }
            }
        }
    }

    private static void parseUserTask(Element el, ProcessBuilder builder) {
        String id = el.getAttribute("id");
        String name = el.getAttribute("name");

        // 优先检查 flowable:assignee="${varName}" 动态 assignee
        String flowableAssignee = el.getAttributeNS(BpmnExporter.FLOWABLE_NS, "assignee");
        if (flowableAssignee.isBlank()) {
            flowableAssignee = el.getAttribute("flowable:assignee");
        }
        if (!flowableAssignee.isBlank() && flowableAssignee.startsWith("${") && flowableAssignee.endsWith("}")) {
            String varName = flowableAssignee.substring(2, flowableAssignee.length() - 1);
            builder.userTask(id, name, varName);
        } else {
            // 走原 candidate 逻辑
            Candidate candidate = parseCandidate(el);
            builder.userTask(id, name, candidate);
        }

        // 超时配置
        parseTimeout(el, builder);
    }

    private static Candidate parseCandidate(Element userTask) {
        NodeList extList = userTask.getElementsByTagNameNS(BpmnExporter.BPMN_NS, "extensionElements");
        if (extList.getLength() == 0) {
            extList = userTask.getElementsByTagName("extensionElements");
        }
        for (int i = 0; i < extList.getLength(); i++) {
            Element ext = (Element) extList.item(i);
            NodeList candList = ext.getElementsByTagNameNS(BpmnExporter.WF_NS, "candidate");
            if (candList.getLength() == 0) {
                candList = ext.getElementsByTagName("wf:candidate");
            }
            if (candList.getLength() > 0) {
                Element cand = (Element) candList.item(0);
                String strategy = cand.getAttribute("strategy");
                String users = cand.getTextContent();
                CandidateStrategy cs = CandidateStrategy.valueOf(strategy);
                Set<String> userIds = Set.of(users.split(","));
                return new Candidate(userIds, cs);
            }
        }

        // 回退：从 multiInstanceLoopCharacteristics 的 wf:cardinality 推断（仅 ALL 策略）
        NodeList miList = userTask.getElementsByTagNameNS(BpmnExporter.BPMN_NS, "multiInstanceLoopCharacteristics");
        if (miList.getLength() == 0) {
            miList = userTask.getElementsByTagName("multiInstanceLoopCharacteristics");
        }
        if (miList.getLength() > 0) {
            Element mi = (Element) miList.item(0);
            String card = mi.getAttributeNS(BpmnExporter.WF_NS, "cardinality");
            if (card.isBlank()) {
                card = mi.getAttribute("wf:cardinality");
            }
            if (!card.isBlank()) {
                int n = Integer.parseInt(card);
                // 没有具体用户列表，用占位符 u1..uN
                Set<String> placeholders = new java.util.LinkedHashSet<>();
                for (int i = 1; i <= n; i++) {
                    placeholders.add("u" + i);
                }
                return new Candidate(placeholders, CandidateStrategy.ALL);
            }
        }

        throw new BpmnException("userTask 节点 " + userTask.getAttribute("id") + " 缺少候选人定义");
    }

    private static void parseTimeout(Element userTask, ProcessBuilder builder) {
        NodeList extList = userTask.getElementsByTagNameNS(BpmnExporter.BPMN_NS, "extensionElements");
        if (extList.getLength() == 0) {
            extList = userTask.getElementsByTagName("extensionElements");
        }
        for (int i = 0; i < extList.getLength(); i++) {
            Element ext = (Element) extList.item(i);
            NodeList toList = ext.getElementsByTagNameNS(BpmnExporter.WF_NS, "timeout");
            if (toList.getLength() == 0) {
                toList = ext.getElementsByTagName("wf:timeout");
            }
            if (toList.getLength() > 0) {
                Element to = (Element) toList.item(0);
                long millis = Long.parseLong(to.getAttribute("millis"));
                TimeoutPolicy policy = TimeoutPolicy.valueOf(to.getAttribute("policy"));
                String target = to.getAttribute("targetUser");
                if (target.isBlank()) {
                    target = null;
                }
                builder.timeout(userTask.getAttribute("id"), millis, policy, target);
            }
        }
    }

    private static void parseServiceTask(Element el, ProcessBuilder builder) {
        String id = el.getAttribute("id");
        String name = el.getAttribute("name");

        // 优先读 flowable:delegateExpression="${varName}"（Flowable 标准）
        String delegateExpr = el.getAttributeNS(BpmnExporter.FLOWABLE_NS, "delegateExpression");
        if (delegateExpr.isBlank()) {
            delegateExpr = el.getAttribute("flowable:delegateExpression");
        }
        String delegateKey = null;
        if (!delegateExpr.isBlank() && delegateExpr.startsWith("${") && delegateExpr.endsWith("}")) {
            delegateKey = delegateExpr.substring(2, delegateExpr.length() - 1);
        }

        // 兜底：从 extensionElements 读 wf:delegate key（自有格式）
        if (delegateKey == null || delegateKey.isBlank()) {
            NodeList extList = el.getElementsByTagNameNS(BpmnExporter.BPMN_NS, "extensionElements");
            if (extList.getLength() == 0) {
                extList = el.getElementsByTagName("extensionElements");
            }
            for (int i = 0; i < extList.getLength(); i++) {
                Element ext = (Element) extList.item(i);
                NodeList delegateList = ext.getElementsByTagNameNS(BpmnExporter.WF_NS, "delegate");
                if (delegateList.getLength() == 0) {
                    delegateList = ext.getElementsByTagName("wf:delegate");
                }
                if (delegateList.getLength() > 0) {
                    Element delegate = (Element) delegateList.item(0);
                    delegateKey = delegate.getAttribute("key");
                    break;
                }
            }
        }

        if (delegateKey == null || delegateKey.isBlank()) {
            throw new BpmnException("serviceTask 节点 " + id + " 缺少 delegate key 定义");
        }

        builder.serviceTask(id, name, delegateKey);

        // 超时配置
        parseTimeout(el, builder);
    }

    private static void parseCallActivity(Element el, ProcessBuilder builder) {
        String id = el.getAttribute("id");
        String name = el.getAttribute("name");
        String calledElement = el.getAttribute("calledElement");
        if (calledElement.isBlank()) {
            throw new BpmnException("callActivity 节点 " + id + " 缺少 calledElement 属性");
        }
        builder.subProcess(id, name, calledElement);
    }

    /**
     * 解析 intermediateCatchEvent —— 可能是 MESSAGE_EVENT 或 SIGNAL_EVENT。
     * 通过 extensionElements 中的 wf:message / wf:signal 区分。
     */
    private static void parseIntermediateCatchEvent(Element el, ProcessBuilder builder) {
        String id = el.getAttribute("id");
        String name = el.getAttribute("name");
        if (name.isBlank()) {
            name = id;
        }

        // 尝试读取 wf:message
        NodeList msgList = el.getElementsByTagNameNS(BpmnExporter.WF_NS, "message");
        if (msgList.getLength() == 0) {
            msgList = el.getElementsByTagName("wf:message");
        }
        if (msgList.getLength() > 0) {
            Element msg = (Element) msgList.item(0);
            String messageName = msg.getAttribute("messageName");
            String correlationKey = msg.getAttribute("correlationKey");
            if (messageName.isBlank() || correlationKey.isBlank()) {
                throw new BpmnException("intermediateCatchEvent " + id + " 的 wf:message 缺少 messageName 或 correlationKey");
            }
            builder.messageEvent(id, name, messageName, correlationKey);
            return;
        }

        // 尝试读取 wf:signal
        NodeList sigList = el.getElementsByTagNameNS(BpmnExporter.WF_NS, "signal");
        if (sigList.getLength() == 0) {
            sigList = el.getElementsByTagName("wf:signal");
        }
        if (sigList.getLength() > 0) {
            Element sig = (Element) sigList.item(0);
            String signalName = sig.getAttribute("signalName");
            if (signalName.isBlank()) {
                throw new BpmnException("intermediateCatchEvent " + id + " 的 wf:signal 缺少 signalName");
            }
            builder.signalEvent(id, name, signalName);
            return;
        }

        throw new BpmnException("intermediateCatchEvent " + id + " 缺少 wf:message 或 wf:signal 扩展定义");
    }

    /**
     * 解析 boundaryEvent —— 必须是 TIMER_BOUNDARY。
     * 从 extensionElements 中的 wf:timer 读取 attachedTo / duration / interrupting。
     */
    private static void parseBoundaryEvent(Element el, ProcessBuilder builder) {
        String id = el.getAttribute("id");
        String name = el.getAttribute("name");
        if (name.isBlank()) {
            name = id;
        }

        NodeList tmrList = el.getElementsByTagNameNS(BpmnExporter.WF_NS, "timer");
        if (tmrList.getLength() == 0) {
            tmrList = el.getElementsByTagName("wf:timer");
        }
        if (tmrList.getLength() == 0) {
            throw new BpmnException("boundaryEvent " + id + " 缺少 wf:timer 扩展定义");
        }

        Element tmr = (Element) tmrList.item(0);
        String attachedTo = tmr.getAttribute("attachedTo");
        String durationStr = tmr.getAttribute("duration");
        String interruptingStr = tmr.getAttribute("interrupting");

        if (attachedTo.isBlank()) {
            throw new BpmnException("boundaryEvent " + id + " 的 wf:timer 缺少 attachedTo");
        }
        if (durationStr.isBlank()) {
            throw new BpmnException("boundaryEvent " + id + " 的 wf:timer 缺少 duration");
        }

        long durationMillis = Long.parseLong(durationStr);
        boolean interrupting = interruptingStr.isBlank() || Boolean.parseBoolean(interruptingStr);

        builder.timerBoundary(id, name, attachedTo, durationMillis, interrupting);
    }

    /**
     * 解析 businessRuleTask —— 决策节点。
     * 从 extensionElements 中的 wf:decision 读取 tableId。
     */
    private static void parseBusinessRuleTask(Element el, ProcessBuilder builder) {
        String id = el.getAttribute("id");
        String name = el.getAttribute("name");
        if (name.isBlank()) {
            name = id;
        }

        NodeList decList = el.getElementsByTagNameNS(BpmnExporter.WF_NS, "decision");
        if (decList.getLength() == 0) {
            decList = el.getElementsByTagName("wf:decision");
        }
        if (decList.getLength() == 0) {
            throw new BpmnException("businessRuleTask " + id + " 缺少 wf:decision 扩展定义");
        }

        Element dec = (Element) decList.item(0);
        String tableId = dec.getAttribute("tableId");

        if (tableId.isBlank()) {
            throw new BpmnException("businessRuleTask " + id + " 的 wf:decision 缺少 tableId");
        }

        builder.decision(id, name, tableId);
    }

    private static void parseSequenceFlow(Element el, ProcessBuilder builder) {
        String from = el.getAttribute("sourceRef");
        String to = el.getAttribute("targetRef");
        if (from.isBlank() || to.isBlank()) {
            throw new BpmnException("sequenceFlow 缺少 sourceRef 或 targetRef");
        }

        // 条件表达式
        NodeList condList = el.getElementsByTagNameNS(BpmnExporter.BPMN_NS, "conditionExpression");
        if (condList.getLength() == 0) {
            condList = el.getElementsByTagName("conditionExpression");
        }
        if (condList.getLength() > 0) {
            String condition = condList.item(0).getTextContent();
            builder.connect(from, to, condition);
        } else {
            builder.connect(from, to);
        }
    }

    private static int parseInt(String s, int defaultValue) {
        if (s == null || s.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }
}
