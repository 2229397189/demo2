package com.agi.assistant.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private long total;

    private List<T> records;

    private int page;

    private int size;

    public PageResult() {
    }

    public PageResult(long total, List<T> records, int page, int size) {
        this.total = total;
        this.records = records;
        this.page = page;
        this.size = size;
    }

    public static <T> PageResult<T> of(long total, List<T> records, int page, int size) {
        return new PageResult<>(total, records, page, size);
    }

    /**
     * 获取总页数
     */
    public long getPages() {
        if (size <= 0) {
            return 0;
        }
        return (total + size - 1) / size;
    }
}
