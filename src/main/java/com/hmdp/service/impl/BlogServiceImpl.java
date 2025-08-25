package com.hmdp.service.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Resource;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;

/**
 * <p>
 * 服务实现类
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

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return;
        }
        Long userid = UserHolder.getUser().getId();
        Double score = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + blog.getId(), userid.toString());
        blog.setIsLike(score != null);
    }

    @Override
    public Result queryBlogList(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            Long userId = blog.getUserId();
            User user = userService.getById(userId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id) {
        Long userid = UserHolder.getUser().getId();
        Double score = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + id, userid.toString());
        if (score == null) {
            boolean updated = update().setSql("liked = liked + 1").eq("id", id).update();
            if (updated) {
                stringRedisTemplate.opsForZSet().add(BLOG_LIKED_KEY + id, userid.toString(), System.currentTimeMillis());
            }
        } else {
            boolean updated = update().setSql("liked = liked - 1").eq("id", id).update();
            if (updated) {
                stringRedisTemplate.opsForZSet().remove(BLOG_LIKED_KEY + id, userid.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        Set<String> ranged = stringRedisTemplate.opsForZSet().range(BLOG_LIKED_KEY + id, 0, 4);
        if (ranged == null || ranged.isEmpty()) {
            return Result.fail(Collections.emptyList().toString());
        }
        List<Long> ids = ranged.stream().map(Long::valueOf).toList();
        String idstr = StrUtil.join(",", ids);
        List<User> users = userService.query().in("id", ids).last("order by field(id, " + idstr + ")").list();
        List<UserDTO> userDTOS = users.stream().
                map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .toList();
        return Result.ok(userDTOS);
    }

    @Override
    @Transactional
    public Result saveBlog(Blog blog) {
        Long id = UserHolder.getUser().getId();
        blog.setUserId(id);
        boolean saved = save(blog);
        if (!saved) {
            return Result.fail("<UNK>");
        }
        List<Follow> followers = followService.query().eq("follow_user_id", id).list();
        for (Follow follower : followers) {
            Long userId = follower.getUserId();
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        Long userid = UserHolder.getUser().getId();
        String key = FEED_KEY + userid;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 3);
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            ids.add(Long.valueOf(typedTuple.getValue()));
            long l = typedTuple.getScore().longValue();
            if (l == minTime) {
                os++;
            } else {
                minTime = l;
                os = 1;
            }
        }
        String idstr = StrUtil.join(",", ids);
        List<Blog> blogList = query().in("id", ids).last("order by field(id, " + idstr + ")").list();
        if (blogList.isEmpty()) {
            return Result.ok(new ScrollResult()); // 如果列表为空，直接返回
        }
        // 1. 从 blogList 中提取出所有的 userId
        Set<Long> userIds = blogList.stream()
                .map(Blog::getUserId)
                .collect(Collectors.toSet());

        // 2. 使用 IN 查询，一次性获取所有需要的用户信息
        //    结果是一个 Map<userId, User>，方便后续查找
        Map<Long, User> userMap = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, user -> user));
        blogList.forEach(blog -> {
            User user = userMap.get(blog.getUserId());
            if (user != null) {
                blog.setName(user.getNickName());
                blog.setIcon(user.getIcon());
            }
            isBlogLiked(blog); // isBlogLiked 内部也应该用类似的方法优化
        });
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogList);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }
}
