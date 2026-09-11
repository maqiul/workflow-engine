package com.workflow.bpmn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * BPMN 导入诊断 —— 记录「导入成功但语义有损」的事实。
 *
 * <p><b>为什么需要它</b>：导入器最危险的失败不是抛异常，而是<b>静默降级</b>——
 * 定义导进来了、流程也能跑，但某个节点少了会签、丢了候选池、忽略了超时，
 * 直到生产上有人绕过审批才发现。抛异常至少是响的，静默降级是哑的。
 *
 * <p>因此导入器的约定是：能映射的映射，不能映射的一律记在这里，
 * 由调用方决定接受、告警还是拒绝该定义。{@code importFrom(xml)} 单参重载
 * 会把它们写进日志；需要程序化处理的用双参重载拿这个对象。
 */
public final class BpmnImportDiagnostics {

    private final List<String> warnings = new ArrayList<>();

    /** 记录一条语义降级（消息应当能让读者直接定位到节点并知道后果）。 */
    public void warn(String message) {
        warnings.add(message);
    }

    /** 全部警告，按发生顺序。 */
    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }

    public int size() {
        return warnings.size();
    }

    @Override
    public String toString() {
        return warnings.isEmpty() ? "无导入警告" : String.join("; ", warnings);
    }
}
