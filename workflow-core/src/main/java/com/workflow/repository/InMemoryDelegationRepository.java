package com.workflow.repository;

import com.workflow.runtime.Delegation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存版委托关系仓储 - 线程安全
 */
public class InMemoryDelegationRepository implements DelegationRepository {

    private final List<Delegation> delegations = new CopyOnWriteArrayList<>();

    @Override
    public void save(Delegation delegation) {
        delegations.add(delegation);
    }

    @Override
    public List<Delegation> findByDelegate(String delegate) {
        List<Delegation> result = new ArrayList<>();
        for (Delegation d : delegations) {
            if (d.getDelegate().equals(delegate)) {
                result.add(d);
            }
        }
        return result;
    }

    @Override
    public List<Delegation> findByDelegator(String delegator) {
        List<Delegation> result = new ArrayList<>();
        for (Delegation d : delegations) {
            if (d.getDelegator().equals(delegator)) {
                result.add(d);
            }
        }
        return result;
    }

    @Override
    public void remove(Delegation delegation) {
        delegations.remove(delegation);
    }

    @Override
    public void clear() {
        delegations.clear();
    }
}
