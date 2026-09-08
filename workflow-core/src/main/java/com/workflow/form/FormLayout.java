package com.workflow.form;

import java.util.List;

/**
 * 表单布局定义
 * 
 * <p>描述表单字段的布局方式，支持网格布局、分栏布局等。
 */
public class FormLayout {
    
    /** 布局类型 */
    private final LayoutType type;
    
    /** 列数（用于网格布局） */
    private final int columns;
    
    /** 布局区域列表 */
    private final List<LayoutSection> sections;
    
    public FormLayout(LayoutType type, int columns, List<LayoutSection> sections) {
        this.type = type;
        this.columns = columns;
        this.sections = sections;
    }
    
    public LayoutType getType() {
        return type;
    }
    
    public int getColumns() {
        return columns;
    }
    
    public List<LayoutSection> getSections() {
        return sections;
    }
    
    /**
     * 布局类型枚举
     */
    public enum LayoutType {
        SINGLE_COLUMN,    // 单列布局
        TWO_COLUMNS,      // 双列布局
        THREE_COLUMNS,    // 三列布局
        GRID,             // 网格布局
        TABS,             // 标签页布局
        STEPS             // 步骤布局
    }
    
    /**
     * 布局区域
     */
    public static class LayoutSection {
        
        /** 区域标题 */
        private final String title;
        
        /** 区域包含的字段 ID 列表 */
        private final List<String> fieldIds;
        
        /** 区域列跨度 */
        private final int colSpan;
        
        public LayoutSection(String title, List<String> fieldIds, int colSpan) {
            this.title = title;
            this.fieldIds = fieldIds;
            this.colSpan = colSpan;
        }
        
        public String getTitle() {
            return title;
        }
        
        public List<String> getFieldIds() {
            return fieldIds;
        }
        
        public int getColSpan() {
            return colSpan;
        }
    }
}
