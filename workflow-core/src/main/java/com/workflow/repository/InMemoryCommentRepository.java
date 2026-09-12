package com.workflow.repository;

import com.workflow.runtime.Comment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版意见仓储 —— 线程安全。
 *
 * <p>拷贝语义：{@link Comment} 本身不可变，所以「存入副本」天然成立，
 * 不需要像可变 domain 那样做防御性复制。查询返回的是新的 List，
 * 调用方改它不会影响仓库。
 */
public class InMemoryCommentRepository implements CommentRepository {

    /** 按 id 存，天然幂等覆盖。 */
    private final ConcurrentHashMap<String, Comment> records = new ConcurrentHashMap<>();

    /** 审批链顺序：先按时间，同毫秒按 seq —— 与历史活动表同一套定序规则。 */
    private static final Comparator<Comment> CHRONOLOGICAL =
            Comparator.comparingLong(Comment::getCreateTime)
                    .thenComparingLong(Comment::getSeq);

    @Override
    public void save(Comment comment) {
        records.put(comment.getId(), comment);
    }

    @Override
    public List<Comment> findByInstanceId(String instanceId) {
        List<Comment> result = new ArrayList<>();
        for (Comment c : records.values()) {
            if (c.getInstanceId().equals(instanceId)) {
                result.add(c);
            }
        }
        result.sort(CHRONOLOGICAL);
        return result;
    }

    @Override
    public List<Comment> findByTaskId(String taskId) {
        List<Comment> result = new ArrayList<>();
        for (Comment c : records.values()) {
            if (taskId.equals(c.getTaskId())) {
                result.add(c);
            }
        }
        result.sort(CHRONOLOGICAL);
        return result;
    }

    @Override
    public List<Comment> findByUser(String userId) {
        List<Comment> result = new ArrayList<>();
        for (Comment c : records.values()) {
            if (c.getUserId().equals(userId)) {
                result.add(c);
            }
        }
        result.sort(CHRONOLOGICAL.reversed());
        return result;
    }

    @Override
    public int deleteBefore(long cutoffMillis) {
        int removed = 0;
        for (Comment c : records.values()) {
            if (c.getCreateTime() < cutoffMillis && records.remove(c.getId()) != null) {
                removed++;
            }
        }
        return removed;
    }

    /** 测试与演示用：清空。 */
    public void clear() {
        records.clear();
    }
}
