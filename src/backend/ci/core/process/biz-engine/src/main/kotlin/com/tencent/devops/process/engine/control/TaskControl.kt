/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.devops.process.engine.control

import com.tencent.devops.common.api.pojo.ErrorType
import com.tencent.devops.common.api.util.Watcher
import com.tencent.devops.common.api.util.timestampmilli
import com.tencent.devops.common.event.dispatcher.pipeline.PipelineEventDispatcher
import com.tencent.devops.common.event.enums.ActionType
import com.tencent.devops.common.pipeline.enums.BuildStatus
import com.tencent.devops.common.pipeline.pojo.element.RunCondition
import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.common.service.utils.LogUtils
import com.tencent.devops.process.engine.atom.AtomResponse
import com.tencent.devops.process.engine.atom.TaskAtomService
import com.tencent.devops.process.engine.common.BS_ATOM_STATUS_REFRESH_DELAY_MILLS
import com.tencent.devops.process.engine.common.BS_TASK_HOST
import com.tencent.devops.process.engine.control.lock.ContainerIdLock
import com.tencent.devops.process.engine.pojo.PipelineBuildTask
import com.tencent.devops.process.engine.pojo.event.PipelineBuildAtomTaskEvent
import com.tencent.devops.process.engine.service.PipelineRuntimeService
import com.tencent.devops.process.pojo.mq.PipelineBuildContainerEvent
import com.tencent.devops.process.service.PipelineTaskService
import com.tencent.devops.process.util.TaskUtils
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

/**
 * 任务（最小单元Atom）控制器
 * @version 1.0
 */
@Service
class TaskControl @Autowired constructor(
    private val redisOperation: RedisOperation,
    private val taskAtomService: TaskAtomService,
    private val pipelineEventDispatcher: PipelineEventDispatcher,
    private val pipelineRuntimeService: PipelineRuntimeService,
    private val pipelineTaskService: PipelineTaskService
) {

    companion object {
        private val LOG = LoggerFactory.getLogger(TaskControl::class.java)
        private const val DEFAULT_DELAY = 5000
    }

    /**
     * 处理[event]插件任务执行逻辑入口
     */
    fun handle(event: PipelineBuildAtomTaskEvent) {
        val watcher = Watcher(
            id = "ENGINE|TaskControl|${event.traceId}|${event.buildId}|Job#${event.containerId}|Task#${event.taskId}"
        )
        with(event) {
            val containerIdLock = ContainerIdLock(redisOperation, buildId, containerId)
            try {
                watcher.start("lock")
                containerIdLock.lock()
                watcher.start("execute")
                execute()
            } finally {
                containerIdLock.unlock()
                watcher.stop()
                LogUtils.printCostTimeWE(watcher = watcher, warnThreshold = 2000)
            }
        }
    }

    /**
     * 处理[PipelineBuildAtomTaskEvent]事件，开始执行/结束插件任务
     */
    private fun PipelineBuildAtomTaskEvent.execute() {

        val buildInfo = pipelineRuntimeService.getBuildInfo(buildId)

        val buildTask = pipelineRuntimeService.getBuildTask(buildId, taskId)
        // 检查构建状态,防止重复跑
        if (buildInfo?.status?.isFinish() == true || buildTask?.status?.isFinish() == true) {
            LOG.info("ENGINE|$buildId|$source|ATOM_$actionType|$stageId|j($containerId)|t($taskId)" +
                "|status=${buildTask?.status ?: "not exists"}")
            pipelineEventDispatcher.dispatch(
                PipelineBuildContainerEvent(
                    source = "from_t($taskId)",
                    projectId = projectId,
                    pipelineId = pipelineId,
                    userId = userId,
                    buildId = buildId,
                    stageId = stageId,
                    containerId = containerId,
                    containerType = containerType,
                    actionType = actionType,
                    errorCode = errorCode,
                    errorTypeName = errorTypeName,
                    reason = reason
                )
            )
            return
        }

        // 构建机的任务不在此运行
        if (taskAtomService.runByVmTask(buildTask!!)) {
            // 构建机上运行中任务目前无法直接后台干预，便在此处设置状态，使流程继续
            val additionalOptions = buildTask.additionalOptions
            val runCondition = additionalOptions?.runCondition
            val failedEvenCancelFlag = runCondition == RunCondition.PRE_TASK_FAILED_EVEN_CANCEL
            if (actionType.isTerminate() ||
                (actionType == ActionType.END && !failedEvenCancelFlag)) {
                LOG.info("ENGINE|$buildId|$source|ATOM_$actionType|$stageId|j($containerId)|t($taskId)|code=$errorCode")
                val buildStatus = if (actionType.isTerminate()) { // 区分系统终止还是用户手动终止
                    BuildStatus.TERMINATE
                } else {
                    BuildStatus.CANCELED
                }
                val atomResponse = AtomResponse(
                    buildStatus = buildStatus,
                    errorCode = errorCode,
                    errorType = if (errorTypeName != null) {
                        ErrorType.getErrorType(errorTypeName!!)
                    } else {
                        null
                    },
                    errorMsg = reason
                )
                taskAtomService.taskEnd(
                    task = buildTask,
                    startTime = buildTask.startTime?.timestampmilli() ?: System.currentTimeMillis(),
                    atomResponse = atomResponse
                )
                return finishTask(buildTask, buildStatus)
            }
        } else {
            buildTask.starter = userId
            if (taskParam.isNotEmpty()) { // 追加事件传递的参数变量值
                buildTask.taskParams.putAll(taskParam)
            }
            LOG.info("ENGINE|$buildId|$source|ATOM_$actionType|$stageId|j($containerId)|t($taskId)|" +
                "${buildTask.status}|code=$errorCode|$errorTypeName|$reason")
            val buildStatus = runTask(userId = userId, actionType = actionType, buildTask = buildTask)

            if (buildStatus.isRunning()) { // 仍然在运行中--没有结束的
                // 如果是要轮循的才需要定时消息轮循
                loopDispatch(buildTask = buildTask)
            } else {
                finishTask(buildTask = buildTask, buildStatus = buildStatus)
            }
        }
    }

    /**
     * 运行插件任务[buildTask], 并返回执行状态[BuildStatus]
     */
    private fun runTask(userId: String, actionType: ActionType, buildTask: PipelineBuildTask) = when {
        buildTask.status.isReadyToRun() -> { // 准备启动执行
            val runCondition = buildTask.additionalOptions?.runCondition
            if (actionType.isTerminate() ||
                (actionType == ActionType.END && runCondition != RunCondition.PRE_TASK_FAILED_EVEN_CANCEL)
            ) {
                // #2400 因任务终止&结束的事件命令而未执行的原子设置为UNEXEC，而不是SKIP
                pipelineRuntimeService.updateTaskStatus(
                    task = buildTask, userId = userId, buildStatus = BuildStatus.UNEXEC
                )
                BuildStatus.UNEXEC // SKIP 仅当是用户意愿明确正常运行情况要跳过执行的，不影响主流程的才能是SKIP
            } else {
                atomBuildStatus(taskAtomService.start(buildTask))
            }
        }
        buildTask.status.isRunning() -> { // 运行中的，检查是否运行结束，以及决定是否强制终止
            atomBuildStatus(taskAtomService.tryFinish(task = buildTask, actionType = actionType))
        }
        else -> buildTask.status // 其他状态不做动作
    }

    /**
     * 对于未结束的插件任务[buildTask]，进行循环消息投诉处理
     */
    private fun PipelineBuildAtomTaskEvent.loopDispatch(buildTask: PipelineBuildTask) {
        val loopDelayMills =
            if (buildTask.taskParams[BS_ATOM_STATUS_REFRESH_DELAY_MILLS] != null) {
                buildTask.taskParams[BS_ATOM_STATUS_REFRESH_DELAY_MILLS].toString().trim().toInt()
            } else {
                DEFAULT_DELAY
            }
        // 将执行结果参数写回事件消息中，方便再次传递
        taskParam.putAll(buildTask.taskParams)
        delayMills = loopDelayMills
        actionType = ActionType.REFRESH // 尝试刷新任务状态
        // 特定消费者
        if (buildTask.taskParams[BS_TASK_HOST] != null) {
            routeKeySuffix = buildTask.taskParams[BS_TASK_HOST].toString()
        }
        pipelineEventDispatcher.dispatch(this)
    }

    /**
     * 对结束的任务[buildTask], 根据状态[buildStatus]是否失败，以及[buildTask]配置：
     * 1. 需要失败重试，将[buildTask]的构建状态设置为RETRY
     */
    private fun PipelineBuildAtomTaskEvent.finishTask(buildTask: PipelineBuildTask, buildStatus: BuildStatus) {
        if (buildStatus == BuildStatus.CANCELED) {
            // 删除redis中取消构建标识
            redisOperation.delete("${BuildStatus.CANCELED.name}_$buildId")
            // 当task任务是取消状态时，把taskId存入redis供心跳接口获取
            redisOperation.leftPush(TaskUtils.getCancelTaskIdRedisKey(buildId, containerId), taskId)
        }
        if (buildStatus.isFailure() && !FastKillUtils.isTerminateCode(errorCode)) { // 失败的任务 并且不是需要终止的错误码
            // 如果配置了失败重试，且重试次数上线未达上限，则将状态设置为重试，让其进入
            if (pipelineTaskService.isRetryWhenFail(taskId, buildId)) {
                LOG.info("ENGINE|$buildId|$source|ATOM_FIN|$stageId|j($containerId)|t($taskId)|RetryFail")
                pipelineRuntimeService.updateTaskStatus(
                    task = buildTask, userId = buildTask.starter, buildStatus = BuildStatus.RETRY
                )
            } else {
                // 如果配置了失败继续，则继续下去的行为是在ContainerControl处理，而非在Task
                pipelineTaskService.createFailTaskVar(
                    buildId = buildId,
                    projectId = projectId,
                    pipelineId = pipelineId,
                    taskId = taskId
                )
            }
        } else {
            // 清除该原子内的重试记录
            pipelineTaskService.removeRetryCache(buildId, taskId)
            // 清理插件错误信息（重试插件成功的情况下）
            pipelineTaskService.removeFailTaskVar(
                buildId = buildId,
                projectId = projectId,
                pipelineId = pipelineId,
                taskId = taskId
            )
        }

        if (errorTypeName != null && ErrorType.getErrorType(errorTypeName!!) != null) {
            pipelineRuntimeService.setTaskErrorInfo(
                buildId = buildId,
                taskId = taskId,
                errorCode = errorCode,
                errorType = ErrorType.getErrorType(errorTypeName!!)!!,
                errorMsg = reason ?: "unknown"
            )
        }

        pipelineEventDispatcher.dispatch(
            PipelineBuildContainerEvent(
                source = "from_t($taskId)",
                projectId = projectId,
                pipelineId = pipelineId,
                userId = userId,
                buildId = buildId,
                stageId = stageId,
                containerId = containerId,
                containerType = containerType,
                actionType = actionType,
                errorCode = errorCode,
                errorTypeName = errorTypeName,
                reason = reason
            )
        )
    }

    private fun atomBuildStatus(response: AtomResponse): BuildStatus {
        return response.buildStatus
    }
}
