package com.workflow.debug;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.WorkflowEngine;
import com.workflow.repository.InMemoryInstanceRepository;
import com.workflow.repository.InMemoryProcessRepository;
import com.workflow.repository.InMemoryTaskRepository;
import com.workflow.runtime.ProcessInstance;

public class SubDebug {
    public static void main(String[] args) {
        InMemoryProcessRepository pr = new InMemoryProcessRepository();
        InMemoryInstanceRepository ir = new InMemoryInstanceRepository();
        InMemoryTaskRepository tr = new InMemoryTaskRepository();
        WorkflowEngine engine = new WorkflowEngine(pr, ir, tr);

        ProcessDefinition sub = ProcessBuilder.create("sub")
                .start("start")
                .userTask("review", "子流程审批", com.workflow.definition.Candidate.ofAny("carol"))
                .end("end")
                .connect("start", "review")
                .connect("review", "end")
                .build();
        pr.save(sub);

        ProcessDefinition main = ProcessBuilder.create("main")
                .start("start")
                .userTask("apply", "提交", com.workflow.definition.Candidate.ofAny("alice"))
                .subProcess("sub1", "子流程", "sub")
                .end("end")
                .connect("start", "apply")
                .connect("apply", "sub1")
                .connect("sub1", "end")
                .build();
        pr.save(main);

        String mainId = engine.start("main", java.util.Map.of());
        ProcessInstance mainInst = engine.getInstance(mainId);
        System.out.println("[DEBUG] main apply task: " + mainInst.getTasks().get(0).getId());

        engine.completeTask(mainInst.getTasks().get(0).getId(), "alice", true);

        mainInst = engine.getInstance(mainId);
        System.out.println("[DEBUG] main after apply: status=" + mainInst.getStatus()
                + " tokens=" + mainInst.getActiveTokens().values());
        System.out.println("[DEBUG] main variables=" + mainInst.getVariables());

        String childId = mainInst.getVariables().entrySet().stream()
                .filter(e -> e.getKey().startsWith("__sub_") && !e.getKey().equals("__sub_depth"))
                .map(e -> e.getValue().toString()).findFirst().orElseThrow();
        System.out.println("[DEBUG] childId=" + childId);

        ProcessInstance child = engine.getInstance(childId);
        System.out.println("[DEBUG] child status=" + child.getStatus()
                + " tasks=" + child.getTasks()
                + " tokens=" + child.getActiveTokens().values());

        engine.completeTask(child.getTasks().get(0).getId(), "carol", true);

        child = engine.getInstance(childId);
        mainInst = engine.getInstance(mainId);
        System.out.println("[DEBUG] AFTER complete: child status=" + child.getStatus()
                + " child tasks=" + child.getTasks()
                + " child tokens=" + child.getActiveTokens().values());
        System.out.println("[DEBUG] AFTER complete: main status=" + mainInst.getStatus()
                + " main tokens=" + mainInst.getActiveTokens().values()
                + " main tasks=" + mainInst.getTasks());
    }
}
