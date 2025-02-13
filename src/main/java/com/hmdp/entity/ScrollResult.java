package com.hmdp.entity;

import lombok.Data;

import java.util.List;

@Data
public class ScrollResult {
    private List<?> data;
    private Integer offset;
    private Long minTime;
}
