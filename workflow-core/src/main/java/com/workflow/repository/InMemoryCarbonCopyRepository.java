package com.workflow.repository;

import com.workflow.runtime.CarbonCopy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 内存版抄送仓储 - 线程安全
 */
public class InMemoryCarbonCopyRepository implements CarbonCopyRepository {

    private final List<CarbonCopy> records = new CopyOnWriteArrayList<>();

    @Override
    public void save(CarbonCopy cc) {
        records.add(cc);
    }

    @Override
    public List<CarbonCopy> findByRecipient(String recipient) {
        return records.stream()
                .filter(cc -> cc.getRecipient().equals(recipient))
                .sorted((a, b) -> Long.compare(b.getCreateTime(), a.getCreateTime()))
                .collect(Collectors.toList());
    }

    @Override
    public List<CarbonCopy> findUnreadByRecipient(String recipient) {
        return records.stream()
                .filter(cc -> cc.getRecipient().equals(recipient) && !cc.isRead())
                .sorted((a, b) -> Long.compare(b.getCreateTime(), a.getCreateTime()))
                .collect(Collectors.toList());
    }

    @Override
    public List<CarbonCopy> findByInstanceId(String instanceId) {
        return records.stream()
                .filter(cc -> cc.getInstanceId().equals(instanceId))
                .sorted((a, b) -> Long.compare(b.getCreateTime(), a.getCreateTime()))
                .collect(Collectors.toList());
    }

    @Override
    public void markRead(String ccId) {
        for (CarbonCopy cc : records) {
            if (cc.getId().equals(ccId)) {
                cc.markRead();
                return;
            }
        }
    }

    @Override
    public void clear() {
        records.clear();
    }
}
