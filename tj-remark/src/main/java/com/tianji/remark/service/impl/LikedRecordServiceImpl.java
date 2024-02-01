package com.tianji.remark.service.impl;

import com.tianji.api.dto.remark.LikedTimesDTO;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.utils.StringUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.remark.domain.dto.LikeRecordFormDTO;
import com.tianji.remark.domain.po.LikedRecord;
import com.tianji.remark.mapper.LikedRecordMapper;
import com.tianji.remark.service.ILikedRecordService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.tianji.common.constants.MqConstants.Exchange.LIKE_RECORD_EXCHANGE;
import static com.tianji.common.constants.MqConstants.Key.LIKED_TIMES_KEY_TEMPLATE;

/**
 * <p>
 * 点赞记录表 服务实现类
 * </p>
 *
 * @author author
 * @since 2023-12-21
 */
//@Service
@RequiredArgsConstructor
public class LikedRecordServiceImpl extends ServiceImpl<LikedRecordMapper, LikedRecord> implements ILikedRecordService {

    private final RabbitMqHelper mqHelper;

    @Override
    public void addLikeRecord(LikeRecordFormDTO recordDTO) {
        // 1.获取登录用户
        Long userId = UserContext.getUser();
        // 2.点赞或取消点赞
        boolean success = recordDTO.getLiked() ? like(recordDTO,userId) : unlike(recordDTO,userId);
        if (!success) {
            return;
        }
        // 3.如果执行成功，统计点赞总数
        Integer likedTimes = lambdaQuery()
                .eq(LikedRecord::getBizId,recordDTO.getBizId())
                .count();
        // 3.发送MQ通知,更新点赞数量
        mqHelper.send(
                LIKE_RECORD_EXCHANGE,
                StringUtils.format(LIKED_TIMES_KEY_TEMPLATE, recordDTO.getBizType()),
                LikedTimesDTO.of(recordDTO.getBizId(), likedTimes));

    }

    @Override
    public Set<Long> isBizLiked(List<Long> bizIds) {
        // 1.获取登录用户id
        Long userId = UserContext.getUser();
        // 2.查询点赞状态
        List<LikedRecord> list = lambdaQuery()
                .in(LikedRecord::getBizId,bizIds)
                .eq(LikedRecord::getUserId,userId)
                .list();
        // 3.返回结果
        return list.stream().map(LikedRecord::getBizId).collect(Collectors.toSet());
    }

    @Override
    public void readLikedTimesAndSendMessage(String bizType, int maxBizSize) {

    }

    private boolean unlike(LikeRecordFormDTO recordDTO, Long userId) {
        return remove(
                lambdaQuery()
                        .eq(LikedRecord::getUserId, userId)
                        .eq(LikedRecord::getBizId, recordDTO.getBizId())
                        .getWrapper()
        );
    }

    private boolean like(LikeRecordFormDTO recordDTO, Long userId) {
        // 1.查询
        Integer count = lambdaQuery()
                .eq(LikedRecord::getUserId,userId)
                .eq(LikedRecord::getBizId,recordDTO.getBizId())
                .count();
        if (count > 0) {
            // 已点赞，无需处理
            return false;
        }

        // 2.新增
        LikedRecord r = new LikedRecord();
        r.setUserId(userId);
        r.setBizId(recordDTO.getBizId());
        r.setBizType(recordDTO.getBizType());
        return save(r);
    }
}
