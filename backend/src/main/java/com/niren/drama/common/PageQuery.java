package com.niren.drama.common;

import lombok.Data;

@Data
public class PageQuery {
    private int page = 1;
    private int size = 10;
    private String keyword;
    private String orderBy;
    private String orderDir = "desc";
}
