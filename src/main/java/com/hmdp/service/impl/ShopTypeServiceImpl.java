package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 查询店铺类型列表
     *
     * @return
     */
    public Result queryShopTypeList() {
        // 1.从redis查询缓存
        String shopTypeListJson = stringRedisTemplate.opsForValue().get("cache:shopTypeList");
        if (StrUtil.isNotBlank(shopTypeListJson)) {
            // 2.redis中命中，直接返回客户端
            List<ShopType> shopTypeList = JSONUtil.toList(shopTypeListJson, ShopType.class);
            return Result.ok(shopTypeList);
        }
        // 3.未命中，查询数据库
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();

        if (shopTypeList == null || shopTypeList.isEmpty()) {
            // 4. 数据库中没有数据，返回空列表或错误信息
            return Result.fail("店铺类型列表为空");
        }
        // 5.数据库中查到的数据保存到redis
        stringRedisTemplate.opsForValue().set("cache:shopTypeList", JSONUtil.toJsonStr(shopTypeList));
        // 6.返回客户端
        return Result.ok(shopTypeList);
    }
}
