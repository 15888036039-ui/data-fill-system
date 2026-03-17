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

                if (mode == null || mode.isBlank()) {

                    mode = "DEADLINE";

                }

                LocalTime rt = parseReminderTime(form.getReminderTime());

                LocalDateTime scheduledTime;

                if ("DEADLINE".equalsIgnoreCase(mode) || "MONTHLY".equalsIgnoreCase(mode) || "WEEKLY".equalsIgnoreCase(mode)) {

                    int reminderDays = form.getReminderDays() != null ? form.getReminderDays() : 3;

                    java.time.LocalDate reminderDate = deadline.toLocalDate().minusDays(reminderDays);

                    scheduledTime = reminderDate.atTime(rt);

                    // 仅在目标日期当天、到达（或略晚于）提醒时间后触发

                    if (!now.toLocalDate().equals(reminderDate) || now.isBefore(scheduledTime)) {

                        continue;

                    }

                } else {

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

            if (reminderTime != null && !reminderTime.isBlank()) {

                String[] parts = reminderTime.split(":");

                int hour = Integer.parseInt(parts[0]);

                int minute = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;

                if (hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59) {

                    return LocalTime.of(hour, minute);

                }

            }

        } catch (Exception ignored) {

        }

        return LocalTime.of(9, 0);

    }

    /**

     * 检查并发送截止警告邮件

     */

    private void checkAndSendDeadlineWarnings(LocalDateTime now) {

        // 查询即将到期的表单（剩余1天）

        QueryWrapper<DataFillForm> queryWrapper = new QueryWrapper<>();

        queryWrapper.eq("status", "ACTIVE")

                   .isNotNull("deadline")

                   .isNotNull("recipient_emails");

        List<DataFillForm> forms = formMapper.selectList(queryWrapper);

        for (DataFillForm form : forms) {

            try {

                int daysLeft = calculateDaysLeft(form.getDeadline(), now);

                // 如果剩余1天或已到期，发送截止警告 (只在9点触发一次)

                if (daysLeft <= 1 && daysLeft >= 0 && now.getHour() == 9 && now.getMinute() == 0) {

                    log.info("表单 {} 即将到期（剩余 {} 天），发送截止警告", form.getName(), daysLeft);

                    notificationService.createDeadlineWarningNotification(form.getId(), now);

                }

            } catch (Exception e) {

                log.error("处理表单截止警告失败: {}", form.getId(), e);

            }

        }

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

    public void initOrRefreshDeadline(DataFillForm form, LocalDateTime now) {

        String mode = form.getReminderMode();

        LocalDateTime deadline = form.getDeadline();

        if (mode == null || "DEADLINE".equalsIgnoreCase(mode)) {

            return;

        }

        // 如果还没过期，什么都不做

        if (deadline != null && now.isBefore(deadline)) {

            return;

        }

        java.time.LocalDate today = now.toLocalDate();

        int reminderDays = form.getReminderDays() != null ? form.getReminderDays() : 3;

        java.time.LocalDate calculatedReminderDate = null;

        if ("MONTHLY".equalsIgnoreCase(mode)) {

            Integer day = form.getMonthlyDay();

            if (day == null || day < 1) day = 10;

            // 当前月的发信日

            java.time.LocalDate targetDate = today.withDayOfMonth(Math.min(day, today.lengthOfMonth()));

            java.time.LocalDate cycleDeadline = targetDate.plusDays(reminderDays);

            // 如果连"当前月的截止日"都已经过了，就安排到下个月

            if (today.isAfter(cycleDeadline)) {

                java.time.LocalDate nextMonth = today.plusMonths(1);

                calculatedReminderDate = nextMonth.withDayOfMonth(Math.min(day, nextMonth.lengthOfMonth()));

            } else {

                calculatedReminderDate = targetDate;

            }

        } else if ("WEEKLY".equalsIgnoreCase(mode)) {

            Integer dow = form.getWeeklyDayOfWeek();

            if (dow == null || dow < 1 || dow > 7) dow = 1;

            int todayDow = today.getDayOfWeek().getValue();

            // 先找出"这周"的那个发信日

            java.time.LocalDate thisWeekTarget = today.minusDays(todayDow - 1).plusDays(dow - 1);

            java.time.LocalDate cycleDeadline = thisWeekTarget.plusDays(reminderDays);

            // 如果连"这周的发信截止日"都已经过了，就安排到下周

            if (today.isAfter(cycleDeadline)) {

                calculatedReminderDate = thisWeekTarget.plusDays(7);

            } else {

                calculatedReminderDate = thisWeekTarget;

            }

        }

        if (calculatedReminderDate != null) {

            LocalDateTime newDeadline = calculatedReminderDate.plusDays(reminderDays).atTime(23, 59, 59);

            form.setDeadline(newDeadline);

        }

    }

    /**

     * 计算剩余天数

     */

    private int calculateDaysLeft(LocalDateTime deadline, LocalDateTime referenceTime) {

        if (deadline == null || referenceTime == null) {

            return 0;

        }

        return (int) java.time.Duration.between(referenceTime, deadline).toDays();

    }

    /**

     * 手动触发通知检查（用于测试）

     */

    public void triggerNotificationCheck() {

        log.info("手动触发通知检查");

        notificationCheck();

    }

}