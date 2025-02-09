package com.simplefanc.voj.backend.judge;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.cloud.nacos.NacosServiceManager;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.simplefanc.voj.backend.dao.judge.JudgeServerEntityService;
import com.simplefanc.voj.backend.mapper.RemoteJudgeAccountMapper;
import com.simplefanc.voj.common.constants.RemoteOj;
import com.simplefanc.voj.common.pojo.entity.judge.JudgeServer;
import com.simplefanc.voj.common.pojo.entity.judge.RemoteJudgeAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @Author: chenfan
 * @Date: 2021/5/24 17:30
 * @Description: 筛选可用判题机
 */
@Component
@Slf4j(topic = "voj")
@RequiredArgsConstructor
public class ChooseUtils {

    private final NacosServiceManager nacosServiceManager;

    private final NacosDiscoveryProperties discoveryProperties;

    @Value("${service-url.name}")
    private String judgeServiceName;

    private final JudgeServerEntityService judgeServerEntityService;

    private final RemoteJudgeAccountMapper remoteJudgeAccountMapper;

    /**
     * @param
     * @MethodName chooseServer
     * @Description 选择可以用调用判题的判题服务器
     * @Return
     * @Since 2021/4/15
     */
    @Transactional(rollbackFor = Exception.class)
    public JudgeServer chooseJudgeServer(Boolean isRemote) {
        // 获取该微服务的所有健康实例
        List<Instance> instances = getInstances(judgeServiceName);
        if (instances.size() <= 0) {
            return null;
        }
        List<String> keyList = new ArrayList<>();
        // 获取当前健康实例取出ip和port拼接
        for (Instance instance : instances) {
            keyList.add(instance.getIp() + ":" + instance.getPort());
        }

        // 过滤出小于或等于规定最大并发判题任务数的服务实例且健康的判题机
        QueryWrapper<JudgeServer> judgeServerQueryWrapper = new QueryWrapper<>();
        judgeServerQueryWrapper.in("url", keyList)
                .eq("is_remote", isRemote)
                .orderByAsc("task_number")
                // 开启排他锁
                .last("for update");

        // 如果一个条件无法通过索引快速过滤，存储引擎层面就会将所有记录加锁后返回，再由MySQL Server层进行过滤
        // 但在实际使用过程当中，MySQL做了一些改进，在MySQL Server过滤条件，发现不满足后，会调用unlock_row方法，把不满足条件的记录释放锁 (违背了二段锁协议的约束)。
        List<JudgeServer> judgeServerList = judgeServerEntityService.list(judgeServerQueryWrapper);

        // 获取可用判题机
        for (JudgeServer judgeServer : judgeServerList) {
            if (judgeServer.getTaskNumber() < judgeServer.getMaxTaskNumber()) {
                judgeServer.setTaskNumber(judgeServer.getTaskNumber() + 1);
                boolean isOk = judgeServerEntityService.updateById(judgeServer);
                if (isOk) {
                    return judgeServer;
                }
            }
        }

        return null;
    }

    /**
     * @param serviceId
     * @MethodName getInstances
     * @Description 根据服务id获取对应的健康实例列表
     * @Return
     * @Since 2021/4/15
     */
    private List<Instance> getInstances(String serviceId) {
        // 获取服务发现的相关API
        NamingService namingService = nacosServiceManager.getNamingService(discoveryProperties.getNacosProperties());
        try {
            // 获取该微服务的所有健康实例
            return namingService.selectInstances(serviceId, true);
        } catch (NacosException e) {
            log.error("获取微服务健康实例发生异常--------->", e);
            return Collections.emptyList();
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public RemoteJudgeAccount chooseRemoteAccount(String remoteOjName) {
        if (RemoteOj.GYM.getName().equals(remoteOjName)) {
            remoteOjName = RemoteOj.CF.getName();
        }

        // 过滤出当前远程oj可用的账号列表 悲观锁
        List<RemoteJudgeAccount> remoteJudgeAccountList = remoteJudgeAccountMapper.getAvailableAccount(remoteOjName);

        for (RemoteJudgeAccount remoteJudgeAccount : remoteJudgeAccountList) {
            int count = remoteJudgeAccountMapper.updateAccountStatusById(remoteJudgeAccount.getId());
            if (count > 0) {
                return remoteJudgeAccount;
            }
        }

        return null;
    }

}