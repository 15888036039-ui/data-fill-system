package com.example.datafill.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import com.example.datafill.entity.DataFillForm;

import com.example.datafill.mapper.DataFillFormMapper;

import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;

import org.springframework.scheduling.annotation.Scheduled;

import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import java.time.LocalTime;

import java.util.List;

/**

 * 定时任务服务

 */

@Slf4j

@Service

@RequiredArgsConstructor

public class SchedulerService {

    private final DataFillFormMapper formMapper;

    private final NotificationService notificationService;

    @Value("${data-fill.scheduler.enabled:true}")

    private boolean schedulerEnabled;

    /**

     * 定时任务：检查表单状态，发送提醒邮件

     * 每分钟执行一次，精确匹配用户配置的 reminderTime

     */

    @Scheduled(cron = "${data-fill.scheduler.cron:0 * * * * }")

    @Transactional

    public void notificationCheck() {

        if (!schedulerEnabled) {

            log.info("定时任务已禁用");

            return;

        }

        log.info("开始执行定时通知检查任务");

        LocalDateTime now = LocalDateTime.now();

        // 0. 先刷新“每月任务”等循环类表单的截止时间

        refreshRecurringFormDeadlines(now);

        // 1. 检查活跃表单，发送提醒邮件

        checkAndSendReminders(now);

        // 2. 检查即将到期的表单，发送截止警告

        checkAndSendDeadlineWarnings(now);

        // 3. 更新过期表单状态

        updateExpiredForms(now);

        // 4. 处理待发送的通知

        notificationService.processPendingNotifications();

        log.info("通知检查任务执行完成");

    }

    /**

     * 检查并发送提醒邮件

     * - DEADLINE：在“截止前 N 天”的那一天，按 reminderTime 触发

     * - MONTHLY/WEEKLY：在本期截止日当天，按 reminderTime 触发

     */

    private void checkAndSendReminders(LocalDateTime now) {

        // 查询活跃状态的表单

        QueryWrapper<DataFillForm> queryWrapper = new QueryWrapper<>();

        queryWrapper.eq("status", "ACTIVE")

                   .isNotNull("deadline")

                   .isNotNull("recipient_emails")

                   .gt("deadline", now); // 还未过期

        List<DataFillForm> activeForms = formMapper.selectList(queryWrapper);

        for (DataFillForm form : activeForms) {

            try {

                LocalDateTime deadline = form.getDeadline();

                if (deadline == null) {

                    continue;

                }

                String mode = form.getReminderMode();

                if (mode == null || mode.trim().isEmpty()) {

                    mode = "DEADLINE";

                }

                LocalTime rt = parseReminderTime(form.getReminderTime());
                if (rt == null) {
                    continue; // 提醒时点未填，不发提醒
                }
                LocalDateTime scheduledTime = null;

                if (form.getReminderDateTime() != null) {
                    // 优先使用精确指定的提醒时间 (无论是手动设置的 DEADLINE 还是自动生成的周期任务)
                    scheduledTime = form.getReminderDateTime().withNano(0);
                } else if ("DEADLINE".equalsIgnoreCase(mode)) {
                    // 固定截止模式需要有截止时间
                    if (deadline == null) continue;
                    double rDays = form.getReminderDays() != null ? form.getReminderDays() : 3.0;
                    scheduledTime = deadline.minusHours((long)(rDays * 24)).with(rt).withNano(0);
                } else {
                    // 对于周期任务，如果 reminderDateTime 为空，尝试实时计算本周期的提醒时间
                    scheduledTime = calculateCurrentCycleReminderTime(form);
                }

                if (scheduledTime == null) {
                    continue;
                }

                // 仅在提醒时间当天且已到达时点时发送
                if (!now.toLocalDate().equals(scheduledTime.toLocalDate()) || now.isBefore(scheduledTime)) {
                    continue;
                }

                log.info("表单 {} 触发提醒检查，计划发送时间 {}", form.getName(), scheduledTime);
                notificationService.createReminderNotification(form.getId(), scheduledTime);

            } catch (Exception e) {

                log.error("处理表单提醒失败: {}", form.getId(), e);

            }

        }

    }

    /**

     * 解析提醒时间字符串（HH:mm），默认 09:00

     */

    private LocalTime parseReminderTime(String reminderTime) {

        try {

            if (reminderTime != null && !reminderTime.trim().isEmpty()) {

                String[] parts = reminderTime.split(":");

                int hour = Integer.parseInt(parts[0]);

                int minute = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;

                if (hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59) {

                    return LocalTime.of(hour, minute);

                }

            }

        } catch (Exception ignored) {

        }

        return null; // 不再使用默认 09:00，未填直接返回 null
    }

    /**

     * 检查并发送截止警告邮件

     */

    private void checkAndSendDeadlineWarnings(LocalDateTime now) {

        QueryWrapper<DataFillForm> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("status", "ACTIVE")
                   .isNotNull("deadline")
                   .isNotNull("recipient_emails")
                   .gt("deadline", now); // 只查还没过期的

        List<DataFillForm> forms = formMapper.selectList(queryWrapper);

        for (DataFillForm form : forms) {
            try {
                LocalDateTime warningTime = calculateWarningTime(form);
                if (warningTime == null) continue;

                // 仅在预警时间当天、已到达预警时点、且尚未过截止时间时触发
                if (now.toLocalDate().equals(warningTime.toLocalDate())
                        && !now.isBefore(warningTime)
                        && now.isBefore(form.getDeadline())) {
                    log.info("表单 {} 进入预警期，预警时间 {}，截止时间 {}",
                            form.getName(), warningTime, form.getDeadline());
                    notificationService.createDeadlineWarningNotification(form.getId(), warningTime);
                }
            } catch (Exception e) {
                log.error("处理表单截止警告失败: {}", form.getId(), e);
            }
        }
    }

    /**
     * 根据周期类型计算预警时间点
     * - MONTHLY：截止前 3 天（~30 天周期的 10%）
     * - WEEKLY ：截止前 17 小时（7 天周期的 10%）
     * - DEADLINE：截止前 1 天（兜底）
     */
    private LocalDateTime calculateWarningTime(DataFillForm form) {
        LocalDateTime deadline = form.getDeadline();
        if (deadline == null) return null;

        String mode = form.getReminderMode();
        if (mode == null || mode.trim().isEmpty()) mode = "DEADLINE";

        // 使用用户配置的截止时点，没有则退回提醒时点
        LocalTime warningTimeOfDay = parseReminderTime(
                form.getDeadlineTime() != null ? form.getDeadlineTime() : form.getReminderTime());
        
        if (warningTimeOfDay == null) return null; // 无法解析出时点，不发预警

        LocalDateTime warningTime;
        if ("MONTHLY".equalsIgnoreCase(mode)) {
            // 月任务：~30 天周期，10% ≈ 3 天
            warningTime = deadline.minusDays(3).with(warningTimeOfDay);
        } else if ("WEEKLY".equalsIgnoreCase(mode)) {
            // 周任务：7 天周期，10% ≈ 17 小时（直接按小时偏移，不强制时点）
            warningTime = deadline.minusHours(17);
        } else {
            // 固定截止 / 兜底：截止前 1 天
            warningTime = deadline.minusDays(1).with(warningTimeOfDay);
        }

        return warningTime.withSecond(0).withNano(0);
    }

    /**

     * 更新过期表单状态

     */

    private void updateExpiredForms(LocalDateTime now) {

        QueryWrapper<DataFillForm> queryWrapper = new QueryWrapper<>();

        queryWrapper.eq("status", "ACTIVE")

                   .isNotNull("deadline")

                   .le("deadline", now); // 已过期

        List<DataFillForm> expiredForms = formMapper.selectList(queryWrapper);

        for (DataFillForm form : expiredForms) {

            try {

                form.setStatus("EXPIRED");

                form.setUpdateTime(now);

                formMapper.updateById(form);

                log.info("表单 {} 已过期，状态更新为 EXPIRED", form.getName());

            } catch (Exception e) {

                log.error("更新表单过期状态失败: {}", form.getId(), e);

            }

        }

    }

    /**

     * 针对循环类表单，根据当前时间刷新下一次截止时间

     */

    private void refreshRecurringFormDeadlines(LocalDateTime now) {

        QueryWrapper<DataFillForm> wrapper = new QueryWrapper<>();

        wrapper.eq("status", "ACTIVE")

               .in("reminder_mode", "MONTHLY", "WEEKLY");

        List<DataFillForm> forms = formMapper.selectList(wrapper);

        if (forms == null || forms.isEmpty()) {

            return;

        }

        for (DataFillForm form : forms) {

            try {

                LocalDateTime oldDeadline = form.getDeadline();

                initOrRefreshDeadline(form, now);

                if (form.getDeadline() != null && !form.getDeadline().equals(oldDeadline)) {

                    form.setUpdateTime(now);

                    formMapper.updateById(form);

                    log.info("刷新循环表单 {} 的下一次截止时间为 {}", form.getName(), form.getDeadline());

                }

            } catch (Exception e) {

                log.error("刷新循环表单截止时间失败: {}", form.getId(), e);

            }

        }

    }

    /**

     * 初始化或刷新表单的下一次截止时间，用于建表或定时任务

     */

    /**
     * 初始化或刷新表单的下一次截止时间，用于建表或定时任务
     */
    public void initOrRefreshDeadline(DataFillForm form, LocalDateTime now) {
        String mode = form.getReminderMode();
        LocalDateTime deadline = form.getDeadline();

        if (mode == null || "DEADLINE".equalsIgnoreCase(mode)) {
            return;
        }

        // 如果还没过期，且提醒还没发送，或者已经在当前周期内，什么都不做
        if (deadline != null && now.isBefore(deadline)) {
            return;
        }

        java.time.LocalDate today = now.toLocalDate();
        java.time.LocalDate calculatedReminderDate = null;
        java.time.LocalDate calculatedDeadlineDate = null;

        if ("MONTHLY".equalsIgnoreCase(mode)) {
            Integer rDay = form.getMonthlyDay();
            Integer dDay = form.getDeadlineMonthlyDay();
            if (rDay == null || dDay == null) return; // 必须同时填提醒日和截止日
            
            // 1. 确定提醒日期
            calculatedReminderDate = today.withDayOfMonth(Math.min(rDay, today.lengthOfMonth()));
            // 如果这个月的提醒日期已经过了，且对应的截止日期也已经过了，则推到下个月
            LocalDateTime currentCycleDeadline = calculateSpecificDeadline(calculatedReminderDate, rDay, dDay, form.getReminderTime(), form.getDeadlineTime(), "MONTHLY");
            if (currentCycleDeadline == null || now.isAfter(currentCycleDeadline)) {
                java.time.LocalDate nextMonth = today.plusMonths(1);
                calculatedReminderDate = nextMonth.withDayOfMonth(Math.min(rDay, nextMonth.lengthOfMonth()));
            }
            
            // 2. 确定截止日期（在提醒日期之后）
            calculatedDeadlineDate = calculateNextOccurrence(calculatedReminderDate, dDay, "MONTHLY");

        } else if ("WEEKLY".equalsIgnoreCase(mode)) {
            Integer rDow = form.getWeeklyDayOfWeek();
            Integer dDow = form.getDeadlineWeeklyDayOfWeek();
            if (rDow == null || dDow == null) return; // 必须同时填提醒日币截止日

            // 1. 确定提醒日期 (最近的一个配置好的周几)
            int todayDow = today.getDayOfWeek().getValue();
            calculatedReminderDate = today.minusDays(todayDow - 1).plusDays(rDow - 1);
            
            LocalDateTime currentCycleDeadline = calculateSpecificDeadline(calculatedReminderDate, rDow, dDow, form.getReminderTime(), form.getDeadlineTime(), "WEEKLY");
            if (currentCycleDeadline == null || now.isAfter(currentCycleDeadline)) {
                calculatedReminderDate = calculatedReminderDate.plusDays(7);
            }

            // 2. 确定截止日期
            calculatedDeadlineDate = calculateNextOccurrence(calculatedReminderDate, dDow, "WEEKLY");
        }

        if (calculatedReminderDate != null && calculatedDeadlineDate != null) {
            LocalTime rt = parseReminderTime(form.getReminderTime());
            LocalTime dt = parseReminderTime(form.getDeadlineTime()); // 复用解析函数

            if (rt != null && dt != null) {
                form.setReminderDateTime(calculatedReminderDate.atTime(rt));
                form.setDeadline(calculatedDeadlineDate.atTime(dt));
            } else {
                // 如果时间没填，清空已有的计算值，确保不发邮件
                form.setReminderDateTime(null);
                form.setDeadline(null);
            }
        }
    }

    /**
     * 计算特定配置下的截止时间点
     */
    private LocalDateTime calculateSpecificDeadline(java.time.LocalDate reminderDate, int rVal, int dVal, String rTime, String dTime, String mode) {
        java.time.LocalDate deadlineDate = calculateNextOccurrence(reminderDate, dVal, mode);
        LocalTime dt = parseReminderTime(dTime);
        return dt != null ? deadlineDate.atTime(dt) : null;
    }

    /**
     * 从起始日期开始，找下一个（或当日）目标日期（周几或几号）
     */
    private java.time.LocalDate calculateNextOccurrence(java.time.LocalDate startDate, int targetVal, String mode) {
        if ("WEEKLY".equalsIgnoreCase(mode)) {
            int currentDow = startDate.getDayOfWeek().getValue();
            int daysToAdd = (targetVal - currentDow + 7) % 7;
            return startDate.plusDays(daysToAdd);
        } else {
            // MONTHLY
            if (targetVal >= startDate.getDayOfMonth()) {
                return startDate.withDayOfMonth(Math.min(targetVal, startDate.lengthOfMonth()));
            } else {
                java.time.LocalDate next = startDate.plusMonths(1);
                return next.withDayOfMonth(Math.min(targetVal, next.lengthOfMonth()));
            }
        }
    }

    /**
     * 计算当前表单截止时间对应的提醒时间点（兜底逻辑）
     */
    private LocalDateTime calculateCurrentCycleReminderTime(DataFillForm form) {
        if (form.getDeadline() == null) return null;
        if ("WEEKLY".equalsIgnoreCase(form.getReminderMode())) {
            Integer rDow = form.getWeeklyDayOfWeek();
            if (rDow == null) return null;
            LocalDateTime reminder = form.getDeadline();
            while (reminder.getDayOfWeek().getValue() != rDow) {
                reminder = reminder.minusDays(1);
            }
            LocalTime rt = parseReminderTime(form.getReminderTime());
            return rt != null ? reminder.with(rt) : null;
        }
        // Monthly 等逻辑同理...
        return null;
    }





    /**

     * 手动触发通知检查（用于测试）

     */

    public void triggerNotificationCheck() {

        log.info("手动触发通知检查");

        notificationCheck();

    }

}