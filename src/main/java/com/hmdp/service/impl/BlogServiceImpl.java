package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    /**
     * 根据id查询blog
     *
     * @param id
     * @return
     */
    public Result queryByBlogId(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        queryBlogUser(blog);
        isBlogLiked(blog);
        return Result.ok(blog);
    }


    /**
     * 查询热门blog
     *
     * @param current
     * @return
     */
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 查询blog关联的用户信息
     * @param blog
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }


    /**
     * 判断用户是否点赞
     * @param blog
     */
    private void isBlogLiked (Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 用户未登录，无需查询是否点赞
            return;
        }
        Long userId = user.getId();
        Double score = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + blog.getId(), userId.toString());
        blog.setIsLike(score != null);
    }


    /**
     * 修改点赞数量
     *
     * @param id
     * @return
     */
    public Result likeBlog(Long id) {

        String key = BLOG_LIKED_KEY + id;
        // 1. 判断当前用户是否已点赞
        Long userId = UserHolder.getUser().getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());

        // zset中如果元素不存在，调用score函数查询分数会返回nil
        if (score == null) {
            // 2. 未点赞，修改数据库+1，并保存用户到redis集合
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 3. 已点赞，修改数据库-1，将该用户从redis移除
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 获取博客点赞列表
     *
     * @param id
     * @return
     */
    public Result queryBlogLikes(Long id) {
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(BLOG_LIKED_KEY + id, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 解析出用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        // 根据用户id查询用户
        // 由于数据库默认根据id升序返回查询结果，需要使用order by自定义顺序
        String idStr = StrUtil.join(",", ids);
        List<UserDTO> userDTOs = userService.query().in("id", ids)
                .last("ORDER BY FIELD(id, " + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOs);
    }

    /**
     * 新增笔记
     *
     * @param blog
     * @return
     */
    public Result saveBlog(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 2.保存探店博文
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("新增笔记失败！");
        }
        // 3.查询笔记作者的所有粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        // 4.将blog_id存入粉丝收件箱
        for (Follow follow : follows) {
            Long userId = follow.getUserId();
            stringRedisTemplate.opsForZSet().add(FEED_KEY + userId, blog.getId().toString(), System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    /**
     * 查看关注用户的博客
     *
     * @param max
     * @param offset
     * @return
     */
    public Result queryBlogOfFollow(Long max, Integer offset) {
        Long userId = UserHolder.getUser().getId();
        Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet().
                reverseRangeByScoreWithScores(FEED_KEY + userId, 0, max, offset, 2);
        if (tuples == null || tuples.isEmpty()) {
            return Result.ok();
        }

        // 解析出blog_id，minTime, offset
        List<Long> blogIds = new ArrayList<>(tuples.size());
        Long minTime = 0L;
        int os = 1;  // 初始化offset为1

        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            String blogId = tuple.getValue();
            blogIds.add(Long.valueOf(blogId));

            // 统计与最小score一样的元素有几个，
            Long time = tuple.getScore().longValue();
            if (time == minTime) {
                os += 1;
            } else {
                minTime = time;
                os = 1;
            }
        }
        // 根据blogIds查询blog，注意指明顺序
        String idStr = StrUtil.join(",", blogIds);
        List<Blog> blogs = query().in("id", blogIds)
                .last("ORDER BY FIELD(id, " + idStr + ")").list();
        // 查出blog后还要补充发布blog的作者信息，以及当前登陆用户是否给此blog点过赞
        blogs.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });

        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }

}
