package com.workflow.persistence.jpa.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * wf_process_def 复合主键 - (key, version)
 */
public class WfProcessDefPK implements Serializable {

    private String key;
    private int version;

    public WfProcessDefPK() {}

    public WfProcessDefPK(String key, int version) {
        this.key = key;
        this.version = version;
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WfProcessDefPK that)) return false;
        return version == that.version && Objects.equals(key, that.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, version);
    }
}
