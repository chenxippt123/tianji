package com.tianji.promotion.service;

import com.tianji.api.dto.promotion.CouponDiscountDTO;
import com.tianji.api.dto.promotion.OrderCourseDTO;

import java.util.List;

public interface IDiscountService {

    List<CouponDiscountDTO> findDiscountSolution(List<OrderCourseDTO> orderCourses);
}
