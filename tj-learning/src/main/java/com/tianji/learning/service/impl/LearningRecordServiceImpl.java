package com.tianji.learning.service.impl;

import com.tianji.api.client.course.CourseClient;
import com.tianji.api.dto.course.CourseFullInfoDTO;
import com.tianji.api.dto.leanring.LearningLessonDTO;
import com.tianji.api.dto.leanring.LearningRecordDTO;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.exceptions.DbException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.dto.LearningRecordFormDTO;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.domain.po.LearningRecord;
import com.tianji.learning.enums.LessonStatus;
import com.tianji.learning.enums.SectionType;
import com.tianji.learning.mapper.LearningRecordMapper;
import com.tianji.learning.service.ILearningLessonService;
import com.tianji.learning.service.ILearningRecordService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.learning.utils.LearningRecordDelayTaskHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 学习记录表 服务实现类
 * </p>
 *
 * @author zj
 * @since 2023-12-16
 */
@Service
@RequiredArgsConstructor
public class LearningRecordServiceImpl extends ServiceImpl<LearningRecordMapper, LearningRecord> implements ILearningRecordService {

    private final ILearningLessonService lessonService;
    private final CourseClient courseClient;
    private final LearningRecordDelayTaskHandler taskHandler;

    /**
     * 查询当前用户课程的学习记录
     * @param courseId
     * @return
     */
    @Override
    public LearningLessonDTO queryLearningRecordByCourse(Long courseId) {
        // 1.获取登录用户
        Long userId = UserContext.getUser();
        // 2.查询课表
        LearningLesson lesson = lessonService.queryByUserAndCourseId(userId,courseId);
        // 3.查询学习记录
        List<LearningRecord> records = lambdaQuery().eq(LearningRecord::getLessonId,lesson.getId()).list();
        // 4.封装结果
        LearningLessonDTO dto = new LearningLessonDTO();
        dto.setId(lesson.getId());
        dto.setLatestSectionId(lesson.getLatestSectionId());
        dto.setRecords(BeanUtils.copyList(records, LearningRecordDTO.class));

        return dto;
    }

    @Override
    @Transactional
    public void addLearningRecord(LearningRecordFormDTO recordDTO) {
        // 1.获取登录用户
        Long userId = UserContext.getUser();

        // 2.处理学习记录
        boolean finished = false; // 本小结是否学完
        if(recordDTO.getSectionType() == SectionType.VIDEO){
            // 2.1.处理视频
            finished = handleVideoRecord(userId, recordDTO);
        }else{
            // 2.2.处理考试
            finished = handleExamRecord(userId, recordDTO);
        }
        if (!finished) {
            // 没有新学完的小节，无需执行下面的方法更新课表中的学习进度，因为延迟队列的任务会处理。
            return;
        }
        // 3.处理课表数据
        handleLearningLessonsChanges(recordDTO);
    }

    // 更新课表中的学习进度，该方法只有在第一次学完学完的小节时调用
    private void handleLearningLessonsChanges(LearningRecordFormDTO recordDTO) {
        // 1.查询课表
        LearningLesson lesson = lessonService.getById(recordDTO.getLessonId());
        if (lesson == null) {
            throw new BizIllegalException("课程不存在，无法更新数据！");
        }
        boolean allLearned = false; // 课程的所有小节都已学完

        // 判断课程的所有小节都已学完
        CourseFullInfoDTO cInfo = courseClient.getCourseInfoById(lesson.getCourseId(),false,false);
        if (cInfo == null) {
            throw new BizIllegalException("课程不存在，无法更新数据！");
        }
        // 4.比较课程是否全部学完：已学习小节 >= 课程总小节
        allLearned = lesson.getLearnedSections() +1 >=cInfo.getSectionNum();
        // 5.更新课表
        lessonService.lambdaUpdate()
                .set(lesson.getLearnedSections() == 0, LearningLesson::getStatus, LessonStatus.LEARNING.getValue())
                .set(allLearned, LearningLesson::getStatus, LessonStatus.FINISHED.getValue())
                .setSql("learned_sections = learned_sections + 1")
                .eq(LearningLesson::getId, lesson.getId())
                .update();


    }

    private boolean handleExamRecord(Long userId, LearningRecordFormDTO recordDTO) {
        // 新增学习记录，状态设置为完成
        LearningRecord record = BeanUtils.copyBean(recordDTO,LearningRecord.class);
        // 2.填充数据
        record.setUserId(userId);
        record.setFinished(true);
        record.setFinishTime(recordDTO.getCommitTime());
        // 3.写入数据库
        boolean success = save(record);
        if (!success) {
            throw new DbException("新增考试记录失败！");
        }
        return true;
    }

    private boolean handleVideoRecord(Long userId, LearningRecordFormDTO recordDTO) {
        // 1.查询旧的学习记录
        LearningRecord old = queryOldRecord(recordDTO.getLessonId(),recordDTO.getSectionId());
        // 2.判断是否存在
        if(old==null){
            // 3.不存在，则新增
            LearningRecord record = BeanUtils.copyBean(recordDTO, LearningRecord.class);
            record.setUserId(userId);
            // 判断是否是第一次完成
            boolean finished = recordDTO.getMoment() * 2 >= recordDTO.getDuration();
            record.setFinished(finished);
            boolean success = save(record);
            if (!success) {
                throw new DbException("新增学习记录失败！");
            }
            return finished;
        }else{
            // 4.存在，则更新
            // 4.1.判断是否是第一次完成
            boolean finished = !old.getFinished() && recordDTO.getMoment() * 2 >= recordDTO.getDuration();
            if(!finished){
                // 更新到redis
                LearningRecord record = new LearningRecord();
                record.setLessonId(recordDTO.getLessonId());
                record.setSectionId(recordDTO.getSectionId());
                record.setMoment(recordDTO.getMoment());
                record.setId(old.getId());
                record.setFinished(old.getFinished());
                taskHandler.addLearningRecordTask(record);
                return false;
            }
            // 4.2.更新数据到数据库
            boolean success = lambdaUpdate()
                    .set(LearningRecord::getMoment, recordDTO.getMoment())
                    .set(LearningRecord::getFinished, true)
                    .set(LearningRecord::getFinishTime, recordDTO.getCommitTime())
                    .eq(LearningRecord::getId, old.getId())
                    .update();

            if(!success){
                throw new DbException("更新学习记录失败！");
            }
            // 4.3.清理缓存
            taskHandler.cleanRecordCache(recordDTO.getLessonId(), recordDTO.getSectionId());
            return true ;
        }

    }

    private LearningRecord queryOldRecord( Long lessonId, Long sectionId) {
        // 1.查询缓存
        LearningRecord record = taskHandler.readRecordCache(lessonId,sectionId);
        // 2.如果命中，直接返回
        if (record != null) {
            return record;
        }
        // 3.未命中，查询数据库
        record = lambdaQuery()
                    .eq(LearningRecord::getLessonId,lessonId)
                    .eq(LearningRecord::getSectionId,sectionId)
                    .one();

        // 4.写入缓存
        taskHandler.writeRecordCache(record);
        return record;

    }
}
