package com.simplefanc.voj.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;
import com.simplefanc.voj.pojo.entity.discussion.Reply;

/**
 * @Author: chenfan
 * @Date: 2021/5/5 22:07
 * @Description:
 */

@Mapper
@Repository
public interface ReplyMapper extends BaseMapper<Reply> {
}