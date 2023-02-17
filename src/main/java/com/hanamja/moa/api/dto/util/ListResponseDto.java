package com.hanamja.moa.api.dto.util;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class ListResponseDto<T> {
    private List<T> items;
}