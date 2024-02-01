package com.tianji.promotion.service.impl;

import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.api.dto.promotion.CouponDiscountDTO;
import com.tianji.api.dto.promotion.OrderCourseDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.CouponScope;
import com.tianji.promotion.mapper.UserCouponMapper;
import com.tianji.promotion.service.ICouponScopeService;
import com.tianji.promotion.service.IDiscountService;
import com.tianji.promotion.strategy.discount.Discount;
import com.tianji.promotion.strategy.discount.DiscountStrategy;
import com.tianji.promotion.utils.PermuteUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiscountServiceImpl implements IDiscountService {

    private final UserCouponMapper userCouponMapper;

    private final ICouponScopeService scopeService;

    private final Executor discountSolutionExecutor;

    @Override
    public List<CouponDiscountDTO> findDiscountSolution(List<OrderCourseDTO> orderCourses) {
        // 1.查询我的所有可用优惠券
        List<Coupon> coupons = userCouponMapper.queryMyCoupons(UserContext.getUser());
        if (CollUtils.isEmpty(coupons)) {
            return CollUtils.emptyList();
        }
        // 2.初筛: 基于订单金额刷选出可用优惠券
        // 2.1.计算订单总价
        int totalAmount = orderCourses.stream().mapToInt(OrderCourseDTO::getPrice).sum();
        // 2.2.筛选可用券
        List<Coupon> availableCoupons = coupons.stream()
                .filter(c-> DiscountStrategy.getDiscount(c.getDiscountType()).canUse(totalAmount,c))
                .collect(Collectors.toList());
        if (CollUtils.isEmpty(availableCoupons)) {
            return CollUtils.emptyList();
        }
        
        // 3.排列组合出所有方案
        // 3.1.细筛（找出每一个优惠券的可用的课程，判断课程总价是否达到优惠券的使用需求）
        Map<Coupon, List<OrderCourseDTO>> availableCouponMap = findAvailableCoupon(availableCoupons, orderCourses);
        if (CollUtils.isEmpty(availableCouponMap)) {
            return CollUtils.emptyList();
        }

        // 3.2.计算优惠方案全排列
        availableCoupons = new ArrayList<>(availableCouponMap.keySet());
        List<List<Coupon>> solutions = PermuteUtil.permute(availableCoupons);
        // 3.3.添加单券的方案
        for (Coupon c : availableCoupons) {
            solutions.add(List.of(c));
        }
        // 4.计算方案的优惠明细
        List<CouponDiscountDTO> list = Collections.synchronizedList(new ArrayList<>(solutions.size()));
//        同步方式
//        for (List<Coupon> solution : solutions) {
//            list.add(calculateSolutionDiscount(availableCouponMap, orderCourses, solution));
//        }
        //异步优化
        CountDownLatch latch = new CountDownLatch(solutions.size());
        for (List<Coupon> solution : solutions) {
            CompletableFuture.supplyAsync(
                    ()->calculateSolutionDiscount(availableCouponMap, orderCourses, solution),
                    discountSolutionExecutor
            ).thenAccept(dto->{
                list.add(dto);
                latch.countDown();
            });
        }
        // 4.4.等待运算结束
        try {
            latch.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            log.error("优惠方案计算被中断，{}", e.getMessage());
        }

        // 5.筛选最优解
        return findBestSolution(list);
    }

    private List<CouponDiscountDTO> findBestSolution(List<CouponDiscountDTO> list) {
        // 1.准备Map记录最优解
        Map<String, CouponDiscountDTO> moreDiscountMap = new HashMap<>(); // key:优惠券ids
        Map<Integer, CouponDiscountDTO> lessCouponMap = new HashMap<>();  // key: 可优惠金额
        // 2.遍历，筛选最优解
        for(CouponDiscountDTO solution : list){
            // 2.2.比较用券相同时，优惠金额是否最大
            String ids = solution.getIds().stream()
                    .sorted(Long::compare).map(String::valueOf).collect(Collectors.joining(","));
            CouponDiscountDTO best = moreDiscountMap.get(ids);
            if(best!=null && best.getDiscountAmount()>=solution.getDiscountAmount()){
                continue;
            }
            // 2.4.更新最优解
            moreDiscountMap.put(ids,solution);

            // 2.3.比较金额相同时，用券数量是否最少
            best = lessCouponMap.get(solution.getDiscountAmount());
            int size = solution.getIds().size();
            if(size>1 && best!=null && best.getIds().size()<=size){ // size>1是为了保留单券的方案
                // 当前方案用券更多，放弃
                continue;
            }
            // 2.4.更新最优解
            lessCouponMap.put(solution.getDiscountAmount(),solution);
        }
        // 3.求交集
        Collection<CouponDiscountDTO> bestSolutions = CollUtils.intersection(moreDiscountMap.values(),lessCouponMap.values());
        // 4.排序，按优惠金额降序
        return bestSolutions.stream()
                .sorted(Comparator.comparingInt(CouponDiscountDTO::getDiscountAmount).reversed())
                .collect(Collectors.toList());
    }

    /**
     * 计算方案的优惠明细
     * @param couponMap : key:优惠券,value:该优惠券可用课程列表
     * @param courses  订单中的课程列表
     * @param solution  一个优惠方案，可能是优惠券叠加，也可能是单优惠券。
     * @return
     */
    private CouponDiscountDTO calculateSolutionDiscount(
            Map<Coupon, List<OrderCourseDTO>> couponMap, List<OrderCourseDTO> courses, List<Coupon> solution) {
        // 1.初始化DTO
        CouponDiscountDTO dto = new CouponDiscountDTO();
        // 2.初始化折扣明细的映射,
        Map<Long, Integer> detailMap = courses.stream().collect(Collectors.toMap(OrderCourseDTO::getId, oc -> 0));
        // 3.计算折扣
        for(Coupon coupon : solution){
            // 3.1.获取优惠券限定范围对应的课程
            List<OrderCourseDTO> availableCourses = couponMap.get(coupon);
            // 3.2.计算课程总价(课程原价 - 折扣明细)
            int totalAmount = availableCourses.stream()
                    .mapToInt(oc->oc.getPrice() - detailMap.get(oc.getId())).sum();
            // 3.3.判断是否可用
            Discount discount = DiscountStrategy.getDiscount(coupon.getDiscountType());
            if(!discount.canUse(totalAmount,coupon)){
                // 券不可用，跳过
                continue;
            }
            // 3.4.计算优惠金额
            int discountAmount = discount.calculateDiscount(totalAmount,coupon);
            // 3.5.计算优惠明细, 更新到detailMap
            calculateDiscountDetails(detailMap, availableCourses, totalAmount, discountAmount);
            // 3.6.更新DTO数据
            dto.getIds().add(coupon.getCreater());  // 注意这里的Creater暂时保存的是用户优惠券id，不是原始的创建者。
            dto.getRules().add(discount.getRule(coupon));
            dto.setDiscountAmount(discountAmount + dto.getDiscountAmount());
        }

        return dto;
    }

    /**
     * 计算优惠明细，即把优惠数额按比例分摊到订单的各课程中
     * @param detailMap 优惠明细
     * @param courses 课程列表
     * @param totalAmount 课程总金额
     * @param discountAmount 折扣金额
     */
    private void calculateDiscountDetails(Map<Long, Integer> detailMap, List<OrderCourseDTO> courses, int totalAmount, int discountAmount) {
        int remainDiscount = discountAmount;
        for(int i=0;i<courses.size();i++){
            OrderCourseDTO course = courses.get(i);
            int discount = 0;
            if(i==courses.size()-1){
                // 是最后一个课程，总折扣金额 - 之前所有商品的折扣金额之和
                discount = remainDiscount;
            }else{
                // 计算折扣明细（课程价格在总价中占的比例，乘以总的折扣）
                discount = discountAmount * course.getPrice() / totalAmount;
                remainDiscount = discountAmount - discount;
            }
            detailMap.put(course.getId(),discount+detailMap.get(course.getId()));
        }
    }

    /**
     * 根据使用范围计算出来的价格刷选出优惠券
     * @param coupons
     * @param courses
     * @return 可用优惠券及其适用的课程列表
     */
    private Map<Coupon, List<OrderCourseDTO>> findAvailableCoupon(List<Coupon> coupons, List<OrderCourseDTO> courses) {
        Map<Coupon, List<OrderCourseDTO>> map = new HashMap<>(coupons.size());
        for(Coupon coupon : coupons){
            // 1.找出优惠券的可用的课程
            List<OrderCourseDTO> availableCourses = courses;
            if(coupon.getSpecific()){
                // 1.1.限定了范围，查询券的可用范围
                List<CouponScope> scopes = scopeService.lambdaQuery().eq(CouponScope::getCouponId,coupon.getId()).list();
                // 1.2.获取范围对应的分类id
                Set<Long> scopeIds = scopes.stream().map(CouponScope::getBizId).collect(Collectors.toSet());
                // 1.3.筛选课程
                availableCourses = courses.stream()
                        .filter(c->scopeIds.contains(c.getCateId()))
                        .collect(Collectors.toList());
            }
            if (CollUtils.isEmpty(availableCourses)) {
                // 没有任何可用课程，抛弃
                continue;
            }
            // 2.计算课程总价
            int totalAmount = availableCourses.stream().mapToInt(OrderCourseDTO::getPrice).sum();
            // 3.判断是否可用
            Discount discount = DiscountStrategy.getDiscount(coupon.getDiscountType());
            if(discount.canUse(totalAmount,coupon)){
                map.put(coupon,availableCourses);
            }
        }
        return map;
    }
}







