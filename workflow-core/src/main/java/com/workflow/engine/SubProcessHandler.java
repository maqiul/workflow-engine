package com.workflow.engine;

import com.workflow.definition.NodeDefinition;
import com.workflow.definition.ProcessDefinition;
import com.workflow.repository.InstanceRepository;
import com.workflow.repository.ProcessRepository;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.Token;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 子流程处理器 - 负责子流程的启动与完成回调
 * 
 * <p>职责：
 * <ul>
 *   <li>启动子流程实例</li>
 *   <li>处理子流程完成后的父流程推进</li>
 *   <li>子流程嵌套深度检查</li>
 *   <li>父子流程变量继承</li>
 * </ul>
 */
public class SubProcessHandler {
    
    private static final Logger log = LoggerFactory.getLogger(SubProcessHandler.class);
    
    /** 子流程嵌套最大深度 - 防循环引用死递归 */
    private static final int MAX_SUB_PROCESS_DEPTH = 10;
    /** 子流程深度变量 key(内部使用) */
    private static final String SUB_DEPTH_VAR = "__sub_depth";
    /** 子流程发起标记变量前缀(内部使用):__sub_<tokenId> = childInstanceId */
    private static final String SUB_MARK_PREFIX = "__sub_";
    
    private final ProcessRepository processRepo;
    private final InstanceRepository instanceRepo;
    
    /** 回调：推进 Token */
    private final TokenAdvancer tokenAdvancer;
    
    /** 回调：获取流程定义 */
    private final ProcessDefinitionResolver defResolver;
    
    public interface ProcessDefinitionResolver {
        ProcessDefinition resolve(ProcessInstance instance);
    }
    
    public interface TokenAdvancer {
        void advanceToken(ProcessInstance instance, ProcessDefinition def, String tokenId);
    }
    
    public SubProcessHandler(
            ProcessRepository processRepo,
            InstanceRepository instanceRepo,
            TokenAdvancer tokenAdvancer,
            ProcessDefinitionResolver defResolver) {
        this.processRepo = processRepo;
        this.instanceRepo = instanceRepo;
        this.tokenAdvancer = tokenAdvancer;
        this.defResolver = defResolver;
    }
    
    /**
     * 在父流程的 SUB_PROCESS 节点发起子流程实例
     * Token 停留在 SUB_PROCESS 节点,子流程完成后由 onSubProcessCompleted 推进
     */
    public void startSubProcess(ProcessInstance parent, ProcessDefinition parentDef,
                                Token token, NodeDefinition nodeDef) {
        String subKey = nodeDef.getSubProcessKey();
        ProcessDefinition subDef = processRepo.findByKey(subKey);

        // 深度检查 - 沿 __sub_depth 变量,防循环引用
        int depth = 1;
        Object d = parent.getVariable(SUB_DEPTH_VAR);
        if (d instanceof Number n) {
            depth = n.intValue() + 1;
        }
        if (depth > MAX_SUB_PROCESS_DEPTH) {
            throw new IllegalStateException(
                    "子流程嵌套超过最大深度 " + MAX_SUB_PROCESS_DEPTH + ",疑似循环引用: " + subKey);
        }

        // 创建子实例(携带父上下文)
        ProcessInstance child = new ProcessInstance(subDef.getKey(), subDef.getVersion(),
                parent.getId(), token.getId(), nodeDef.getId());
        // 子实例继承父树的根 —— 引擎按「流程树根」加锁，父子共享同一把锁，
        // 从而杜绝 startSubProcess(父→子) 与 onSubProcessCompleted(子→父) 构成 ABBA 死锁
        child.assignRootInstanceId(parent.getRootInstanceId());
        // 继承父流程变量(浅拷贝)
        parent.getVariables().forEach(child::setVariable);
        child.setVariable(SUB_DEPTH_VAR, depth);

        Token childToken = new Token(child.getId(), subDef.getStartNodeId());
        child.addToken(childToken);
        instanceRepo.save(child);

        // 父实例打标记:该 token 的子流程已发起,避免重复发起
        parent.setVariable(SUB_MARK_PREFIX + token.getId(), child.getId());
        instanceRepo.save(parent);

        log.info("[SubProcessHandler] 发起子流程 parent={} node={} subKey={} child={} depth={}",
                parent.getId(), nodeDef.getId(), subKey, child.getId(), depth);
        tokenAdvancer.advanceToken(child, subDef, childToken.getId());
    }

    /**
     * 子流程实例完成 -> 回调父流程,推进停在 SUB_PROCESS 节点上的 Token
     */
    public void onSubProcessCompleted(ProcessInstance child) {
        String parentId = child.getParentInstanceId();
        String parentTokenId = child.getParentTokenId();
        if (parentId == null || parentTokenId == null) {
            return;
        }
        ProcessInstance parent = instanceRepo.findById(parentId);
        ProcessDefinition parentDef = defResolver.resolve(parent);
        Token token = parent.getActiveTokens().get(parentTokenId);
        if (token == null) {
            // 父 Token 已不存在(父流程可能被终止)
            log.warn("[SubProcessHandler] 子流程完成但父 Token 已不存在 parent={} token={}", parentId, parentTokenId);
            return;
        }
        log.info("[SubProcessHandler] 子流程 {} 完成,推进父流程 token={} node={}",
                child.getId(), parentTokenId, token.getCurrentNodeId());
        tokenAdvancer.advanceToken(parent, parentDef, parentTokenId);
    }
}
