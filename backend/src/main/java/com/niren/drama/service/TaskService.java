package com.niren.drama.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.niren.drama.entity.TaskRecord;
import com.niren.drama.exception.BusinessException;
import com.niren.drama.mapper.TaskRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskRecordMapper taskRecordMapper;

    public TaskRecord getTask(Long id) {
        TaskRecord task = taskRecordMapper.selectById(id);
        if (task == null) throw new BusinessException("任务不存在");
        return task;
    }

    public List<TaskRecord> listByProject(Long projectId) {
        return taskRecordMapper.selectList(new LambdaQueryWrapper<TaskRecord>()
                .eq(TaskRecord::getProjectId, projectId)
                .orderByDesc(TaskRecord::getCreateTime));
    }

    public List<TaskRecord> listByUser(Long userId) {
        return taskRecordMapper.selectList(new LambdaQueryWrapper<TaskRecord>()
                .eq(TaskRecord::getUserId, userId)
                .orderByDesc(TaskRecord::getCreateTime)
                .last("LIMIT 50"));
    }
}
