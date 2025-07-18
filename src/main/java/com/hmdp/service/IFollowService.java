package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    /**
     * 关注/取关
     * @param followUserId
     * @param isFollow
     * @return
     */
    Result follow(Long followUserId, Boolean isFollow);

    /**
     * 查询是否关注
     * @param followUserId
     * @return
     */
    Result followOrNot(Long followUserId);

    /**
     * 获取共同关注的用户
     * @param followUserId
     * @return
     */
    Result followCommons(Long followUserId);
}
