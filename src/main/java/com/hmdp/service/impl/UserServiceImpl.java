package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送手机验证码
     *
     * @param phone
     * @param session
     */
    public Result sendCode(String phone, HttpSession session) {

        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.为无效手机号，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 4.保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 5.发送验证码（实际需调用第三方平台如阿里云实现发送短信）
        log.info("发送短信验证码成功，验证码：{}", code);

        return Result.ok();

    }

    /**
     * 登录功能
     *
     * @param loginForm
     * @param session
     * @return
     */
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.为无效手机号，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 3.校验验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (cacheCode == null || !code.equals(cacheCode)) {
            // 4.不一致，报错
            return Result.fail("验证码过期或错误！");
        }
        // 5.一致，根据手机号查询用户 select * from tb_user where phone = ?
        User user = query().eq("phone", phone).one();
        // 6.判断用户是否存在
        if (user == null) {
            // 7.不存在，创建新用户并保存
            user = createUserWithPhone(phone);
        }
        // 8.保存用户信息到redis中(生成随机token作为key并保存到前端作为后续请求鉴权依据，将user信息转换为hash结构存储)
        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().
                        setIgnoreNullValue(true).
                        setFieldValueEditor((fieldName, fieldValue)->fieldValue.toString()));
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
//        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);

        return Result.ok(token);
    }

    /**
     * 用户签到
     */
    public Result sign() {
        // 获取当前登陆用户
        Long userId = UserHolder.getUser().getId();
        // 拼接key
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 获取当前天
        int dayOfMonth = now.getDayOfMonth();
        // 写入redis进行签到
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    /**
     * 统计连续签到天数
     *
     * @return
     */
    public Result signCount() {
        // 获取当前登陆用户
        Long userId = UserHolder.getUser().getId();
        // 拼接key
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 获取当前天
        int dayOfMonth = now.getDayOfMonth();

        // 获取本月截止今天的全部签到记录，返回的是十进制
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key, BitFieldSubCommands.create().
                get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                .valueAt(0));
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }

        // 从最后一位往前遍历，通过与1作与运算判断0/1，遇到第一个为0的位停止
        int count = 0;
        while (true) {
            if ((num & 1) == 0) {
                // 未签到，直接结束
                break;
            } else {
                // 签到天数+1
                count += 1;
            }
            num >>>= 1;  // 右移一位并赋值给num
        }
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
