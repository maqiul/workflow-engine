package com.workflow.tests.engine;

import com.workflow.definition.ProcessDefinition;
import com.workflow.enums.TaskStatus;
import com.workflow.runtime.ProcessInstance;
import com.workflow.tests.EngineTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 流程版本管理测试
 *
 * 覆盖:
 *  - 同一 key 注册多个版本,findByKey 返回最新版
 *  - 按指定版本发起流程(不同版本走不同拓扑)
 *  - 默认发起走最新版本
 *  - 不存在的版本发起 -> 抛异常
 *  - getVersions 返回全部版本
 */
class ProcessVersionTest extends EngineTestBase {

    @BeforeEach
    void init() { setUp(); }

    @Test
    void should_find_latest_version_by_key() {
        register(simple("v-flow")
                .version(1)
                .start("start")
                .userTask("t1", "节点1", any("u1"))
                .end("end")
                .connect("start", "t1")
                .connect("t1", "end")
                .build());
        register(simple("v-flow")
                .version(2)
                .start("start")
                .userTask("t2", "节点2", any("u2"))
                .end("end")
                .connect("start", "t2")
                .connect("t2", "end")
                .build());

        assertThat(procRepo.findByKey("v-flow").getVersion()).isEqualTo(2);
        assertThat(procRepo.getVersions("v-flow")).containsExactly(1, 2);
    }

    @Test
    void should_start_specific_version() {
        register(simple("v-flow")
                .version(1)
                .start("start")
                .userTask("t1", "节点1", any("u1"))
                .end("end")
                .connect("start", "t1")
                .connect("t1", "end")
                .build());
        register(simple("v-flow")
                .version(2)
                .start("start")
                .userTask("t2", "节点2", any("u2"))
                .end("end")
                .connect("start", "t2")
                .connect("t2", "end")
                .build());

        // 指定 v1 发起 -> 落在 t1
        String id1 = engine.start("v-flow", 1, Map.of());
        assertThat(engine.getInstance(id1).getTasks())
                .anyMatch(t -> t.getNodeId().equals("t1") && t.getStatus() == TaskStatus.PENDING);

        // 指定 v2 发起 -> 落在 t2
        String id2 = engine.start("v-flow", 2, Map.of());
        assertThat(engine.getInstance(id2).getTasks())
                .anyMatch(t -> t.getNodeId().equals("t2") && t.getStatus() == TaskStatus.PENDING);

        // 不指定 -> 默认最新 v2
        String id3 = engine.start("v-flow", Map.of());
        assertThat(engine.getInstance(id3).getTasks())
                .anyMatch(t -> t.getNodeId().equals("t2") && t.getStatus() == TaskStatus.PENDING);
    }

    @Test
    void should_throw_when_version_not_exist() {
        register(simple("v-flow")
                .version(1)
                .start("start")
                .userTask("t1", "节点1", any("u1"))
                .end("end")
                .connect("start", "t1")
                .connect("t1", "end")
                .build());

        assertThatThrownBy(() -> engine.start("v-flow", 99, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("v99");
    }

    @Test
    void should_override_same_version() {
        register(simple("v-flow")
                .version(1)
                .start("start")
                .userTask("old", "旧节点", any("u1"))
                .end("end")
                .connect("start", "old")
                .connect("old", "end")
                .build());
        // 同版本覆盖
        register(simple("v-flow")
                .version(1)
                .start("start")
                .userTask("new", "新节点", any("u2"))
                .end("end")
                .connect("start", "new")
                .connect("new", "end")
                .build());

        ProcessDefinition def = procRepo.findByKey("v-flow");
        assertThat(def.getVersion()).isEqualTo(1);
        assertThat(def.hasNode("new")).isTrue();
        assertThat(def.hasNode("old")).isFalse();
        assertThat(procRepo.getVersions("v-flow")).containsExactly(1);
    }

    @Test
    void versioned_instances_independent() {
        // v1: 2 节点; v2: 1 节点
        register(simple("v-flow")
                .version(1)
                .start("start")
                .userTask("t1", "节点1", any("u1"))
                .userTask("t2", "节点2", any("u2"))
                .end("end")
                .connect("start", "t1")
                .connect("t1", "t2")
                .connect("t2", "end")
                .build());
        register(simple("v-flow")
                .version(2)
                .start("start")
                .userTask("only", "单节点", any("u3"))
                .end("end")
                .connect("start", "only")
                .connect("only", "end")
                .build());

        String id1 = engine.start("v-flow", 1, Map.of());
        String id2 = engine.start("v-flow", 2, Map.of());

        // v1 实例走完 t1 -> t2
        ProcessInstance inst1 = engine.getInstance(id1);
        String t1 = inst1.getTasks().stream().filter(t -> t.getNodeId().equals("t1")).findFirst().get().getId();
        engine.completeTask(t1, "u1", true);
        assertThat(engine.getInstance(id1).getTasks())
                .anyMatch(t -> t.getNodeId().equals("t2") && t.getStatus() == TaskStatus.PENDING);

        // v2 实例走完 only -> end
        ProcessInstance inst2 = engine.getInstance(id2);
        String only = inst2.getTasks().stream().filter(t -> t.getNodeId().equals("only")).findFirst().get().getId();
        engine.completeTask(only, "u3", true);
        assertThat(engine.getInstance(id2).getTasks())
                .noneMatch(t -> t.getStatus() == TaskStatus.PENDING);
    }
}
