package com.simplefanc.voj.server.pojo.vo;

import com.simplefanc.voj.common.pojo.entity.discussion.Reply;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * @Author: chenfan
 * @Date: 2021/5/5 22:30
 * @Description:
 */
@ApiModel(value = "评论数据列表VO", description = "")
@Data
public class CommentVo {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "评论id")
    private Integer id;

    @ApiModelProperty(value = "评论内容")
    private String content;

    @ApiModelProperty(value = "评论者id")
    private String fromUid;

    @ApiModelProperty(value = "评论者用户名")
    private String fromName;

    @ApiModelProperty(value = "评论组头像地址")
    private String fromAvatar;

    @ApiModelProperty(value = "评论者角色")
    private String fromRole;

    @ApiModelProperty(value = "点赞数量")
    private Integer likeNum;

    @ApiModelProperty(value = "该评论的总回复数")
    private Integer totalReplyNum;

    private Date gmtCreate;

    @ApiModelProperty(value = "该评论回复列表")
    private List<Reply> replyList;
}