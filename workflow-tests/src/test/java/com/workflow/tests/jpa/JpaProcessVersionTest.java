package com.workflow.tests.jpa;

import com.workflow.definition.ProcessDefinition;
import com.workflow.enums.TaskStatus;
import com.workflow.runtime.ProcessInstance;
import com.workflow.tests.JpaEngineTestBase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JPA 版流程版本管理测试 - 验证复合主键(key, version)下多版本共存
 */
class JpaProcessVersionTest extends JpaEngineTestBase {

    @Test
    void should_find_latest_version_by_key() {
        register(simple("jpa-v-flow")
                .version(1)
                .start("start")
                .userTask("t1", "节点1", any("u1"))
                .end("end")
                .connect("start", "t1")
                .connect("t1", "end")
                .build());
        register(simple("jpa-v-flow")
                .version(2)
                .start("start")
                .userTask("t2", "节点2", any("u2"))
                .end("end")
                .connect("start", "t2")
                .connect("t2", "end")
                .build());

        assertThat(procRepo.findByKey("jpa-v-flow").getVersion()).isEqualTo(2);
        assertThat(procRepo.getVersions("jpa-v-flow")).containsExactly(1, 2);
    }

    @Test
    void should_start_specific_version() {
        register(simple("jpa-v-flow")
                .version(1)
                .start("start")
                .userTask("t1", "节点1", any("u1"))
                .end("end")
                .connect("start", "t1")
                .connect("t1", "end")
                .build());
        register(simple("jpa-v-flow")
                .version(2)
                .start("start")
                .userTask("t2", "节点2", any("u2"))
                .end("end")
                .connect("start", "t2")
                .connect("t2", "end")
                .build());

        String id1 = engine.start("jpa-v-flow", 1, Map.of());
        assertThat(engine.getInstance(id1).getTasks())
                .anyMatch(t -> t.getNodeId().equals("t1") && t.getStatus() == TaskStatus.PENDING);

        String id2 = engine.start("jpa-v-flow", 2, Map.of());
        assertThat(engine.getInstance(id2).getTasks())
                .anyMatch(t -> t.getNodeId().equals("t2") && t.getStatus() == TaskStatus.PENDING);

        String id3 = engine.start("jpa-v-flow", Map.of());
        assertThat(engine.getInstance(id3).getTasks())
                .anyMatch(t -> t.getNodeId().equals("t2") && t.getStatus() == TaskStatus.PENDING);
    }

    @Test
    void should_throw_when_version_not_exist() {
        register(simple("jpa-v-flow")
                .version(1)
                .start("start")
                .userTask("t1", "节点1", any("u1"))
                .end("end")
                .connect("start", "t1")
                .connect("t1", "end")
                .build());

        assertThatThrownBy(() -> engine.start("jpa-v-flow", 99, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("v99");
    }

    @Test
    void should_override_same_version() {
        register(simple("jpa-v-flow")
                .version(1)
                .start("start")
                .userTask("old", "旧节点", any("u1"))
                .end("end")
                .connect("start", "old")
                .connect("old", "end")
                .build());
        register(simple("jpa-v-flow")
                .version(1)
                .start("start")
                .userTask("new", "新节点", any("u2"))
                .end("end")
                .connect("start", "new")
                .connect("new", "end")
                .build());

        ProcessDefinition def = procRepo.findByKey("jpa-v-flow");
        assertThat(def.getVersion()).isEqualTo(1);
        assertThat(def.hasNode("new")).isTrue();
        assertThat(def.hasNode("old")).isFalse();
        assertThat(procRepo.getVersions("jpa-v-flow")).containsExactly(1);
    }

    @Test
    void versioned_instances_independent() {
        register(simple("jpa-v-flow")
                .version(1)
                .start("start")
                .userTask("t1", "节点1", any("u1"))
                .userTask("t2", "节点2", any("u2"))
                .end("end")
                .connect("start", "t1")
                .connect("t1", "t2")
                .connect("t2", "end")
                .build());
        register(simple("jpa-v-flow")
                .version(2)
                .start("start")
                .userTask("only", "单节点", any("u3"))
                .end("end")
                .connect("start", "only")
                .connect("only", "end")
                .build());

        String id1 = engine.start("jpa-v-flow", 1, Map.of());
        String id2 = engine.start("jpa-v-flow", 2, Map.of());

        ProcessInstance inst1 = engine.getInstance(id1);
        String t1 = inst1.getTasks().stream().filter(t -> t.getNodeId().equals("t1")).findFirst().get().getId();
        engine.completeTask(t1, "u1", true);
        assertThat(engine.getInstance(id1).getTasks())
                .anyMatch(t -> t.getNodeId().equals("t2") && t.getStatus() == TaskStatus.PENDING);

        ProcessInstance inst2 = engine.getInstance(id2);
        String only = inst2.getTasks().stream().filter(t -> t.getNodeId().equals("only")).findFirst().get().getId();
        engine.completeTask(only, "u3", true);
        assertThat(engine.getInstance(id2).getTasks())
                .noneMatch(t -> t.getStatus() == TaskStatus.PENDING);
    }
}
