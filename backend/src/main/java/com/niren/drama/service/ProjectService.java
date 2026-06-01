package com.niren.drama.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.niren.drama.common.PageQuery;
import com.niren.drama.common.ProjectStyleSupport;
import com.niren.drama.dto.project.ProjectCreateRequest;
import com.niren.drama.entity.Project;
import com.niren.drama.exception.BusinessException;
import com.niren.drama.mapper.ProjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectMapper projectMapper;

    public Project createProject(Long userId, ProjectCreateRequest request) {
        Project project = new Project();
        project.setUserId(userId);
        project.setName(request.getName());
        project.setDescription(request.getDescription());
        project.setProjectType(ProjectStyleSupport.resolveProjectType(request.getProjectType()));
        project.setGenre(ProjectStyleSupport.resolveGenre(request.getGenre()));
        project.setEpisodes(request.getEpisodes());
        project.setEpisodeDuration(request.getEpisodeDuration());
        project.setStatus("draft");
        projectMapper.insert(project);
        return project;
    }

    public Page<Project> listProjects(Long userId, PageQuery query) {
        Page<Project> page = new Page<>(query.getPage(), query.getSize());
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<Project>()
                .eq(Project::getUserId, userId)
                .orderByDesc(Project::getCreateTime);
        if (StringUtils.isNotBlank(query.getKeyword())) {
            String kw = query.getKeyword().trim();
            wrapper.and(w -> w.like(Project::getName, kw)
                    .or()
                    .like(Project::getDescription, kw));
        }
        return projectMapper.selectPage(page, wrapper);
    }

    public Project getProject(Long id) {
        Project project = projectMapper.selectById(id);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        return project;
    }

    public Project getProject(Long userId, Long id) {
        Project project = projectMapper.selectOne(new LambdaQueryWrapper<Project>()
                .eq(Project::getId, id)
                .eq(Project::getUserId, userId)
                .last("limit 1"));
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        return project;
    }

    public Project updateProject(Long id, ProjectCreateRequest request) {
        Project project = getProject(id);
        project.setName(request.getName());
        project.setDescription(request.getDescription());
        project.setProjectType(ProjectStyleSupport.resolveProjectType(request.getProjectType()));
        project.setGenre(ProjectStyleSupport.resolveGenre(request.getGenre()));
        if (request.getEpisodes() != null) project.setEpisodes(request.getEpisodes());
        if (request.getEpisodeDuration() != null) project.setEpisodeDuration(request.getEpisodeDuration());
        projectMapper.updateById(project);
        return project;
    }

    public void deleteProject(Long id) {
        projectMapper.deleteById(id);
    }

    public void updateStatus(Long id, String status) {
        Project project = getProject(id);
        project.setStatus(status);
        projectMapper.updateById(project);
    }

    public void updateCommonInfo(Long userId, Long id, String commonInfo) {
        Project project = getProject(userId, id);
        project.setCommonInfo(commonInfo);
        projectMapper.updateById(project);
    }

    /**
     * 将项目名称更新为通用信息中解析出的 AI 正式片名（非空且通过校验时写入）。
     */
    public void updateProjectName(Long userId, Long id, String name) {
        if (StringUtils.isBlank(name)) {
            return;
        }
        Project project = getProject(userId, id);
        project.setName(name);
        projectMapper.updateById(project);
    }
}
