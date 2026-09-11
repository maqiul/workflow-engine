package com.workflow.definition;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.workflow.enums.CandidateStrategy;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 候选人 JSON 编解码 —— 仓储层 {@code candidate_json} 列的唯一读写出口。
 *
 * <p><b>为什么不直接 {@code JSON.parseObject(json, Candidate.class)}</b>：
 * {@link Candidate} 是不可变值对象（final 类 + final 字段 + 无 setter），
 * 让 JSON 框架用反射往 final 字段里灌值，等于把"类长什么样"和"磁盘上存过什么"
 * 隐式绑死 —— 加一个字段，老数据读回来就是 {@code null}，而编译期毫无提示。
 * 本项目此前正是这个写法，且散在 JPA / MyBatis 两套仓储共 10 处。
 *
 * <p>这里改为<b>显式映射</b>：
 * <ul>
 *   <li>写入只写有意义的键（无候选组时不写 {@code groupIds}），格式稳定可控</li>
 *   <li>读取对每个字段做类型校验与默认值兜底，<b>老数据（没有 groupIds）读回来得到空集</b></li>
 *   <li>数据损坏时抛异常而不是返回 {@code null} —— 让问题在读取处暴露，而非在下游 NPE</li>
 * </ul>
 */
public final class CandidateCodec {

    private static final String FIELD_STRATEGY = "strategy";
    private static final String FIELD_USER_IDS = "userIds";
    private static final String FIELD_GROUP_IDS = "groupIds";

    private CandidateCodec() {
    }

    /** 编码为 JSON；{@code candidate} 为 null 时返回 null。 */
    public static String toJson(Candidate candidate) {
        if (candidate == null) {
            return null;
        }
        JSONObject obj = new JSONObject();
        obj.put(FIELD_STRATEGY, candidate.getStrategy().name());
        obj.put(FIELD_USER_IDS, candidate.getUserIds());
        // 无组时不写该键：老版本读取端不认识它，少一个键少一份兼容负担
        if (candidate.hasGroups()) {
            obj.put(FIELD_GROUP_IDS, candidate.getGroupIds());
        }
        return obj.toJSONString();
    }

    /**
     * 解码 JSON。
     *
     * @param json 磁盘上读到的 {@code candidate_json}；空白视为 null
     * @return 重建的候选人；{@code json} 为空白时返回 null
     * @throws IllegalStateException json 非空但既无用户也无候选组（数据损坏）
     */
    public static Candidate fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        JSONObject obj = JSON.parseObject(json);
        if (obj == null) {
            return null;
        }

        CandidateStrategy strategy = parseStrategy(obj.getString(FIELD_STRATEGY));
        Set<String> userIds = toStringSet(obj.get(FIELD_USER_IDS));
        Set<String> groupIds = toStringSet(obj.get(FIELD_GROUP_IDS));

        if (userIds.isEmpty() && groupIds.isEmpty()) {
            throw new IllegalStateException(
                    "candidate_json 数据损坏：既无候选用户也无候选组 —— " + json);
        }
        return new Candidate(userIds, groupIds, strategy);
    }

    private static CandidateStrategy parseStrategy(String name) {
        if (name == null || name.isBlank()) {
            // 老数据理论上一定有 strategy；缺失时按最宽松的或签处理，避免整条流程读不出来
            return CandidateStrategy.ANY;
        }
        try {
            return CandidateStrategy.valueOf(name);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("candidate_json 数据损坏：未知的候选策略 " + name, ex);
        }
    }

    private static Set<String> toStringSet(Object raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null && !item.toString().isBlank()) {
                    out.add(item.toString());
                }
            }
        }
        return out;
    }
}
