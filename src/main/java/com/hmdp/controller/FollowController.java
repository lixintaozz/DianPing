package com.hmdp.controller;


import cn.hutool.core.util.BooleanUtil;
import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {
    @Autowired
    private IFollowService followService;

    @PutMapping("/{id}/{followed}")
    public Result follow(@PathVariable("id") Long id, @PathVariable("followed")Boolean followed)
    {
        return followService.follow(id, followed);
    }

    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long id)
    {
        return followService.isFollow(id);
    }
}
