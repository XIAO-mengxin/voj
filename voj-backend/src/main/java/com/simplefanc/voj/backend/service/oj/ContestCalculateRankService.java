package com.simplefanc.voj.backend.service.oj;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.simplefanc.voj.backend.common.utils.RedisUtil;
import com.simplefanc.voj.backend.dao.contest.ContestRecordEntityService;
import com.simplefanc.voj.backend.dao.user.UserInfoEntityService;
import com.simplefanc.voj.backend.pojo.vo.ACMContestRankVo;
import com.simplefanc.voj.backend.pojo.vo.ContestRecordVo;
import com.simplefanc.voj.backend.pojo.vo.OIContestRankVo;
import com.simplefanc.voj.common.constants.ContestConstant;
import com.simplefanc.voj.common.constants.ContestEnum;
import com.simplefanc.voj.common.pojo.entity.contest.Contest;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @Author: chenfan
 * @Date: 2022/3/11 20:11
 * @Description:
 */
@Component
public class ContestCalculateRankService {

    @Resource
    private UserInfoEntityService userInfoEntityService;

    @Resource
    private RedisUtil redisUtil;

    @Resource
    private ContestRecordEntityService contestRecordEntityService;

    public List<ACMContestRankVo> calculateACMRank(boolean isOpenSealRank, boolean removeStar, Contest contest,
                                                   String currentUserId, List<String> concernedList) {
        return calculateACMRank(isOpenSealRank, removeStar, contest, currentUserId, concernedList, false, null);
    }

    public List<OIContestRankVo> calculateOIRank(Boolean isOpenSealRank, Boolean removeStar, Contest contest,
                                                 String currentUserId, List<String> concernedList) {

        return calculateOIRank(isOpenSealRank, removeStar, contest, currentUserId, concernedList, false, null);
    }

    /**
     * @param isOpenSealRank 是否是查询封榜后的数据
     * @param removeStar     是否需要移除打星队伍
     * @param contest        比赛实体信息
     * @param currentUserId  当前查看榜单的用户uuid,不为空则将该数据复制一份放置列表最前
     * @param concernedList  关注的用户（uuid）列表
     * @param useCache       是否对初始排序计算的结果进行缓存
     * @param cacheTime      缓存的时间 单位秒
     * @MethodName calcACMRank
     * @Description
     * @Return
     * @Since 2021/12/10
     */
    public List<ACMContestRankVo> calculateACMRank(boolean isOpenSealRank, boolean removeStar, Contest contest,
                                                   String currentUserId, List<String> concernedList, boolean useCache, Long cacheTime) {

        List<ACMContestRankVo> orderResultList;
        if (useCache) {
            String key = ContestConstant.CONTEST_RANK_CAL_RESULT_CACHE + "_" + contest.getId();
            orderResultList = (List<ACMContestRankVo>) redisUtil.get(key);
            if (orderResultList == null) {
                orderResultList = getACMOrderRank(contest, isOpenSealRank);
                redisUtil.set(key, orderResultList, cacheTime);
            }
        } else {
            orderResultList = getACMOrderRank(contest, isOpenSealRank);
        }

        // 需要打星的用户名列表
        HashMap<String, Boolean> starAccountMap = starAccountToMap(contest.getStarAccount());

        // 如果选择了移除打星队伍，同时该用户属于打星队伍，则将其移除
        if (removeStar) {
            orderResultList.removeIf(acmContestRankVo -> starAccountMap.containsKey(acmContestRankVo.getUsername()));
        }
        // 记录当前用户排名数据和关注列表的用户排名数据
        List<ACMContestRankVo> topACMRankVoList = new ArrayList<>();
        boolean needAddConcernedUser = false;
        if (!CollectionUtils.isEmpty(concernedList)) {
            needAddConcernedUser = true;
            // 移除关注列表与当前用户重复
            concernedList.remove(currentUserId);
        }

        int rankNum = 1;
        int len = orderResultList.size();
        ACMContestRankVo lastACMRankVo = null;
        for (int i = 0; i < len; i++) {
            ACMContestRankVo currentACMRankVo = orderResultList.get(i);
            if (starAccountMap.containsKey(currentACMRankVo.getUsername())) {
                // 打星队伍排名为-1
                currentACMRankVo.setRank(-1);
            } else {
                if (rankNum == 1) {
                    currentACMRankVo.setRank(rankNum);
                } else {
                    // 当前用户的总罚时和AC数跟前一个用户一样的话，同时前一个不应该为打星，排名则一样
                    if (lastACMRankVo.getAc().equals(currentACMRankVo.getAc())
                            && lastACMRankVo.getTotalTime().equals(currentACMRankVo.getTotalTime())) {
                        currentACMRankVo.setRank(lastACMRankVo.getRank());
                    } else {
                        currentACMRankVo.setRank(rankNum);
                    }
                }
                lastACMRankVo = currentACMRankVo;
                rankNum++;
            }

            if (!StrUtil.isEmpty(currentUserId) && currentACMRankVo.getUid().equals(currentUserId)) {
                topACMRankVoList.add(currentACMRankVo);
            }

            // 需要添加关注用户
            if (needAddConcernedUser) {
                if (concernedList.contains(currentACMRankVo.getUid())) {
                    topACMRankVoList.add(currentACMRankVo);
                }
            }
        }
        topACMRankVoList.addAll(orderResultList);
        return topACMRankVoList;
    }

    // TODO 行数过多
    private List<ACMContestRankVo> getACMOrderRank(Contest contest, Boolean isOpenSealRank) {

        List<ContestRecordVo> contestRecordList = contestRecordEntityService.getACMContestRecord(contest.getAuthor(),
                contest.getId());

        List<String> superAdminUidList = getSuperAdminUidList();

        List<ACMContestRankVo> result = new ArrayList<>();

        HashMap<String, Integer> uidMapIndex = new HashMap<>();

        int index = 0;

        HashMap<String, Long> firstACMap = new HashMap<>();

        for (ContestRecordVo contestRecord : contestRecordList) {
            // 超级管理员的提交不入排行榜
            if (superAdminUidList.contains(contestRecord.getUid())) {
                continue;
            }

            ACMContestRankVo ACMContestRankVo;
            // 如果该用户信息没还记录
            if (!uidMapIndex.containsKey(contestRecord.getUid())) {

                // 初始化参数
                ACMContestRankVo = new ACMContestRankVo();
                ACMContestRankVo.setRealname(contestRecord.getRealname())
                        .setAvatar(contestRecord.getAvatar())
                        .setSchool(contestRecord.getSchool())
                        .setGender(contestRecord.getGender())
                        .setUid(contestRecord.getUid())
                        .setUsername(contestRecord.getUsername())
                        .setNickname(contestRecord.getNickname())
                        .setAc(0)
                        .setTotalTime(0L)
                        .setTotal(0);

                HashMap<String, HashMap<String, Object>> submissionInfo = new HashMap<>();
                ACMContestRankVo.setSubmissionInfo(submissionInfo);

                result.add(ACMContestRankVo);
                uidMapIndex.put(contestRecord.getUid(), index);
                index++;
            } else {
                // 根据记录的index进行获取
                ACMContestRankVo = result.get(uidMapIndex.get(contestRecord.getUid()));
            }
            // TODO put 键
            HashMap<String, Object> problemSubmissionInfo = ACMContestRankVo.getSubmissionInfo()
                    .get(contestRecord.getDisplayId());

            if (problemSubmissionInfo == null) {
                problemSubmissionInfo = new HashMap<>();
                problemSubmissionInfo.put("errorNum", 0);
            }

            ACMContestRankVo.setTotal(ACMContestRankVo.getTotal() + 1);

            // 如果是当前是开启封榜的时段和同时该提交是处于封榜时段 尝试次数+1
            if (isOpenSealRank && isInSealTimeSubmission(contest, contestRecord.getSubmitTime())) {

                int tryNum = (int) problemSubmissionInfo.getOrDefault("tryNum", 0);
                problemSubmissionInfo.put("tryNum", tryNum + 1);
            } else {
                // 如果该题目已经AC过了，其它都不记录了
                if ((Boolean) problemSubmissionInfo.getOrDefault("isAC", false)) {
                    continue;
                }

                // 记录已经按题目提交耗时time升序了

                // 通过的话
                if (contestRecord.getStatus().intValue() == ContestEnum.RECORD_AC.getCode()) {
                    // 总解决题目次数ac+1
                    ACMContestRankVo.setAc(ACMContestRankVo.getAc() + 1);

                    // 判断是不是first AC
                    boolean isFirstAC = false;
                    Long time = firstACMap.getOrDefault(contestRecord.getDisplayId(), null);
                    if (time == null) {
                        isFirstAC = true;
                        firstACMap.put(contestRecord.getDisplayId(), contestRecord.getTime());
                    } else {
                        // 相同提交时间也是first AC
                        if (time.longValue() == contestRecord.getTime().longValue()) {
                            isFirstAC = true;
                        }
                    }

                    int errorNumber = (int) problemSubmissionInfo.getOrDefault("errorNum", 0);
                    problemSubmissionInfo.put("isAC", true);
                    problemSubmissionInfo.put("isFirstAC", isFirstAC);
                    problemSubmissionInfo.put("ACTime", contestRecord.getTime());
                    problemSubmissionInfo.put("errorNum", errorNumber);

                    // 同时计算总耗时，总耗时加上 该题目未AC前的错误次数*20*60+题目AC耗时
                    ACMContestRankVo.setTotalTime(
                            ACMContestRankVo.getTotalTime() + errorNumber * 20 * 60 + contestRecord.getTime());

                    // 未通过同时需要记录罚时次数
                } else if (contestRecord.getStatus().intValue() == ContestEnum.RECORD_NOT_AC_PENALTY.getCode()) {

                    int errorNumber = (int) problemSubmissionInfo.getOrDefault("errorNum", 0);
                    problemSubmissionInfo.put("errorNum", errorNumber + 1);
                } else {

                    int errorNumber = (int) problemSubmissionInfo.getOrDefault("errorNum", 0);
                    problemSubmissionInfo.put("errorNum", errorNumber);
                }
            }
            ACMContestRankVo.getSubmissionInfo().put(contestRecord.getDisplayId(), problemSubmissionInfo);
        }

        return result.stream()
                // 再以总耗时升序
                // 先以总ac数降序
                .sorted(Comparator.comparing(ACMContestRankVo::getAc, Comparator.reverseOrder())
                        .thenComparing(ACMContestRankVo::getTotalTime))
                .collect(Collectors.toList());
    }

    /**
     * @param isOpenSealRank 是否是查询封榜后的数据
     * @param removeStar     是否需要移除打星队伍
     * @param contest        比赛实体信息
     * @param currentUserId  当前查看榜单的用户uuid,不为空则将该数据复制一份放置列表最前
     * @param concernedList  关注的用户（uuid）列表
     * @param useCache       是否对初始排序计算的结果进行缓存
     * @param cacheTime      缓存的时间 单位秒
     * @MethodName calcOIRank
     * @Description
     * @Return
     * @Since 2021/12/10
     */
    public List<OIContestRankVo> calculateOIRank(boolean isOpenSealRank, boolean removeStar, Contest contest,
                                                 String currentUserId, List<String> concernedList, boolean useCache, Long cacheTime) {

        List<OIContestRankVo> orderResultList;
        if (useCache) {
            String key = ContestConstant.CONTEST_RANK_CAL_RESULT_CACHE + "_" + contest.getId();
            orderResultList = (List<OIContestRankVo>) redisUtil.get(key);
            if (orderResultList == null) {
                orderResultList = getOIOrderRank(contest, isOpenSealRank);
                redisUtil.set(key, orderResultList, cacheTime);
            }
        } else {
            orderResultList = getOIOrderRank(contest, isOpenSealRank);
        }

        // 需要打星的用户名列表
        HashMap<String, Boolean> starAccountMap = starAccountToMap(contest.getStarAccount());

        // 如果选择了移除打星队伍，同时该用户属于打星队伍，则将其移除
        if (removeStar) {
            orderResultList.removeIf(acmContestRankVo -> starAccountMap.containsKey(acmContestRankVo.getUsername()));
        }

        // 记录当前用户排名数据和关注列表的用户排名数据
        List<OIContestRankVo> topOIRankVoList = new ArrayList<>();
        boolean needAddConcernedUser = false;
        if (!CollectionUtils.isEmpty(concernedList)) {
            needAddConcernedUser = true;
            // 移除关注列表与当前用户重复
            concernedList.remove(currentUserId);
        }

        int rankNum = 1;
        OIContestRankVo lastOIRankVo = null;
        int len = orderResultList.size();
        for (int i = 0; i < len; i++) {
            OIContestRankVo currentOIRankVo = orderResultList.get(i);
            if (starAccountMap.containsKey(currentOIRankVo.getUsername())) {
                // 打星队伍排名为-1
                currentOIRankVo.setRank(-1);
            } else {
                if (rankNum == 1) {
                    currentOIRankVo.setRank(rankNum);
                } else {
                    // 当前用户的程序总运行时间和总得分跟前一个用户一样的话，同时前一个不应该为打星用户，排名则一样
                    if (lastOIRankVo.getTotalScore().equals(currentOIRankVo.getTotalScore())
                            && lastOIRankVo.getTotalTime().equals(currentOIRankVo.getTotalTime())) {
                        currentOIRankVo.setRank(lastOIRankVo.getRank());
                    } else {
                        currentOIRankVo.setRank(rankNum);
                    }
                }
                lastOIRankVo = currentOIRankVo;
                rankNum++;
            }

            if (!StrUtil.isEmpty(currentUserId) && currentOIRankVo.getUid().equals(currentUserId)) {
                topOIRankVoList.add(currentOIRankVo);
            }

            // 需要添加关注用户
            if (needAddConcernedUser) {
                if (concernedList.contains(currentOIRankVo.getUid())) {
                    topOIRankVoList.add(currentOIRankVo);
                }
            }
        }
        topOIRankVoList.addAll(orderResultList);
        return topOIRankVoList;
    }

    // TODO 行数过多
    private List<OIContestRankVo> getOIOrderRank(Contest contest, Boolean isOpenSealRank) {

        List<ContestRecordVo> oiContestRecord = contestRecordEntityService.getOIContestRecord(contest, isOpenSealRank);

        List<String> superAdminUidList = getSuperAdminUidList();

        List<OIContestRankVo> result = new ArrayList<>();

        HashMap<String, Integer> uidMapIndex = new HashMap<>();

        HashMap<String, HashMap<String, Integer>> uidMapTime = new HashMap<>();

        boolean isHighestRankScore = ContestConstant.OI_RANK_HIGHEST_SCORE.equals(contest.getOiRankScoreType());

        int index = 0;
        for (ContestRecordVo contestRecord : oiContestRecord) {
            // 超级管理员的提交不入排行榜
            if (superAdminUidList.contains(contestRecord.getUid())) {
                continue;
            }
            // AC
            if (Objects.equals(contestRecord.getStatus(), ContestEnum.RECORD_AC.getCode())) {
                HashMap<String, Integer> pidMapTime = uidMapTime.get(contestRecord.getUid());
                if (pidMapTime != null) {
                    Integer useTime = pidMapTime.get(contestRecord.getDisplayId());
                    if (useTime != null) {
                        // 如果时间消耗比原来的少
                        if (useTime > contestRecord.getUseTime()) {
                            pidMapTime.put(contestRecord.getDisplayId(), contestRecord.getUseTime());
                        }
                    } else {
                        pidMapTime.put(contestRecord.getDisplayId(), contestRecord.getUseTime());
                    }
                } else {
                    HashMap<String, Integer> tmp = new HashMap<>();
                    tmp.put(contestRecord.getDisplayId(), contestRecord.getUseTime());
                    uidMapTime.put(contestRecord.getUid(), tmp);
                }
            }

            OIContestRankVo oiContestRankVo;
            // 如果该用户信息没还记录
            if (!uidMapIndex.containsKey(contestRecord.getUid())) {
                // 初始化参数
                oiContestRankVo = new OIContestRankVo()
                        .setRealname(contestRecord.getRealname())
                        .setUid(contestRecord.getUid())
                        .setUsername(contestRecord.getUsername())
                        .setSchool(contestRecord.getSchool())
                        .setAvatar(contestRecord.getAvatar())
                        .setGender(contestRecord.getGender())
                        .setNickname(contestRecord.getNickname())
                        .setTotalScore(0)
                        .setSubmissionInfo(new HashMap<>());

                result.add(oiContestRankVo);
                uidMapIndex.put(contestRecord.getUid(), index);
                index++;
            } else {
                // 根据记录的index进行获取
                oiContestRankVo = result.get(uidMapIndex.get(contestRecord.getUid()));
            }

            // 记录总分
            HashMap<String, Integer> submissionInfo = oiContestRankVo.getSubmissionInfo();
            Integer score = submissionInfo.get(contestRecord.getDisplayId());
            if (isHighestRankScore) {
                if (score == null) {
                    oiContestRankVo.setTotalScore(oiContestRankVo.getTotalScore() + contestRecord.getScore());
                    submissionInfo.put(contestRecord.getDisplayId(), contestRecord.getScore());
                }
            } else {
                if (contestRecord.getScore() != null) {
                    // 为了避免同个提交时间的重复计算
                    if (score != null) {
                        oiContestRankVo.setTotalScore(oiContestRankVo.getTotalScore() - score + contestRecord.getScore());
                    } else {
                        oiContestRankVo.setTotalScore(oiContestRankVo.getTotalScore() + contestRecord.getScore());
                    }
                }
                submissionInfo.put(contestRecord.getDisplayId(), contestRecord.getScore());
            }

        }

        for (OIContestRankVo oiContestRankVo : result) {
            HashMap<String, Integer> pidMapTime = uidMapTime.get(oiContestRankVo.getUid());
            int sumTime = 0;
            if (pidMapTime != null) {
                for (String key : pidMapTime.keySet()) {
                    Integer time = pidMapTime.get(key);
                    sumTime += time == null ? 0 : time;
                }
            }
            oiContestRankVo.setTotalTime(sumTime);
            oiContestRankVo.setTimeInfo(pidMapTime);
        }

        // 根据总得分进行降序,再根据总时耗升序排序
        return result.stream()
                .sorted(Comparator.comparing(OIContestRankVo::getTotalScore, Comparator.reverseOrder())
                        .thenComparing(OIContestRankVo::getTotalTime, Comparator.naturalOrder()))
                .collect(Collectors.toList());
    }

    private List<String> getSuperAdminUidList() {
        return userInfoEntityService.getSuperAdminUidList();
    }

    private boolean isInSealTimeSubmission(Contest contest, Date submissionDate) {
        return DateUtil.isIn(submissionDate, contest.getSealRankTime(), contest.getEndTime());
    }

    private HashMap<String, Boolean> starAccountToMap(String starAccountStr) {
        if (StrUtil.isEmpty(starAccountStr)) {
            return new HashMap<>();
        }
        JSONObject jsonObject = JSONUtil.parseObj(starAccountStr);
        List<String> list = jsonObject.get("star_account", List.class);
        HashMap<String, Boolean> res = new HashMap<>();
        for (String str : list) {
            if (!StrUtil.isEmpty(str)) {
                res.put(str, true);
            }
        }
        return res;
    }

}