package com.workflow.bpmn;

import com.alibaba.fastjson2.JSON;
import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.definition.VariableDefinition;
import com.workflow.enums.CandidateStrategy;
import com.workflow.enums.TimeoutPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BPMN 2.0 XML → 流程定义。
 *
 * <p><b>必须走 {@link ProcessBuilder#build()} 的校验</b>，不能直接构造 ProcessDefinition ——
 * 否则就是我们在 REST 层刚拒绝过的"第二条无校验入口"。
 *
 * <p>支持的 BPMN 元素：
 * <ul>
 *   <li>startEvent / endEvent → START / END</li>
 *   <li>userTask → USER_TASK（候选人见下）/ MULTI_INSTANCE（带 flowable:collection 时）</li>
 *   <li>serviceTask → SERVICE_TASK（读 flowable:delegateExpression 或 wf:delegate）</li>
 *   <li>exclusiveGateway → EXCLUSIVE_GATEWAY</li>
 *   <li>parallelGateway → PARALLEL_GATEWAY</li>
 *   <li>callActivity → SUB_PROCESS（读 calledElement 属性）</li>
 *   <li>intermediateCatchEvent → MESSAGE_EVENT / SIGNAL_EVENT（从 extensionElements 读 wf:message / wf:signal）</li>
 *   <li>boundaryEvent → TIMER_BOUNDARY（从 extensionElements 读 wf:timer）</li>
 *   <li>businessRuleTask → DECISION（从 extensionElements 读 wf:decision）</li>
 *   <li>sequenceFlow → Transition（读 conditionExpression）</li>
 * </ul>
 *
 * <p><b>userTask 的审批人来源（按优先级）</b>：
 * <ol>
 *   <li>{@code flowable:assignee="${var}"} → 动态办理人（运行时从变量取）</li>
 *   <li>{@code flowable:assignee="u1"} → 静态单人</li>
 *   <li>{@code wf:candidate} 扩展 → 本项目原生格式（含 ANY/ALL 策略）</li>
 *   <li>{@code flowable:candidateUsers="u1,u2"} → 候选池（ANY）</li>
 *   <li>{@code flowable:candidateGroups="g1"} → 见下方「组织架构」说明</li>
 *   <li>{@code wf:cardinality} 占位 → 手写 XML 的兜底，生成 u1..uN 占位用户</li>
 * </ol>
 *
 * <p><b>组织架构不在引擎职责内，但组是一等公民</b>：Flowable 把候选组交给
 * ACT_ID_ 表 + IdentityService 解析，本项目同理 —— {@code candidateGroups} 的组名存进候选人的
 * {@code groupIds}（<b>与 userIds 分开</b>，不再混为一谈），运行时由调用方注入的
 * {@code GroupResolver} 在任务创建时展开成具体用户并快照进候选人。
 * 未注入解析器时组保持未展开，任务对任何人都不可办理并<b>告警</b>（不会静默丢弃）。
 *
 * <p><b>多实例会签</b>：{@code multiInstanceLoopCharacteristics} 带 {@code flowable:collection="${var}"}
 * 时映射为 MULTI_INSTANCE 节点（运行时按集合里每人各建一个独立任务）。
 * {@code flowable:elementVariable} 无需映射——引擎以人为单位建任务，天然具备该语义。
 * 判定依据是 {@code flowable:collection} 的存在性：本项目导出时只写 {@code wf:cardinality}，
 * 因此两种格式不会互相误判。
 *
 * <p>不支持的：
 * <ul>
 *   <li>DYNAMIC_PARALLEL —— BPMN 标准里没有对应物，导出时已拒绝，导入时也不支持</li>
 *   <li>sendTask / receiveTask / scriptTask / manualTask —— 引擎没有这些节点类型</li>
 * </ul>
 *
 * <p><b>不静默丢弃</b>：无法映射的属性与元素一律记入 {@link BpmnImportDiagnostics}。
 * 单参 {@link #importFrom(String)} 写日志，双参重载把诊断交给调用方。
 */
public final class BpmnImporter {

    private static final Logger log = LoggerFactory.getLogger(BpmnImporter.class);

    /** flowable: 属性前缀——用限定名识别，命名空间感知与非感知两种解析模式都适用。 */
    private static final String FLOWABLE_PREFIX = "flowable:";

    /** 已映射到引擎语义的 flowable: 属性，之外的会记入诊断而非静默丢弃。 */
    private static final Set<String> HANDLED_FLOWABLE_ATTRS = Set.of(
            "assignee", "candidateUsers", "candidateGroups",
            "delegateExpression", "collection", "elementVariable");

    /** process 级已知且无需处理、不产生诊断的元素。 */
    private static final Set<String> SILENT_PROCESS_CHILDREN = Set.of(
            "documentation", "extensionElements");

    /** 完成条件里的「完成数 >= N」提取器，N == 1 即或签。 */
    private static final Pattern COMPLETION_THRESHOLD =
            Pattern.compile("nrOfCompletedInstances\\s*>=\\s*(\\d+)");

    private BpmnImporter() { }

    /** 导入，语义降级只写日志（保持既有调用方不变）。 */
    public static ProcessDefinition importFrom(String xml) {
        BpmnImportDiagnostics diag = new BpmnImportDiagnostics();
        ProcessDefinition def = importFrom(xml, diag);
        if (diag.hasWarnings()) {
            log.warn("[BpmnImporter] 流程 [{}] 导入成功，但有 {} 条语义降级：{}",
                    def.getKey(), diag.size(), diag);
        }
        return def;
    }

    /**
     * 导入并收集诊断 —— 导入器只做「能映射就映射」，不静默丢弃：
     * 无法映射的属性/元素全部记入 {@code diag}，调用方据此决定接受、告警还是拒绝该定义。
     */
    public static ProcessDefinition importFrom(String xml, BpmnImportDiagnostics diag) {
        try {
            Document doc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            doc.getDocumentElement().normalize();

            Element process = findProcess(doc);
            if (process == null) {
                throw new BpmnException("BPMN 文件中未找到 <process> 元素");
            }
            return parseProcess(process, diag);
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

    private static ProcessDefinition parseProcess(Element process, BpmnImportDiagnostics diag) {
        String key = process.getAttribute("id");
        String name = process.getAttribute("name");
        // 版本号：先尝试命名空间属性，再尝试带前缀的属性名
        String versionStr = process.getAttributeNS(BpmnExporter.WF_NS, "version");
        if (versionStr.isBlank()) {
            versionStr = process.getAttribute("wf:version");
        }
        int version = parseInt(versionStr, 1);

        ProcessBuilder builder = ProcessBuilder.create(key, name).version(version);
        scanUnsupportedAttributes(process, "process [" + key + "]", diag);

        // 先读变量定义（挂在 process 的 extensionElements 下）
        parseVariableDefinitions(process, builder);

        // 遍历节点
        NodeList children = process.getChildNodes();
        // 被忽略节点的 id —— 若随后 build() 校验失败，它们往往是真正的原因
        Set<String> skippedNodeIds = new LinkedHashSet<>();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element el)) {
                continue;
            }
            // 处理带前缀的标签名（如 bpmn:startEvent → startEvent）
            String tag = localTag(el);
            scanUnsupportedAttributes(el, "<" + tag + " id=" + el.getAttribute("id") + ">", diag);
            switch (tag) {
                case "startEvent" -> builder.start(el.getAttribute("id"));
                case "endEvent" -> builder.end(el.getAttribute("id"));
                case "userTask" -> parseUserTask(el, builder, diag);
                case "serviceTask" -> parseServiceTask(el, builder);
                case "exclusiveGateway" -> builder.exclusiveGateway(el.getAttribute("id"));
                case "parallelGateway" -> builder.parallelGateway(el.getAttribute("id"));
                case "callActivity" -> parseCallActivity(el, builder);
                case "intermediateCatchEvent" -> parseIntermediateCatchEvent(el, builder);
                case "boundaryEvent" -> parseBoundaryEvent(el, builder);
                case "businessRuleTask" -> parseBusinessRuleTask(el, builder);
                case "sequenceFlow" -> parseSequenceFlow(el, builder);
                // multiInstanceLoopCharacteristics 在 userTask 内部处理，这里忽略
                default -> {
                    if (!SILENT_PROCESS_CHILDREN.contains(tag)) {
                        String skippedId = el.getAttribute("id");
                        if (!skippedId.isBlank()) {
                            skippedNodeIds.add(skippedId);
                        }
                        diag.warn("不支持的 BPMN 元素 <" + tag + "> 已忽略（引擎无对应节点类型）"
                                + (skippedId.isBlank() ? "" : "，id=" + skippedId));
                    }
                }
            }
        }

        try {
            return builder.build();
        } catch (IllegalStateException ex) {
            if (skippedNodeIds.isEmpty()) {
                throw ex;
            }
            // 被忽略的节点常常正是校验失败的根因（连线指向了不存在的节点）。
            // 不点出来，调用方只会看到一句莫名其妙的「转移终点不存在」。
            throw new IllegalStateException(ex.getMessage()
                    + "；另外，以下节点因类型不受支持已被忽略 → " + skippedNodeIds
                    + "，若它们参与了连线，请先移除或改造相关连线", ex);
        }
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

    private static void parseUserTask(Element el, ProcessBuilder builder, BpmnImportDiagnostics diag) {
        String id = el.getAttribute("id");
        String name = el.getAttribute("name");

        // 先判多实例：Flowable 的真会签写 flowable:collection，本项目导出只写 wf:cardinality，
        // 两种格式不会互相误判（见类 javadoc）。
        Element mi = firstChildElement(el, "multiInstanceLoopCharacteristics");
        if (mi != null && !readFlowableAttr(mi, "collection").isBlank()) {
            parseMultiInstanceUserTask(el, mi, id, name, builder, diag);
            return;
        }

        parseApprover(el, id, name, builder, diag);
        parseTimeout(el, builder);
    }

    /** 多实例会签/或签：flowable:collection="${var}" → MULTI_INSTANCE 节点。 */
    private static void parseMultiInstanceUserTask(Element el, Element mi, String id, String name,
                                                   ProcessBuilder builder, BpmnImportDiagnostics diag) {
        scanUnsupportedAttributes(mi, "userTask [" + id + "] 的 multiInstanceLoopCharacteristics", diag);
        String collectionVar = unwrapVar(readFlowableAttr(mi, "collection"));
        CandidateStrategy strategy = readCompletionStrategy(mi);

        if ("true".equalsIgnoreCase(mi.getAttribute("isSequential"))) {
            diag.warn("userTask [" + id + "] 是顺序多实例（isSequential=true），"
                    + "引擎的 MULTI_INSTANCE 一次展开全部任务（并行语义），顺序性未保留");
        }
        if (hasTimeoutExtension(el)) {
            diag.warn("userTask [" + id + "] 为多实例节点，引擎不支持其超时配置，wf:timeout 已忽略");
        }

        builder.multiInstance(id, name, collectionVar, strategy);
    }

    /**
     * 单实例 userTask 的审批人解析（优先级见类 javadoc）。
     * 所有来源都不存在时抛错而非兜一个默认人 —— 没有办理人的用户任务在引擎里无从推进。
     */
    private static void parseApprover(Element el, String id, String name,
                                      ProcessBuilder builder, BpmnImportDiagnostics diag) {
        // 1) flowable:assignee —— 动态 ${var} 或静态用户
        String assignee = readFlowableAttr(el, "assignee");
        if (!assignee.isBlank()) {
            if (!readFlowableAttr(el, "candidateUsers").isBlank()
                    || !readFlowableAttr(el, "candidateGroups").isBlank()) {
                diag.warn("userTask [" + id + "] 同时声明了 flowable:assignee 与 candidateUsers/candidateGroups，"
                        + "按 Flowable 语义 assignee 优先，候选池已忽略");
            }
            if (isVariableExpression(assignee)) {
                builder.userTask(id, name, unwrapVar(assignee));
            } else {
                builder.userTask(id, name, Candidate.ofAny(assignee));
            }
            return;
        }

        // 2) wf:candidate —— 本项目原生格式
        Candidate wfCandidate = parseWfCandidate(el);
        if (wfCandidate != null) {
            builder.userTask(id, name, wfCandidate);
            return;
        }

        // 3) flowable:candidateUsers / candidateGroups —— 两者语义不同，必须分开存：
        //    candidateUsers 是"哪些人能办"，candidateGroups 是"哪些组能办"。
        //    此前把组名并进 userIds，模型层就再也分不清人和组，
        //    查询过滤、会签人数、导出往返全跟着错。
        Set<String> users = splitCsv(readFlowableAttr(el, "candidateUsers"));
        Set<String> groups = splitCsv(readFlowableAttr(el, "candidateGroups"));
        if (!users.isEmpty() || !groups.isEmpty()) {
            if (!groups.isEmpty()) {
                diag.warn("userTask [" + id + "] 使用 flowable:candidateGroups=" + groups
                        + "；组名存进候选组的 groupIds，运行时需注入 GroupResolver 展开为具体用户，"
                        + "否则该任务对任何人都不可办理");
            }
            builder.userTask(id, name, new Candidate(users, groups, CandidateStrategy.ANY));
            return;
        }

        // 4) wf:cardinality 占位兜底 —— 项目自身导出带 wf:candidate，故仅手写 XML 会走到这里
        Integer cardinality = readWfCardinality(el);
        if (cardinality != null) {
            Set<String> placeholders = new LinkedHashSet<>();
            for (int i = 1; i <= cardinality; i++) {
                placeholders.add("u" + i);
            }
            diag.warn("userTask [" + id + "] 只有 wf:cardinality=" + cardinality
                    + " 而无具体审批人，已生成占位用户 " + placeholders + "，上线前必须替换为真实用户");
            builder.userTask(id, name, new Candidate(placeholders, CandidateStrategy.ALL));
            return;
        }

        throw new BpmnException("userTask 节点 " + id + " 没有任何可用的审批人定义。支持的形式："
                + "flowable:assignee（静态用户名或 ${变量}）、flowable:candidateUsers、"
                + "flowable:candidateGroups、wf:candidate 扩展。"
                + "若该节点在 Flowable 中依赖表单或监听器动态指定办理人，需先改造后再导入");
    }

    /** 读 wf:candidate 扩展（本项目原生候选格式）。 */
    private static Candidate parseWfCandidate(Element userTask) {
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
                CandidateStrategy cs = CandidateStrategy.valueOf(cand.getAttribute("strategy"));
                Set<String> userIds = splitCsv(cand.getTextContent());
                Set<String> groups = splitCsv(cand.getAttribute("groups"));
                if (userIds.isEmpty() && groups.isEmpty()) {
                    throw new BpmnException("userTask 节点 " + userTask.getAttribute("id")
                            + " 的 wf:candidate 既未列出用户、也未列出候选组");
                }
                return new Candidate(userIds, groups, cs);
            }
        }
        return null;
    }

    /** wf:cardinality（本项目导出 USER_TASK 时附带的标准多实例标记）→ 人数。 */
    private static Integer readWfCardinality(Element userTask) {
        Element mi = firstChildElement(userTask, "multiInstanceLoopCharacteristics");
        if (mi == null) {
            return null;
        }
        String card = mi.getAttributeNS(BpmnExporter.WF_NS, "cardinality");
        if (card.isBlank()) {
            card = mi.getAttribute("wf:cardinality");
        }
        if (card.isBlank()) {
            return null;
        }
        int n = parseInt(card, 0);
        return n > 0 ? n : null;
    }

    /** 或签判定：完成条件为「完成数 >= 1」即任一完成即通过，其余（含缺省）按会签处理。 */
    private static CandidateStrategy readCompletionStrategy(Element mi) {
        Element cc = firstChildElement(mi, "completionCondition");
        if (cc == null) {
            return CandidateStrategy.ALL;
        }
        String expr = cc.getTextContent();
        if (expr == null || expr.isBlank()) {
            return CandidateStrategy.ALL;
        }
        Matcher m = COMPLETION_THRESHOLD.matcher(expr);
        return m.find() && "1".equals(m.group(1)) ? CandidateStrategy.ANY : CandidateStrategy.ALL;
    }

    /** 该节点是否带本项目自有的 wf:timeout 扩展。 */
    private static boolean hasTimeoutExtension(Element el) {
        return el.getElementsByTagNameNS(BpmnExporter.WF_NS, "timeout").getLength() > 0
                || el.getElementsByTagName("wf:timeout").getLength() > 0;
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

    /** 元素标签的本地名，剥离 bpmn: 之类的前缀；命名空间感知与非感知解析都适用。 */
    private static String localTag(Element el) {
        String tag = el.getTagName();
        int idx = tag.indexOf(':');
        return idx >= 0 ? tag.substring(idx + 1) : tag;
    }

    /** 只找直接子元素（不递归），按本地名匹配。 */
    private static Element firstChildElement(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child && localName.equals(localTag(child))) {
                return child;
            }
        }
        return null;
    }

    /** 读 flowable: 属性；先走命名空间，再退回限定名（兼容未开启 namespace-aware 的解析）。 */
    private static String readFlowableAttr(Element el, String localName) {
        String value = el.getAttributeNS(BpmnExporter.FLOWABLE_NS, localName);
        if (value == null || value.isBlank()) {
            value = el.getAttribute(FLOWABLE_PREFIX + localName);
        }
        return value == null ? "" : value.trim();
    }

    /** 逗号分隔 → 有序去重集合，忽略空白项。 */
    private static Set<String> splitCsv(String csv) {
        Set<String> result = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) {
            return result;
        }
        for (String item : csv.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static boolean isVariableExpression(String value) {
        return value.startsWith("${") && value.endsWith("}") && value.length() > 3;
    }

    /** {@code ${var}} → {@code var}；非表达式原样返回。 */
    private static String unwrapVar(String value) {
        return isVariableExpression(value) ? value.substring(2, value.length() - 1).trim() : value;
    }

    /**
     * 扫描元素上未映射的 flowable: 属性并记入诊断。
     *
     * <p>这是「不静默丢弃」的落点：Flowable 定义里 formKey / taskListener / priority
     * 之类的属性引擎不认，早先会被无声吞掉，导入"成功"但行为不对。
     */
    private static void scanUnsupportedAttributes(Element el, String where, BpmnImportDiagnostics diag) {
        NamedNodeMap attrs = el.getAttributes();
        if (attrs == null) {
            return;
        }
        for (int i = 0; i < attrs.getLength(); i++) {
            String nodeName = attrs.item(i).getNodeName();
            if (!nodeName.startsWith(FLOWABLE_PREFIX)) {
                continue;
            }
            String local = nodeName.substring(FLOWABLE_PREFIX.length());
            if (!HANDLED_FLOWABLE_ATTRS.contains(local)) {
                diag.warn(where + " 上的 flowable:" + local + " 不受支持，已忽略");
            }
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
