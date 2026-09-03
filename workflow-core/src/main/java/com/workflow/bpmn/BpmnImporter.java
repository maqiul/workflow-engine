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
        NodeList list = doc.getElementsByTagNameNS(BpmnExporter.BPMN_NS, "process");
        if (list.getLength() == 0) {
            list = doc.getElementsByTagName("process");
        }
        return list.getLength() > 0 ? (Element) list.item(0) : null;
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
            String tag = el.getLocalName() != null ? el.getLocalName() : el.getTagName();
            switch (tag) {
                case "startEvent" -> builder.start(el.getAttribute("id"));
                case "endEvent" -> builder.end(el.getAttribute("id"));
                case "userTask" -> parseUserTask(el, builder);
                case "exclusiveGateway" -> builder.exclusiveGateway(el.getAttribute("id"));
                case "parallelGateway" -> builder.parallelGateway(el.getAttribute("id"));
                case "callActivity" -> parseCallActivity(el, builder);
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
        Candidate candidate = parseCandidate(el);
        builder.userTask(id, name, candidate);

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

    private static void parseCallActivity(Element el, ProcessBuilder builder) {
        String id = el.getAttribute("id");
        String name = el.getAttribute("name");
        String calledElement = el.getAttribute("calledElement");
        if (calledElement.isBlank()) {
            throw new BpmnException("callActivity 节点 " + id + " 缺少 calledElement 属性");
        }
        builder.subProcess(id, name, calledElement);
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
