package com.equestrian.club.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.equestrian.club.common.BizException;
import com.equestrian.club.dict.EquestrianDict;
import com.equestrian.club.domain.Horse;
import com.equestrian.club.domain.HorseRepository;
import com.equestrian.club.domain.Stall;
import com.equestrian.club.domain.StallRepository;
import com.equestrian.club.dto.OccupyRequest;
import com.equestrian.club.dto.StallRequest;
import com.equestrian.club.dto.view.StallView;

/**
 * 马房与栏位模块：栏位编号唯一 / 一个栏位只能放一匹马 / 一匹马只能占一个栏位 / 维护中禁止入栏。
 */
@Service
public class StallService {

    /** 栏位挂着 horseId 但马匹档案查不到时（极端情况下的数据不一致），明细里统一用它占位，不能让整页 500 */
    private static final String MISSING_HORSE_NAME = "马匹档案缺失";

    private final StallRepository stallRepository;
    private final HorseRepository horseRepository;
    private final HorseService horseService;

    public StallService(StallRepository stallRepository,
            HorseRepository horseRepository,
            HorseService horseService) {
        this.stallRepository = stallRepository;
        this.horseRepository = horseRepository;
        this.horseService = horseService;
    }

    /**
     * 看板唯一的明细来源：顶部汇总、马房分组、卡片信息全部由前端基于这份列表计算。
     * 马匹信息一次性批量取出，避免逐栏位查库；即使某条 horseId 在马匹表里已经不存在，
     * 也要返回该栏位并用「马匹档案缺失」占位，而不是把整份列表打成 500。
     */
    @Transactional(readOnly = true)
    public List<StallView> list() {
        List<Stall> stalls = stallRepository.findAllByOrderByIdAsc();
        List<Long> horseIds = stalls.stream()
                .map(Stall::getHorseId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, Horse> horses = new HashMap<>();
        if (!horseIds.isEmpty()) {
            horseRepository.findAllById(horseIds)
                    .forEach(horse -> horses.put(horse.getId(), horse));
        }
        return stalls.stream().map(stall -> toView(stall, horses.get(stall.getHorseId()))).toList();
    }

    @Transactional(readOnly = true)
    public StallView detail(Long id) {
        Stall stall = require(id);
        Horse horse = stall.getHorseId() == null ? null : horseRepository.findById(stall.getHorseId()).orElse(null);
        return toView(stall, horse);
    }

    @Transactional
    public StallView create(StallRequest request) {
        if (request.getStallNo() == null || request.getStallNo().isBlank()) {
            throw new BizException("栏位编号不能为空");
        }
        String stallNo = request.getStallNo().trim();
        if (stallRepository.existsByStallNo(stallNo)) {
            throw new BizException("栏位编号「" + stallNo + "」已存在，编号必须唯一");
        }
        if (request.getBarnName() == null || request.getBarnName().isBlank()) {
            throw new BizException("所属马房不能为空");
        }

        Stall stall = new Stall();
        stall.setStallNo(stallNo);
        stall.setBarnName(request.getBarnName().trim());
        stall.setAreaSqm(request.getAreaSqm());

        // 默认值放在 service 的 create 里，实体上不写默认值
        String status = request.getStatus() == null ? EquestrianDict.STALL_IDLE : request.getStatus();
        if (!EquestrianDict.isValidStallStatus(status)) {
            throw new BizException("栏位状态取值不合法：" + status + "，只支持 IDLE / OCCUPIED / MAINTENANCE");
        }
        if (EquestrianDict.STALL_OCCUPIED.equals(status)) {
            throw new BizException("新建栏位不能直接置为「占用」，请先用入栏操作绑定马匹");
        }
        stall.setStatus(status);
        stall.setHorseId(null);

        return toView(stallRepository.save(stall), null);
    }

    @Transactional
    public StallView update(Long id, StallRequest request) {
        Stall stall = require(id);
        if (request.getStallNo() != null && !request.getStallNo().isBlank()) {
            String stallNo = request.getStallNo().trim();
            if (!stallNo.equals(stall.getStallNo())) {
                if (stallRepository.existsByStallNo(stallNo)) {
                    throw new BizException("栏位编号「" + stallNo + "」已存在，编号必须唯一");
                }
                stall.setStallNo(stallNo);
            }
        }
        if (request.getBarnName() != null && !request.getBarnName().isBlank()) {
            stall.setBarnName(request.getBarnName().trim());
        }
        if (request.getAreaSqm() != null) {
            stall.setAreaSqm(request.getAreaSqm());
        }
        if (request.getStatus() != null) {
            throw new BizException("栏位状态由入栏 / 出栏 / 维护操作驱动，不通过编辑直接修改");
        }
        Stall saved = stallRepository.save(stall);
        Horse horse = saved.getHorseId() == null
                ? null
                : horseRepository.findById(saved.getHorseId()).orElse(null);
        return toView(saved, horse);
    }

    /**
     * 入栏：维护中的栏位不能入栏，已有马的栏位不能再放，一匹马也只能占一个栏位。
     */
    @Transactional
    public StallView occupy(Long id, OccupyRequest request) {
        Stall stall = require(id);

        // 可空外键先判 null，再查库，避免甩给用户一个非人话的数据库报错
        Long horseId = request == null ? null : request.getHorseId();
        if (horseId == null) {
            throw new BizException("入栏失败：请先选择要入栏的马匹");
        }

        if (EquestrianDict.STALL_MAINTENANCE.equals(stall.getStatus())) {
            throw new BizException("栏位「" + stall.getStallNo() + "」正在维护中，禁止入栏");
        }
        if (stall.getHorseId() != null) {
            String currentName = horseRepository.findById(stall.getHorseId())
                    .map(Horse::getName)
                    .orElse(MISSING_HORSE_NAME);
            throw new BizException("栏位「" + stall.getStallNo() + "」已放着马匹「" + currentName
                    + "」，一个栏位只能放一匹马");
        }

        Horse horse = horseService.require(horseId);
        stallRepository.findByHorseId(horseId).ifPresent(other -> {
            if (!other.getId().equals(stall.getId())) {
                throw new BizException("马匹「" + horse.getName() + "」已在栏位「" + other.getStallNo()
                        + "」中，一匹马只能占用一个栏位");
            }
        });

        stall.setHorseId(horse.getId());
        stall.setStatus(EquestrianDict.STALL_OCCUPIED);
        return toView(stallRepository.save(stall), horse);
    }

    /** 出栏：清空占用并把栏位置回空闲 */
    @Transactional
    public StallView release(Long id) {
        Stall stall = require(id);
        if (stall.getHorseId() == null) {
            throw new BizException("栏位「" + stall.getStallNo() + "」当前没有马匹，无需出栏");
        }
        stall.setHorseId(null);
        if (!EquestrianDict.STALL_MAINTENANCE.equals(stall.getStatus())) {
            stall.setStatus(EquestrianDict.STALL_IDLE);
        }
        return toView(stallRepository.save(stall), null);
    }

    /** 转入维护：栏位里还有马就必须先出栏 */
    @Transactional
    public StallView maintenance(Long id) {
        Stall stall = require(id);
        if (EquestrianDict.STALL_MAINTENANCE.equals(stall.getStatus())) {
            throw new BizException("栏位「" + stall.getStallNo() + "」已经是维护中状态");
        }
        if (stall.getHorseId() != null) {
            String horseName = horseRepository.findById(stall.getHorseId())
                    .map(Horse::getName)
                    .orElse(MISSING_HORSE_NAME);
            throw new BizException("栏位「" + stall.getStallNo() + "」仍放着马匹「" + horseName
                    + "」，请先出栏再转入维护");
        }
        stall.setStatus(EquestrianDict.STALL_MAINTENANCE);
        return toView(stallRepository.save(stall), null);
    }

    /** 维护完成，恢复空闲 */
    @Transactional
    public StallView restore(Long id) {
        Stall stall = require(id);
        if (!EquestrianDict.STALL_MAINTENANCE.equals(stall.getStatus())) {
            throw new BizException("栏位「" + stall.getStallNo() + "」不在维护中，无需恢复");
        }
        stall.setStatus(EquestrianDict.STALL_IDLE);
        return toView(stallRepository.save(stall), null);
    }

    private Stall require(Long id) {
        if (id == null) {
            throw new BizException("请先选择栏位");
        }
        return stallRepository.findById(id)
                .orElseThrow(() -> new BizException("栏位不存在，编号：" + id));
    }

    /**
     * 组装栏位视图：horse 由调用方（批量或单条）提前查出。
     * horseId 非空但马匹档案缺失属于极端数据不一致，仍要稳定返回该栏位，
     * 用占位文案提示，绝不能让整份看板列表报错。
     */
    private StallView toView(Stall stall, Horse horse) {
        String horseNo = null;
        String horseName = null;
        if (stall.getHorseId() != null) {
            if (horse != null) {
                horseNo = horse.getHorseNo();
                horseName = horse.getName();
            } else {
                horseNo = "#" + stall.getHorseId();
                horseName = MISSING_HORSE_NAME;
            }
        }
        return new StallView(
                stall.getId(),
                stall.getStallNo(),
                stall.getBarnName(),
                stall.getAreaSqm(),
                stall.getStatus(),
                EquestrianDict.stallStatusName(stall.getStatus()),
                stall.getHorseId(),
                horseNo,
                horseName);
    }
}
