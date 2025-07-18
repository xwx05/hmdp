package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    /**
     * 根据id查询blog
     * @param id
     * @return
     */
    Result queryByBlogId(Long id);


    /**
     * 查询热门blog
     * @param current
     * @return
     */
    Result queryHotBlog(Integer current);


    /**
     * 修改点赞数量
     * @param id
     * @return
     */
    Result likeBlog(Long id);


    /**
     * 获取博客点赞列表
     * @param id
     * @return
     */
    Result queryBlogLikes(Long id);


    /**
     * 新增笔记
     * @param blog
     * @return
     */
    Result saveBlog(Blog blog);


    /**
     * 查看关注用户的博客
     * @param max
     * @param offset
     * @return
     */
    Result queryBlogOfFollow(Long max, Integer offset);
}
