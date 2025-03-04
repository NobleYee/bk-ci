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

import com.tencent.devops.common.api.enums.RepositoryConfig
import com.tencent.devops.common.api.util.EnvUtils
import com.tencent.devops.common.api.util.Watcher
import com.tencent.devops.common.event.dispatcher.pipeline.PipelineEventDispatcher
import com.tencent.devops.common.event.enums.ActionType
import com.tencent.devops.common.event.pojo.pipeline.PipelineBuildStartBroadCastEvent
import com.tencent.devops.common.event.pojo.pipeline.PipelineBuildStatusBroadCastEvent
import com.tencent.devops.common.log.utils.BuildLogPrinter
import com.tencent.devops.common.pipeline.Model
import com.tencent.devops.common.pipeline.container.Stage
import com.tencent.devops.common.pipeline.container.TriggerContainer
import com.tencent.devops.common.pipeline.enums.BuildStatus
import com.tencent.devops.common.pipeline.enums.GitPullModeType
import com.tencent.devops.common.pipeline.pojo.BuildNoType
import com.tencent.devops.common.pipeline.pojo.element.agent.CodeGitElement
import com.tencent.devops.common.pipeline.pojo.element.agent.CodeGitlabElement
import com.tencent.devops.common.pipeline.pojo.element.agent.CodeSvnElement
import com.tencent.devops.common.pipeline.pojo.element.agent.GithubElement
import com.tencent.devops.common.pipeline.utils.RepositoryConfigUtils
import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.common.service.utils.LogUtils
import com.tencent.devops.process.engine.control.lock.BuildIdLock
import com.tencent.devops.process.engine.control.lock.PipelineBuildNoLock
import com.tencent.devops.process.engine.control.lock.PipelineBuildStartLock
import com.tencent.devops.process.engine.pojo.BuildInfo
import com.tencent.devops.process.engine.pojo.LatestRunningBuild
import com.tencent.devops.process.engine.pojo.event.PipelineBuildCancelEvent
import com.tencent.devops.process.engine.pojo.event.PipelineBuildFinishEvent
import com.tencent.devops.process.engine.pojo.event.PipelineBuildStageEvent
import com.tencent.devops.process.engine.pojo.event.PipelineBuildStartEvent
import com.tencent.devops.process.engine.service.PipelineBuildDetailService
import com.tencent.devops.process.engine.service.PipelineRepositoryService
import com.tencent.devops.process.engine.service.PipelineRuntimeExtService
import com.tencent.devops.process.engine.service.PipelineRuntimeService
import com.tencent.devops.process.engine.service.PipelineStageService
import com.tencent.devops.process.pojo.setting.PipelineRunLockType
import com.tencent.devops.process.service.BuildStartupParamService
import com.tencent.devops.process.service.BuildVariableService
import com.tencent.devops.process.service.scm.ScmProxyService
import com.tencent.devops.process.utils.BUILD_NO
import com.tencent.devops.process.utils.PIPELINE_TIME_START
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * 构建控制器
 * @version 1.0
 */
@Service
@Suppress("TooManyFunctions", "LongParameterList")
class BuildStartControl @Autowired constructor(
    private val pipelineEventDispatcher: PipelineEventDispatcher,
    private val redisOperation: RedisOperation,
    private val pipelineRuntimeService: PipelineRuntimeService,
    private val pipelineRuntimeExtService: PipelineRuntimeExtService,
    private val pipelineStageService: PipelineStageService,
    private val pipelineRepositoryService: PipelineRepositoryService,
    private val buildDetailService: PipelineBuildDetailService,
    private val buildStartupParamService: BuildStartupParamService,
    private val buildVariableService: BuildVariableService,
    private val scmProxyService: ScmProxyService,
    private val buildLogPrinter: BuildLogPrinter
) {

    companion object {
        private val LOG = LoggerFactory.getLogger(BuildStartControl::class.java)!!
        private const val TAG = "startVM-0"
        private const val JOB_ID = "0"
        private const val DEFAULT_DELAY = 1000
    }

    fun handle(event: PipelineBuildStartEvent) {
        val watcher = Watcher(id = "ENGINE|BuildStart|${event.traceId}|${event.buildId}|${event.status}")
        with(event) {
            val pipelineBuildLock = PipelineBuildStartLock(redisOperation, pipelineId)
            try {
                watcher.start("tryLock")
                if (pipelineBuildLock.tryLock()) {
                    watcher.start("execute")
                    execute(watcher)
                } else {
                    retry() // 进行重试
                }
            } catch (ignored: Throwable) {
                LOG.error("ENGINE|$buildId|$source| start fail $ignored", ignored)
            } finally {
                pipelineBuildLock.unlock()
                watcher.stop()
                LogUtils.printCostTimeWE(watcher = watcher)
            }
        }
    }

    private fun PipelineBuildStartEvent.retry() {
        LOG.info("ENGINE|$buildId|$source|RETRY_TO_LOCK")
        this.delayMills = DEFAULT_DELAY
        pipelineEventDispatcher.dispatch(this)
    }

    fun PipelineBuildStartEvent.execute(watcher: Watcher) {
        val executeCount = buildVariableService.getBuildExecuteCount(buildId = buildId)
        buildLogPrinter.addLine(buildId = buildId, message = "Enter BuildStartControl",
            tag = TAG, jobId = JOB_ID, executeCount = executeCount
        )

        watcher.start("pickUpReadyBuild")
        val buildInfo = pickUpReadyBuild(executeCount = executeCount) ?: run {
            return
        }
        watcher.stop()

        watcher.start("buildModel")
        buildModel(buildInfo = buildInfo, executeCount = executeCount)
        watcher.stop()

        buildLogPrinter.addLine(buildId = buildId, message = "BuildStartControl End",
            tag = TAG, jobId = JOB_ID, executeCount = executeCount
        )

        buildLogPrinter.stopLog(buildId = buildId, tag = TAG, jobId = JOB_ID, executeCount = executeCount)
    }

    private fun PipelineBuildStartEvent.pickUpReadyBuild(executeCount: Int): BuildInfo? {

        val buildIdLock = BuildIdLock(redisOperation = redisOperation, buildId = buildId)
        return try {
            buildIdLock.lock()
            val buildInfo = pipelineRuntimeService.getBuildInfo(buildId)
            if (buildInfo == null || buildInfo.status.isFinish()) {
                buildLogPrinter.addLine(message = "Stop #${buildInfo?.buildNum} ${buildInfo?.status}",
                    buildId = buildId, tag = TAG, jobId = JOB_ID, executeCount = executeCount
                )
                LOG.info("ENGINE|$buildId][$source|BUILD_START_DONE|status=${buildInfo?.status}")
                null
            } else if (tryToStartRunBuild(buildInfo, executeCount = executeCount)) {
                buildInfo
            } else {
                null
            }
        } finally {
            buildIdLock.unlock()
        }
    }

    private fun PipelineBuildStartEvent.tryToStartRunBuild(buildInfo: BuildInfo, executeCount: Int): Boolean {
        LOG.info("ENGINE|$buildId|$source|BUILD_START|${buildInfo.status}")
        var canStart = true
        // 已经是启动状态的，直接返回
        if (!buildInfo.status.isReadyToRun()) {
            buildLogPrinter.addLine(message = "Illegal build #${buildInfo.buildNum} [${buildInfo.status}]",
                buildId = buildId, tag = TAG, jobId = JOB_ID, executeCount = executeCount
            )
            return canStart
        }

        val runLockType = pipelineRepositoryService.getSetting(pipelineId)?.runLockType
        // #4074 LOCK 不会进入到这里，在启动API已经拦截
        if (runLockType == PipelineRunLockType.SINGLE || runLockType == PipelineRunLockType.SINGLE_LOCK) {
            // #4074 锁定当前构建是队列中第一个排队待执行的
            if (buildInfo.status != BuildStatus.QUEUE_CACHE) {
                canStart = pipelineRuntimeExtService.queueCanPend2Start(projectId, pipelineId, buildId = buildId)
            }
            if (canStart) {
                val buildSummaryRecord = pipelineRuntimeService.getBuildSummaryRecord(pipelineId)
                if (buildSummaryRecord!!.runningCount > 0) {
                    // 需要重新入队等待
                    pipelineRuntimeService.updateBuildInfoStatus2Queue(buildId, BuildStatus.QUEUE_CACHE)

                    buildLogPrinter.addLine(message = "Mode: $runLockType, queue: ${buildSummaryRecord.runningCount}",
                        buildId = buildId, tag = TAG, jobId = JOB_ID, executeCount = executeCount
                    )
                    canStart = false
                }
            } else {
                buildLogPrinter.addLine(message = "Waiting build #${buildInfo.buildNum - 1}",
                    buildId = buildId, tag = TAG, jobId = JOB_ID, executeCount = executeCount
                )
            }
        }

        if (canStart) {
            buildLogPrinter.addLine(
                message = "Build #${buildInfo.buildNum} preparing",
                buildId = buildId, tag = TAG, jobId = JOB_ID, executeCount = executeCount
            )
            handleBuildNo()
            pipelineRuntimeService.startLatestRunningBuild(
                latestRunningBuild = LatestRunningBuild(
                    projectId = projectId,
                    pipelineId = pipelineId,
                    buildId = buildId,
                    userId = buildInfo.startUser,
                    status = BuildStatus.RUNNING,
                    taskCount = buildInfo.taskCount,
                    buildNum = buildInfo.buildNum
                ),
                retry = (actionType == ActionType.RETRY)
            )
            broadcastStartEvent(buildInfo)
        }

        return canStart
    }

    private fun PipelineBuildStartEvent.handleBuildNo() {
        if (buildNoType == BuildNoType.SUCCESS_BUILD_INCREMENT) {
            // 防止"每次构建成功+1"读取到相同buildNo的情况正式启动前为var表设置最新的buildNo
            val buildNoLock = PipelineBuildNoLock(redisOperation = redisOperation, pipelineId = pipelineId)
            try {
                buildNoLock.lock()
                val buildSummary = pipelineRuntimeService.getBuildSummaryRecord(pipelineId = pipelineId)
                val buildNo = buildSummary?.buildNo
                if (buildNo != null) {
                    buildVariableService.setVariable(
                        projectId = projectId,
                        pipelineId = pipelineId,
                        buildId = buildId,
                        varName = BUILD_NO,
                        varValue = buildNo
                    )
                }
            } finally {
                buildNoLock.unlock()
            }
        }
    }

    private fun PipelineBuildStartEvent.broadcastStartEvent(buildInfo: BuildInfo) {
        pipelineEventDispatcher.dispatch(
            // 广播构建即将启动消息给订阅者
            PipelineBuildStartBroadCastEvent(
                source = TAG,
                projectId = projectId,
                pipelineId = pipelineId,
                userId = buildInfo.startUser,
                buildId = buildId,
                startTime = buildInfo.startTime,
                triggerType = buildInfo.trigger
            ),
            // 根据状态做响应的扩展广播消息给订阅者
            PipelineBuildStatusBroadCastEvent(
                source = source,
                projectId = projectId,
                pipelineId = pipelineId,
                userId = userId,
                buildId = buildId,
                actionType = ActionType.START
            )
        )
    }

    private fun updateModel(model: Model, buildInfo: BuildInfo, taskId: String) {
        val now = LocalDateTime.now()
        val stage = model.stages[0]
        val container = stage.containers[0]
        run lit@{
            container.elements.forEach {
                if (it.id == taskId) {
                    pipelineRuntimeService.updateContainerStatus(
                        buildId = buildInfo.buildId,
                        stageId = stage.id!!,
                        containerId = container.id!!,
                        startTime = now,
                        endTime = now,
                        buildStatus = BuildStatus.SUCCEED
                    )
                    it.status = BuildStatus.SUCCEED.name
                    return@lit
                }
            }
        }

        pipelineStageService.updateStageStatus(
            buildId = buildInfo.buildId,
            stageId = stage.id!!,
            buildStatus = BuildStatus.SUCCEED,
            checkIn = stage.checkIn,
            checkOut = stage.checkOut
        )

        stage.status = BuildStatus.SUCCEED.name
        stage.elapsed = if (System.currentTimeMillis() - buildInfo.queueTime < 0) {
            0
        } else System.currentTimeMillis() - buildInfo.queueTime
        container.status = BuildStatus.SUCCEED.name
        container.systemElapsed = System.currentTimeMillis() - buildInfo.queueTime
        container.elementElapsed = 0
        container.startVMStatus = BuildStatus.SUCCEED.name

        buildDetailService.updateModel(buildId = buildInfo.buildId, model = model)
    }

    @Suppress("ALL")
    private fun supplementModel(
        projectId: String,
        pipelineId: String,
        fullModel: Model,
        startParams: MutableMap<String, String>
    ) {
        val variables = mutableMapOf<String, String>()
        fullModel.stages.forEach { stage ->
            stage.containers.forEach nextContainer@{ container ->
                if (container is TriggerContainer) {
                    // 解析变量
                    container.params.forEach { param ->
                        if (startParams[param.id] != null) {
                            variables[param.id] = startParams[param.id].toString()
                        } else {
                            variables[param.id] = param.defaultValue.toString()
                        }
                    }
                    return@nextContainer
                }
                var callScm = true
                container.elements.forEach nextElement@{ ele ->
                    if (!ele.isElementEnable()) {
                        return@nextElement
                    }
                    if (!ele.status.isNullOrBlank()) {
                        val eleStatus = BuildStatus.valueOf(ele.status!!)
                        if (eleStatus.isFinish() && eleStatus != BuildStatus.SKIP) {
                            callScm = false
                            ele.status = ""
                            ele.elapsed = null
                            return@nextElement
                        }
                    }

                    val (repositoryConfig: RepositoryConfig, branchName: String?) =
                        when (ele) {
                            is CodeSvnElement -> {
                                if (ele.revision.isNullOrBlank()) {
                                    RepositoryConfigUtils.buildConfig(ele) to ele.svnPath
                                } else {
                                    return@nextElement
                                }
                            }
                            is CodeGitElement -> {
                                val branchName = when {
                                    ele.gitPullMode != null -> {
                                        if (ele.gitPullMode!!.type != GitPullModeType.COMMIT_ID) {
                                            EnvUtils.parseEnv(ele.gitPullMode!!.value, variables)
                                        } else {
                                            return@nextElement
                                        }
                                    }
                                    !ele.branchName.isNullOrBlank() -> EnvUtils.parseEnv(ele.branchName!!, variables)
                                    else -> return@nextElement
                                }
                                RepositoryConfigUtils.buildConfig(ele) to branchName
                            }
                            is CodeGitlabElement -> {
                                val branchName = when {
                                    ele.gitPullMode != null -> {
                                        if (ele.gitPullMode!!.type != GitPullModeType.COMMIT_ID) {
                                            EnvUtils.parseEnv(ele.gitPullMode!!.value, variables)
                                        } else {
                                            return@nextElement
                                        }
                                    }
                                    !ele.branchName.isNullOrBlank() -> EnvUtils.parseEnv(ele.branchName!!, variables)
                                    else -> return@nextElement
                                }
                                RepositoryConfigUtils.buildConfig(ele) to branchName
                            }
                            is GithubElement -> {
                                val branchName = when {
                                    ele.gitPullMode != null -> {
                                        if (ele.gitPullMode!!.type != GitPullModeType.COMMIT_ID) {
                                            EnvUtils.parseEnv(ele.gitPullMode!!.value, variables)
                                        } else {
                                            return@nextElement
                                        }
                                    }
                                    else -> return@nextElement
                                }
                                RepositoryConfigUtils.buildConfig(ele) to branchName
                            }
                            else -> return@nextElement
                        }

                    if (callScm) {
                        val latestRevision =
                            scmProxyService.recursiveFetchLatestRevision(
                                projectId = projectId,
                                pipelineId = pipelineId,
                                repositoryConfig = repositoryConfig,
                                branchName = branchName,
                                variables = variables,
                                retry = 0
                            )
                        if (latestRevision.isOk() && latestRevision.data != null) {
                            when (ele) {
                                is CodeSvnElement -> {
                                    ele.revision = latestRevision.data!!.revision
                                    ele.specifyRevision = true
                                }
                                is CodeGitElement -> ele.revision = latestRevision.data!!.revision
                                is GithubElement -> ele.revision = latestRevision.data!!.revision
                                is CodeGitlabElement -> ele.revision = latestRevision.data!!.revision
                                else -> return@nextElement
                            }
                        }
                    }
                }
            }
        }
    }

    private fun PipelineBuildStartEvent.buildModel(buildInfo: BuildInfo, executeCount: Int) {
        val model = buildDetailService.getBuildModel(buildId) ?: run {
            pipelineEventDispatcher.dispatch(PipelineBuildCancelEvent(
                source = TAG, projectId = projectId, pipelineId = pipelineId,
                userId = userId, buildId = buildId, status = BuildStatus.UNEXEC
            )
            )
            return // model不存在直接取消构建
        }

        // 单步重试不做操作，手动重试需还原各节点状态，启动需获取revision信息
        buildLogPrinter.addLine(message = "Async fetch latest commit/revision, please wait...",
            buildId = buildId, tag = TAG, jobId = JOB_ID, executeCount = executeCount
        )
        val startParams: Map<String, String> by lazy { buildVariableService.getAllVariable(buildId) }
        if (actionType == ActionType.START) {
            supplementModel(projectId = projectId, pipelineId = pipelineId,
                fullModel = model, startParams = startParams as MutableMap<String, String>
            )
        }

        if (buildInfo.status.isReadyToRun()) {
            buildLogPrinter.addLine(message = "Updating model & start parameters & variables",
                buildId = buildId, tag = TAG, jobId = JOB_ID, executeCount = executeCount
            )
            updateModel(model = model, buildInfo = buildInfo, taskId = taskId)
            // 写入启动参数
            buildStartupParamService.writeStartParam(
                allVariable = startParams, projectId = projectId,
                pipelineId = pipelineId, buildId = buildId, model = model
            )
            buildVariableService.setVariable(
                projectId = projectId,
                pipelineId = pipelineId,
                buildId = buildId,
                varName = PIPELINE_TIME_START,
                varValue = System.currentTimeMillis().toString()
            )
        }

        val stages = model.stages
        val firstValidStage = getFirstValidStage(stages)
        if (stages.size == 1 || firstValidStage == null) { // 空节点或者没有可用的stage
            pipelineEventDispatcher.dispatch(
                PipelineBuildFinishEvent(source = TAG,
                    projectId = projectId, pipelineId = pipelineId, userId = userId,
                    buildId = buildId, status = BuildStatus.SUCCEED
                )
            )
        } else { // 对第一个可用的Stage下发指令
            pipelineEventDispatcher.dispatch(
                PipelineBuildStageEvent(source = TAG,
                    projectId = projectId, pipelineId = pipelineId, userId = userId,
                    buildId = buildId, stageId = firstValidStage.id!!, actionType = actionType
                )
            )
        }
    }

    private fun getFirstValidStage(stages: List<Stage>): Stage? {
        var firstValidStage: Stage? = null
        val stageSize = stages.size
        if (stageSize == 1) {
            return null
        }
        val endIndex = stageSize - 1
        for (i in 1..endIndex) {
            val stage = stages[i]
            if (stage.stageControlOption?.enable != false) {
                firstValidStage = stage
                break
            }
        }
        return firstValidStage
    }
}
